package com.steamstreet.graphkt.server

import kotlinx.serialization.json.JsonElement

/**
 * Hierarchical representation of the data that is being requested. Each implementation
 * will define this differently so that the server processor can be the same.
 */
public interface RequestSelection {
    /**
     * The field name
     */
    public val name: String

    /**
     * The list of children being requested
     */
    public val children: List<RequestSelection>

    /**
     * Parameters passed in the original request
     */
    public val parameters: Map<String, String>

    /**
     * Returns a parameter as a JsonElement.
     */
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