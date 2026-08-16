package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLPathSegment
import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.server.execution.testSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphQLServerExecutionTest {
    @Test
    fun `executes aliases fragments directives and coerced arguments`() = runTest {
        var nodeCalls = 0
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                nodeCalls += 1
                assertEquals("node", node.name)
                assertEquals("profile", node.responseName)
                assertEquals("7", node.inputParameter("id").jsonPrimitive.content)
                assertEquals(1, node.children.count { it.name == "id" })
                node.resolveFieldValue(nonNull = false) {
                    selectUser(node, name = "Ada")
                }
            },
        )

        val response = server.execute(
            GraphQLRequest(
                query = """
                    query Lookup(${DOLLAR}id: ID!, ${DOLLAR}showName: Boolean!) {
                      profile: node(id: ${DOLLAR}id) {
                        id
                        ...BaseNode
                        ... on User @include(if: ${DOLLAR}showName) { label: name }
                        ... on Product { title }
                      }
                    }
                    fragment BaseNode on Node { id }
                """.trimIndent(),
                variables = JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(7),
                        "showName" to JsonPrimitive(true),
                    ),
                ),
            ),
            Unit,
        )

        assertEquals(1, nodeCalls)
        assertEquals(
            JsonObject(
                mapOf(
                    "profile" to JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("7"),
                            "label" to JsonPrimitive("Ada"),
                        ),
                    ),
                ),
            ),
            response.data,
        )
        assertNull(response.errors)
    }

    @Test
    fun `runs custom field directive handlers in document order with coerced defaults`() = runTest {
        val events = mutableListOf<String>()
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<String> { context, node ->
                node.resolveFieldValue(nonNull = false) {
                    events += "resolve:$context"
                    selectUser(node)
                }
            },
            directiveHandlers = mapOf(
                "cached" to GraphQLDirectiveHandler { context, directive, selection, next ->
                    events += "before:${directive.arguments.getValue("ttl").jsonPrimitive.content}:$context:${selection.name}"
                    val value = next.proceed()
                    events += "after:${directive.arguments.getValue("ttl").jsonPrimitive.content}"
                    value
                },
            ),
        )

        val response = server.execute(
            GraphQLRequest(
                """
                {
                    node(id: "1") @cached @cached(ttl: 5) { id }
                }
                """.trimIndent(),
            ),
            "context",
        )

        assertNull(response.errors)
        assertEquals(
            listOf(
                "before:60:context:node",
                "before:5:context:node",
                "resolve:context",
                "after:5",
                "after:60",
            ),
            events,
        )
    }

    @Test
    fun `propagates a non-null child failure to its nullable parent`() = runTest {
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                node.resolveFieldValue(nonNull = false) {
                    selectUser(node, nameFailure = IllegalStateException("private detail"))
                }
            },
        )

        val response = server.execute(
            GraphQLRequest("{ profile: node(id: \"1\") { id ... on User { name } } }"),
            Unit,
        )

        assertEquals(JsonObject(mapOf("profile" to JsonNull)), response.data)
        assertEquals("Internal Server Error", response.errors?.single()?.message)
        assertEquals(
            listOf(GraphQLPathSegment.Field("profile"), GraphQLPathSegment.Field("name")),
            response.errors?.single()?.path,
        )
    }

    @Test
    fun `includes a list index in errors and propagates a non-null element`() = runTest {
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, search ->
                search.resolveFieldValue(nonNull = true) {
                    JsonArray(
                        listOf("first", "second").mapIndexed { index, name ->
                            search.resolveListElement(index, nonNull = true) { elementSelection ->
                                selectUser(
                                    selection = elementSelection,
                                    name = name,
                                    nameFailure = if (index == 1) IllegalStateException("hidden") else null,
                                )
                            }
                        },
                    )
                }
            },
        )

        val response = server.execute(
            GraphQLRequest("{ search(term: \"all\") { ... on User { name } } }"),
            Unit,
        )

        assertNull(response.data)
        assertEquals(
            listOf(
                GraphQLPathSegment.Field("search"),
                GraphQLPathSegment.Index(1),
                GraphQLPathSegment.Field("name"),
            ),
            response.errors?.single()?.path,
        )
    }

    @Test
    fun `does not convert cancellation into a GraphQL error`() = runTest {
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                node.resolveFieldValue(nonNull = false) {
                    throw CancellationException("cancelled")
                }
            },
        )

        assertFailsWith<CancellationException> {
            server.execute(GraphQLRequest("{ node(id: \"1\") { id } }"), Unit)
        }
    }

    @Test
    fun `rejects a mutation from query-only execution before context construction`() = runTest {
        var contextCalls = 0
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, _ -> JsonNull },
            mutation = rootResolver<Unit> { _, _ -> JsonNull },
        )

        val failure = assertFailsWith<GraphQLOperationNotAllowedException> {
            server.executeQuery(GraphQLRequest("mutation { rename(name: \"Ada\") { name } }")) {
                contextCalls += 1
                Unit
            }
        }

        assertEquals(GraphQLOperationType.MUTATION, failure.operationType)
        assertEquals(0, contextCalls)
    }

    @Test
    fun `does not create lazy context for an invalid operation`() = runTest {
        var contextCalls = 0
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, _ -> JsonNull },
        )

        val response = server.execute(GraphQLRequest("{ missing }")) {
            contextCalls += 1
            Unit
        }

        assertEquals(0, contextCalls)
        assertTrue(response.errors.orEmpty().isNotEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `executes query root fields concurrently up to the configured limit`() = runTest {
        val started = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        var resolverFactoryCalls = 0
        val server = GraphQLServer<Unit>(
            schema = testSchema(),
            query = GraphQLRootResolverFactory<Unit> {
                resolverFactoryCalls += 1
                GraphQLRootFieldResolver { field ->
                    field.resolveFieldValue(nonNull = false) {
                        started += field.responseName
                        release.await()
                        JsonNull
                    }
                }
            },
            executionPolicy = GraphQLExecutionPolicy(maximumQueryParallelism = 2),
        )

        val response = async {
            server.execute(
                GraphQLRequest(
                    """
                    {
                      first: node(id: "1") { id }
                      second: node(id: "2") { id }
                      third: node(id: "3") { id }
                    }
                    """.trimIndent(),
                ),
                Unit,
            )
        }
        runCurrent()

        assertEquals(listOf("first", "second"), started)
        assertEquals(1, resolverFactoryCalls)

        release.complete(Unit)
        val envelope = response.await()
        assertEquals(listOf("first", "second", "third"), started)
        assertEquals(listOf("first", "second", "third"), envelope.data?.keys?.toList())
    }

    @Test
    fun `keeps concurrent query errors in response order`() = runTest {
        val secondFailed = CompletableDeferred<Unit>()
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, field ->
                field.resolveFieldValue(nonNull = false) {
                    if (field.responseName == "first") {
                        secondFailed.await()
                    } else {
                        secondFailed.complete(Unit)
                    }
                    throw IllegalStateException("private ${field.responseName}")
                }
            },
        )

        val response = server.execute(
            GraphQLRequest(
                """
                {
                  first: node(id: "1") { id }
                  second: node(id: "2") { id }
                }
                """.trimIndent(),
            ),
            Unit,
        )

        assertEquals(
            listOf(
                listOf(GraphQLPathSegment.Field("first")),
                listOf(GraphQLPathSegment.Field("second")),
            ),
            response.errors?.map { it.path },
        )
        assertEquals(
            JsonObject(mapOf("first" to JsonNull, "second" to JsonNull)),
            response.data,
        )
    }

    @Test
    fun `executes mutation root fields serially in document order`() = runTest {
        val events = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        var resolverFactoryCalls = 0
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, _ -> JsonNull },
            mutation = GraphQLRootResolverFactory {
                resolverFactoryCalls += 1
                GraphQLRootFieldResolver { field ->
                    field.resolveFieldValue(nonNull = false) {
                        events += "start:${field.responseName}"
                        if (field.responseName == "first") releaseFirst.await()
                        events += "end:${field.responseName}"
                        JsonNull
                    }
                }
            },
        )

        val response = async {
            server.execute(
                GraphQLRequest(
                    """
                    mutation {
                      first: rename(name: "Ada") { name }
                      second: rename(name: "Grace") { name }
                    }
                    """.trimIndent(),
                ),
                Unit,
            )
        }
        yield()

        assertEquals(listOf("start:first"), events)
        assertEquals(1, resolverFactoryCalls)

        releaseFirst.complete(Unit)
        response.await()
        assertEquals(
            listOf("start:first", "end:first", "start:second", "end:second"),
            events,
        )
    }

    @Test
    fun `batches and caches loads across concurrent fields within one request`() = runTest {
        val batches = mutableListOf<List<String>>()
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<BatchingContext> { context, node ->
                node.resolveFieldValue(nonNull = false) {
                    val id = node.inputParameter("id").jsonPrimitive.content
                    selectUser(node, name = context.names.load(id) ?: "missing")
                }
            },
        )

        val response = server.execute(
            GraphQLRequest(
                """
                {
                  first: node(id: "1") { ... on User { name } }
                  second: node(id: "2") { ... on User { name } }
                  repeated: node(id: "1") { ... on User { name } }
                }
                """.trimIndent(),
            ),
        ) {
            BatchingContext(
                names = batchLoader(
                    BatchLoader { keys ->
                        batches.add(keys)
                        keys.associateWith { key -> "User $key" }
                    },
                ),
            )
        }

        assertNull(response.errors)
        assertEquals(listOf(listOf("1", "2")), batches)
        assertEquals(
            listOf("User 1", "User 2", "User 1"),
            response.data?.values?.map { node ->
                (node as JsonObject).getValue("name").jsonPrimitive.content
            },
        )
    }

    @Test
    fun `does not share batch caches between requests`() = runTest {
        val batches = mutableListOf<List<String>>()
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<BatchingContext> { context, node ->
                node.resolveFieldValue(nonNull = false) {
                    val id = node.inputParameter("id").jsonPrimitive.content
                    context.names.load(id)
                    selectUser(node)
                }
            },
        )
        val request = GraphQLRequest("{ node(id: \"1\") { id } }")

        repeat(2) {
            server.execute(request) {
                BatchingContext(
                    batchLoader(
                        BatchLoader { keys ->
                            batches.add(keys)
                            keys.associateWith { key -> "User $key" }
                        },
                    ),
                )
            }
        }

        assertEquals(listOf(listOf("1"), listOf("1")), batches)
    }

    @Test
    fun `releases request resources in reverse order after execution`() = runTest {
        val events = mutableListOf<String>()
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                node.resolveFieldValue(nonNull = false) { selectUser(node) }
            },
        )

        val response = server.execute(GraphQLRequest("{ node(id: \"1\") { id } }")) {
            onClose { events += "first" }
            onClose { events += "second" }
            Unit
        }

        assertNull(response.errors)
        assertEquals(listOf("second", "first"), events)
    }

    @Test
    fun `releases request resources after context and resolver failures`() = runTest {
        val events = mutableListOf<String>()
        val resolverFailureServer = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                node.resolveFieldValue(nonNull = false) { error("resolver failed") }
            },
        )

        val response = resolverFailureServer.execute(GraphQLRequest("{ node(id: \"1\") { id } }")) {
            onClose { events += "resolver" }
            Unit
        }
        assertTrue(response.errors.orEmpty().isNotEmpty())

        val contextFailure = assertFailsWith<IllegalStateException> {
            resolverFailureServer.execute(GraphQLRequest("{ node(id: \"1\") { id } }")) {
                onClose {
                    events += "context"
                    error("context cleanup failed")
                }
                error("context failed")
            }
        }

        assertEquals("context failed", contextFailure.message)
        assertEquals("context cleanup failed", contextFailure.suppressedExceptions.single().message)
        assertEquals(listOf("resolver", "context"), events)
    }

    @Test
    fun `releases request resources without masking cancellation`() = runTest {
        var released = false
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                node.resolveFieldValue(nonNull = false) { throw CancellationException("cancelled") }
            },
        )

        val failure = assertFailsWith<CancellationException> {
            server.execute(GraphQLRequest("{ node(id: \"1\") { id } }")) {
                onClose {
                    released = true
                    error("cancellation cleanup failed")
                }
                Unit
            }
        }

        assertEquals("cancelled", failure.message)
        assertEquals("cancellation cleanup failed", failure.suppressedExceptions.single().message)
        assertTrue(released)
    }

    @Test
    fun `reports cleanup failures after running every cleanup action`() = runTest {
        val events = mutableListOf<String>()
        val server = GraphQLServer(
            schema = testSchema(),
            query = rootResolver<Unit> { _, node ->
                node.resolveFieldValue(nonNull = false) { selectUser(node) }
            },
        )

        val failure = assertFailsWith<GraphQLRequestCleanupException> {
            server.execute(GraphQLRequest("{ node(id: \"1\") { id } }")) {
                onClose { events += "first" }
                onClose {
                    events += "failing"
                    error("private cleanup detail")
                }
                onClose { events += "last" }
                Unit
            }
        }

        assertEquals(listOf("last", "failing", "first"), events)
        assertEquals("One or more GraphQL request resources could not be released", failure.message)
        assertEquals(1, failure.failures.size)
    }
}

private data class BatchingContext(
    val names: RequestBatchLoader<String, String>,
)

private suspend fun selectUser(
    selection: RequestSelection,
    name: String = "Ada",
    nameFailure: Throwable? = null,
): JsonElement {
    val fields = selection.children.mapNotNull { child ->
        if (!child.appliesTo("User")) return@mapNotNull null
        val value = child.resolveFieldValue(nonNull = true) {
            when (child.name) {
                "id" -> JsonPrimitive("7")
                "name" -> nameFailure?.let { throw it } ?: JsonPrimitive(name)
                "__typename" -> JsonPrimitive("User")
                else -> error("Unexpected User field '${child.name}'")
            }
        }
        child.responseName to value
    }
    return JsonObject(fields.toMap())
}

private fun <Context> rootResolver(
    block: suspend (Context, RequestSelection) -> JsonElement?,
): GraphQLRootResolverFactory<Context> = GraphQLRootResolverFactory { context ->
    GraphQLRootFieldResolver { selection -> block(context, selection) }
}

private const val DOLLAR: Char = '$'
