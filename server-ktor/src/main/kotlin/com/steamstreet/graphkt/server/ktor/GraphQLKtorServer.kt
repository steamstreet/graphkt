package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.gqlContext
import graphql.language.*
import graphql.parser.Parser
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.*
import kotlinx.serialization.json.*
import java.io.PrintWriter
import java.io.StringWriter

interface GraphQLConfiguration {
    fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)
    fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)

    /**
     * Install an error handler. This won't impact the response, but will
     * allow for extra handling (logging, etc.)
     */
    fun errorHandler(block: suspend (Throwable) -> Unit)
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
    var errorHandler: (suspend (t: Throwable) -> Unit)? = null
    val config: GraphQLConfiguration = object : GraphQLConfiguration {
        override fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?) {
            queryGetter = block
        }

        override fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?) {
            mutationGetter = block
        }

        override fun errorHandler(block: suspend (Throwable) -> Unit) {
            errorHandler = block
        }
    }
    config.block()


    suspend fun ApplicationCall.respondError(t: Throwable) {
        if (errorHandler != null) {
            errorHandler?.invoke(t)
        } else {
            t.printStackTrace()
        }

        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        t.printStackTrace(printWriter)
        printWriter.flush()

        val error = GraphQLError(t.message ?: "Internal Server Error", extensions = buildJsonObject {
            this.put("stacktrace", writer.toString())
        })

        val responseEnvelope = JsonObject(mapOf("errors" to buildJsonArray {
            add(json.encodeToJsonElement(GraphQLError.serializer(), error))
        }))

        respondText(responseEnvelope.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }

    post {
        val request = call.receiveText()
        val requestElement = json.parseToJsonElement(request) as JsonObject
        val query = requestElement["query"]?.jsonPrimitive?.contentOrNull?.let {
            parseQraphQLOperation(it)
        }
        val variables = requestElement["variables"]?.jsonObject

        try {
            val selection = ServerRequestSelection(call, variables ?: emptyMap(),
                    query?.selectionSet ?: throw IllegalArgumentException())
            val result = mutationGetter?.invoke(call, selection) ?: throw NotFoundException()
            val responseEnvelope = JsonObject(mapOf("data" to result))

            call.respondText(responseEnvelope.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (t: Throwable) {
            call.respondError(t)
        }
    }

    get {
        val query = call.request.queryParameters["query"]?.let {
            parseQraphQLOperation(it)
        }
        val variables = call.request.queryParameters["variables"]?.let {
            json.parseToJsonElement(it) as JsonObject
        }

        try {
            val selection = ServerRequestSelection(call, variables ?: emptyMap(),
                    query?.selectionSet ?: throw IllegalArgumentException())
            val result = queryGetter?.invoke(call, selection) ?: throw NotFoundException()
            val responseEnvelope = JsonObject(mapOf("data" to result))

            call.respondText(responseEnvelope.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (t: Throwable) {
            call.respondError(t)
        }
    }
}