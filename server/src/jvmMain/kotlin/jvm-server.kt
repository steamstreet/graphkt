package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import graphql.language.*
import graphql.parser.Parser
import kotlinx.serialization.json.*

public val json: Json = Json

public fun parseGraphQLOperation(query: String): OperationDefinition {
    val parser = Parser()
    val result = parser.parseDocument(query)
    return result.definitions.firstOrNull() as? OperationDefinition
        ?: throw IllegalArgumentException("Operation was not found")
}

/**
 * A [RequestSelection] over a GraphQL Java document, for JVM code that drives generated `gqlSelect`
 * functions directly, as 2.x applications did. It does not validate the document; new code should
 * execute requests through `GraphQLServer`.
 *
 * A generated root `gqlSelect` returns the response `data`. Resolver failures are recorded in
 * [errors], and execution follows GraphQL null propagation: a failed non-null field makes its
 * nearest nullable ancestor null. When no nullable ancestor exists below the root, `gqlSelect`
 * returns [JsonNull] rather than an object. Pass the result to [buildResponse] with [errors] to
 * produce `{"data": null, "errors": [...]}`.
 *
 * With [keyByFieldName] false, the default, `gqlSelect` keys each response field by its response
 * name, which is the alias when the document gives one. With [keyByFieldName] true, every field in
 * the tree is keyed by its field name instead, for a caller that applies aliases itself.
 * [forRootField] selections do this. Error paths use response names in both modes.
 */
