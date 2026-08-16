package com.steamstreet.graphkt.server.execution

import com.steamstreet.graphkt.GraphQLRequest
import com.steamstreet.graphkt.GraphQLResponseEnvelopeSerializer
import com.steamstreet.graphkt.server.BatchLoader
import com.steamstreet.graphkt.server.DefaultGraphQLRequestScope
import com.steamstreet.graphkt.server.GraphQLRootFieldResolver
import com.steamstreet.graphkt.server.GraphQLRootResolverFactory
import com.steamstreet.graphkt.server.GraphQLServer
import com.steamstreet.graphkt.server.resolveFieldValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class GraphQLPerformanceBaseline {
    @Test
    fun recordRuntimeBaselines() {
        if (System.getProperty("graphkt.performance.baseline") != "true") return

        println(
            "GraphKt baseline environment: " +
                "os=${System.getProperty("os.name")}, " +
                "arch=${System.getProperty("os.arch")}, " +
                "java=${System.getProperty("java.version")}",
        )

        val parser = GraphQLDocumentParser()
        val validator = GraphQLDocumentValidator(testSchema())
        val smallDocument = parser.parse(SMALL_QUERY)
        val mediumDocument = parser.parse(MEDIUM_QUERY)

        recordBaseline("parse-small", operationsPerSample = 5_000) { parser.parse(SMALL_QUERY) }
        recordBaseline("parse-medium-100-fields", operationsPerSample = 500) { parser.parse(MEDIUM_QUERY) }
        recordBaseline("validate-small", operationsPerSample = 2_000) {
            validator.validate(smallDocument, "Node")
        }
        recordBaseline("validate-medium-100-fields", operationsPerSample = 100) {
            validator.validate(mediumDocument, "Nodes")
        }

        val server = baselineServer()
        val smallRequest = GraphQLRequest(
            query = SMALL_QUERY,
            operationName = "Node",
            variables = buildJsonObject { put("id", "1") },
        )
        val mediumRequest = GraphQLRequest(query = MEDIUM_QUERY, operationName = "Nodes")
        val smallResponse = runBlocking { server.execute(smallRequest, Unit) }
        val mediumResponse = runBlocking { server.execute(mediumRequest, Unit) }

        recordSuspendBaseline("execute-small", operationsPerSample = 500) {
            server.execute(smallRequest, Unit)
        }
        recordSuspendBaseline("execute-medium-100-fields", operationsPerSample = 20) {
            server.execute(mediumRequest, Unit)
        }
        recordSuspendBaseline("batch-100-loads-50-keys", operationsPerSample = 100) {
            loadBatch()
        }
        recordBaseline("encode-small-response", operationsPerSample = 20_000) {
            BASELINE_JSON.encodeToString(GraphQLResponseEnvelopeSerializer, smallResponse)
        }
        recordBaseline("encode-medium-100-fields", operationsPerSample = 2_000) {
            BASELINE_JSON.encodeToString(GraphQLResponseEnvelopeSerializer, mediumResponse)
        }
    }
}

private fun recordBaseline(
    name: String,
    operationsPerSample: Int,
    warmupSamples: Int = 10,
    measuredSamples: Int = 20,
    block: () -> Any?,
) {
    repeat(warmupSamples) { runBatch(operationsPerSample, block) }
    val samples = LongArray(measuredSamples) {
        val start = System.nanoTime()
        runBatch(operationsPerSample, block)
        (System.nanoTime() - start) / operationsPerSample
    }.sorted()

    val median = samples[samples.size / 2]
    val p95 = samples[((samples.size * 95 + 99) / 100 - 1).coerceAtMost(samples.lastIndex)]
    val operationsPerSecond = 1_000_000_000L / median.coerceAtLeast(1)
    println("GraphKt baseline $name: median=$median ns/op, p95=$p95 ns/op, throughput=$operationsPerSecond ops/s")
}

private fun runBatch(operations: Int, block: () -> Any?) {
    var result: Any? = null
    repeat(operations) { result = block() }
    performanceBlackhole = result
}

private fun recordSuspendBaseline(
    name: String,
    operationsPerSample: Int,
    warmupSamples: Int = 10,
    measuredSamples: Int = 20,
    block: suspend () -> Any?,
) = runBlocking {
    repeat(warmupSamples) { runSuspendBatch(operationsPerSample, block) }
    val samples = LongArray(measuredSamples) {
        val start = System.nanoTime()
        runSuspendBatch(operationsPerSample, block)
        (System.nanoTime() - start) / operationsPerSample
    }.sorted()

    val median = samples[samples.size / 2]
    val p95 = samples[((samples.size * 95 + 99) / 100 - 1).coerceAtMost(samples.lastIndex)]
    val operationsPerSecond = 1_000_000_000L / median.coerceAtLeast(1)
    println("GraphKt baseline $name: median=$median ns/op, p95=$p95 ns/op, throughput=$operationsPerSecond ops/s")
}

private suspend fun runSuspendBatch(operations: Int, block: suspend () -> Any?) {
    var result: Any? = null
    repeat(operations) { result = block() }
    performanceBlackhole = result
}

private fun baselineServer(): GraphQLServer<Unit> = GraphQLServer(
    schema = testSchema(),
    query = GraphQLRootResolverFactory {
        GraphQLRootFieldResolver { selection ->
            selection.resolveFieldValue(nonNull = false) {
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("1"),
                        "name" to JsonPrimitive("Ada"),
                    ),
                )
            }
        }
    },
)

private suspend fun loadBatch(): List<Int?> {
    val scope = DefaultGraphQLRequestScope(currentCoroutineContext())
    return try {
        scope.batchLoader<Int, Int>(
            BatchLoader { keys -> keys.associateWith { key -> key * 2 } },
        ).loadMany(BATCH_KEYS)
    } finally {
        scope.close()
    }
}

@Volatile
private var performanceBlackhole: Any? = null

private val BASELINE_JSON: Json = Json

private val BATCH_KEYS: List<Int> = List(100) { index -> index % 50 }

private const val SMALL_QUERY: String =
    "query Node(${'$'}id: ID!) { node(id: ${'$'}id) { id ... on User { name } } }"

private val MEDIUM_QUERY: String = buildString {
    append("query Nodes { ")
    repeat(100) { index ->
        append("node$index: node(id: \"")
        append(index)
        append("\") { id ... on User { name } } ")
    }
    append("}")
}
