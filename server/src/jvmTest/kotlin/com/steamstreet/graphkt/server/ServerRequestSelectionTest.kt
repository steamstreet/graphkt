package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.GraphQLPathSegment
import graphql.language.Field
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
