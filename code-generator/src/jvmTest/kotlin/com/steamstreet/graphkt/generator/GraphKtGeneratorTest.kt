package com.steamstreet.graphkt.generator

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphKtGeneratorTest {
    @Test
    fun `generates merged schema files once and separates server output`(@TempDir tempDir: File) {
        val schemas = File(tempDir, "schemas").apply { mkdirs() }
        val rootSchema = File(schemas, "schema.graphql").apply {
            writeText(
                """
                schema {
                    query: Query
                }

                type Query {
                    viewer: User
                }
                """.trimIndent(),
            )
        }
        val userSchema = File(schemas, "user.graphql").apply {
            writeText(
                """
                type User {
                    id: ID!
                    name: String!
                }
                """.trimIndent(),
            )
        }
        val commonOutput = File(tempDir, "common")
        val serverOutput = File(tempDir, "server")

        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(rootSchema, userSchema),
                packageName = "com.steamstreet.graphkt.generated",
                outputs = GenerationOutputs(
                    common = commonOutput,
                    server = serverOutput,
                ),
            ),
        )

        assertEquals(1, result.operationCount)
        assertEquals(7, result.typeCount)
        assertTrue(File(commonOutput, "com/steamstreet/graphkt/generated/common.kt").isFile)
        assertTrue(File(commonOutput, "com/steamstreet/graphkt/generated/client/query.kt").isFile)
        assertFalse(File(commonOutput, "com/steamstreet/graphkt/generated/server/services.kt").exists())
        assertTrue(File(serverOutput, "com/steamstreet/graphkt/generated/server/services.kt").isFile)
        assertTrue(File(serverOutput, "com/steamstreet/graphkt/generated/server/service-mapping.kt").isFile)
        assertTrue(File(serverOutput, "com/steamstreet/graphkt/generated/server/server.kt").isFile)
        assertTrue(
            compileKotlinFiles(result.generatedFiles, tempDir),
            "Generated sources must compile together",
        )
    }

    @Test
    fun `generates normalized input enum and scalar types`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                scalar Instant

                enum Status {
                    DRAFT
                    PUBLISHED
                }

                input Filter {
                    timestamps: [[Instant!]!]!
                    statuses: [Status!]
                }

                type Query {
                    posts(filter: Filter): [String!]!
                }
                """.trimIndent(),
            )
        }
        val output = File(tempDir, "generated")

        GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = false, server = false),
                outputs = GenerationOutputs(output),
            ),
        )

        val generated = File(output, "com/steamstreet/graphkt/generated/common.kt").readText()
        assertTrue(generated.contains("public typealias Instant = String"), generated)
        assertTrue(generated.contains("public val timestamps: List<List<Instant>>"), generated)
        assertTrue(generated.contains("public val statuses: List<Status>? = null"), generated)
        assertTrue(generated.contains("public sealed class Status"), generated)
        assertTrue(generated.contains("public class Unknown("), generated)
    }

    @Test
    fun `generates compilable nested list variables`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                type Query {
                    search(ids: [[ID!]!]!): [String!]!
                }
                """.trimIndent(),
            )
        }
        val output = File(tempDir, "generated")

        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = true, server = false),
                outputs = GenerationOutputs(output),
            ),
        )

        val query = File(output, "com/steamstreet/graphkt/generated/client/query.kt").readText()
        assertTrue(query.contains("ListSerializer(ListSerializer(serializer<ID>()))"), query)
        assertTrue(query.contains("\"[[ID!]!]!\""), query)
        assertTrue(compileKotlinFiles(result.generatedFiles, tempDir), "Generated nested-list query must compile")
    }

    @Test
    fun `preserves omitted arguments and explicit null`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                type Query {
                    search(filter: Filter, limit: Int! = 20, term: String!): [String!]!
                }

                input Filter {
                    tags: [String!]
                }
                """.trimIndent(),
            )
        }
        val output = File(tempDir, "generated")

        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = true, server = false),
                outputs = GenerationOutputs(output),
            ),
        )

        val query = File(output, "com/steamstreet/graphkt/generated/client/query.kt").readText()
        assertTrue(query.contains("filter: OptionalInput<Filter?> = OptionalInput.Absent"), query)
        assertTrue(query.contains("limit: OptionalInput<Int> = OptionalInput.Absent"), query)
        assertTrue(query.contains("is OptionalInput.Present"), query)
        assertTrue(query.contains("OptionalInput.Absent -> Unit"), query)
        assertTrue(compileKotlinFiles(result.generatedFiles, tempDir), "Generated optional-input query must compile")
    }

    @Test
    fun `generates union selections and custom operation roots`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                schema {
                    query: RootQuery
                }

                union SearchResult = Product | User

                type RootQuery {
                    search: [[SearchResult]]!
                }

                type Product {
                    id: ID!
                }

                type User {
                    id: ID!
                }
                """.trimIndent(),
            )
        }
        val output = File(tempDir, "generated")

        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = true, server = true),
                outputs = GenerationOutputs(output),
            ),
        )

        val query = File(output, "com/steamstreet/graphkt/generated/client/query.kt").readText()
        assertTrue(query.contains("public class _SearchResultQuery"), query)
        assertTrue(query.contains("public fun onProduct("), query)
        assertTrue(query.contains("public fun onUser("), query)
        assertTrue(query.contains("_selection: _SearchResultQuery.() -> Unit"), query)
        assertTrue(query.contains("writer.println(\"__typename\")"), query)
        assertTrue(query.contains("public suspend fun GraphQLClient.query("), query)
        assertTrue(query.contains("): RootQuery"), query)
        assertTrue(query.contains("::RootQueryResponse"), query)

        val responses = File(output, "com/steamstreet/graphkt/generated/client/responses.kt").readText()
        assertTrue(responses.contains("public interface SearchResult"), responses)
        assertTrue(responses.contains("public interface Product : SearchResult"), responses)
        assertTrue(responses.contains("\"Product\" -> ProductResponse"), responses)
        assertTrue(responses.contains("mapIndexed"), responses)
        assertTrue(responses.contains("forElement(index"), responses)

        val services = File(output, "com/steamstreet/graphkt/generated/server/services.kt").readText()
        assertTrue(services.contains("public interface SearchResult"), services)
        assertTrue(services.contains("public interface Product : SearchResult"), services)
        assertTrue(services.contains("public suspend fun search(): List<List<SearchResult?>?>"), services)
        assertFalse(services.contains("ResolverContext"), services)

        val mapping = File(output, "com/steamstreet/graphkt/generated/server/service-mapping.kt").readText()
        assertFalse(mapping.contains("setAsContext"), mapping)
        assertFalse(mapping.contains("gqlRequestContext"), mapping)
        assertTrue(mapping.contains("child.resolveFieldValue(nonNull = true)"), mapping)
        assertTrue(mapping.contains("child.responseName to value"), mapping)
        assertTrue(mapping.contains("resolveListElement"), mapping)

        val server = File(output, "com/steamstreet/graphkt/generated/server/server.kt").readText()
        assertTrue(server.contains("public fun <Context> graphKtServer("), server)
        assertTrue(server.contains("val resolver = query.create(context)"), server)
        assertTrue(server.contains("resolver.gqlSelectChild(selection)"), server)
        assertTrue(server.contains("executionPolicy = executionPolicy"), server)
        assertTrue(server.contains("directiveHandlers: Map<String, GraphQLDirectiveHandler<Context>>"), server)
        assertTrue(server.contains("directiveHandlers = directiveHandlers"), server)
        assertTrue(compileKotlinFiles(result.generatedFiles, tempDir), "Generated union client must compile")
    }

    @Test
    fun `generates common server schema metadata with defaults and oneOf inputs`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                input Filter {
                    limit: Int! = 20
                    tags: [String!]
                }

                input UserKey @oneOf {
                    id: ID
                    email: String
                }

                interface Node {
                    id: ID!
                }

                type User implements Node {
                    id: ID!
                }

                union Result = User

                directive @cached(ttl: Int! = 60) repeatable on FIELD | QUERY

                type Query {
                    search(filter: Filter, limit: Int! = 10): [Result!]!
                    user(key: UserKey!): User
                }
                """.trimIndent(),
            )
        }
        val output = File(tempDir, "generated")

        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = false, server = true),
                outputs = GenerationOutputs(output),
            ),
        )

        val metadata = File(output, "com/steamstreet/graphkt/generated/server/schema.kt").readText()
        assertTrue(metadata.contains("public val graphKtSchema: GraphQLSchemaDefinition"), metadata)
        assertTrue(metadata.contains("isOneOf = true"), metadata)
        assertTrue(metadata.contains("defaultValue = OptionalInput.Present(JsonPrimitive(20))"), metadata)
        assertTrue(metadata.contains("GraphQLUnionType(\"Result\", setOf("), metadata)
        assertTrue(metadata.contains("GraphQLDirectiveDefinition("), metadata)
        assertTrue(metadata.contains("name = \"cached\""), metadata)
        assertTrue(metadata.contains("GraphQLDirectiveLocation.QUERY"), metadata)
        assertTrue(metadata.contains("repeatable = true"), metadata)
        assertTrue(compileKotlinFiles(result.generatedFiles, tempDir), "Generated server metadata must compile")
    }

    @Test
    fun `rejects an empty schema file list`(@TempDir tempDir: File) {
        val error = assertFailsWith<GenerationException> {
            GraphKtGenerator().generate(
                GenerationRequest(
                    schemaFiles = emptyList(),
                    packageName = "com.steamstreet.graphkt.generated",
                    outputs = GenerationOutputs(tempDir),
                ),
            )
        }

        assertEquals(listOf("GRAPHKT_REQUEST"), error.diagnostics.map { it.code })
        assertTrue(error.message.orEmpty().contains("At least one schema file is required"))
    }

    @Test
    fun `reports schema syntax diagnostics and preserves existing output`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply { writeText("type Query {") }
        val output = File(tempDir, "generated").apply { mkdirs() }
        val existing = File(output, "existing.kt").apply { writeText("existing") }

        val error = assertFailsWith<GenerationException> {
            GraphKtGenerator().generate(
                GenerationRequest(
                    schemaFiles = listOf(schema),
                    packageName = "com.steamstreet.graphkt.generated",
                    outputs = GenerationOutputs(output),
                ),
            )
        }

        val diagnostic = error.diagnostics.single()
        assertEquals("GRAPHKT_SCHEMA_SYNTAX", diagnostic.code)
        assertEquals(GenerationDiagnosticSeverity.ERROR, diagnostic.severity)
        assertEquals(schema.canonicalFile, diagnostic.location?.file)
        assertTrue((diagnostic.location?.line ?: 0) > 0)
        assertEquals("existing", existing.readText())
    }

    @Test
    fun `replaces generated output and removes stale files`(@TempDir tempDir: File) {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText("type Query { value: String! }")
        }
        val output = File(tempDir, "generated")
        val generator = GraphKtGenerator()

        generator.generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = true, server = false),
                outputs = GenerationOutputs(output),
            ),
        )
        val stale = File(output, "stale.kt").apply { writeText("stale") }
        val oldClientFile = File(output, "com/steamstreet/graphkt/generated/client/query.kt")
        assertTrue(oldClientFile.isFile)

        val result = generator.generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = false, server = false),
                outputs = GenerationOutputs(output),
            ),
        )

        assertFalse(stale.exists())
        assertFalse(oldClientFile.exists())
        assertEquals(
            listOf(File(output, "com/steamstreet/graphkt/generated/common.kt").canonicalFile),
            result.generatedFiles,
        )
    }
}
