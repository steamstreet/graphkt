package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLPathSegment
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

    /** The response key, including a field alias when the document supplies one. */
    public val responseName: String get() = name

    /**
     * For polymorphic types, defines the concrete type for this selector.
     */
    public val typeName: String? get() = null

    /** The response path for errors that occur while this selection is resolved. */
    public val path: List<GraphQLPathSegment>

    /**
     * The list of children being requested
     */
    public val children: List<RequestSelection>

    /** Field directives in document order with coerced argument values. */
    public val directives: List<GraphQLAppliedDirective> get() = emptyList()

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

    /** Returns this selection at one list index and rebases all child error paths. */
    public fun forIndex(index: Int): RequestSelection

    /** Returns true when this fragment-backed selection applies to a concrete object type. */
    public fun appliesTo(concreteTypeName: String): Boolean = typeName == null || typeName == concreteTypeName

    /**
     * Declare an error for this selection.
     */
    public fun error(t: Throwable)
}
