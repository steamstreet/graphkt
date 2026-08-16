package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQLRequestPreparerTest {
    @Test
    fun preparesAValidRequestBeforeExecution() {
        val result = GraphQLRequestPreparer(testSchema()).prepare(
            GraphQLRequest(
                query = "query Node(${'$'}id: ID!) { node(id: ${'$'}id) { id } }",
                operationName = "Node",
                variables = buildJsonObject { put("id", 42) },
            ),
        )

        assertTrue(result.isValid, result.errors.toString())
        assertEquals(JsonPrimitive("42"), result.operation?.variables?.get("id"))
    }

    @Test
    fun stopsBeforeCoercionWhenValidationFails() {
        val result = GraphQLRequestPreparer(testSchema()).prepare(
            GraphQLRequest(
                query = "query Node(${'$'}id: ID!) { unknown(id: ${'$'}id) }",
                operationName = "Node",
                variables = buildJsonObject { put("id", 42) },
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.orEmpty().contains("does not exist") })
    }

    @Test
    fun stopsBeforeExecutionWhenVariableCoercionFails() {
        val result = GraphQLRequestPreparer(testSchema()).prepare(
            GraphQLRequest(
                query = "query Node(${'$'}id: ID!) { node(id: ${'$'}id) { id } }",
                operationName = "Node",
                variables = JsonObject(mapOf("id" to JsonPrimitive(true))),
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.message.orEmpty().contains("must be a string or integer") })
    }

    @Test
    fun reportsSyntaxErrorsAsRequestErrors() {
        val result = GraphQLRequestPreparer(testSchema()).prepare(GraphQLRequest("query Broken {"))

        assertFalse(result.isValid)
        assertTrue(result.errors.single().locations?.single()?.line == 1)
    }

    @Test
    fun appliesTheVariableByteLimitBeforeParsing() {
        val result = GraphQLRequestPreparer(
            testSchema(),
            GraphQLDocumentLimits(maxVariableBytes = 8),
        ).prepare(
            GraphQLRequest(
                query = "{ node(id: \"1\") { id } }",
                variables = buildJsonObject { put("value", "too long") },
            ),
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.single().message.orEmpty().contains("8-byte limit"))
    }
}
