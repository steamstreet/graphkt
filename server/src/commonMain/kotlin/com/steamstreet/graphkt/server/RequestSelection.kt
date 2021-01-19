package com.steamstreet.graphkt.server

import kotlinx.serialization.json.JsonElement

interface RequestSelection {
    val name: String
    val children: List<RequestSelection>
    val parameters: Map<String, String>

    fun inputParameter(key: String): JsonElement

    /**
     * The variables that were passed to the request.
     */
    fun variable(key: String): JsonElement

    /**
     * Set this node as the context for current processing. Generally this will cause
     * a thread local variable to be set.
     */
    fun setAsContext() {}

    /**
     * Declare an error for this selection.
     */
    fun error(t: Throwable)
}

expect fun gqlRequestContext(): RequestSelection