public class ServerRequestSelection(
    public val parent: ServerRequestSelection?,
    public val variables: Map<String, JsonElement>,
    public val node: Node<*>,
    public val errors: MutableList<GraphQLError>,
    override val typeName: String? = null,
    private val errorFactory: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory.Generic,
    private val pathOverride: List<GraphQLPathSegment>? = null,
    public val keyByFieldName: Boolean = false,
) : RequestSelection {
    override val name: String
        get() = (node as? NamedNode<*>)?.name ?: throw IllegalStateException("Not a named node")
    override val responseName: String
        get() = if (keyByFieldName) name else documentResponseName

    private val documentResponseName: String
        get() = (node as? Field)?.alias ?: name
    override val children: List<RequestSelection>
        get() {
            val selectionSet = when (node) {
                is Field -> node.selectionSet ?: return emptyList()
                is SelectionSet -> node
                else -> TODO("not implemented")
            }
            return selectionSet.selections.flatMap { selection: Selection<*> ->
                if (selection is InlineFragment) {
                    selection.selectionSet.selections.map {
                        ServerRequestSelection(
                            parent = this,
                            variables = variables,
                            node = it,
                            errors = errors,
                            typeName = selection.typeCondition?.name,
                            errorFactory = errorFactory,
                            keyByFieldName = keyByFieldName,
                        )
                    }
                } else {
                    listOf(
                        ServerRequestSelection(
                            parent = this,
                            variables = variables,
                            node = selection,
                            errors = errors,
                            errorFactory = errorFactory,
                            keyByFieldName = keyByFieldName,
                        ),
                    )
                }
            }
        }
    override val parameters: Map<String, String>
        get() = (node as? Field)?.arguments.orEmpty().associate { it.name to getJsonValue(it.value).toString() }

    private fun getJsonValue(value: Value<*>?): JsonElement {
        return when (value) {
            is BooleanValue -> value.isValue.let { JsonPrimitive(it) }
            is StringValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
            is IntValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
            is FloatValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
            is EnumValue -> JsonPrimitive(value.name)
            is ArrayValue -> JsonArray(value.values.map { getJsonValue(it) })
            is ObjectValue -> buildJsonObject {
                value.objectFields.forEach { field ->
                    put(field.name, getJsonValue(field.value))
                }
            }
            is VariableReference -> variables[value.name] ?: JsonNull
            is NullValue, null -> JsonNull
            else -> throw IllegalArgumentException("Unsupported argument value: ${value.javaClass.name}")
        }
    }

    override fun inputParameter(key: String): JsonElement =
        getJsonValue((node as Field).arguments.find { it.name == key }?.value)

    override fun variable(key: String): JsonElement {
        return variables[key] ?: JsonNull
    }

    override fun forIndex(index: Int): RequestSelection = ServerRequestSelection(
        parent = parent,
        variables = variables,
        node = node,
        errors = errors,
        typeName = typeName,
        errorFactory = errorFactory,
        pathOverride = path + GraphQLPathSegment.Index(index),
        keyByFieldName = keyByFieldName,
    )

    override fun error(t: Throwable) {
        errors.add(errorFactory.create(t, path))
    }

    override val path: List<GraphQLPathSegment>
        get() {
            pathOverride?.let { return it }
            val field = when (val currentNode = node) {
                is Field -> GraphQLPathSegment.Field(currentNode.alias ?: currentNode.name)
                is NamedNode<*> -> GraphQLPathSegment.Field(currentNode.name)
                else -> null
            }
            return parent?.path.orEmpty() + listOfNotNull(field)
        }

    public companion object {
        private const val ARGUMENT_VARIABLE_PREFIX = "__graphkt_argument_"

        /**
         * Builds a root selection holding the single field [fieldName], for requests that deliver a
         * field's selection set and its already-resolved arguments separately rather than as one
         * document. AWS AppSync HTTP and Lambda resolvers do this, through `info.selectionSetGraphQL`
         * and `arguments`. Pass the result to a generated root `gqlSelect`, and read the field's
         * value from the returned object under [fieldName]. `gqlSelect` returns [JsonNull] instead
         * of an object when the field is non-null and a null propagated to it, so the field's value
         * is null and [errors] holds the failures.
         *
         * AppSync applies the aliases of the original request itself: it reads each field of the
         * returned value by its field name and then renames it. The returned selection therefore
         * sets [keyByFieldName], so every field below the root is keyed by its field name, as 2.x
         * did, while error paths keep the aliases. A consequence is that one result cannot serve two
         * aliases of the same field, such as `first: search(limit: 1) { id }` and
         * `all: search { id }`: both are keyed `search`, and the one selected last overwrites the
         * other. AppSync reads the same value for both aliases. Give such fields resolvers of their
         * own in AppSync, or request them in separate operations.
         *
         * @param selectionSet the field's selection set, such as `{ id name }`, or null for a field
         * of a leaf type.
         * @param arguments the field's arguments, already resolved to values.
         * @param variables the variables that nested fields of [selectionSet] refer to.
         */
        public fun forRootField(
            fieldName: String,
            selectionSet: String?,
            arguments: Map<String, JsonElement>,
            variables: Map<String, JsonElement>,
            errors: MutableList<GraphQLError>,
            errorFactory: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory.Generic,
        ): ServerRequestSelection {
            // Each argument is passed through a variable of its own, so that resolved JSON values
            // reach the resolver unchanged instead of round-tripping through GraphQL literals.
            val field = Field.newField(fieldName)
                .arguments(arguments.keys.map { Argument(it, VariableReference("$ARGUMENT_VARIABLE_PREFIX$it")) })
                .apply {
                    if (!selectionSet.isNullOrBlank()) {
                        selectionSet(parseGraphQLOperation(selectionSet).selectionSet)
                    }
                }
                .build()
            return ServerRequestSelection(
                parent = null,
                variables = variables + arguments.mapKeys { (name, _) -> "$ARGUMENT_VARIABLE_PREFIX$name" },
                node = SelectionSet.newSelectionSet().selection(field).build(),
                errors = errors,
                errorFactory = errorFactory,
                keyByFieldName = true,
            )
        }
    }
}

public fun buildResponse(data: JsonElement, errors: List<GraphQLError>): JsonObject {
    return buildJsonObject {
        put("data", data)
        if (errors.isNotEmpty()) {
            put("errors", buildJsonArray {
                errors.forEach {
                    add(json.encodeToJsonElement(GraphQLError.serializer(), it))
                }
            })
        }
    }
}
