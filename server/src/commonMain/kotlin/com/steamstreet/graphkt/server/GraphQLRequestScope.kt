package com.steamstreet.graphkt.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.CoroutineContext

/** Loads a set of unique keys in one application-defined batch. */
public fun interface BatchLoader<K, V> {
    public suspend fun load(keys: List<K>): Map<K, V>
}

/** A cached batch loader whose lifetime is one GraphQL request. */
public interface RequestBatchLoader<K, V> {
    /** Returns the cached value for [key], or schedules the key for the next batch. */
    public suspend fun load(key: K): V?

    /** Loads values concurrently and returns them in the same order as [keys]. */
    public suspend fun loadMany(keys: List<K>): List<V?>

    /** Removes one cached key. An in-progress load continues for its existing callers. */
    public suspend fun clear(key: K)

    /** Removes all cached keys. In-progress loads continue for their existing callers. */
    public suspend fun clearAll()

    /** Adds [value] only when [key] does not already have a cached load or value. */
    public suspend fun prime(key: K, value: V?)
}

/** Owns loaders and cleanup actions for one valid GraphQL operation. */
public interface GraphQLRequestScope {
    /** Creates a cached loader that is closed and cleared with this request scope. */
    public suspend fun <K, V> batchLoader(loader: BatchLoader<K, V>): RequestBatchLoader<K, V>

    /** Registers a cleanup action. Actions run once in reverse registration order. */
    public suspend fun onClose(release: suspend () -> Unit)
}

/** Reports failures from request cleanup without exposing their messages by default. */
public class GraphQLRequestCleanupException(
    failures: List<Throwable>,
) : IllegalStateException("One or more GraphQL request resources could not be released", failures.firstOrNull()) {
    public val failures: List<Throwable> = failures.toList()

    init {
        require(failures.isNotEmpty()) { "failures must not be empty" }
    }
}

internal class DefaultGraphQLRequestScope(
    parentContext: CoroutineContext,
) : GraphQLRequestScope {
    private val scopeJob = Job(parentContext[Job])
    private val coroutineScope = CoroutineScope(parentContext + scopeJob)
    private val mutex = Mutex()
    private val cleanupActions = mutableListOf<suspend () -> Unit>()
    private var closed = false

    override suspend fun <K, V> batchLoader(loader: BatchLoader<K, V>): RequestBatchLoader<K, V> {
        val requestLoader = DefaultRequestBatchLoader(loader, coroutineScope)
        try {
            onClose(requestLoader::close)
        } catch (failure: Throwable) {
            requestLoader.close()
            throw failure
        }
        return requestLoader
    }

    override suspend fun onClose(release: suspend () -> Unit) {
        mutex.withLock {
            check(!closed) { "The GraphQL request scope is already closed" }
            cleanupActions += release
        }
    }

    suspend fun close(): List<Throwable> {
        val actions = mutex.withLock {
            if (closed) return@withLock null
            closed = true
            cleanupActions.asReversed().toList().also { cleanupActions.clear() }
        } ?: return emptyList()

        val failures = buildList {
            actions.forEach { release ->
                try {
                    release()
                } catch (failure: Throwable) {
                    add(failure)
                }
            }
        }
        scopeJob.cancelAndJoin()
        return failures
    }
}

private class DefaultRequestBatchLoader<K, V>(
    private val loader: BatchLoader<K, V>,
    private val coroutineScope: CoroutineScope,
) : RequestBatchLoader<K, V> {
    private val mutex = Mutex()
    private val cache = mutableMapOf<K, CompletableDeferred<V?>>()
    private val pending = mutableListOf<PendingLoad<K, V>>()
    private var dispatchScheduled = false
    private var closed = false

    override suspend fun load(key: K): V? {
        return resultsFor(listOf(key)).single().await()
    }

    override suspend fun loadMany(keys: List<K>): List<V?> = resultsFor(keys).awaitAll()

    override suspend fun clear(key: K) {
        mutex.withLock {
            check(!closed) { "The request batch loader is already closed" }
            cache.remove(key)
        }
    }

    override suspend fun clearAll() {
        mutex.withLock {
            check(!closed) { "The request batch loader is already closed" }
            cache.clear()
        }
    }

    override suspend fun prime(key: K, value: V?) {
        mutex.withLock {
            check(!closed) { "The request batch loader is already closed" }
            if (key !in cache) cache[key] = CompletableDeferred(value)
        }
    }

    suspend fun close() {
        val outstanding = mutex.withLock {
            if (closed) return
            closed = true
            pending.clear()
            cache.values.toList().also { cache.clear() }
        }
        val failure = IllegalStateException("The request batch loader closed before the load completed")
        outstanding.forEach { deferred ->
            if (!deferred.isCompleted) deferred.completeExceptionally(failure)
        }
    }

    private fun scheduleDispatch() {
        if (dispatchScheduled) return
        dispatchScheduled = true
        coroutineScope.launch {
            yield()
            dispatchPendingLoads()
        }
    }

    private suspend fun resultsFor(keys: List<K>): List<CompletableDeferred<V?>> = mutex.withLock {
        check(!closed) { "The request batch loader is already closed" }
        keys.map { key ->
            cache[key] ?: CompletableDeferred<V?>().also { deferred ->
                cache[key] = deferred
                pending += PendingLoad(key, deferred)
                scheduleDispatch()
            }
        }
    }

    private suspend fun dispatchPendingLoads() {
        while (true) {
            val batch = mutex.withLock {
                if (closed || pending.isEmpty()) {
                    dispatchScheduled = false
                    return
                }
                pending.toList().also { pending.clear() }
            }
            try {
                val values = loader.load(batch.map(PendingLoad<K, V>::key).distinct())
                batch.forEach { load -> load.result.complete(values[load.key]) }
            } catch (failure: CancellationException) {
                batch.forEach { load -> load.result.completeExceptionally(failure) }
                throw failure
            } catch (failure: Exception) {
                batch.forEach { load -> load.result.completeExceptionally(failure) }
            }
        }
    }
}

private data class PendingLoad<K, V>(
    val key: K,
    val result: CompletableDeferred<V?>,
)
