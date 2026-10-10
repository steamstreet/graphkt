package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers [RequestSelection.forOperation] on every target. The JVM-only `OperationSelectionParityTest`
 * compares it with `ServerRequestSelection` over a large corpus. The expectations here are the
 * values that comparison establishes, so that Kotlin/Native checks them too.
 */
class OperationSelectionTest {
    @Test
    fun aliasesAreResponseNames() {
        val root = RequestSelection.forOperation(
            "{ first: search(limit: 1) { id } all: search { id } plain }",
            null,
            mutableListOf(),
        )

        assertEquals(listOf("search", "search", "plain"), root.children.map { it.name })
        assertEquals(listOf("first", "all", "plain"), root.children.map { it.responseName })
        assertEquals(
            listOf(listOf(GraphQLPathSegment.Field("first")), listOf(GraphQLPathSegment.Field("all"))),
            root.children.take(2).map { it.path },
        )
        assertEquals(
            listOf(GraphQLPathSegment.Field("first"), GraphQLPathSegment.Field("id")),
            root.children.first().children.single().path,
        )
        assertEquals(emptyList(), root.path)
        assertFailsWith<IllegalStateException> { root.name }
    }

    @Test
    fun inlineFragmentsSetTheTypeNameOfTheirFields() {
        val root = RequestSelection.forOperation(
            "{ entity { id ... on Venue { name } ... { other } ... on Event { title } } }",
            null,
            mutableListOf(),
        )
        val fields = root.children.single().children

        assertEquals(listOf("id", "name", "other", "title"), fields.map { it.name })
        assertEquals(listOf(null, "Venue", null, "Event"), fields.map { it.typeName })
        assertTrue(fields[1].appliesTo("Venue"))
        assertTrue(!fields[1].appliesTo("Event"))
        assertTrue(fields[0].appliesTo("Event"))
        // A fragment adds nothing to the path.
        assertEquals(
            listOf(GraphQLPathSegment.Field("entity"), GraphQLPathSegment.Field("name")),
            fields[1].path,
        )
    }

