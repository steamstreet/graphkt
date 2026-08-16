package com.steamstreet.graphkt.server.lambda

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent
import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.GraphQLResponseEnvelope
import com.steamstreet.graphkt.server.GraphQLRootFieldResolver
import com.steamstreet.graphkt.server.GraphQLRootResolverFactory
import com.steamstreet.graphkt.server.GraphQLServer
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.execution.GraphQLFieldDefinition
import com.steamstreet.graphkt.server.execution.GraphQLInputValueDefinition
import com.steamstreet.graphkt.server.execution.GraphQLObjectType
import com.steamstreet.graphkt.server.execution.GraphQLScalarType
import com.steamstreet.graphkt.server.execution.GraphQLSchemaDefinition
import com.steamstreet.graphkt.server.execution.GraphQLTypeRef
import com.steamstreet.graphkt.server.resolveFieldValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class GraphQLLambdaHandlerTest {
    @Test
    fun `executes a base64 POST request and writes a proxy response`() {
        var contextCalls = 0
        var cleanupCalls = 0
        val handler = GraphQLLambda(echoServer()) { event ->
            contextCalls += 1
            onClose { cleanupCalls += 1 }
            event.headers.orEmpty()["x-prefix"].orEmpty()
        }
        val requestBody = lambdaJson.encodeToString(
            GraphQLRequest.serializer(),
            GraphQLRequest("query Echo(${DOLLAR}value: String!) { reply: echo(value: ${DOLLAR}value) }", variables = variables("Ada")),
        )
        val event = event("POST").apply {
            headers = mapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "x-prefix" to "hello",
            )
            body = Base64.getEncoder().encodeToString(requestBody.encodeToByteArray())
            isBase64Encoded = true
        }
        val output = ByteArrayOutputStream()

        handler.execute(event, output)

        val proxy = lambdaJson.decodeFromString(ProxyResponse.serializer(), output.toString())
        val envelope = lambdaJson.decodeFromString(GraphQLResponseEnvelope.serializer(), proxy.body.orEmpty())
        assertEquals(200, proxy.statusCode)
        assertEquals("application/graphql-response+json; charset=utf-8", proxy.headers?.get("Content-Type"))
        assertEquals(1, contextCalls)
        assertEquals(1, cleanupCalls)
        assertEquals(JsonObject(mapOf("reply" to JsonPrimitive("hello:Ada"))), envelope.data)
        assertEquals(null, envelope.errors)
    }

    @Test
    fun `decodes GET variables and operation name`() = runBlocking {
        val handler = GraphQLLambda(echoServer()) { "get" }
        val event = event("GET").apply {
            queryStringParameters = mapOf(
                "query" to "query Echo(${DOLLAR}value: String!) { echo(value: ${DOLLAR}value) }",
                "operationName" to "Echo",
                "variables" to variables("Grace").toString(),
            )
        }

        val proxy = handler.execute(event)
        val envelope = lambdaJson.decodeFromString(GraphQLResponseEnvelope.serializer(), proxy.body.orEmpty())

        assertEquals(200, proxy.statusCode)
        assertEquals(JsonObject(mapOf("echo" to JsonPrimitive("get:Grace"))), envelope.data)
    }

    @Test
    fun `rejects malformed request bodies before context construction`() = runBlocking {
        var contextCalls = 0
        val handler = GraphQLLambda(echoServer()) {
            contextCalls += 1
            "unused"
        }
        val event = event("POST").apply {
            body = "not-valid-base64 database-password=secret"
            isBase64Encoded = true
        }

        val proxy = handler.execute(event)
        val body = proxy.body.orEmpty()
        val envelope = lambdaJson.decodeFromString(GraphQLResponseEnvelope.serializer(), body)

        assertEquals(400, proxy.statusCode)
        assertEquals(0, contextCalls)
        assertEquals("Invalid GraphQL request", envelope.errors?.single()?.message)
        assertFalse(body.contains("database-password"))
        assertFalse(body.contains("stacktrace", ignoreCase = true))
    }

    @Test
    fun `negotiates JSON and rejects unsupported media types before context construction`() = runBlocking {
        var contextCalls = 0
        val handler = GraphQLLambda(echoServer()) {
            contextCalls += 1
            "lambda"
        }
        val requestBody = lambdaJson.encodeToString(
            GraphQLRequest.serializer(),
            GraphQLRequest("{ echo(value: \"Ada\") }"),
        )
        val jsonEvent = event("POST").apply {
            headers = mapOf(
                "content-type" to "application/json",
                "accept" to "application/json",
            )
            body = requestBody
        }
        val unacceptableEvent = event("POST").apply {
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "text/plain",
            )
            body = requestBody
        }
        val unsupportedContentEvent = event("POST").apply {
            headers = mapOf("Content-Type" to "text/plain")
            body = requestBody
        }

        val jsonResponse = handler.execute(jsonEvent)
        val unacceptableResponse = handler.execute(unacceptableEvent)
        val unsupportedContentResponse = handler.execute(unsupportedContentEvent)

        assertEquals(200, jsonResponse.statusCode)
        assertEquals("application/json; charset=utf-8", jsonResponse.headers?.get("Content-Type"))
        assertEquals(406, unacceptableResponse.statusCode)
        assertEquals(415, unsupportedContentResponse.statusCode)
        assertEquals(1, contextCalls)
    }

    @Test
    fun `distinguishes malformed JSON from an invalid request envelope`() = runBlocking {
        var contextCalls = 0
        val handler = GraphQLLambda(echoServer()) {
            contextCalls += 1
            "unused"
        }
        val malformedJson = event("POST").apply { body = "{" }
        val invalidEnvelope = event("POST").apply { body = "{}" }

        val malformedResponse = handler.execute(malformedJson)
        val invalidEnvelopeResponse = handler.execute(invalidEnvelope)

        assertEquals(400, malformedResponse.statusCode)
        assertEquals(422, invalidEnvelopeResponse.statusCode)
        assertEquals(0, contextCalls)
    }

    @Test
    fun `maps document and validation failures to distinct statuses`() = runBlocking {
        var contextCalls = 0
        val handler = GraphQLLambda(echoServer()) {
            contextCalls += 1
            "unused"
        }
        val invalidDocument = event("POST").apply {
            body = """{"query":"{"}"""
        }
        val invalidRequest = event("POST").apply {
            body = """{"query":"{ missing }"}"""
        }

        val documentResponse = handler.execute(invalidDocument)
        val requestResponse = handler.execute(invalidRequest)

        assertEquals(400, documentResponse.statusCode)
        assertEquals(422, requestResponse.statusCode)
        assertEquals(0, contextCalls)
    }

    @Test
    fun `returns a safe response when context construction fails`() = runBlocking {
        val privateFailure = IllegalStateException("database-password=secret")
        var reportedFailure: Throwable? = null
        val handler = GraphQLLambdaHandler(
            server = echoServer(),
            context = { throw privateFailure },
            onFailure = { reportedFailure = it },
        )
        val event = event("POST").apply {
            body = lambdaJson.encodeToString(
                GraphQLRequest.serializer(),
                GraphQLRequest("{ echo(value: \"Ada\") }"),
            )
        }

        val proxy = handler.execute(event)
        val body = proxy.body.orEmpty()
        val envelope = lambdaJson.decodeFromString(GraphQLResponseEnvelope.serializer(), body)

        assertEquals(500, proxy.statusCode)
        assertSame(privateFailure, reportedFailure)
        assertEquals("Internal Server Error", envelope.errors?.single()?.message)
        assertFalse(body.contains("database-password"))
        assertFalse(body.contains("stacktrace", ignoreCase = true))
    }

    @Test
    fun `rejects unsupported HTTP methods`() = runBlocking {
        val handler = GraphQLLambda(echoServer()) { "unused" }

        val proxy = handler.execute(event("DELETE"))

        assertEquals(405, proxy.statusCode)
        assertEquals("GET, POST", proxy.headers?.get("Allow"))
    }

    @Test
    fun `rejects mutations over GET before context construction`() = runBlocking {
        var contextCalls = 0
        val handler = GraphQLLambda(mutationServer()) {
            contextCalls += 1
            Unit
        }
        val event = event("GET").apply {
            queryStringParameters = mapOf(
                "query" to "mutation { change(value: \"new\") }",
            )
        }

        val proxy = handler.execute(event)
        val envelope = lambdaJson.decodeFromString(GraphQLResponseEnvelope.serializer(), proxy.body.orEmpty())

        assertEquals(405, proxy.statusCode)
        assertEquals("POST", proxy.headers?.get("Allow"))
        assertEquals(0, contextCalls)
        assertEquals("The selected operation is not allowed with GET", envelope.errors?.single()?.message)
    }
}

