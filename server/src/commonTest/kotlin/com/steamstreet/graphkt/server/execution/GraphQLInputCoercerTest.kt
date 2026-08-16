package com.steamstreet.graphkt.server.execution

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQLInputCoercerTest {
    private val schema = testSchema()
    private val parser = GraphQLDocumentParser()
    private val coercer = GraphQLInputCoercer(schema)

    @Test
    fun coercesVariablesRecursivelyAndAppliesDefaults() {
        val operation = parser.parse(
            """
            query Values(
                ${'$'}term: String!
                ${'$'}filter: Filter
                ${'$'}ids: [ID!]!
                ${'$'}status: Status!
                ${'$'}page: Int = 3
                ${'$'}optional: String
            ) {
                search(term: ${'$'}term) { __typename }
            }
            """.trimIndent(),
        ).selectOperation("Values")
        val variables = buildJsonObject {
            put("term", "kotlin")
            put(
                "filter",
                buildJsonObject {
                    put("status", "OPEN")
                    put("tags", "native")
                },
            )
            put("ids", JsonPrimitive(42))
            put("status", "CLOSED")
        }

        val result = coercer.coerceVariables(operation, variables)

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive(3), result.values["page"])
        assertFalse("optional" in result.values)
        assertEquals(JsonPrimitive(20), result.values.getValue("filter").jsonObject["limit"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("native"))),
            result.values.getValue("filter").jsonObject["tags"]?.jsonArray,
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("42"))),
            result.values.getValue("ids"),
        )
    }

    @Test
    fun preservesExplicitNullForNullableVariables() {
        val operation = parser.parse(
            "query Value(${'$'}optional: String) { node(id: \"1\") { id } }",
        ).selectOperation("Value")

        val result = coercer.coerceVariables(
            operation,
            JsonObject(mapOf("optional" to JsonNull)),
        )

        assertTrue(result.isValid)
        assertTrue("optional" in result.values)
        assertEquals(JsonNull, result.values["optional"])
    }

    @Test
    fun reportsRequiredScalarInputObjectAndOneOfErrors() {
        val operation = parser.parse(
            """
            query Values(${'$'}required: String!, ${'$'}count: Int!, ${'$'}filter: Filter!, ${'$'}key: UserKey!) {
                search(term: ${'$'}required) { __typename }
            }
            """.trimIndent(),
        ).selectOperation("Values")
        val variables = buildJsonObject {
            put("count", 2_147_483_648L)
            put("filter", buildJsonObject { put("unknown", true) })
            put("key", buildJsonObject { put("id", "1"); put("email", "a@example.com") })
        }

        val result = coercer.coerceVariables(operation, variables)
        val messages = result.errors.map { it.message.orEmpty() }

        assertFalse(result.isValid)
        assertTrue(messages.any { "Required variable '${'$'}required' was not provided" in it }, messages.toString())
        assertTrue(messages.any { "must be a 32-bit integer" in it }, messages.toString())
        assertTrue(messages.any { "contains unknown field 'unknown'" in it }, messages.toString())
        assertTrue(messages.any { "exactly one non-null field" in it }, messages.toString())
    }

    @Test
    fun appliesArgumentDefaultWhenNullableVariableIsOmitted() {
        val operation = parser.parse(
            """
            query Search(${'$'}limit: Int) {
                search(term: "kotlin", limit: ${'$'}limit) { __typename }
            }
            """.trimIndent(),
        ).selectOperation("Search")
        val variables = coercer.coerceVariables(operation, null)
        val selection = operation.selections.single() as FieldSelection
        val query = schema.type("Query") as GraphQLObjectType
        val field = query.field("search")!!

        val arguments = coercer.coerceArguments(field, selection.arguments, variables.values)

        assertTrue(arguments.isValid, arguments.errors.toString())
        assertEquals(JsonPrimitive("kotlin"), arguments.values["term"])
        assertEquals(JsonPrimitive(10), arguments.values["limit"])
    }

    @Test
    fun rejectsExplicitNullInsteadOfUsingArgumentDefault() {
        val operation = parser.parse(
            """
            query Search(${'$'}limit: Int) {
                search(term: "kotlin", limit: ${'$'}limit) { __typename }
            }
            """.trimIndent(),
        ).selectOperation("Search")
        val variables = coercer.coerceVariables(operation, JsonObject(mapOf("limit" to JsonNull)))
        val selection = operation.selections.single() as FieldSelection
        val field = (schema.type("Query") as GraphQLObjectType).field("search")!!

        val arguments = coercer.coerceArguments(field, selection.arguments, variables.values)

        assertFalse(arguments.isValid)
        assertTrue(arguments.errors.single().message.orEmpty().contains("must not be null"))
    }

    @Test
    fun resolvesVariablesInsideInputObjectLiterals() {
        val operation = parser.parse(
            """
            query Search(${'$'}limit: Int, ${'$'}tag: String) {
                search(term: "kotlin", filter: {limit: ${'$'}limit, tags: ${'$'}tag}) { __typename }
            }
            """.trimIndent(),
        ).selectOperation("Search")
        val coercedVariables = coercer.coerceVariables(
            operation,
            buildJsonObject { put("tag", "native") },
        )
        val selection = operation.selections.single() as FieldSelection
        val field = (schema.type("Query") as GraphQLObjectType).field("search")!!

        val arguments = coercer.coerceArguments(field, selection.arguments, coercedVariables.values)
        val filter = arguments.values.getValue("filter").jsonObject

        assertTrue(arguments.isValid, arguments.errors.toString())
        assertEquals(JsonPrimitive(20), filter["limit"])
        assertEquals(JsonArray(listOf(JsonPrimitive("native"))), filter["tags"])
    }

    @Test
    fun coercesIntegerJsonValuesWithAnEmptyFraction() {
        val operation = parser.parse(
            "query Value(${'$'}count: Int!) { node(id: \"1\") { id } }",
        ).selectOperation("Value")

        val result = coercer.coerceVariables(
            operation,
            JsonObject(mapOf("count" to JsonPrimitive(4.0))),
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(4, result.values.getValue("count").jsonPrimitive.content.toInt())
    }
}
