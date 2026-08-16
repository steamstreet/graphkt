package com.steamstreet.graphkt.client.ktor

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQLKtorClientTest {
    @Test
    fun namedQueryIncludesNameInDocumentAndEnvelope() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("NamedQuery", request.url.parameters["operationName"])
            assertTrue(request.url.parameters["query"]!!.startsWith("query NamedQuery"))
            assertEquals(GRAPHQL_ACCEPT, request.headers[HttpHeaders.Accept])
            respond(
                content = """{"data":{"greeting":"hello"}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = GraphQLKtorClient("https://example.test/graphql", engine)

        client.execute("NamedQuery", Json) {
            type = "query"
            println("greeting")
        }
    }

    @Test
    fun namedMutationUsesJsonEnvelope() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(GRAPHQL_ACCEPT, request.headers[HttpHeaders.Accept])
            assertEquals(ContentType.Application.Json, request.body.contentType)
            val envelope = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("NamedMutation", envelope["operationName"]?.jsonPrimitive?.content)
            assertTrue(envelope["query"]!!.jsonPrimitive.content.startsWith("mutation NamedMutation"))
            respond(
                content = """{"data":{"updated":true}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = GraphQLKtorClient("https://example.test/graphql", engine)

        client.execute("NamedMutation", Json) {
            type = "mutation"
            println("updated")
        }
    }

    private companion object {
        const val GRAPHQL_ACCEPT: String = "application/graphql-response+json, application/json;q=0.9"
    }
}
