package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers [RequestSelection.forRootField], the common equivalent of the JVM `ServerRequestSelection.forRootField`. */
class RootFieldSelectionTest {
    @Test
    fun buildsARootSelectionForOneExternallySuppliedField() {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
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

        assertEquals(emptyList(), root.path)
        assertEquals("search", search.name)
        assertEquals("search", search.responseName)
        assertEquals(listOf(GraphQLPathSegment.Field("search")), search.path)
        assertEquals(JsonPrimitive("jazz"), search.inputParameter("query"))
        assertEquals("LAS", search.inputParameter("filter").jsonObject.getValue("near").jsonPrimitive.content)
        assertEquals(listOf("events", "venues"), search.children.map { it.name })

        val events = search.children.first()
        assertEquals(JsonPrimitive(5), events.inputParameter("limit"))
        assertEquals(JsonPrimitive(5), events.variable("limit"))
        assertEquals(
            listOf(GraphQLPathSegment.Field("search"), GraphQLPathSegment.Field("events")),
            events.path,
        )
    }

    @Test
    fun passesResolvedArgumentsThroughUnchanged() {
        val filter = buildJsonObject {
            put("near", "LAS")
            put("tags", buildJsonArray { add(JsonPrimitive("jazz")); add(JsonNull) })
            put("radius", 2.5)
        }
        val search = RequestSelection.forRootField(
            fieldName = "search",
            selectionSet = null,
            arguments = mapOf("filter" to filter, "cursor" to JsonNull, "limit" to JsonPrimitive(3)),
            variables = emptyMap(),
            errors = mutableListOf(),
        ).children.single()

        assertEquals(filter, search.inputParameter("filter"))
        assertEquals(JsonNull, search.inputParameter("cursor"))
        assertEquals(JsonPrimitive(3), search.inputParameter("limit"))
        assertEquals(JsonNull, search.inputParameter("absent"))
        assertEquals(
            mapOf("filter" to filter.toString(), "cursor" to "null", "limit" to "3"),
            search.parameters,
        )
    }

