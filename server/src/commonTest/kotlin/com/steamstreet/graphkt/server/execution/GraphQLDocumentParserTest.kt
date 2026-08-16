package com.steamstreet.graphkt.server.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQLDocumentParserTest {
    @Test
    fun parsesExecutableDocument() {
        val document = GraphQLDocumentParser().parse(
            """
            query Search(${'$'}term: String!, ${'$'}limit: Int = 10) @cached {
                results: search(
                    term: ${'$'}term
                    filter: {states: [OPEN, CLOSED], exact: true}
                ) {
                    __typename
                    ... on User @include(if: true) { id name }
                    ...ResultFields
                }
            }

            fragment ResultFields on SearchResult {
                score
            }
            """.trimIndent(),
        )

        val operation = document.selectOperation("Search")
        assertEquals(OperationType.QUERY, operation.type)
        assertEquals("Search", operation.name)
        assertEquals(2, operation.variables.size)
        assertIs<TypeReference.NonNull>(operation.variables.first().type)
        assertEquals(Value.IntValue("10"), operation.variables.last().defaultValue)
        assertEquals("cached", operation.directives.single().name)

        val results = assertIs<FieldSelection>(operation.selections.single())
        assertEquals("results", results.responseName)
        assertEquals("search", results.name)
        assertEquals(2, results.arguments.size)
        assertIs<Value.Variable>(results.arguments.first().value)
        assertIs<Value.ObjectValue>(results.arguments.last().value)
        assertIs<InlineFragment>(results.selections[1])
        assertIs<FragmentSpread>(results.selections[2])

        assertEquals("ResultFields", document.fragments.single().name)
        assertEquals("SearchResult", document.fragments.single().typeCondition)
    }

    @Test
    fun selectsOnlyOperationWhenNameIsAbsent() {
        val operation = GraphQLDocumentParser().parse("{ viewer { id } }").selectOperation(null)

        assertNull(operation.name)
        assertEquals(OperationType.QUERY, operation.type)
    }

    @Test
    fun requiresNameForMultipleOperations() {
        val document = GraphQLDocumentParser().parse("query One { one } query Two { two }")

        val error = assertFailsWith<GraphQLDocumentException> { document.selectOperation(null) }
        assertTrue(error.message.orEmpty().contains("operation name is required"))
    }

    @Test
    fun parsesBlockStringsAndFloats() {
        val document = GraphQLDocumentParser().parse(
            "query Example {\n" +
                "  field(text: \"\"\"\n" +
                "    first\n" +
                "      second\n" +
                "    \"\"\", ratio: -1.25e+2)\n" +
                "}",
        )

        val field = assertIs<FieldSelection>(document.operations.single().selections.single())
        assertEquals(Value.StringValue("first\n  second"), field.arguments[0].value)
        assertEquals(Value.FloatValue("-1.25e+2"), field.arguments[1].value)
    }

    @Test
    fun parsesSeptember2025UnicodeEscapes() {
        val document = GraphQLDocumentParser().parse(
            "{ field(variableWidth: \"\\u{1F4A9}\", legacy: \"\\uD83D\\uDCA9\") }",
        )

        val field = assertIs<FieldSelection>(document.operations.single().selections.single())
        assertEquals(Value.StringValue("💩"), field.arguments[0].value)
        assertEquals(Value.StringValue("💩"), field.arguments[1].value)
        assertFailsWith<GraphQLDocumentException> {
            GraphQLDocumentParser().parse("{ field(value: \"\\uDEAD\") }")
        }
    }

    @Test
    fun appliesDocumentByteLimit() {
        val parser = GraphQLDocumentParser(GraphQLDocumentLimits(maxDocumentBytes = 8))

        assertFailsWith<GraphQLDocumentLimitException> { parser.parse("{ field }") }
    }

    @Test
    fun appliesTokenLimit() {
        val parser = GraphQLDocumentParser(GraphQLDocumentLimits(maxTokens = 4))

        assertFailsWith<GraphQLDocumentLimitException> { parser.parse("{ one two three }") }
    }

    @Test
    fun appliesSyntaxNestingLimit() {
        val parser = GraphQLDocumentParser(GraphQLDocumentLimits(maxSyntaxNesting = 2))

        assertFailsWith<GraphQLDocumentLimitException> {
            parser.parse("{ user { profile { id } } }")
        }
    }

    @Test
    fun reportsMalformedDocumentsWithLocation() {
        val error = assertFailsWith<GraphQLDocumentException> {
            GraphQLDocumentParser().parse("query Broken { field(value: 01) }")
        }

        assertTrue(error.message.orEmpty().contains("leading zero"))
        assertEquals(1, error.location?.line)
    }
}
