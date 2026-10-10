package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
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

    public companion object {
        /**
         * Parses [query] and builds the root selection of one of its operations, for requests that
         * arrive as a GraphQL document. It is the common equivalent of constructing a JVM
         * `ServerRequestSelection` over `parseGraphQLOperation(query).selectionSet`, and runs on every
         * target, including Kotlin/Native. Pass the result to a generated root `gqlSelect`.
         *
         * The selection matches the JVM one. Each field is keyed by its response name, which is its
         * alias when the document gives one. An inline fragment's type condition becomes the
         * [RequestSelection.typeName] of the fields it selects. [RequestSelection.parameters] render
         * each argument as the string form of its JSON, and a variable an argument refers to is
         * replaced by its value in [variables], or by `JsonNull` when it is absent. The selection is
         * not validated against a schema. `@skip` and `@include` are not applied, and the defaults
         * of variable definitions are not used, as in the JVM version. A float literal in
         * exponent form is rendered as the JVM does, so `1e3` reads as `1E+3`.
         *
         * Without an [operationName] the document's first definition must be an operation, and it is
         * used, as `parseGraphQLOperation` does. With one, the operation of that name is used,
         * which the JVM version cannot do. A named fragment spread is expanded in place with the
         * fragment's type condition, where the JVM version treats it as a field named for the
         * fragment.
         *
         * To learn whether the operation is a query or a mutation, use [RequestOperation.parse].
         *
         * @param query the GraphQL document.
         * @param variables the request's variables, or null when it has none.
         * @param errors the list that resolver failures are recorded in.
         * @param errorFactory builds the recorded error from a resolver failure.
         * @param operationName the operation to select from a document that holds several.
         * @throws IllegalArgumentException when [query] cannot be parsed, or holds no operation to
         * select.
         */
        public fun forOperation(
            query: String,
            variables: Map<String, JsonElement>?,
            errors: MutableList<GraphQLError>,
            errorFactory: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory.Generic,
            operationName: String? = null,
        ): RequestSelection = buildOperation(query, variables, errors, errorFactory, operationName).selection

        /**
         * Builds a root selection holding the single field [fieldName], for requests that deliver a
         * field's selection set and its already-resolved arguments separately rather than as one
         * document. AWS AppSync HTTP and Lambda resolvers do this, through `info.selectionSetGraphQL`
         * and `arguments`. It is the common equivalent of the JVM `ServerRequestSelection.forRootField`,
         * with the same contract, and runs on every target, including Kotlin/Native.
         *
         * Pass the result to a generated root `gqlSelect`, and read the field's value from the
         * returned object under [fieldName]. `gqlSelect` returns `JsonNull` instead of an object when
         * the field is non-null and a null propagated to it, so the field's value is null and
         * [errors] holds the failures. A resolver that throws, including one that calls `TODO()`,
         * records an error built by [errorFactory] and fails only its own field.
         *
         * AppSync applies the aliases of the original request itself: it reads each field of the
         * returned value by its field name and then renames it. Every field of the returned
         * selection is therefore keyed by its field name, as 2.x did, while error paths keep the
         * aliases. A consequence is that one result cannot serve two aliases of the same field, such
         * as `first: search(limit: 1) { id }` and `all: search { id }`: both are keyed `search`, and
         * the one selected last overwrites the other. Give such fields resolvers of their own in
         * AppSync, or request them in separate operations.
         *
         * The selection is not validated against a schema, and `@skip` and `@include` are not
         * applied, as in the JVM version. An inline fragment's type condition becomes the
         * [RequestSelection.typeName] of the fields it selects. A float literal in exponent form,
         * such as `1e3`, keeps its literal text, where the JVM version rewrites it as `1E+3`; both
         * decode to the same number.
         *
         * Named fragment spreads are not supported. AppSync keeps a spread such as `...eventFields`
         * in `selectionSetGraphQL` but omits the fragment's definition, so the fields it selects
         * cannot be known. A selection set that contains one fails with an
         * [IllegalArgumentException] that names the fragment, before any resolver runs. Clients of
         * an AppSync API served this way must select those fields directly or in inline fragments.
         *
         * @param selectionSet the field's selection set, such as `{ id name }`, or null or blank for
         * a field of a leaf type.
         * @param arguments the field's arguments, already resolved to values. Each reaches the
         * resolver unchanged, through a variable of its own, rather than as a GraphQL literal.
         * @param variables the variables that nested fields of [selectionSet] refer to.
         * @param errors the list that resolver failures are recorded in.
         * @throws IllegalArgumentException when [selectionSet] is not a single selection set, or when
         * it contains a named fragment spread.
         */
        public fun forRootField(
            fieldName: String,
            selectionSet: String?,
            arguments: Map<String, JsonElement>,
            variables: Map<String, JsonElement>,
            errors: MutableList<GraphQLError>,
            errorFactory: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory.Generic,
        ): RequestSelection = buildRootFieldSelection(
            fieldName = fieldName,
            selectionSet = selectionSet,
            arguments = arguments,
            variables = variables,
            errors = errors,
            errorFactory = errorFactory,
        )
    }
}
