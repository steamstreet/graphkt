package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.server.GraphQLResolverErrorFactory
import com.steamstreet.graphkt.server.RequestOperation
import com.steamstreet.graphkt.server.RequestOperationType
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.buildResponse
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
 * Each callback receives a [RequestSelection] for the operation and usually passes it to a
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

    // As the JVM route did, the document is parsed outside the error envelope: a document that
    // cannot be parsed fails the request, where a request without a document gets the envelope.
    fun parse(
        query: String?, variables: JsonObject?, operationName: String?, errors: MutableList<GraphQLError>,
    ): RequestOperation? = query?.let {
        RequestOperation.parse(
            query = it,
            variables = variables,
            errors = errors,
            errorFactory = errorFactory,
            operationName = operationName,
        )
    }

    suspend fun invoke(
        call: ApplicationCall, operation: RequestOperation?, errors: List<GraphQLError>,
        function: (suspend (ApplicationCall, RequestSelection) -> JsonElement?)?
    ) {
        try {
            val selection = operation?.selection ?: throw IllegalArgumentException()
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
        val errors = ArrayList<GraphQLError>()
        val operation = parse(
            query = requestElement["query"]?.jsonPrimitive?.contentOrNull,
            variables = requestElement["variables"] as? JsonObject,
            operationName = requestElement["operationName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
            errors = errors,
        )

        val operationInvocation = when (operation?.type) {
            RequestOperationType.QUERY -> queryGetter
            RequestOperationType.MUTATION -> mutationGetter
            else -> null
        }
        invoke(call, operation, errors, operationInvocation)
    }

    get {
        val errors = ArrayList<GraphQLError>()
        val operation = parse(
            query = call.request.queryParameters["query"],
            variables = call.request.queryParameters["variables"]?.let {
                json.parseToJsonElement(it) as? JsonObject
            },
            operationName = call.request.queryParameters["operationName"]?.takeIf { it.isNotEmpty() },
            errors = errors,
        )
        invoke(call, operation, errors, queryGetter)
    }
}
