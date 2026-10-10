package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.resolveFieldValue
import com.steamstreet.graphkt.server.resolveSelectionSet
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The routes that build their selections from the document with the common parser. */
class GraphQLKtorOperationRouteTest {
    private suspend fun HttpClient.post(body: String) = post("/graphql") {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        setBody(body)
    }.bodyAsText()

    @Test
    fun `routes queries and mutations to their own roots and keys fields by alias`() = testApplication {
        application {
            routing {
                route("/graphql") {
                    graphQL(block = {
                        query { _, selection -> selection.resolveSelectionSet { echo("query", it) } }
                        mutation { _, selection -> selection.resolveSelectionSet { echo("mutation", it) } }
                    })
                }
            }
        }

        assertEquals("""{"data":{"a":"query:one","two":"query:one"}}""", client.post("""{"query":"{ a: one two: one }"}"""))
        assertEquals("""{"data":{"one":"mutation:one"}}""", client.post("""{"query":"mutation { one }"}"""))
        assertEquals(
            """{"data":{"two":"query:two"}}""",
            client.post("""{"query":"query A { one } query B { two }","operationName":"B"}"""),
        )
        assertEquals("""{"data":{"one":"query:one"}}""", client.post("""{"query":"query A { one } query B { two }"}"""))
        assertEquals(
            """{"data":{"one":"query:one"}}""",
            client.get("/graphql?query=%7B%20one%20%7D&variables=%7B%22a%22%3A1%7D").bodyAsText(),
        )
    }

    @Test
    fun `answers a missing document or an unsupported operation with the error envelope`() = testApplication {
        application {
            routing {
                route("/graphql") {
                    graphQL(block = {
                        query { _, selection -> selection.resolveSelectionSet { echo("query", it) } }
                    })
                }
            }
        }
        val envelope = """{"errors":[{"message":"Internal Server Error"}]}"""

        assertEquals(envelope, client.post("""{"variables":{}}"""))
        assertEquals(envelope, client.post("""{"query":"mutation { one }"}"""))
        assertEquals(envelope, client.post("""{"query":"subscription { one }"}"""))
    }
}

private suspend fun echo(root: String, field: RequestSelection): JsonElement =
    field.resolveFieldValue(nonNull = false) { JsonPrimitive("$root:${field.name}") }
