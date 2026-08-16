package com.steamstreet.graphkt.server

import kotlinx.serialization.json.JsonElement

/** One validated field directive with coerced argument values. */
public data class GraphQLAppliedDirective(
    public val name: String,
    public val arguments: Map<String, JsonElement>,
)

/** Continues resolution once through the remaining field directive handlers. */
public fun interface GraphQLDirectiveContinuation {
    public suspend fun proceed(): JsonElement
}

/** Adds application-defined behavior around a field that uses a custom directive. */
public fun interface GraphQLDirectiveHandler<Context> {
    public suspend fun resolve(
        context: Context,
        directive: GraphQLAppliedDirective,
        selection: RequestSelection,
        next: GraphQLDirectiveContinuation,
    ): JsonElement
}
