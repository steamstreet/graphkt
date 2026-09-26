package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import graphql.language.Field
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRequestSelectionTest {
    @Test
    fun doesNotExposeResolverFailureDetails() {
        val errors = mutableListOf<GraphQLError>()
        val selection = ServerRequestSelection(
            parent = null,
            variables = emptyMap(),
            node = Field.newField("secret").build(),
            errors = errors,
        )

        selection.error(IllegalStateException("database-password=should-not-leak"))

        assertEquals(1, errors.size)
        assertEquals("Internal Server Error", errors.single().message)
        assertEquals(listOf(GraphQLPathSegment.Field("secret")), errors.single().path)
        assertNull(errors.single().extensions)
    }

    @Test
    fun recordsExceptionDetailsWhenAskedTo() {
        val errors = mutableListOf<GraphQLError>()
        val selection = ServerRequestSelection(
            parent = null,
            variables = emptyMap(),
            node = Field.newField("render").build(),
            errors = errors,
            errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
        )

        selection.error(IllegalStateException("template missing"))

        assertEquals("template missing", errors.single().message)
        assertTrue(
            errors.single().extensions?.get("stacktrace")?.jsonPrimitive?.content.orEmpty()
                .contains("IllegalStateException"),
        )
    }

    @Test
    fun childSelectionsInheritTheErrorFactory() {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection(
            parent = null,
            variables = emptyMap(),
            node = parseGraphQLOperation("{ page(items: [1, 2], sort: NEWEST, missing: null) { title } }").selectionSet,
            errors = errors,
            errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
        )
        val page = root.children.single()

        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), page.inputParameter("items"))
        assertEquals(JsonPrimitive("NEWEST"), page.inputParameter("sort"))
        assertEquals(JsonNull, page.inputParameter("missing"))
        assertEquals(JsonNull, page.inputParameter("absent"))

        page.children.single().forIndex(3).error(IllegalArgumentException("bad title"))

        assertEquals("bad title", errors.single().message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("page"), GraphQLPathSegment.Field("title"), GraphQLPathSegment.Index(3)),
            errors.single().path,
        )
    }

    @Test
    fun buildsARootSelectionForOneExternallySuppliedField() {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "search",
            selectionSet = "{ events(limit: ${'$'}limit) { id } venues { id } }",
            arguments = mapOf(
                "query" to JsonPrimitive("jazz"),
                "filter" to buildJsonObject { put("near", "LAS") },
            ),
            variables = mapOf("limit" to JsonPrimitive(5)),
            errors = errors,
        )
        val search = root.children.single()

        assertEquals("search", search.name)
        assertEquals("search", search.responseName)
        assertEquals(listOf(GraphQLPathSegment.Field("search")), search.path)
        assertEquals(JsonPrimitive("jazz"), search.inputParameter("query"))
        assertEquals("LAS", search.inputParameter("filter").jsonObject.getValue("near").jsonPrimitive.content)
        assertEquals(listOf("events", "venues"), search.children.map { it.name })

        val events = search.children.first()
        assertEquals(JsonPrimitive(5), events.inputParameter("limit"))
        assertEquals(
            listOf(GraphQLPathSegment.Field("search"), GraphQLPathSegment.Field("events")),
            events.path,
        )
    }

    @Test
    fun buildsARootSelectionForALeafField() {
        val root = ServerRequestSelection.forRootField(
            fieldName = "version",
            selectionSet = null,
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = mutableListOf(),
        )

        val version = root.children.single()
        assertEquals("version", version.name)
        assertEquals(emptyList(), version.children)
    }

    @Test
    fun rootSelectionBecomesNullWhenANonNullRootFieldFails() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection(
            parent = null,
            variables = emptyMap(),
            node = parseGraphQLOperation("{ event { id title } version }").selectionSet,
            errors = errors,
            errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
        )

        val data = QueryResolver().gqlSelect(root)

        assertEquals(JsonNull, data)
        assertEquals("title unavailable", errors.single().message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("event"), GraphQLPathSegment.Field("title")),
            errors.single().path,
        )
        val response = buildResponse(data, errors)
        assertEquals(JsonNull, response["data"])
        assertEquals(1, (response["errors"] as JsonArray).size)
    }

    @Test
    fun rootFieldSelectionBecomesNullWhenItsNonNullFieldFails() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "event",
            selectionSet = "{ id title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        val data = QueryResolver().gqlSelect(root)

        assertEquals(JsonNull, data)
        assertEquals("Internal Server Error", errors.single().message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("event"), GraphQLPathSegment.Field("title")),
            errors.single().path,
        )
    }

    @Test
    fun nullPropagationStopsAtTheNearestNullableAncestor() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection(
            parent = null,
            variables = emptyMap(),
            node = parseGraphQLOperation("{ maybeEvent { id title } events { id title } version }").selectionSet,
            errors = errors,
        )

        val data = QueryResolver().gqlSelect(root)

        assertEquals(
            JsonObject(
                mapOf(
                    "maybeEvent" to JsonNull,
                    "events" to JsonArray(listOf(JsonNull)),
                    "version" to JsonPrimitive("3"),
                ),
            ),
            data,
        )
        assertEquals(
            listOf(
                listOf(GraphQLPathSegment.Field("maybeEvent"), GraphQLPathSegment.Field("title")),
                listOf(
                    GraphQLPathSegment.Field("events"),
                    GraphQLPathSegment.Index(0),
                    GraphQLPathSegment.Field("title"),
                ),
            ),
            errors.map { it.path },
        )
    }

    @Test
    fun rootSelectionIsKeptWhenOnlyNullableRootFieldsFail() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ id title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(JsonObject(mapOf("maybeEvent" to JsonNull)), QueryResolver().gqlSelect(root))
        assertEquals(1, errors.size)
    }
}

// Mirrors the mapping code that the generator emits for this schema:
//
//     type Query { event: Event!  maybeEvent: Event  events: [Event]!  version: String! }
//     type Event { id: ID!  title: String! }
private class QueryResolver
private class EventResolver {
    fun title(): String = throw IllegalStateException("title unavailable")
}

private suspend fun QueryResolver.gqlSelectChild(child: RequestSelection): JsonElement? = when (child.name) {
    "event" -> child.resolveFieldValue(nonNull = true) { EventResolver().gqlSelect(child) }
    "maybeEvent" -> child.resolveFieldValue(nonNull = false) { EventResolver().gqlSelect(child) }
    "events" -> child.resolveFieldValue(nonNull = true) {
        JsonArray(
            listOf(EventResolver()).mapIndexed { index, event ->
                child.resolveListElement(index, nonNull = false) { element -> event.gqlSelect(element) }
            },
        )
    }
    "version" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("3") }
    else -> throw IllegalArgumentException("Unknown field '${child.name}' on Query")
}

private suspend fun QueryResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }

private suspend fun EventResolver.gqlSelectChild(child: RequestSelection): JsonElement? = when (child.name) {
    "id" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("e1") }
    "title" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive(title()) }
    else -> throw IllegalArgumentException("Unknown field '${child.name}' on Event")
}

private suspend fun EventResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }
