package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.ServerRequestSelection
import com.steamstreet.graphkt.server.buildResponse
import com.steamstreet.graphkt.server.parseGraphQLOperation
import graphql.language.OperationDefinition
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
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
    fun errorHandler(block: suspend (List<GraphQLError>) -> Unit)
}

/**
 * Initialize the GraphQL system. Provide a callback that will create the root GraphQL object.
 */
@Suppress("BlockingMethodInNonBlockingContext")
fun Route.graphQL(block: GraphQLConfiguration.() -> Unit) {
    val json = Json {}

    var queryGetter: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)? = null
    var mutationGetter: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)? = null
    var errorHandler: (suspend (t: List<GraphQLError>) -> Unit)? = null
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


    suspend fun ApplicationCall.respondError(t: Throwable) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        t.printStackTrace(printWriter)
        printWriter.flush()

        val error = GraphQLError(t.message ?: "Internal Server Error", extensions = buildJsonObject {
            this.put("stacktrace", writer.toString())
        })

        if (errorHandler != null) {
            errorHandler?.invoke(listOf(error))
        } else {
            t.printStackTrace()
        }

        val responseEnvelope = JsonObject(mapOf("errors" to buildJsonArray {
            add(json.encodeToJsonElement(GraphQLError.serializer(), error))
        }))

        respondText(responseEnvelope.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }

    suspend fun invoke(
        call: ApplicationCall, query: OperationDefinition?, variables: Map<String, JsonElement>?,
        function: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)?
    ) {
        try {
            val errors = ArrayList<GraphQLError>()
            val selection = ServerRequestSelection(
                null, variables ?: emptyMap(),
                query?.selectionSet ?: throw IllegalArgumentException(),
                errors
            )
            val result = function?.invoke(call, selection) ?: throw NotFoundException()
            val response = buildResponse(result, errors)

            if (errors.isNotEmpty()) {
                errorHandler?.invoke(errors)
            }

            call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
        } catch (t: Throwable) {
            call.respondError(t)
        }
    }

    post {
        val request = call.receiveText()
        val requestElement = json.parseToJsonElement(request) as JsonObject
        val query = requestElement["query"]?.jsonPrimitive?.contentOrNull?.let {
            parseGraphQLOperation(it)
        }
        val variables = requestElement["variables"]?.jsonObject

        invoke(call, query, variables, mutationGetter)
    }

    get {
        val query = call.request.queryParameters["query"]?.let {
            parseGraphQLOperation(it)
        }
        val variables = call.request.queryParameters["variables"]?.let {
            json.parseToJsonElement(it) as JsonObject
        }
        invoke(call, query, variables, queryGetter)
    }
}