    @Test
    fun convertsNestedArgumentLiteralsToJson() {
        val page = RequestSelection.forRootField(
            fieldName = "feed",
            selectionSet = """
                {
                  page(
                    items: [1, 2], sort: NEWEST, missing: null, ratio: 1.5, big: 12345678901234567890,
                    label: "x", on: true, where: { near: ${'$'}near, within: [ { miles: 3 } ] }
                  ) { title }
                }
            """.trimIndent(),
            arguments = emptyMap(),
            variables = mapOf("near" to JsonPrimitive("LAS")),
            errors = mutableListOf(),
        ).children.single().children.single()

        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), page.inputParameter("items"))
        assertEquals(JsonPrimitive("NEWEST"), page.inputParameter("sort"))
        assertEquals(JsonNull, page.inputParameter("missing"))
        assertEquals(1.5, page.inputParameter("ratio").jsonPrimitive.content.toDouble())
        assertEquals("12345678901234567890", page.inputParameter("big").jsonPrimitive.content)
        assertEquals(false, page.inputParameter("big").jsonPrimitive.isString)
        assertEquals(JsonPrimitive("x"), page.inputParameter("label"))
        assertEquals(JsonPrimitive(true), page.inputParameter("on"))
        assertEquals(
            buildJsonObject {
                put("near", "LAS")
                put("within", buildJsonArray { add(buildJsonObject { put("miles", 3) }) })
            },
            page.inputParameter("where"),
        )
    }

    @Test
    fun buildsARootSelectionForALeafField() {
        listOf(null, "", "  \n").forEach { selectionSet ->
            val root = RequestSelection.forRootField(
                fieldName = "version",
                selectionSet = selectionSet,
                arguments = emptyMap(),
                variables = emptyMap(),
                errors = mutableListOf(),
            )

            val version = root.children.single()
            assertEquals("version", version.name)
            assertEquals(emptyList(), version.children)
        }
    }

    @Test
    fun inlineFragmentsSetTheTypeNameOfTheirFields() {
        val node = RequestSelection.forRootField(
            fieldName = "node",
            selectionSet = "{ id ... on Event { title ... { pending } } ... on Venue { name ... on Venue { id } } ... { kind } }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = mutableListOf(),
        ).children.single()

        assertEquals(
            listOf(
                "id" to null,
                "title" to "Event",
                "pending" to "Event",
                "name" to "Venue",
                "id" to "Venue",
                "kind" to null,
            ),
            node.children.map { it.name to it.typeName },
        )
        assertTrue(node.children[1].appliesTo("Event"))
        assertTrue(!node.children[1].appliesTo("Venue"))
        assertEquals(
            listOf(GraphQLPathSegment.Field("node"), GraphQLPathSegment.Field("title")),
            node.children[1].path,
        )
    }

    @Test
    fun rejectsNamedFragmentSpreadsBeforeAnyResolverRuns() {
        val failure = assertFailsWith<IllegalArgumentException> {
            RequestSelection.forRootField(
                fieldName = "event",
                selectionSet = "{ id venue { ...venueFields } }",
                arguments = emptyMap(),
                variables = emptyMap(),
                errors = mutableListOf(),
            )
        }

        assertTrue(failure.message.orEmpty().contains("'venueFields'"), failure.message)
        assertTrue(failure.message.orEmpty().contains("inline fragment"), failure.message)
    }

    @Test
    fun rejectsADocumentThatIsNotOneSelectionSet() {
        assertFailsWith<IllegalArgumentException> {
            RequestSelection.forRootField("event", "{ id } { title }", emptyMap(), emptyMap(), mutableListOf())
        }
        assertFailsWith<IllegalArgumentException> {
            RequestSelection.forRootField("event", "{ id", emptyMap(), emptyMap(), mutableListOf())
        }
    }

    @Test
    fun doesNotExposeResolverFailureDetailsByDefault() {
        val errors = mutableListOf<GraphQLError>()
        val secret = RequestSelection.forRootField("secret", null, emptyMap(), emptyMap(), errors).children.single()

        secret.error(IllegalStateException("database-password=should-not-leak"))

        assertEquals("Internal Server Error", errors.single().message)
        assertEquals(listOf(GraphQLPathSegment.Field("secret")), errors.single().path)
        assertNull(errors.single().extensions)
    }

    @Test
    fun recordsExceptionDetailsWhenAskedTo() {
        val errors = mutableListOf<GraphQLError>()
        val page = RequestSelection.forRootField(
            fieldName = "page",
            selectionSet = "{ heading: title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
            errorFactory = GraphQLResolverErrorFactory.WithExceptionDetails,
        ).children.single()

        page.children.single().forIndex(3).error(IllegalStateException("template missing"))

        assertEquals("template missing", errors.single().message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("page"), GraphQLPathSegment.Field("heading"), GraphQLPathSegment.Index(3)),
            errors.single().path,
        )
        assertTrue(
            errors.single().extensions?.get("stacktrace")?.jsonPrimitive?.content.orEmpty()
                .contains("IllegalStateException"),
        )
    }

    @Test
    fun rootFieldSelectionBecomesNullWhenItsNonNullFieldFails() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
            fieldName = "event",
            selectionSet = "{ id title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        val data = RootQueryResolver().gqlSelect(root)

        assertEquals(JsonNull, data)
        assertEquals("Internal Server Error", errors.single().message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("event"), GraphQLPathSegment.Field("title")),
            errors.single().path,
        )
    }

    @Test
    fun rootSelectionIsKeptWhenOnlyNullableRootFieldsFail() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ id title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(JsonObject(mapOf("maybeEvent" to JsonNull)), RootQueryResolver().gqlSelect(root))
        assertEquals(1, errors.size)
    }

    @Test
    fun rootFieldSelectionKeysNestedFieldsByFieldName() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ ident: id place: venue { called: name } }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        val data = RootQueryResolver().gqlSelect(root)

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
        val root = RequestSelection.forRootField(
            fieldName = "events",
            selectionSet = "{ heading: title }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(JsonObject(mapOf("events" to JsonArray(listOf(JsonNull)))), RootQueryResolver().gqlSelect(root))
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
    fun resolvesInlineFragmentsAgainstTheRuntimeType() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
            fieldName = "nodes",
            selectionSet = "{ __typename id ... on Venue { label: name } ... on Event { place: venue { name } } }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(
            JsonObject(
                mapOf(
                    "nodes" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "__typename" to JsonPrimitive("Event"),
                                    "id" to JsonPrimitive("e1"),
                                    "venue" to JsonObject(mapOf("name" to JsonPrimitive("The Venue"))),
                                ),
                            ),
                            JsonObject(
                                mapOf(
                                    "__typename" to JsonPrimitive("Venue"),
                                    "id" to JsonPrimitive("v1"),
                                    "name" to JsonPrimitive("The Venue"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            RootQueryResolver().gqlSelect(root),
        )
        assertEquals(emptyList(), errors)
    }

    @Test
    fun recordsATodoResolverAsAFieldError() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
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
            RootQueryResolver().gqlSelect(root),
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
        val root = RequestSelection.forRootField(
            fieldName = "upcoming",
            selectionSet = "{ id }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertEquals(JsonNull, RootQueryResolver().gqlSelect(root))
        assertEquals(listOf(GraphQLPathSegment.Field("upcoming")), errors.single().path)
    }

    @Test
    fun doesNotRecordAFatalErrorAsAFieldError() = runTest {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forRootField(
            fieldName = "maybeEvent",
            selectionSet = "{ id fatal }",
            arguments = emptyMap(),
            variables = emptyMap(),
            errors = errors,
        )

        assertFailsWith<RootFieldFatalError> { RootQueryResolver().gqlSelect(root) }
        assertEquals(emptyList(), errors)
    }
}

/** Stands in for a fatal JVM error such as `StackOverflowError`, which common code cannot name. */
private class RootFieldFatalError : Error("fatal")

// Mirrors the mapping code that the generator emits for this schema:
//
//     type Query { event: Event!  maybeEvent: Event  events: [Event]!  upcoming: Event!  nodes: [Node!]! }
//     interface Node { id: ID! }
//     type Event implements Node { id: ID!  title: String!  venue: Venue  pending: String  fatal: String }
//     type Venue implements Node { id: ID!  name: String! }
private class RootQueryResolver {
    fun upcoming(): RootEventResolver = TODO("upcoming")
}

private interface RootNodeResolver

private class RootEventResolver : RootNodeResolver {
    fun title(): String = throw IllegalStateException("title unavailable")
    fun pending(): String = TODO("pending")
    fun fatal(): String = throw RootFieldFatalError()
}

private class RootVenueResolver : RootNodeResolver

private suspend fun RootQueryResolver.gqlSelectChild(child: RequestSelection): JsonElement? = when (child.name) {
    "event" -> child.resolveFieldValue(nonNull = true) { RootEventResolver().gqlSelect(child) }
    "maybeEvent" -> child.resolveFieldValue(nonNull = false) { RootEventResolver().gqlSelect(child) }
    "events" -> child.resolveFieldValue(nonNull = true) {
        JsonArray(
            listOf(RootEventResolver()).mapIndexed { index, event ->
                child.resolveListElement(index, nonNull = false) { element -> event.gqlSelect(element) }
            },
        )
    }
    "upcoming" -> child.resolveFieldValue(nonNull = true) { upcoming().gqlSelect(child) }
    "nodes" -> child.resolveFieldValue(nonNull = true) {
        JsonArray(
            listOf<RootNodeResolver>(RootEventResolver(), RootVenueResolver()).mapIndexed { index, node ->
                child.resolveListElement(index, nonNull = true) { element -> node.gqlSelect(element) }
            },
        )
    }
    else -> throw IllegalArgumentException("Unknown field '${child.name}' on Query")
}

private suspend fun RootQueryResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }

