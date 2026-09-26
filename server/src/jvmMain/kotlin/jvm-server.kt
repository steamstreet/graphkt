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

/** Converts a resolver failure into the error that a [ServerRequestSelection] records. */
public fun interface GraphQLResolverErrorFactory {
    public fun create(failure: Throwable, path: List<GraphQLPathSegment>): GraphQLError

    public companion object {
        /** Records a generic message, so that no exception detail reaches the response. The default. */
        public val Generic: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory { _, path ->
            GraphQLError("Internal Server Error", path = path)
        }

        /**
         * Records the exception message and, in the `stacktrace` extension, its stack trace, which is
         * what 2.x recorded for every failure. Use it only where the errors never reach an untrusted
         * client, such as in-process rendering or an internal service.
         */
        public val WithExceptionDetails: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory { failure, path ->
            GraphQLError(
                message = failure.message ?: "Internal Server Error",
                path = path,
                extensions = buildJsonObject { put("stacktrace", failure.stackTraceToString()) },
            )
        }
    }
}

/**
 * A [RequestSelection] over a GraphQL Java document, for JVM code that drives generated `gqlSelect`
 * functions directly, as 2.x applications did. It does not validate the document; new code should
 * execute requests through `GraphQLServer`.
 */
public class ServerRequestSelection(
    public val parent: ServerRequestSelection?,
    public val variables: Map<String, JsonElement>,
    public val node: Node<*>,
    public val errors: MutableList<GraphQLError>,
    override val typeName: String? = null,
    private val errorFactory: GraphQLResolverErrorFactory = GraphQLResolverErrorFactory.Generic,
    private val pathOverride: List<GraphQLPathSegment>? = null,
) : RequestSelection {
    override val name: String
        get() = (node as? NamedNode<*>)?.name ?: throw IllegalStateException("Not a named node")
    override val responseName: String
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
                        ServerRequestSelection(this, variables, it, errors, selection.typeCondition?.name, errorFactory)
                    }
                } else {
                    listOf(ServerRequestSelection(this, variables, selection, errors, errorFactory = errorFactory))
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
         * value from the returned object under [fieldName].
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
