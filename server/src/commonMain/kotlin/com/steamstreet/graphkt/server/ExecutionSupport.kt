package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLPathSegment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

/** Resolves one field and applies its nullable boundary. */
public suspend fun RequestSelection.resolveFieldValue(
    nonNull: Boolean,
    block: suspend () -> JsonElement,
): JsonElement = resolveFieldValue(nonNull, applyDirectives = true, block)

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
