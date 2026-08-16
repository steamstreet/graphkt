package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLResponseEnvelope
import com.steamstreet.graphkt.GraphQLResponseKind
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQLHttpTest {
    @Test
    fun selectsPreferredSupportedResponseType() {
        assertEquals(
            GraphQLHttpResponseMediaType.GRAPHQL_RESPONSE_JSON,
            negotiateGraphQLHttpResponseMediaType(null),
        )
        assertEquals(
            GraphQLHttpResponseMediaType.GRAPHQL_RESPONSE_JSON,
            negotiateGraphQLHttpResponseMediaType("application/graphql-response+json, application/json;q=0.9"),
        )
        assertEquals(
            GraphQLHttpResponseMediaType.JSON,
            negotiateGraphQLHttpResponseMediaType("application/json"),
        )
        assertEquals(
            GraphQLHttpResponseMediaType.JSON,
            negotiateGraphQLHttpResponseMediaType("application/json, */*;q=0.5"),
        )
        assertNull(negotiateGraphQLHttpResponseMediaType("text/plain"))
        assertNull(negotiateGraphQLHttpResponseMediaType("application/graphql-response+json;q=0"))
    }

    @Test
    fun acceptsOnlyUtf8JsonRequestBodies() {
        assertTrue(isSupportedGraphQLHttpRequestContentType("application/json"))
        assertTrue(isSupportedGraphQLHttpRequestContentType("Application/Json; charset=UTF-8"))
        assertFalse(isSupportedGraphQLHttpRequestContentType(null))
        assertFalse(isSupportedGraphQLHttpRequestContentType("application/graphql"))
        assertFalse(isSupportedGraphQLHttpRequestContentType("application/json; charset=iso-8859-1"))
    }

    @Test
    fun mapsResponsePhasesToHttpStatusCodes() {
        assertEquals(
            400,
            GraphQLResponseEnvelope(
                errors = listOf(GraphQLError("Syntax")),
                kind = GraphQLResponseKind.DOCUMENT_ERROR,
            ).graphQLHttpStatusCode(),
        )
        assertEquals(
            422,
            GraphQLResponseEnvelope(errors = listOf(GraphQLError("Validation"))).graphQLHttpStatusCode(),
        )
        assertEquals(
            200,
            GraphQLResponseEnvelope(data = JsonObject(emptyMap())).graphQLHttpStatusCode(),
        )
    }
}
