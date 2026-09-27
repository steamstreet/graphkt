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
import kotlin.test.assertFailsWith
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

    @Test
    fun rootFieldSelectionKeysNestedFieldsByFieldName() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ ident: id place: venue { called: name } }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        val data = QueryResolver().gqlSelect(root)

        // AppSync reads each field by its name and applies the aliases itself.
        assertEquals(
            JsonObject(
                mapOf(
                    "maybeEvent" to JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("e1"),
                            "venue" to JsonObject(mapOf("name" to JsonPrimitive("The Venue"))),
                        ),
                    ),
                ),
            ),
            data,
        )
        assertEquals(emptyList(), errors)
    }

    @Test
    fun rootFieldSelectionKeepsAliasesInErrorPaths() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "events",
            selectionSet = "{ heading: title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(JsonObject(mapOf("events" to JsonArray(listOf(JsonNull)))), QueryResolver().gqlSelect(root))
        assertEquals(
            listOf(
                GraphQLPathSegment.Field("events"),
                GraphQLPathSegment.Index(0),
                GraphQLPathSegment.Field("heading"),
            ),
            errors.single().path,
        )
    }

    @Test
    fun documentSelectionKeysFieldsByAlias() = runTest {
        val root = ServerRequestSelection(
            parent = null,
            variables = emptyMap(),
            node = parseGraphQLOperation("{ show: maybeEvent { ident: id place: venue { called: name } } }").selectionSet,
            errors = mutableListOf(),
        )

        assertEquals(
            JsonObject(
                mapOf(
                    "show" to JsonObject(
                        mapOf(
                            "ident" to JsonPrimitive("e1"),
                            "place" to JsonObject(mapOf("called" to JsonPrimitive("The Venue"))),
                        ),
                    ),
                ),
            ),
            QueryResolver().gqlSelect(root),
        )
    }

    @Test
    fun recordsATodoResolverAsAFieldError() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ id pending }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
            errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
        )

        assertEquals(
            JsonObject(
                mapOf("maybeEvent" to JsonObject(mapOf("id" to JsonPrimitive("e1"), "pending" to JsonNull))),
            ),
            QueryResolver().gqlSelect(root),
        )
        assertEquals("An operation is not implemented: pending", errors.single().message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("maybeEvent"), GraphQLPathSegment.Field("pending")),
            errors.single().path,
        )
    }

    @Test
    fun propagatesATodoInANonNullRootField() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "upcoming",
            selectionSet = "{ id }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(JsonNull, QueryResolver().gqlSelect(root))
        assertEquals(listOf(GraphQLPathSegment.Field("upcoming")), errors.single().path)
    }

    @Test
    fun doesNotRecordAFatalErrorAsAFieldError() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = ServerRequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ id overflow }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertFailsWith<StackOverflowError> { QueryResolver().gqlSelect(root) }
        assertEquals(emptyList(), errors)
    }
}

// Mirrors the mapping code that the generator emits for this schema:
//
//     type Query { event: Event!  maybeEvent: Event  events: [Event]!  upcoming: Event!  version: String! }
//     type Event { id: ID!  title: String!  venue: Venue  pending: String  overflow: String }
//     type Venue { name: String! }
private class QueryResolver {
    fun upcoming(): EventResolver = TODO("upcoming")
}
private class EventResolver {
    fun title(): String = throw IllegalStateException("title unavailable")
    fun pending(): String = TODO("pending")
    fun overflow(): String = throw StackOverflowError()
}
private class VenueResolver

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
    "upcoming" -> child.resolveFieldValue(nonNull = true) { upcoming().gqlSelect(child) }
    "version" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("3") }
    else -> throw IllegalArgumentException("Unknown field '${child.name}' on Query")
}

private suspend fun QueryResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }

private suspend fun EventResolver.gqlSelectChild(child: RequestSelection): JsonElement? = when (child.name) {
    "id" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("e1") }
    "title" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive(title()) }
    "venue" -> child.resolveFieldValue(nonNull = false) { VenueResolver().gqlSelect(child) }
    "pending" -> child.resolveFieldValue(nonNull = false) { JsonPrimitive(pending()) }
    "overflow" -> child.resolveFieldValue(nonNull = false) { JsonPrimitive(overflow()) }
    else -> throw IllegalArgumentException("Unknown field '${child.name}' on Event")
}

private suspend fun EventResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }

private suspend fun VenueResolver.gqlSelectChild(child: RequestSelection): JsonElement? = when (child.name) {
    "name" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("The Venue") }
    else -> throw IllegalArgumentException("Unknown field '${child.name}' on Venue")
}

private suspend fun VenueResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }
