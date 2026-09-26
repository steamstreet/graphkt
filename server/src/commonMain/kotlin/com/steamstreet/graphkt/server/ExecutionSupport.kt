package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLPathSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

internal interface ExecutionSelectionState {
    val hasPreExecutionError: Boolean
    val resolverFailures: MutableList<ResolverFailure>
    suspend fun resolveWithDirectives(block: suspend () -> JsonElement): JsonElement
}

internal fun interface FieldDirectiveExecutor {
    suspend fun execute(
        selection: RequestSelection,
        block: suspend () -> JsonElement,
    ): JsonElement
}

internal data class ResolverFailure(
    val cause: Throwable,
    val path: List<GraphQLPathSegment>,
)

internal class NonNullPropagationException : RuntimeException()

internal fun RequestSelection.takeResolverFailures(): List<ResolverFailure> = buildList {
    (this@takeResolverFailures as? ExecutionSelectionState)?.resolverFailures?.let { failures ->
        addAll(failures)
        failures.clear()
    }
    this@takeResolverFailures.children.forEach { child -> addAll(child.takeResolverFailures()) }
}

/**
 * Carries the field selection that is being resolved, so that the resolver behind it can look ahead
 * at the subfields the request selected. [currentFieldSelection] reads it.
 */
public class GraphQLFieldSelection(
    public val selection: RequestSelection,
) : AbstractCoroutineContextElement(Key) {
    public companion object Key : CoroutineContext.Key<GraphQLFieldSelection>
}

/**
 * Returns the selection of the field whose resolver is running, or null outside field resolution.
 *
 * A resolver uses it to look ahead at the subfields a request selected before it fetches data, for
 * example to query only the entity types a search result was asked for:
 *
 * ```kotlin
 * override suspend fun search(query: String): SearchResults {
 *     val requested = currentFieldSelection()?.children.orEmpty().map { it.name }.toSet()
 *     return SearchResults(events = if ("events" in requested) searchEvents(query) else emptyList())
 * }
 * ```
 *
 * The selection's [RequestSelection.children] include fragment selections, which carry the type
 * condition in [RequestSelection.typeName]. This replaces 2.x's `gqlContext.get()`.
 *
 * [GraphQLServer] exposes the selection only when [GraphQLExecutionPolicy.fieldSelectionLookahead]
 * is enabled, since installing it costs every field a coroutine context switch; otherwise this
 * returns null. Other [RequestSelection] implementations, such as the JVM `ServerRequestSelection`,
 * always expose it. It is not available to the source resolver of a subscription.
 */
public suspend fun currentFieldSelection(): RequestSelection? =
    currentCoroutineContext()[GraphQLFieldSelection]?.selection

/** Resolves one field and applies its nullable boundary. */
public suspend fun RequestSelection.resolveFieldValue(
    nonNull: Boolean,
    block: suspend () -> JsonElement,
): JsonElement = if (this is ExecutionSelectionState) {
    // The common executor decides whether to expose the selection, in its directive executor.
    resolveFieldValue(nonNull, applyDirectives = true, block)
} else {
    resolveFieldValue(nonNull, applyDirectives = true) {
        withContext(GraphQLFieldSelection(this)) { block() }
    }
}

private suspend fun RequestSelection.resolveFieldValue(
    nonNull: Boolean,
    applyDirectives: Boolean,
    block: suspend () -> JsonElement,
): JsonElement {
    if ((this as? ExecutionSelectionState)?.hasPreExecutionError == true) {
        if (nonNull) throw NonNullPropagationException()
        return JsonNull
    }

    return try {
        val value = if (applyDirectives) {
            (this as? ExecutionSelectionState)?.resolveWithDirectives(block) ?: block()
        } else {
            block()
        }
        if (nonNull && value is JsonNull) {
            error(IllegalStateException("A non-null GraphQL field returned null"))
            throw NonNullPropagationException()
        }
        value
    } catch (failure: NonNullPropagationException) {
        if (nonNull) throw failure
        JsonNull
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        error(failure)
        if (nonNull) throw NonNullPropagationException()
        JsonNull
    }
}

/** Resolves one list element and adds its index to any error path. */
public suspend fun RequestSelection.resolveListElement(
    index: Int,
    nonNull: Boolean,
    block: suspend (RequestSelection) -> JsonElement,
): JsonElement {
    val indexedSelection = forIndex(index)
    return indexedSelection.resolveFieldValue(nonNull, applyDirectives = false) { block(indexedSelection) }
}
