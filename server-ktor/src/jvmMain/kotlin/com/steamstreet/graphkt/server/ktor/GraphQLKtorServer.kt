package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.server.GraphQLResolverErrorFactory
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.ServerRequestSelection
import com.steamstreet.graphkt.server.buildResponse
import com.steamstreet.graphkt.server.parseGraphQLOperation
import graphql.language.OperationDefinition
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

public interface GraphQLConfiguration {
    public fun query(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)
    public fun mutation(block: suspend (ApplicationCall, RequestSelection) -> JsonElement?)

    /**
     * Install an error handler. This won't impact the response, but will
     * allow for extra handling (logging, etc.)
     */
    public fun errorHandler(block: suspend (List<GraphQLError>) -> Unit)
}

/**
 * Simpler configuration of routes.
 */
public fun Route.graphQL(
    query: (suspend (RequestSelection) -> JsonElement?)? = null,
    mutation: (suspend (RequestSelection) -> JsonElement?)? = null,
) {
    this.graphQL(block = {
        if (query != null) {
            query { _, selection ->
                query(selection)
            }
        }
        if (mutation != null) {
            mutation { _, selection ->
                mutation(selection)
            }
        }
    })
}

/**
 * Initialize the GraphQL system. Provide a callback that will create the root GraphQL object.
 *
 * Resolver failures are recorded as field errors with a generic message. To record other errors,
 * pass a [GraphQLResolverErrorFactory] to the overload that takes one.
 */
@Suppress("unused")
public fun Route.graphQL(block: GraphQLConfiguration.() -> Unit) {
    graphQL(GraphQLResolverErrorFactory.Generic, block)
}

/**
 * Initialize the GraphQL system. Provide a callback that will create the root GraphQL object.
 *
 * Each callback receives a [ServerRequestSelection] for the operation and usually passes it to a
 * generated root `gqlSelect` function. Resolver failures are recorded as field errors through
 * [errorFactory].
 *
 * Execution follows GraphQL null propagation. When a callback returns [JsonNull], because a failed
 * non-null root field made the whole result null, the response is `{"data": null, "errors": [...]}`
 * with the recorded errors.
 */
public fun Route.graphQL(
    errorFactory: GraphQLResolverErrorFactory,
    block: GraphQLConfiguration.() -> Unit,
) {
    val json = Json {
        ignoreUnknownKeys = true
    }

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
        if (t is CancellationException) throw t
        val error = GraphQLError("Internal Server Error")

        if (errorHandler != null) {
            errorHandler.invoke(listOf(error))
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
                parent = null,
                variables = variables ?: emptyMap(),
                node = query?.selectionSet ?: throw IllegalArgumentException(),
                errors = errors,
                errorFactory = errorFactory,
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
        val variables = requestElement["variables"] as? JsonObject

        val operationInvocation = when (query?.operation) {
            OperationDefinition.Operation.QUERY -> queryGetter
            OperationDefinition.Operation.MUTATION -> mutationGetter
            else -> null
        }
        invoke(call, query, variables, operationInvocation)
    }

    get {
        val query = call.request.queryParameters["query"]?.let {
            parseGraphQLOperation(it)
        }
        val variables = call.request.queryParameters["variables"]?.let {
            json.parseToJsonElement(it) as? JsonObject
        }
        invoke(call, query, variables, queryGetter)
    }
}
