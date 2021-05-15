package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import graphql.language.*
import graphql.parser.Parser
import kotlinx.serialization.json.*
import java.io.PrintWriter
import java.io.StringWriter

val gqlContext = ThreadLocal<RequestSelection>()
val json = Json {}

actual fun gqlRequestContext(): RequestSelection? {
    return gqlContext.get()
}

fun parseGraphQLOperation(query: String): OperationDefinition {
    val parser = Parser()
    val result = parser.parseDocument(query)
    return result.definitions.firstOrNull() as? OperationDefinition
        ?: throw IllegalArgumentException("Operation was not found")
}

class ServerRequestSelection(
    val parent: ServerRequestSelection?,
    val variables: Map<String, JsonElement>,
    val node: Node<*>,
    val errors: MutableList<GraphQLError>
) : RequestSelection {
    override val name: String
        get() = (node as? NamedNode<*>)?.name ?: throw IllegalStateException("Not a named node")
    override val children: List<RequestSelection>
        get() {
            val selectionSet = when (node) {
                is Field -> node.selectionSet
                is SelectionSet -> node
                else -> TODO("not implemented")
            }
            return selectionSet.selections.map { ServerRequestSelection(this, variables, it, errors) }
        }
    override val parameters: Map<String, String>
        get() = TODO("not implemented")

    override fun inputParameter(key: String): JsonElement {
        val value = (node as Field).arguments.find { it.name == key }?.value
        return if (value is VariableReference) {
            val variableName = value.name
            variables[variableName]!!
        } else {
            return when (value) {
                is BooleanValue -> value.isValue.let { JsonPrimitive(it) }
                is StringValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                is IntValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                is FloatValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                null -> JsonNull
                else -> TODO("not implemented")
            }
        }
    }

    override fun variable(key: String): JsonElement {
        return variables[key]!!
    }

    override fun setAsContext() {
        gqlContext.set(this)
    }

    override fun error(t: Throwable) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        t.printStackTrace(printWriter)
        printWriter.flush()

        val error = GraphQLError(t.message ?: "Internal Server Error", extensions = buildJsonObject {
            this.put("stacktrace", writer.toString())
        }, path = path)
        errors.add(error)
    }

    private val path: List<String>
        get() {
            return (parent?.path.orEmpty() + ((node as? NamedNode<*>)?.name)).filterNotNull()
        }
}

fun buildResponse(data: JsonElement, errors: List<GraphQLError>): JsonObject {
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