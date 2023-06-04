package com.steamstreet.graphkt.server

import kotlinx.serialization.json.JsonElement

public interface RequestSelection {
    public val name: String
    public val children: List<RequestSelection>
    public val parameters: Map<String, String>

    public fun inputParameter(key: String): JsonElement

    /**
     * The variables that were passed to the request.
     */
    public fun variable(key: String): JsonElement

    /**
     * Set this node as the context for current processing. Generally this will cause
     * a thread local variable to be set.
     */
    public fun setAsContext() {}

    /**
     * Declare an error for this selection.
     */
    public fun error(t: Throwable)
}

public expect fun gqlRequestContext(): RequestSelection?