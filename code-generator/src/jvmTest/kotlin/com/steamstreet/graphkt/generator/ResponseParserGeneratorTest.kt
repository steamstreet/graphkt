package com.steamstreet.graphkt.generator

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests of the response parser generator.
 */
class ResponseParserGeneratorTest {
    /**
     * Each generated response class holds private backing state (`_response`, `_element`) alongside
     * one `override val` per schema field. Schema fields whose names match those internals must not
     * produce conflicting declarations in the generated class.
     */
    @Test
    fun schemaFieldsNamedLikeGeneratedInternals(@TempDir outputDir: File) {
        val parser = SchemaParser()
        val schema = parser.parse(
            """
            schema {
                query: Query
            }

            type Query {
                issue: Issue
            }

            type Issue {
                element: String
                response: String!
                hasField: Boolean
                key: String
                result: [String!]
            }
            """.trimIndent()
        )

        val packageName = "com.steamstreet.testreserved"
        Generator(schema, packageName, Properties(), outputDir).generate(server = false)

        val responses = File(outputDir, "com/steamstreet/testreserved/client/responses.kt").readText()

        // The schema fields are exposed as properties of the response class...
        assertTrue(responses.contains("override val element: String?"), responses)
        assertTrue(responses.contains("override val response: String"), responses)
        assertTrue(responses.contains("override val hasField: Boolean?"), responses)

        // ...and none of them collide with the private JSON backing property.
        assertTrue(responses.contains("private val _element:"), responses)
        assertFalse(responses.contains("private val element:"), responses)
        assertFalse(responses.contains("private val response:"), responses)

        assertTrue(compileKotlinFiles(outputDir), "Generated client code should compile")
    }
}