private fun echoServer(): GraphQLServer<String> = GraphQLServer(
    schema = echoSchema(),
    query = rootResolver { context, field ->
        field.resolveFieldValue(nonNull = false) {
            val value = field.inputParameter("value").jsonPrimitive.content
            JsonPrimitive("$context:$value")
        }
    },
)

private fun echoSchema(): GraphQLSchemaDefinition = GraphQLSchemaDefinition(
    queryType = "Query",
    types = listOf(
        GraphQLScalarType("String"),
        GraphQLObjectType(
            name = "Query",
            fields = listOf(
                GraphQLFieldDefinition(
                    name = "echo",
                    type = GraphQLTypeRef.Named("String"),
                    arguments = listOf(
                        GraphQLInputValueDefinition(
                            "value",
                            GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

private fun mutationServer(): GraphQLServer<Unit> = GraphQLServer(
    schema = GraphQLSchemaDefinition(
        queryType = "Query",
        mutationType = "Mutation",
        types = listOf(
            GraphQLScalarType("String"),
            GraphQLObjectType("Query", listOf(GraphQLFieldDefinition("noop", GraphQLTypeRef.Named("String")))),
            GraphQLObjectType(
                "Mutation",
                listOf(
                    GraphQLFieldDefinition(
                        name = "change",
                        type = GraphQLTypeRef.Named("String"),
                        arguments = listOf(
                            GraphQLInputValueDefinition(
                                "value",
                                GraphQLTypeRef.NonNull(GraphQLTypeRef.Named("String")),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    ),
    query = rootResolver<Unit> { _, _ -> JsonNull },
    mutation = rootResolver<Unit> { _, _ -> JsonNull },
)

private fun <Context> rootResolver(
    block: suspend (Context, RequestSelection) -> JsonElement?,
): GraphQLRootResolverFactory<Context> = GraphQLRootResolverFactory { context ->
    GraphQLRootFieldResolver { selection -> block(context, selection) }
}

private fun event(method: String): APIGatewayV2HTTPEvent = APIGatewayV2HTTPEvent().apply {
    if (method.equals("POST", ignoreCase = true)) {
        headers = mapOf("Content-Type" to "application/json")
    }
    requestContext = APIGatewayV2HTTPEvent.RequestContext().apply {
        http = APIGatewayV2HTTPEvent.RequestContext.Http().apply {
            this.method = method
        }
    }
}

private fun variables(value: String): JsonObject = JsonObject(mapOf("value" to JsonPrimitive(value)))

private val lambdaJson: Json = Json { ignoreUnknownKeys = true }

private const val DOLLAR: Char = '$'
