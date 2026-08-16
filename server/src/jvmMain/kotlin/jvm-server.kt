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

public class ServerRequestSelection(
    public val parent: ServerRequestSelection?,
    public val variables: Map<String, JsonElement>,
    public val node: Node<*>,
    public val errors: MutableList<GraphQLError>,
    override val typeName: String? = null,
    private val pathOverride: List<GraphQLPathSegment>? = null,
) : RequestSelection {
    override val name: String
        get() = (node as? NamedNode<*>)?.name ?: throw IllegalStateException("Not a named node")
    override val responseName: String
        get() = (node as? Field)?.alias ?: name
    override val children: List<RequestSelection>
        get() {
            val selectionSet = when (node) {
                is Field -> node.selectionSet
                is SelectionSet -> node
                else -> TODO("not implemented")
            }
            return selectionSet.selections.flatMap { selection: Selection<*> ->
                if (selection is InlineFragment) {
                    selection.selectionSet.selections.map {
                        ServerRequestSelection(this, variables, it, errors, selection.typeCondition?.name)
                    }
                } else {
                    listOf(ServerRequestSelection(this, variables, selection, errors))
                }
            }
        }
    override val parameters: Map<String, String>
        get() = TODO("not implemented")

    private fun getJsonValue(value: Value<*>?): JsonElement {
        return when (value) {
            is BooleanValue -> value.isValue.let { JsonPrimitive(it) }
            is StringValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
            is IntValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
            is FloatValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
            is ObjectValue -> buildJsonObject {
                value.objectFields.forEach { field ->
                    put(field.name, getJsonValue(field.value))
                }
            }
            null -> JsonNull
            else -> TODO("not implemented: ${value.javaClass.name}")
        }
    }

    override fun inputParameter(key: String): JsonElement {
        val value = (node as Field).arguments.find { it.name == key }?.value
        return if (value is VariableReference) {
            val variableName = value.name
            variables[variableName]!!
        } else {
            return getJsonValue(value)
        }
    }

    override fun variable(key: String): JsonElement {
        return variables[key]!!
    }

    override fun forIndex(index: Int): RequestSelection = ServerRequestSelection(
        parent = parent,
        variables = variables,
        node = node,
        errors = errors,
        typeName = typeName,
        pathOverride = path + GraphQLPathSegment.Index(index),
    )

    override fun error(t: Throwable) {
        val error = GraphQLError("Internal Server Error", path = path)
        errors.add(error)
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
