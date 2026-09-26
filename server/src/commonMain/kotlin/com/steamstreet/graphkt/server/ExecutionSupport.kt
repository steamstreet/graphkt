package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLPathSegment
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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

/**
 * Runs [block], which resolves the fields of an operation's root selection set, and returns null
 * when a null propagates past a non-null root field. The GraphQL specification then makes the whole
 * `data` entry null. The errors that the failing fields recorded are unaffected.
 */
internal suspend fun <Data : JsonElement> resolveOperationRoot(block: suspend () -> Data): Data? = try {
    block()
} catch (_: NonNullPropagationException) {
    null
}

/** Resolves [RequestSelection.children] one at a time, in order, and collects their response fields. */
internal suspend fun RequestSelection.resolveFieldsSerially(
    resolveChild: suspend (RequestSelection) -> JsonElement?,
): JsonObject {
    val fields = LinkedHashMap<String, JsonElement>()
    for (child in children) {
        resolveChild(child)?.let { value -> fields[child.responseName] = value }
    }
    return JsonObject(fields)
}

/**
 * Resolves the selection set of an object value, one field at a time, and returns the response
 * object. Generated `gqlSelect` functions call it with their `gqlSelectChild` function.
 * [resolveChild] returns null for a field that does not apply to the object's runtime type, and the
 * field is left out of the response.
 *
 * Each field follows GraphQL null propagation: a failed or null non-null field makes its nearest
 * nullable ancestor null. When no nullable ancestor exists below the operation root, the root
 * selection set itself becomes null. For that case, when this is the operation's root selection set
 * (its [RequestSelection.path] is empty), this function returns [JsonNull] instead of an object.
 * Callers then respond with `{"data": null, "errors": [...]}`. The errors were already recorded
 * through [RequestSelection.error] before the null propagated. For any other selection set, the null
 * propagates to the enclosing field, as the specification requires.
 */
public suspend fun RequestSelection.resolveSelectionSet(
    resolveChild: suspend (RequestSelection) -> JsonElement?,
): JsonElement = try {
    resolveFieldsSerially(resolveChild)
} catch (failure: NonNullPropagationException) {
    if (path.isNotEmpty()) throw failure
    JsonNull
}

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
