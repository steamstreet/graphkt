package com.steamstreet.graphkt.server.lambda

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.OutputStream
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

private val graphKtLambdaJson: Json = Json {
    ignoreUnknownKeys = true
}

/** Executes API Gateway v2 HTTP events through the platform-neutral GraphQL server. */
public class GraphQLLambdaHandler<Context>(
    private val server: GraphQLServer<Context>,
    private val context: suspend GraphQLRequestScope.(APIGatewayV2HTTPEvent) -> Context,
    private val onFailure: (Throwable) -> Unit = Throwable::printStackTrace,
) {
    /** Executes one event and returns its API Gateway proxy response. */
    public suspend fun execute(event: APIGatewayV2HTTPEvent): ProxyResponse {
        val method = event.requestContext?.http?.method?.uppercase()
        if (method !in setOf("GET", "POST")) {
            return errorResponse(
                statusCode = 405,
                message = "HTTP method is not supported",
                headers = mapOf("Allow" to "GET, POST"),
            )
        }

        val responseMediaType = negotiateGraphQLHttpResponseMediaType(event.header("Accept"))
            ?: return errorResponse(
                statusCode = 406,
                message = "No supported GraphQL response media type is acceptable",
            )
        if (
            method == "POST" &&
            !isSupportedGraphQLHttpRequestContentType(event.header("Content-Type"))
        ) {
            return errorResponse(
                statusCode = 415,
                message = "GraphQL POST requests require UTF-8 application/json",
            )
        }

        val request = try {
            when (method) {
                "GET" -> event.decodeGetRequest()
                "POST" -> event.decodePostRequest()
                else -> error("The method was checked before request decoding")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: GraphQLHttpRequestException) {
            return errorResponse(statusCode = failure.statusCode, message = "Invalid GraphQL request")
        }

        val response = try {
            if (method == "GET") {
                server.executeQuery(request) { context.invoke(this, event) }
            } else {
                server.execute(request) { context.invoke(this, event) }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: GraphQLOperationNotAllowedException) {
            return graphQLResponse(
                statusCode = 405,
                response = GraphQLResponseEnvelope(
                    errors = listOf(GraphQLError("The selected operation is not allowed with GET")),
                ),
                mediaType = responseMediaType,
                headers = mapOf("Allow" to "POST"),
            )
        } catch (failure: Exception) {
            reportFailure(failure)
            return errorResponse(statusCode = 500, message = "Internal Server Error")
        }
        return graphQLResponse(
            statusCode = response.graphQLHttpStatusCode(),
            response = response,
            mediaType = responseMediaType,
        )
    }

    /** Executes one event and writes the serialized proxy response to an output stream. */
    public fun execute(event: APIGatewayV2HTTPEvent, out: OutputStream): Unit = runBlocking {
        val response = execute(event)
        val encoded = graphKtLambdaJson.encodeToString(ProxyResponse.serializer(), response)
        out.write(encoded.encodeToByteArray())
        out.flush()
    }

    private fun reportFailure(failure: Throwable) {
        try {
            onFailure(failure)
        } catch (_: Exception) {
            // A logging hook must not replace the safe transport response.
        }
    }
}

/** Creates a Lambda handler that builds application context for each request. */
public fun <Context> GraphQLLambda(
    server: GraphQLServer<Context>,
    onFailure: (Throwable) -> Unit = Throwable::printStackTrace,
    context: suspend GraphQLRequestScope.(APIGatewayV2HTTPEvent) -> Context,
): GraphQLLambdaHandler<Context> = GraphQLLambdaHandler(server, context, onFailure)

/** Creates a Lambda handler that does not need transport context. */
public fun GraphQLLambda(
    server: GraphQLServer<Unit>,
): GraphQLLambdaHandler<Unit> = GraphQLLambdaHandler(server, { _ -> Unit })

private fun APIGatewayV2HTTPEvent.decodePostRequest(): GraphQLRequest {
    val encodedBody = body ?: throw GraphQLHttpRequestException(400)
    val decodedBody = try {
        if (isBase64Encoded) {
            Base64.getDecoder().decode(encodedBody).decodeToString(throwOnInvalidSequence = true)
        } else {
            encodedBody
        }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        throw GraphQLHttpRequestException(400, failure)
    }
    val element = try {
        graphKtLambdaJson.parseToJsonElement(decodedBody)
    } catch (failure: SerializationException) {
        throw GraphQLHttpRequestException(400, failure)
    } catch (failure: IllegalArgumentException) {
        throw GraphQLHttpRequestException(400, failure)
    }
    return try {
        graphKtLambdaJson.decodeFromJsonElement(GraphQLRequest.serializer(), element)
    } catch (failure: SerializationException) {
        throw GraphQLHttpRequestException(422, failure)
    } catch (failure: IllegalArgumentException) {
        throw GraphQLHttpRequestException(422, failure)
    }
}

private fun APIGatewayV2HTTPEvent.decodeGetRequest(): GraphQLRequest {
    val parameters = queryStringParameters.orEmpty()
    return GraphQLRequest(
        query = parameters["query"] ?: throw GraphQLHttpRequestException(422),
        operationName = parameters["operationName"].emptyAsNull(),
        variables = parameters["variables"].decodeJsonObject(),
        extensions = parameters["extensions"].decodeJsonObject(),
    )
}

private fun String?.decodeJsonObject(): JsonObject? {
    val value = emptyAsNull() ?: return null
    return try {
        graphKtLambdaJson.parseToJsonElement(value).jsonObject
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        throw GraphQLHttpRequestException(422, failure)
    }
}

private fun String?.emptyAsNull(): String? = this?.takeIf(String::isNotEmpty)

private fun APIGatewayV2HTTPEvent.header(name: String): String? = headers.orEmpty()
    .entries
    .firstOrNull { (headerName) -> headerName.equals(name, ignoreCase = true) }
    ?.value

private fun errorResponse(
    statusCode: Int,
    message: String,
    headers: Map<String, String> = emptyMap(),
): ProxyResponse = graphQLResponse(
    statusCode = statusCode,
    response = GraphQLResponseEnvelope(errors = listOf(GraphQLError(message))),
    mediaType = GraphQLHttpResponseMediaType.JSON,
    headers = headers,
)

private fun graphQLResponse(
    statusCode: Int,
    response: GraphQLResponseEnvelope,
    mediaType: GraphQLHttpResponseMediaType,
    headers: Map<String, String> = emptyMap(),
): ProxyResponse = ProxyResponse(
    statusCode = statusCode,
    body = graphKtLambdaJson.encodeToString(GraphQLResponseEnvelope.serializer(), response),
    headers = mapOf("Content-Type" to "${mediaType.value}; charset=utf-8") + headers,
)

private class GraphQLHttpRequestException(
    val statusCode: Int,
    cause: Throwable? = null,
) : IllegalArgumentException(null, cause)
