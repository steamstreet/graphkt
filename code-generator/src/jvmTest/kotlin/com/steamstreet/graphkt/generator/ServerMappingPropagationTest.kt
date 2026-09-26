package com.steamstreet.graphkt.generator

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compiles generated mapping code and drives it through the JVM `ServerRequestSelection`. */
class ServerMappingPropagationTest {
    @Test
    fun `root gqlSelect returns null data when a non-null root field fails`(@TempDir tempDir: File) {
        val driver = compileDriver(tempDir)

        assertEquals(
            """{"data":null,"errors":[{"message":"Internal Server Error","path":["event","title"]}]}""",
            driver.select("{ event { id title } version }"),
        )
    }

    @Test
    fun `root gqlSelect returns null data for a failed forRootField selection`(@TempDir tempDir: File) {
        val driver = compileDriver(tempDir)

        assertEquals(
            """{"data":null,"errors":[{"message":"Internal Server Error","path":["event","title"]}]}""",
            driver.selectRootField("event", "{ id title }"),
        )
        assertEquals(
            """{"data":{"version":"3"}}""",
            driver.selectRootField("version", null),
        )
    }

    @Test
    fun `null propagation stops at the nearest nullable ancestor`(@TempDir tempDir: File) {
        val driver = compileDriver(tempDir)

        assertEquals(
            """{"data":{"maybeEvent":null,"events":[null],"version":"3"},"errors":[""" +
                """{"message":"Internal Server Error","path":["maybeEvent","title"]},""" +
                """{"message":"Internal Server Error","path":["events",0,"title"]}]}""",
            driver.select("{ maybeEvent { id title } events { id title } version }"),
        )
    }

    private class Driver(private val type: Class<*>) {
        fun select(query: String): String =
            type.getMethod("select", String::class.java).invoke(null, query) as String

        fun selectRootField(fieldName: String, selectionSet: String?): String =
            type.getMethod("selectRootField", String::class.java, String::class.java)
                .invoke(null, fieldName, selectionSet) as String
    }

    private fun compileDriver(tempDir: File): Driver {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                type Query {
                    event: Event!
                    maybeEvent: Event
                    events: [Event]!
                    version: String!
                }

                type Event {
                    id: String!
                    title: String!
                }
                """.trimIndent(),
            )
        }
        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = false, server = true),
                outputs = GenerationOutputs(File(tempDir, "generated")),
            ),
        )
        val driverSource = File(tempDir, "driver/Driver.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package driver

                import com.steamstreet.graphkt.GraphQLError
                import com.steamstreet.graphkt.generated.server.Event
                import com.steamstreet.graphkt.generated.server.Query
                import com.steamstreet.graphkt.generated.server.gqlSelect
                import com.steamstreet.graphkt.server.ServerRequestSelection
                import com.steamstreet.graphkt.server.buildResponse
                import com.steamstreet.graphkt.server.parseGraphQLOperation
                import kotlinx.coroutines.runBlocking

                class EventResolver : Event {
                    override suspend fun id(): String = "e1"
                    override suspend fun title(): String = throw IllegalStateException("title unavailable")
                }

                class QueryResolver : Query {
                    override suspend fun event(): Event = EventResolver()
                    override suspend fun maybeEvent(): Event? = EventResolver()
                    override suspend fun events(): List<Event?> = listOf(EventResolver())
                    override suspend fun version(): String = "3"
                }

                fun select(query: String): String = runBlocking {
                    val errors = mutableListOf<GraphQLError>()
                    val root = ServerRequestSelection(null, emptyMap(), parseGraphQLOperation(query).selectionSet, errors)
                    buildResponse(QueryResolver().gqlSelect(root), errors).toString()
                }

                fun selectRootField(fieldName: String, selectionSet: String?): String = runBlocking {
                    val errors = mutableListOf<GraphQLError>()
                    val root = ServerRequestSelection.forRootField(fieldName, selectionSet, emptyMap(), emptyMap(), errors)
                    buildResponse(QueryResolver().gqlSelect(root), errors).toString()
                }
                """.trimIndent(),
            )
        }
        val output = File(tempDir, "compiled")
        assertTrue(
            compileKotlinFiles(result.generatedFiles + driverSource, output),
            "Generated server code and its driver must compile",
        )
        val loader = URLClassLoader(arrayOf(File(output, "classes").toURI().toURL()), javaClass.classLoader)
        return Driver(loader.loadClass("driver.DriverKt"))
    }
}
