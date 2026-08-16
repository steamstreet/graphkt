package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.GraphQLResponseEnvelope
import com.steamstreet.graphkt.server.GraphQLHttpResponseMediaType
import com.steamstreet.graphkt.server.GraphQLOperationNotAllowedException
import com.steamstreet.graphkt.server.GraphQLRequestScope
import com.steamstreet.graphkt.server.GraphQLServer
import com.steamstreet.graphkt.server.graphQLHttpStatusCode
import com.steamstreet.graphkt.server.isSupportedGraphQLHttpRequestContentType
import com.steamstreet.graphkt.server.negotiateGraphQLHttpResponseMediaType
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException

private val graphKtTransportJson: Json = Json {
    ignoreUnknownKeys = true
}

/** Installs GET and POST endpoints backed by the platform-neutral GraphQL execution engine. */
public fun <Context> Route.graphQL(
    server: GraphQLServer<Context>,
    context: suspend GraphQLRequestScope.(ApplicationCall) -> Context,
) {
    get {
        call.handleGraphQL(server, context, GraphQLHttpMethod.GET)
    }
    post {
        call.handleGraphQL(server, context, GraphQLHttpMethod.POST)
    }
    handle {
        call.response.headers.append(HttpHeaders.Allow, "GET, POST")
        call.respondTransportError(HttpStatusCode.MethodNotAllowed, "HTTP method is not supported")
    }
}

/** Installs GET and POST endpoints for a server that does not need transport context. */
public fun Route.graphQL(server: GraphQLServer<Unit>) {
    graphQL(server) { Unit }
}

private suspend fun <Context> ApplicationCall.handleGraphQL(
    server: GraphQLServer<Context>,
    context: suspend GraphQLRequestScope.(ApplicationCall) -> Context,
    method: GraphQLHttpMethod,
) {
    val responseMediaType = negotiateGraphQLHttpResponseMediaType(request.headers[HttpHeaders.Accept])
    if (responseMediaType == null) {
        respondTransportError(HttpStatusCode.NotAcceptable, "No supported GraphQL response media type is acceptable")
        return
    }
    if (
        method == GraphQLHttpMethod.POST &&
        !isSupportedGraphQLHttpRequestContentType(request.headers[HttpHeaders.ContentType])
    ) {
        respondTransportError(HttpStatusCode.UnsupportedMediaType, "GraphQL POST requests require UTF-8 application/json")
        return
    }

    val graphQLRequest = try {
        when (method) {
            GraphQLHttpMethod.GET -> decodeGetRequest()
            GraphQLHttpMethod.POST -> decodePostRequest(receiveText())
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: GraphQLHttpRequestException) {
        respondTransportError(HttpStatusCode.fromValue(failure.statusCode), "Invalid GraphQL request")
        return
    } catch (failure: Exception) {
        application.log.debug("GraphQL request body could not be decoded", failure)
        respondTransportError(HttpStatusCode.BadRequest, "Invalid GraphQL request")
        return
    }

    val response = try {
        when (method) {
            GraphQLHttpMethod.GET -> server.executeQuery(graphQLRequest) {
                context.invoke(this, this@handleGraphQL)
            }
            GraphQLHttpMethod.POST -> server.execute(graphQLRequest) {
                context.invoke(this, this@handleGraphQL)
            }
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: GraphQLOperationNotAllowedException) {
        this.response.headers.append(HttpHeaders.Allow, "POST")
        respondGraphQL(
            response = GraphQLResponseEnvelope(
                errors = listOf(GraphQLError("The selected operation is not allowed with GET")),
            ),
            status = HttpStatusCode.MethodNotAllowed,
            mediaType = responseMediaType,
        )
        return
    } catch (failure: Exception) {
        application.log.error("GraphQL request failed before execution completed", failure)
        respondTransportError(HttpStatusCode.InternalServerError, "Internal Server Error")
        return
    }

    respondGraphQL(
        response = response,
        status = HttpStatusCode.fromValue(response.graphQLHttpStatusCode()),
        mediaType = responseMediaType,
    )
}

private fun ApplicationCall.decodeGetRequest(): GraphQLRequest {
    val parameters = request.queryParameters
    return GraphQLRequest(
        query = parameters["query"] ?: throw GraphQLHttpRequestException(422),
        operationName = parameters["operationName"].emptyAsNull(),
        variables = parameters["variables"].decodeJsonObject(),
        extensions = parameters["extensions"].decodeJsonObject(),
    )
}

private fun decodePostRequest(body: String): GraphQLRequest {
    val element = try {
        graphKtTransportJson.parseToJsonElement(body)
    } catch (failure: SerializationException) {
        throw GraphQLHttpRequestException(400, failure)
    } catch (failure: IllegalArgumentException) {
        throw GraphQLHttpRequestException(400, failure)
    }
    return try {
        graphKtTransportJson.decodeFromJsonElement(GraphQLRequest.serializer(), element)
    } catch (failure: SerializationException) {
        throw GraphQLHttpRequestException(422, failure)
    } catch (failure: IllegalArgumentException) {
        throw GraphQLHttpRequestException(422, failure)
    }
}

private fun String?.decodeJsonObject(): JsonObject? {
    val value = emptyAsNull() ?: return null
    return try {
        graphKtTransportJson.parseToJsonElement(value).jsonObject
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        throw GraphQLHttpRequestException(422, failure)
    }
}

private fun String?.emptyAsNull(): String? = this?.takeIf(String::isNotEmpty)

private suspend fun ApplicationCall.respondTransportError(
    status: HttpStatusCode,
    message: String,
) {
    respondGraphQL(
        response = GraphQLResponseEnvelope(errors = listOf(GraphQLError(message))),
        status = status,
        mediaType = GraphQLHttpResponseMediaType.JSON,
    )
}

private suspend fun ApplicationCall.respondGraphQL(
    response: GraphQLResponseEnvelope,
    status: HttpStatusCode,
    mediaType: GraphQLHttpResponseMediaType,
) {
    respondText(
        text = graphKtTransportJson.encodeToString(GraphQLResponseEnvelope.serializer(), response),
        contentType = ContentType.parse(mediaType.value).withCharset(Charsets.UTF_8),
        status = status,
    )
}

private class GraphQLHttpRequestException(
    val statusCode: Int,
    cause: Throwable? = null,
) : IllegalArgumentException(null, cause)

private enum class GraphQLHttpMethod {
    GET,
    POST,
}
