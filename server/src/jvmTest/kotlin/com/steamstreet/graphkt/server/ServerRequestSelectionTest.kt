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
}