private suspend fun RootNodeResolver.gqlSelectChild(child: RequestSelection): JsonElement? {
    val runtimeType = when (this) {
        is RootEventResolver -> "Event"
        is RootVenueResolver -> "Venue"
        else -> throw IllegalArgumentException("Resolver does not implement a concrete type for Node")
    }
    if (!child.appliesTo(runtimeType)) return null
    return when (child.name) {
        "id" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive(if (this is RootEventResolver) "e1" else "v1") }
        "__typename" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive(runtimeType) }
        else -> when (child.typeName) {
            "Event" -> (this as? RootEventResolver)?.gqlSelectChild(child)
            "Venue" -> (this as? RootVenueResolver)?.gqlSelectChild(child)
            else -> throw IllegalArgumentException("Unknown selection '${child.name}' for Node")
        }
    }
}

private suspend fun RootNodeResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }

private suspend fun RootEventResolver.gqlSelectChild(child: RequestSelection): JsonElement? {
    if (!child.appliesTo("Event")) return null
    return when (child.name) {
        "id" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("e1") }
        "title" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive(title()) }
        "venue" -> child.resolveFieldValue(nonNull = false) { RootVenueResolver().gqlSelect(child) }
        "pending" -> child.resolveFieldValue(nonNull = false) { JsonPrimitive(pending()) }
        "fatal" -> child.resolveFieldValue(nonNull = false) { JsonPrimitive(fatal()) }
        "__typename" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("Event") }
        else -> throw IllegalArgumentException("Unknown field '${child.name}' on Event")
    }
}

private suspend fun RootEventResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }

private suspend fun RootVenueResolver.gqlSelectChild(child: RequestSelection): JsonElement? {
    if (!child.appliesTo("Venue")) return null
    return when (child.name) {
        "id" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("v1") }
        "name" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("The Venue") }
        "__typename" -> child.resolveFieldValue(nonNull = true) { JsonPrimitive("Venue") }
        else -> throw IllegalArgumentException("Unknown field '${child.name}' on Venue")
    }
}

private suspend fun RootVenueResolver.gqlSelect(field: RequestSelection): JsonElement =
    field.resolveSelectionSet { child -> gqlSelectChild(child) }
