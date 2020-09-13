package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.server.RequestSelection
import graphql.GraphQLError
import graphql.language.*
import graphql.parser.Parser
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

interface GraphQLConfiguration {
    fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)
    fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)

    /**
     * Install an error handler. This won't impact the response, but will
     * allow for extra handling (logging, etc.)
     */
    fun errorHandler(block: suspend (List<GraphQLError>) -> Unit)
}

class ServerRequestSelection(val call: ApplicationCall,
                             val variables: Map<String, JsonElement>,
                             val node: Node<*>) : RequestSelection {
    override val name: String
        get() = (node as? NamedNode<*>)?.name ?: throw IllegalStateException("Not a named node")
    override val children: List<RequestSelection>
        get() {
            val selectionSet = when (node) {
                is Field -> node.selectionSet
                is SelectionSet -> node
                else -> TODO("not implemented")
            }
            return selectionSet.selections.map { ServerRequestSelection(call, variables, it) }
        }
    override val parameters: Map<String, String>
        get() = TODO("not implemented")

    override fun inputParameter(key: String): JsonElement {
        val value = (node as Field).arguments.find { it.name == key }?.value
        if (value is VariableReference) {
            TODO("not implemented")
        } else {
            return when (value) {
                is BooleanValue -> value.isValue.let { JsonPrimitive(it) }
                is StringValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                is IntValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                is FloatValue -> value.value?.let { JsonPrimitive(it) } ?: JsonNull
                else -> TODO("not implemented")
            }
        }
    }

    override fun variable(key: String): JsonElement {
        return variables[key]!!
    }
}

/**
 * Initialize the GraphQL system. Provide a callback that will create the root GraphQL object.
 */
@OptIn(KtorExperimentalAPI::class)
@Suppress("BlockingMethodInNonBlockingContext")
fun Route.graphQL(block: GraphQLConfiguration.() -> Unit) {
    val json = Json {}

    fun parseQraphQLOperation(query: String): OperationDefinition {
        val parser = Parser()
        val result = parser.parseDocument(query)
        return result.definitions.firstOrNull() as? OperationDefinition
                ?: throw IllegalArgumentException("Operation was not found")
    }

    var queryGetter: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)? = null
    var mutationGetter: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)? = null
    var errorHandler: (suspend (List<GraphQLError>) -> Unit)? = null
    val config: GraphQLConfiguration = object : GraphQLConfiguration {
        override fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?) {
            queryGetter = block
        }

        override fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?) {
            mutationGetter = block
        }

        override fun errorHandler(block: suspend (List<GraphQLError>) -> Unit) {
            errorHandler = block
        }
    }
    config.block()

    @Serializable
    data class GraphQLRequestEnvelope(
            val query: String,
            val operationName: String?,
            val variables: Map<String, String>?
    )

    post {
        val request = call.receiveText()
        val requestElement = json.parseToJsonElement(request) as JsonObject
        val query = requestElement["query"]
        val variables = requestElement["variables"]
//        val operationName = requestElement["operationName"]?.primitive?.contentOrNull

        call.respondText("", ContentType.Application.Json)
    }

    get {
        val query = call.request.queryParameters["query"]?.let {
            parseQraphQLOperation(it)
        }
        val variables = call.request.queryParameters["variables"]?.let {
            json.parseToJsonElement(it) as JsonObject
        }

        val node: Node<out Node<*>> = query?.children?.first() ?: throw IllegalStateException("Unknown state")

        try {
            val selection = ServerRequestSelection(call, variables ?: emptyMap(), node)
            val result = queryGetter?.invoke(call, selection) ?: throw NotFoundException()
            val responseEnvelope = JsonObject(mapOf("data" to result))

            call.respondText(responseEnvelope.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (t: Throwable) {
            t.printStackTrace()
            throw t
        }
    }
}