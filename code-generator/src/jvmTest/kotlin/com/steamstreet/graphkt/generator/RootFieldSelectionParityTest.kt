package com.steamstreet.graphkt.generator

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives generated mapping code through the JVM `ServerRequestSelection.forRootField` and the common
 * `RequestSelection.forRootField` with identical inputs, and requires identical responses. The common
 * version is what a Kotlin/Native AppSync Lambda uses, so it must behave as the JVM version does.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RootFieldSelectionParityTest {
    private lateinit var driver: Driver

    @BeforeAll
    fun compile(@TempDir tempDir: File) {
        driver = compileDriver(tempDir)
    }

    @Test
    fun `aliases are keyed by field name and kept in error paths`() {
        assertSame(
            """{"data":{"event":{"id":"e1","title":"Title e1","venue":{"name":"Venue v-e1"}}}}""",
            fieldName = "event",
            selectionSet = "{ ident: id heading: title place: venue { called: name } }",
            arguments = """{"id":"e1"}""",
        )
        assertSame(
            """{"data":{"maybeEvent":null},"errors":[{"message":"Internal Server Error","path":["maybeEvent","heading"]}]}""",
            fieldName = "maybeEvent",
            selectionSet = "{ ident: id heading: title }",
            arguments = """{"id":"bad1"}""",
        )
    }

    @Test
    fun `two aliases of one field share a single key`() {
        assertSame(
            """{"data":{"event":{"tags":["a","b"]}}}""",
            fieldName = "event",
            selectionSet = "{ one: tags(limit: 1) two: tags(limit: 2) }",
            arguments = """{"id":"e1"}""",
        )
    }

    @Test
    fun `root arguments and nested argument literals reach the resolvers`() {
        assertSame(
            """{"data":{"search":[""" +
                """{"id":"e-jazz","echo":"filter={\"near\":\"X\",\"tags\":[\"t\",null],\"radius\":1.5,\"sort\":\"OLDEST\"} sort=\"NEWEST\" items=[1, null, 3]",""" +
                """"tags":["#a","#b"]},""" +
                """{"id":"v-LAS-2.5-NEWEST","name":"Venue v-LAS-2.5-NEWEST","events":[""" +
                """{"id":"v-LAS-2.5-NEWEST-e0","title":"Title v-LAS-2.5-NEWEST-e0"},""" +
                """{"id":"v-LAS-2.5-NEWEST-e1","title":"Title v-LAS-2.5-NEWEST-e1"}]}]}}""",
            fieldName = "search",
            selectionSet = """
                {
                  id
                  ... on Event {
                    echo(filter: { near: "X", tags: ["t", null], radius: 1.5, sort: OLDEST }, sort: NEWEST, items: [1, null, 3])
                    tags(limit: 2, prefix: "#")
                  }
                  ... on Venue { name events(first: 2) { id title } }
                }
            """.trimIndent(),
            arguments = """{"query":"jazz","filter":{"near":"LAS","tags":["a",null],"radius":2.5,"sort":"NEWEST"},"limit":2}""",
        )
    }

    @Test
    fun `inline fragments select by runtime type`() {
        assertSame(
            """{"data":{"search":[{"__typename":"Event","id":"e-x","title":"Title e-x"},""" +
                """{"__typename":"Venue","id":"v","name":"Venue v"}]}}""",
            fieldName = "search",
            selectionSet = "{ __typename id ... on Event { title } ... on Venue { name } ... on Event { id } }",
            arguments = """{"query":"x"}""",
        )
    }

    @Test
    fun `variables reach nested fields`() {
        assertSame(
            """{"data":{"maybeEvent":{"venue":{"events":[{"id":"v-e1-e0","tags":["#a","#b","#c"]},""" +
                """{"id":"v-e1-e1","tags":["#a","#b","#c"]}]}}}}""",
            fieldName = "maybeEvent",
            selectionSet = "{ venue { events(first: \$first) { id tags(prefix: \$prefix) } } }",
            arguments = """{"id":"e1"}""",
            variables = """{"first":2,"prefix":"#"}""",
        )
        assertSame(
            """{"data":{"maybeEvent":{"venue":{"events":[{"id":"v-e1-e0","tags":["a","b","c"]}]}}}}""",
            fieldName = "maybeEvent",
            selectionSet = "{ venue { events(first: \$first) { id tags(prefix: \$prefix) } } }",
            arguments = """{"id":"e1"}""",
        )
    }

    @Test
    fun `null propagates to the root`() {
        assertSame(
            """{"data":null,"errors":[{"message":"Internal Server Error","path":["event","title"]}]}""",
            fieldName = "event",
            selectionSet = "{ id title }",
            arguments = """{"id":"bad1"}""",
        )
        assertSame(
            """{"data":null,"errors":[{"message":"Internal Server Error","path":["search",1,"name"]}]}""",
            fieldName = "search",
            selectionSet = "{ ... on Event { title } ... on Venue { name } }",
            arguments = """{"query":"x","filter":{"near":"bad"}}""",
        )
        assertSame(
            """{"data":{"maybeEvent":null},"errors":[{"message":"Internal Server Error","path":["maybeEvent","title"]}]}""",
            fieldName = "maybeEvent",
            selectionSet = "{ id title }",
            arguments = """{"id":"bad1"}""",
        )
    }

    @Test
    fun `a throwing resolver fails only its field`() {
        assertSame(
            """{"data":{"event":{"id":"e1","broken":null,"pending":null,"title":"Title e1"}},"errors":[""" +
                """{"message":"broken e1","path":["event","broken"],"extensions":{"stacktrace":"java.lang.IllegalArgumentException: broken e1"}},""" +
                """{"message":"An operation is not implemented: pending","path":["event","pending"],""" +
                """"extensions":{"stacktrace":"kotlin.NotImplementedError: An operation is not implemented: pending"}}]}""",
            fieldName = "event",
            selectionSet = "{ id broken pending title }",
            arguments = """{"id":"e1"}""",
            details = true,
        )
        assertSame(
            """{"data":null,"errors":[{"message":"Internal Server Error","path":["upcoming"]}]}""",
            fieldName = "upcoming",
            selectionSet = "{ id }",
        )
    }

    @Test
    fun `leaf and mutation root fields`() {
        assertSame("""{"data":{"version":"3"}}""", fieldName = "version", selectionSet = null)
        assertSame("""{"data":{"version":"3"}}""", fieldName = "version", selectionSet = " ")
        assertSame(
            """{"data":{"rename":{"id":"e1","title":"Renamed"}}}""",
            fieldName = "rename",
            selectionSet = "{ id heading: title }",
            arguments = """{"id":"e1","title":"Renamed"}""",
            operation = "mutation",
        )
    }

    private fun assertSame(
        expected: String,
        fieldName: String,
        selectionSet: String?,
        arguments: String = "{}",
        variables: String = "{}",
        details: Boolean = false,
        operation: String = "query",
    ) {
        val jvm = driver.select("jvm", operation, fieldName, selectionSet, arguments, variables, details)
        val common = driver.select("common", operation, fieldName, selectionSet, arguments, variables, details)
        assertEquals(jvm, common, "The common forRootField must respond as the JVM version does")
        assertEquals(expected, common)
    }

    private class Driver(private val type: Class<*>) {
        fun select(
            implementation: String,
            operation: String,
            fieldName: String,
            selectionSet: String?,
            arguments: String,
            variables: String,
            details: Boolean,
        ): String = type.getMethod(
            "select",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        ).invoke(null, implementation, operation, fieldName, selectionSet, arguments, variables, details) as String
    }

    private fun compileDriver(tempDir: File): Driver {
        val schema = File(tempDir, "schema.graphql").apply {
            writeText(
                """
                type Query {
                    event(id: ID!): Event!
                    maybeEvent(id: ID): Event
                    search(query: String!, filter: Json, limit: Int): [Node!]!
                    upcoming: Event!
                    version: String!
                }

                type Mutation {
                    rename(id: ID!, title: String!): Event
                }

                scalar Json

                interface Node {
                    id: ID!
                }

                type Event implements Node {
                    id: ID!
                    title: String!
                    venue: Venue
                    tags(limit: Int, prefix: String): [String!]!
                    echo(filter: Json, sort: Json, items: [Int]): String
                    pending: String
                    broken: String
                }

                type Venue implements Node {
                    id: ID!
                    name: String!
                    events(first: Int): [Event!]!
                }
                """.trimIndent(),
            )
        }
        // Object and enum arguments use a JSON scalar rather than an input or enum type: the
        // in-process compiler does not apply the serialization plugin, so the generated input and
        // enum types have no serializers there. The literals still reach the resolver as JSON.
        val properties = File(tempDir, "schema.properties").apply {
            writeText(
                """
                scalar.Json.class=kotlinx.serialization.json.JsonElement
                scalar.Json.serializer=driver.JsonValueSerializer
                """.trimIndent(),
            )
        }
        val result = GraphKtGenerator().generate(
            GenerationRequest(
                schemaFiles = listOf(schema),
                propertiesFile = properties,
                packageName = "com.steamstreet.graphkt.generated",
                features = GenerationFeatures(client = false, server = true),
                outputs = GenerationOutputs(File(tempDir, "generated")),
            ),
        )
        // The driver avoids string templates, so that it needs no escaping inside this raw string.
        val driverSource = File(tempDir, "driver/Driver.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package driver

                import com.steamstreet.graphkt.GraphQLError
                import com.steamstreet.graphkt.generated.server.Event
                import com.steamstreet.graphkt.generated.server.Mutation
                import com.steamstreet.graphkt.generated.server.Node
                import com.steamstreet.graphkt.generated.server.Query
                import com.steamstreet.graphkt.generated.server.Venue
                import com.steamstreet.graphkt.generated.server.gqlSelect
                import com.steamstreet.graphkt.server.GraphQLResolverErrorFactory
                import com.steamstreet.graphkt.server.RequestSelection
                import com.steamstreet.graphkt.server.ServerRequestSelection
                import com.steamstreet.graphkt.server.buildResponse
                import kotlinx.coroutines.runBlocking
                import kotlinx.serialization.json.Json
                import kotlinx.serialization.json.JsonArray
                import kotlinx.serialization.json.JsonElement
                import kotlinx.serialization.json.JsonObject
                import kotlinx.serialization.json.JsonPrimitive
                import kotlinx.serialization.json.jsonObject
                import kotlinx.serialization.json.jsonPrimitive
                import kotlinx.serialization.KSerializer

                object JsonValueSerializer : KSerializer<JsonElement> by JsonElement.serializer()

                class EventResolver(private val id: String, private val newTitle: String? = null) : Event {
                    override suspend fun id(): String = id
                    override suspend fun title(): String =
                        if (id.startsWith("bad")) throw IllegalStateException("title unavailable") else newTitle ?: ("Title " + id)
                    override suspend fun venue(): Venue = VenueResolver("v-" + id)
                    override suspend fun tags(limit: Int?, prefix: String?): List<String> =
                        listOf("a", "b", "c").map { (prefix ?: "") + it }.take(limit ?: 3)
                    override suspend fun echo(filter: JsonElement?, sort: JsonElement?, items: List<Int?>?): String =
                        "filter=" + filter + " sort=" + sort + " items=" + items
                    override suspend fun pending(): String = TODO("pending")
                    override suspend fun broken(): String = throw IllegalArgumentException("broken " + id)
                }

                class VenueResolver(private val id: String) : Venue {
                    override suspend fun id(): String = id
                    override suspend fun name(): String =
                        if (id.contains("bad")) throw IllegalStateException("name unavailable") else "Venue " + id
                    override suspend fun events(first: Int?): List<Event> =
                        List(first ?: 1) { EventResolver(id + "-e" + it) }
                }

                class QueryResolver : Query {
                    override suspend fun event(id: String): Event = EventResolver(id)
                    override suspend fun maybeEvent(id: String?): Event? = id?.let { EventResolver(it) }
                    override suspend fun search(query: String, filter: JsonElement?, limit: Int?): List<Node> {
                        val fields = (filter as? JsonObject).orEmpty()
                        val venueId = listOfNotNull("v", fields["near"], fields["radius"], fields["sort"])
                            .joinToString("-") { (it as? JsonPrimitive)?.content ?: it.toString() }
                        return listOf(EventResolver("e-" + query), VenueResolver(venueId)).take(limit ?: 2)
                    }
                    override suspend fun upcoming(): Event = TODO("upcoming")
                    override suspend fun version(): String = "3"
                }

                class MutationResolver : Mutation {
                    override suspend fun rename(id: String, title: String): Event = EventResolver(id, title)
                }

                fun select(
                    implementation: String,
                    operation: String,
                    fieldName: String,
                    selectionSet: String?,
                    arguments: String,
                    variables: String,
                    details: Boolean,
                ): String = runBlocking {
                    val errors = mutableListOf<GraphQLError>()
                    val factory = if (details) GraphQLResolverErrorFactory.WithExceptionDetails else GraphQLResolverErrorFactory.Generic
                    val args = Json.parseToJsonElement(arguments).jsonObject
                    val vars = Json.parseToJsonElement(variables).jsonObject
                    val selection: RequestSelection = when (implementation) {
                        "jvm" -> ServerRequestSelection.forRootField(fieldName, selectionSet, args, vars, errors, factory)
                        "common" -> RequestSelection.forRootField(fieldName, selectionSet, args, vars, errors, factory)
                        else -> throw IllegalArgumentException(implementation)
                    }
                    val data = if (operation == "mutation") MutationResolver().gqlSelect(selection) else QueryResolver().gqlSelect(selection)
                    withFirstStackTraceLine(buildResponse(data, errors)).toString()
                }

                // Stack traces differ in the frames between the two implementations; the first line
                // names the exception and its message.
                fun withFirstStackTraceLine(response: JsonObject): JsonObject {
                    val errors = response["errors"] as? JsonArray ?: return response
                    return JsonObject(
                        response + ("errors" to JsonArray(
                            errors.map { error ->
                                val extensions = error.jsonObject["extensions"]?.jsonObject ?: return@map error
                                val trace = extensions.getValue("stacktrace").jsonPrimitive.content.lineSequence().first()
                                JsonObject(error.jsonObject + ("extensions" to JsonObject(extensions + ("stacktrace" to JsonPrimitive(trace)))))
                            },
                        )),
                    )
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