    @Test
    fun parametersRenderAsTheStringFormOfTheirJson() {
        val root = RequestSelection.forOperation(
            """
            query Q(${'$'}v: Int, ${'$'}o: Filter) {
              f(
                s: "a \"b\" é", i: -7, big: 12345678901234567890, f: 1.50, e: 1e3, small: 0.0000001,
                neg: -2.5e-3, t: true, n: null, en: NEWEST, l: [1, [2]], o: {x: 1, y: "z"}, v: ${'$'}v,
                vo: ${'$'}o, missing: ${'$'}nope
              ) { id }
            }
            """.trimIndent(),
            buildJsonObject {
                put("v", 5)
                put("o", buildJsonObject { put("k", "w") })
            },
            mutableListOf(),
        )
        val field = root.children.single()

        assertEquals(
            mapOf(
                "s" to "\"a \\\"b\\\" é\"",
                "i" to "-7",
                "big" to "12345678901234567890",
                "f" to "1.50",
                "e" to "1E+3",
                "small" to "1E-7",
                "neg" to "-0.0025",
                "t" to "true",
                "n" to "null",
                "en" to "\"NEWEST\"",
                "l" to "[1,[2]]",
                "o" to "{\"x\":1,\"y\":\"z\"}",
                "v" to "5",
                "vo" to "{\"k\":\"w\"}",
                "missing" to "null",
            ),
            field.parameters,
        )
        assertEquals(JsonPrimitive("NEWEST"), field.inputParameter("en"))
        assertEquals(JsonNull, field.inputParameter("absent"))
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonArray(listOf(JsonPrimitive(2))))), field.inputParameter("l"))
        assertEquals(JsonPrimitive(5), field.variable("v"))
        assertEquals(JsonNull, field.variable("nope"))
    }

    @Test
    fun variableDefaultsAreNotApplied() {
        val field = RequestSelection.forOperation(
            "query(${'$'}a: Int = 5) { f(a: ${'$'}a) { id } }",
            null,
            mutableListOf(),
        ).children.single()

        assertEquals("null", field.parameters.getValue("a"))
    }

    @Test
    fun errorsAreRecordedAgainstTheFieldPathWithIndexes() {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forOperation("{ events { items { id } } }", null, errors)
        val item = root.children.single().children.single().forIndex(3).children.single()

        assertEquals(
            listOf(
                GraphQLPathSegment.Field("events"),
                GraphQLPathSegment.Field("items"),
                GraphQLPathSegment.Index(3),
                GraphQLPathSegment.Field("id"),
            ),
            item.path,
        )
        item.error(IllegalStateException("secret"))

        assertEquals("Internal Server Error", errors.single().message)
        assertEquals(item.path, errors.single().path)
    }

    @Test
    fun errorFactoryBuildsTheRecordedErrors() {
        val errors = mutableListOf<GraphQLError>()
        val root = RequestSelection.forOperation(
            "{ a }",
            null,
            errors,
            errorFactory = { failure, path -> GraphQLError("custom: ${failure.message}", path = path) },
        )

        root.children.single().error(IllegalStateException("boom"))

        assertEquals("custom: boom", errors.single().message)
        assertEquals(listOf(GraphQLPathSegment.Field("a")), errors.single().path)
    }

    @Test
    fun operationTypeAndNameSelectTheOperation() {
        val query = "mutation Create { create { id } } query Read { read { id } } subscription Watch { watch { id } }"
        fun parse(name: String?) = RequestOperation.parse(query, null, mutableListOf(), operationName = name)

        assertEquals(RequestOperationType.MUTATION, parse(null).type)
        assertEquals(listOf("create"), parse(null).selection.children.map { it.name })
        assertEquals(RequestOperationType.QUERY, parse("Read").type)
        assertEquals(RequestOperationType.SUBSCRIPTION, parse("Watch").type)
        assertEquals(RequestOperationType.QUERY, RequestOperation.parse("{ a }", null, mutableListOf()).type)
        assertFailsWith<IllegalArgumentException> { parse("Missing") }
    }

    @Test
    fun documentsThatCannotBeParsedOrHaveNoOperationAreRejected() {
        listOf("", "{", "{ a(b: ) }", "fragment F on T { id } query { a }", "fragment F on T { id }").forEach {
            assertFailsWith<IllegalArgumentException>(it) { RequestSelection.forOperation(it, null, mutableListOf()) }
        }
    }

    @Test
    fun namedFragmentsAreExpandedInPlace() {
        val root = RequestSelection.forOperation(
            "query { entity { id ...Parts } } fragment Parts on Venue { name ...More } fragment More on Venue { address }",
            null,
            mutableListOf(),
        )
        val fields = root.children.single().children

        assertEquals(listOf("id", "name", "address"), fields.map { it.name })
        assertEquals(listOf(null, "Venue", "Venue"), fields.map { it.typeName })
        assertFailsWith<IllegalArgumentException> {
            RequestSelection.forOperation("{ a { ...Missing } }", null, mutableListOf()).children.single().children
        }
        assertFailsWith<IllegalArgumentException> {
            RequestSelection.forOperation("{ a { ...F } } fragment F on T { ...F }", null, mutableListOf())
                .children.single().children
        }
    }

    @Test
    fun floatLiteralsKeepBigDecimalScale() {
        listOf(
            "0.0" to "0.0", "-0.0" to "0.0", "1.5" to "1.5", "1.50" to "1.50", "100.0" to "100.0",
            "0.0001" to "0.0001", "0.000001" to "0.000001", "0.0000001" to "1E-7", "1e3" to "1E+3", "1E3" to "1E+3",
            "1e+3" to "1E+3", "1e-3" to "0.001", "1.0e10" to "1.0E+10", "-2.5e-3" to "-0.0025",
            "1.5E+20" to "1.5E+20", "12345.6789" to "12345.6789", "0.1e1" to "1", "10e-1" to "1.0", "1e0" to "1",
            "0e5" to "0E+5", "123456789.123456789e5" to "12345678912345.6789", "5e-324" to "5E-324",
            "0.00000000000000000001" to "1E-20", "99999999999999999999.9999999999" to "99999999999999999999.9999999999",
        ).forEach { (literal, expected) ->
            assertEquals(expected, bigDecimalString(literal), literal)
        }
    }
}
