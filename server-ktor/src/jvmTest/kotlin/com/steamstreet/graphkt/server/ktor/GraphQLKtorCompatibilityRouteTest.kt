package com.steamstreet.graphkt.server.ktor

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.server.GraphQLResolverErrorFactory
import com.steamstreet.graphkt.server.RequestSelection
import com.steamstreet.graphkt.server.resolveFieldValue
import com.steamstreet.graphkt.server.resolveSelectionSet
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphQLKtorCompatibilityRouteTest {
    @Test
    fun `responds with null data and the recorded errors when a non-null root field fails`() = testApplication {
        val handledErrors = mutableListOf<GraphQLError>()
        application {
            routing {
                route("/graphql") {
                    graphQL(errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails) {
                        query { _, selection -> selectQuery(selection) }
                        errorHandler { errors -> handledErrors += errors }
                    }
                }
            }
        }

        val response = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ event { id title } version }"}""")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonNull, body["data"])
        val error = body.getValue("errors").jsonArray.single().jsonObject
        assertEquals("title unavailable", error.getValue("message").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("event"), JsonPrimitive("title"))), error["path"])
        assertTrue(
            error.getValue("extensions").jsonObject.getValue("stacktrace").jsonPrimitive.content
                .contains("IllegalStateException"),
        )
        assertEquals(
            listOf(listOf(GraphQLPathSegment.Field("event"), GraphQLPathSegment.Field("title"))),
            handledErrors.map { it.path },
        )
    }

    @Test
    fun `records the generic error with its path by default`() = testApplication {
        application {
            routing {
                route("/graphql") {
                    // Named, because a trailing lambda also matches the query and mutation overload.
                    graphQL(block = {
                        query { _, selection -> selectQuery(selection) }
                    })
                }
            }
        }

        val response = client.post("/graphql") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ maybeEvent { id title } version }"}""")
        }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            JsonObject(mapOf("maybeEvent" to JsonNull, "version" to JsonPrimitive("3"))),
            body["data"],
        )
        val error = body.getValue("errors").jsonArray.single().jsonObject
        assertEquals("Internal Server Error", error.getValue("message").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive("maybeEvent"), JsonPrimitive("title"))), error["path"])
        assertEquals(null, error["extensions"])
    }
}

// Mirrors the mapping code that the generator emits for this schema:
//
//     type Query { event: Event!  maybeEvent: Event  version: String! }
//     type Event { id: ID!  title: String! }
private suspend fun selectQuery(field: RequestSelection): JsonElement = field.resolveSelectionSet { child ->
    when (child.name) {
        "event" -> child.resolveFieldValue(nonNull = true) { selectEvent(child) }
        "maybeEvent" -> child.resolveFieldValue(nonNull = false) { selectEvent(child) }
        "version" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("3") }
        else -> throw IllegalArgumentException("Unknown field '${child.name}' on Query")
    }
}

private suspend fun selectEvent(field: RequestSelection): JsonElement = field.resolveSelectionSet { child ->
    when (child.name) {
        "id" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("e1") }
        "title" -> child.resolveFieldValue(nonNull = true) { throw IllegalStateException("title unavailable") }
        else -> throw IllegalArgumentException("Unknown field '${child.name}' on Event")
    }
}
