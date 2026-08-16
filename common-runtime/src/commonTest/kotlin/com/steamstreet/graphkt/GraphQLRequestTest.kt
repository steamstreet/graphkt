package com.steamstreet.graphkt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphQLRequestTest {
    @Test
    fun requestEnvelopeRoundTrips() {
        val request = GraphQLRequest(
            query = "query Viewer { viewer { id } }",
            operationName = "Viewer",
            variables = buildJsonObject { put("limit", 10) },
        )

        assertEquals(request, Json.decodeFromString(Json.encodeToString(request)))
    }

    @Test
    fun errorPathUsesGraphQLScalarFormat() {
        val error = GraphQLError(
            message = "Not found",
            path = listOf(GraphQLPathSegment.Field("users"), GraphQLPathSegment.Index(2)),
        )

        val encoded = Json.encodeToString(error)
        assertTrue(encoded.contains("\"path\":[\"users\",2]"), encoded)
        assertEquals(
            error.path,
            Json.decodeFromString<GraphQLError>(encoded).path,
        )
    }

    @Test
    fun executionResultPreservesExplicitNullData() {
        val response = GraphQLResponseEnvelope(
            data = null,
            errors = listOf(GraphQLError("Failed")),
            kind = GraphQLResponseKind.EXECUTION_RESULT,
        )

        val encoded = Json.encodeToString(response)
        val decoded = Json.decodeFromString<GraphQLResponseEnvelope>(encoded)

        assertTrue(encoded.contains("\"data\":null"), encoded)
        assertEquals(GraphQLResponseKind.EXECUTION_RESULT, decoded.kind)
    }

    @Test
    fun requestErrorOmitsData() {
        val response = GraphQLResponseEnvelope(errors = listOf(GraphQLError("Invalid")))

        val encoded = Json.encodeToString(response)

        assertFalse(encoded.contains("\"data\""), encoded)
    }
}
