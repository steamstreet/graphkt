package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.GraphQLResponseEnvelope
import com.steamstreet.graphkt.server.GraphQLRootFieldResolver
import com.steamstreet.graphkt.server.GraphQLRootResolverFactory
import com.steamstreet.graphkt.server.GraphQLServer
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.execution.GraphQLFieldDefinition
import com.steamstreet.graphkt.server.execution.GraphQLObjectType
import com.steamstreet.graphkt.server.execution.GraphQLScalarType
import com.steamstreet.graphkt.server.execution.GraphQLSchemaDefinition
import com.steamstreet.graphkt.server.execution.GraphQLTypeRef
import com.steamstreet.graphkt.server.resolveFieldValue
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GraphQLKtorTransportTest {
    @Test
    fun `executes a common server with request context`() = testApplication {
        var contextCalls = 0
        var cleanupCalls = 0
        application {
            routing {
                route("/graphql") {
                    graphQL(greetingServer()) {
                        contextCalls += 1
                        onClose { cleanupCalls += 1 }
                        "hello"
                    }
                }
            }
        }

        val response = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ greeting: hello }"}""")
        }
        val envelope = transportJson.decodeFromString(GraphQLResponseEnvelope.serializer(), response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            "application/graphql-response+json",
            response.headers[HttpHeaders.ContentType]?.substringBefore(';'),
        )
        assertEquals(1, contextCalls)
        assertEquals(1, cleanupCalls)
        assertEquals(JsonObject(mapOf("greeting" to JsonPrimitive("hello"))), envelope.data)
        assertEquals(null, envelope.errors)
    }

    @Test
    fun `returns a safe bad request for malformed envelopes`() = testApplication {
        var contextCalls = 0
        application {
            routing {
                route("/graphql") {
                    graphQL(greetingServer()) {
                        contextCalls += 1
                        "unused"
                    }
                }
            }
        }

        val response = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json database-password=secret")
        }
        val body = response.bodyAsText()
        val envelope = transportJson.decodeFromString(GraphQLResponseEnvelope.serializer(), body)

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, contextCalls)
        assertEquals("Invalid GraphQL request", envelope.errors?.single()?.message)
        assertFalse(body.contains("database-password"))
        assertFalse(body.contains("stacktrace", ignoreCase = true))
    }

    @Test
    fun `does not expose resolver exception details`() = testApplication {
        val server = GraphQLServer(
            schema = greetingSchema(),
            query = rootResolver<Unit> { _, field ->
                field.resolveFieldValue(nonNull = false) {
                    throw IllegalStateException("database-password=secret")
                }
            },
        )
        application {
            routing {
                route("/graphql") {
                    graphQL(server)
                }
            }
        }

        val response = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ greeting: hello }"}""")
        }
        val body = response.bodyAsText()
        val envelope = transportJson.decodeFromString(GraphQLResponseEnvelope.serializer(), body)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonObject(mapOf("greeting" to JsonNull)), envelope.data)
        assertEquals("Internal Server Error", envelope.errors?.single()?.message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("greeting")),
            envelope.errors?.single()?.path,
        )
        assertFalse(body.contains("database-password"))
        assertFalse(body.contains("stacktrace", ignoreCase = true))
    }

    @Test
    fun `negotiates JSON and rejects unsupported media types before context construction`() = testApplication {
        var contextCalls = 0
        application {
            routing {
                route("/graphql") {
                    graphQL(greetingServer()) {
                        contextCalls += 1
                        "hello"
                    }
                }
            }
        }

        val jsonResponse = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, "application/json")
            setBody("""{"query":"{ hello }"}""")
        }
        val unacceptableResponse = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            header(HttpHeaders.Accept, "text/plain")
            setBody("""{"query":"{ hello }"}""")
        }
        val unsupportedContentResponse = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            setBody("""{"query":"{ hello }"}""")
        }

        assertEquals(HttpStatusCode.OK, jsonResponse.status)
        assertEquals("application/json", jsonResponse.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals(HttpStatusCode.NotAcceptable, unacceptableResponse.status)
        assertEquals(HttpStatusCode.UnsupportedMediaType, unsupportedContentResponse.status)
        assertEquals(1, contextCalls)
    }

    @Test
    fun `maps envelope document and request failures to distinct statuses`() = testApplication {
        var contextCalls = 0
        application {
            routing {
                route("/graphql") {
                    graphQL(greetingServer()) {
                        contextCalls += 1
                        "unused"
                    }
                }
            }
        }

        val invalidEnvelope = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }
        val invalidDocument = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{"}""")
        }
        val invalidRequest = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ missing }"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, invalidEnvelope.status)
        assertEquals(HttpStatusCode.BadRequest, invalidDocument.status)
        assertEquals(HttpStatusCode.UnprocessableEntity, invalidRequest.status)
        assertEquals(0, contextCalls)
    }

    @Test
    fun `supports GET and rejects GET mutations and unsupported methods`() = testApplication {
        application {
            routing {
                route("/graphql") {
                    graphQL(queryAndMutationServer())
                }
            }
        }

        val queryResponse = client.get("/graphql") {
            parameter("query", "{ hello }")
        }
        val mutationResponse = client.get("/graphql") {
            parameter("query", "mutation { change }")
        }
        val deleteResponse = client.delete("/graphql")

        assertEquals(HttpStatusCode.OK, queryResponse.status)
        assertEquals(HttpStatusCode.MethodNotAllowed, mutationResponse.status)
        assertEquals("POST", mutationResponse.headers[HttpHeaders.Allow])
        assertEquals(HttpStatusCode.MethodNotAllowed, deleteResponse.status)
        assertEquals("GET, POST", deleteResponse.headers[HttpHeaders.Allow])
    }
}

private fun greetingServer(): GraphQLServer<String> = GraphQLServer(
    schema = greetingSchema(),
    query = rootResolver { context, field ->
        field.resolveFieldValue(nonNull = false) {
            JsonPrimitive(context)
        }
    },
)

private fun <Context> rootResolver(
    block: suspend (Context, RequestSelection) -> kotlinx.serialization.json.JsonElement?,
): GraphQLRootResolverFactory<Context> = GraphQLRootResolverFactory { context ->
    GraphQLRootFieldResolver { selection -> block(context, selection) }
}

private fun greetingSchema(): GraphQLSchemaDefinition = GraphQLSchemaDefinition(
    queryType = "Query",
    types = listOf(
        GraphQLScalarType("String"),
        GraphQLObjectType(
            name = "Query",
            fields = listOf(
                GraphQLFieldDefinition("hello", GraphQLTypeRef.Named("String")),
            ),
        ),
    ),
)

private fun queryAndMutationServer(): GraphQLServer<Unit> = GraphQLServer(
    schema = GraphQLSchemaDefinition(
        queryType = "Query",
        mutationType = "Mutation",
        types = listOf(
            GraphQLScalarType("String"),
            GraphQLObjectType(
                name = "Query",
                fields = listOf(GraphQLFieldDefinition("hello", GraphQLTypeRef.Named("String"))),
            ),
            GraphQLObjectType(
                name = "Mutation",
                fields = listOf(GraphQLFieldDefinition("change", GraphQLTypeRef.Named("String"))),
            ),
        ),
    ),
    query = rootResolver<Unit> { _, _ -> JsonPrimitive("hello") },
    mutation = rootResolver<Unit> { _, _ -> JsonPrimitive("changed") },
)

private val transportJson: Json = Json { ignoreUnknownKeys = true }
