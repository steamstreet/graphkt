package com.steamstreet.graphkt.server.lambda

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.ServerRequestSelection
import com.steamstreet.graphkt.server.buildResponse
import com.steamstreet.graphkt.server.parseGraphQLOperation
import graphql.GraphQLException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.OutputStream

private val json = Json { }

@Suppress("unused")
@Serializable
class ProxyResponse(
    var statusCode: Int? = null,
    var headers: Map<String, String>? = null,
    var multiValueHeaders: Map<String, List<String>>? = null,
    var body: String? = null,
    var isBase64Encoded: Boolean = false
)

/**
 * Handles GraphQL requests that come through an HTTP lambda proxy.
 */
class GraphQLLambda {
    private lateinit var errorBlock: suspend (List<GraphQLError>) -> Unit
    private lateinit var mutationBlock: suspend (APIGatewayV2HTTPEvent, RequestSelection) -> JsonElement?
    private lateinit var queryBlock: suspend (APIGatewayV2HTTPEvent, RequestSelection) -> JsonElement?

    fun query(block: suspend (APIGatewayV2HTTPEvent, RequestSelection) -> JsonElement?) {
        this.queryBlock = block
    }

    fun mutation(block: suspend (APIGatewayV2HTTPEvent, RequestSelection) -> JsonElement?) {
        this.mutationBlock = block
    }

    /**
     * Install an error handler. This won't impact the response, but will
     * allow for extra handling (logging, etc.)
     */
    fun errorHandler(block: suspend (List<GraphQLError>) -> Unit) {
        this.errorBlock = block
    }

    fun execute(event: APIGatewayV2HTTPEvent, out: OutputStream) = runBlocking {
        val response = try {
            when (event.requestContext.http.method) {
                "GET" -> {
                    val query = event.queryStringParameters["query"]
                    val variablesString = event.queryStringParameters["variables"] ?: "{}"

                    val operation = parseGraphQLOperation(query!!)
                    val variables = Json.parseToJsonElement(variablesString).jsonObject
                    val errors = mutableListOf<GraphQLError>()

                    val result = queryBlock(
                        event, ServerRequestSelection(
                            null, variables,
                            operation.selectionSet, errors
                        )
                    ) ?: throw GraphQLException()

                    if (errors.isNotEmpty()) {
                        errorBlock.invoke(errors)
                    }

                    val response = buildResponse(result, errors)

                    ProxyResponse(
                        statusCode = 200,
                        body = response.toString(),
                        headers = mapOf(
                            "Content-Type" to "application/json"
                        )
                    )
                }

                "POST" -> {
                    val request = Json.parseToJsonElement(event.body).jsonObject
                    val operation = request["query"]?.jsonPrimitive?.contentOrNull?.let {
                        parseGraphQLOperation(it)
                    } ?: throw IllegalArgumentException()
                    val variables = request["variables"]?.jsonObject ?: buildJsonObject { }
                    val errors = mutableListOf<GraphQLError>()

                    val result = mutationBlock(
                        event, ServerRequestSelection(
                            null, variables,
                            operation.selectionSet, errors
                        )
                    ) ?: throw GraphQLException()

                    if (errors.isNotEmpty()) {
                        errorBlock.invoke(errors)
                    }

                    val response = buildResponse(result, errors)

                    ProxyResponse(
                        statusCode = 200,
                        body = response.toString(),
                        headers = mapOf(
                            "Content-Type" to "application/json"
                        )
                    )
                }

                else -> {
                    ProxyResponse(statusCode = 404)
                }
            }
        } catch (e: IllegalArgumentException) {
            ProxyResponse(statusCode = 404)
        }

        val responseString = json.encodeToString(ProxyResponse.serializer(), response)
        out.write(responseString.toByteArray())
        out.flush()
    }
}

fun GraphQLLambda(block: GraphQLLambda.()->Unit): GraphQLLambda =
    GraphQLLambda().apply(block)