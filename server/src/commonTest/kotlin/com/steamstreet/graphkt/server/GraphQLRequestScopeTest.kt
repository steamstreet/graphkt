package com.steamstreet.graphkt.server

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GraphQLRequestScopeTest {
    @Test
    fun `loadMany batches unique keys and keeps result order`() = runTest {
        val batches = mutableListOf<List<String>>()
        val scope = DefaultGraphQLRequestScope(currentCoroutineContext())
        try {
            val loader = scope.batchLoader<String, String>(
                BatchLoader { keys ->
                    batches.add(keys)
                    keys.associateWith(String::uppercase)
                },
            )

            assertEquals(listOf("A", "B", "A"), loader.loadMany(listOf("a", "b", "a")))
            assertEquals(listOf("B", "A"), loader.loadMany(listOf("b", "a")))
            assertEquals(listOf(listOf("a", "b")), batches)
        } finally {
            scope.close()
        }
    }

    @Test
    fun `prime and clear control the request cache`() = runTest {
        val batches = mutableListOf<List<String>>()
        val scope = DefaultGraphQLRequestScope(currentCoroutineContext())
        try {
            val loader = scope.batchLoader<String, String>(
                BatchLoader { keys ->
                    batches.add(keys)
                    keys.associateWith { key -> "loaded:$key" }
                },
            )

            loader.prime("a", "primed:a")
            assertEquals(listOf("primed:a", "loaded:b"), loader.loadMany(listOf("a", "b")))
            assertEquals(listOf(listOf("b")), batches)

            loader.clear("a")
            assertEquals("loaded:a", loader.load("a"))
            assertEquals(listOf(listOf("b"), listOf("a")), batches)

            loader.clearAll()
            assertEquals(listOf("loaded:a", "loaded:b"), loader.loadMany(listOf("a", "b")))
            assertEquals(listOf(listOf("b"), listOf("a"), listOf("a", "b")), batches)
        } finally {
            scope.close()
        }
    }

    @Test
    fun `missing values and loader failures remain cached`() = runTest {
        var missingCalls = 0
        var failingCalls = 0
        val scope = DefaultGraphQLRequestScope(currentCoroutineContext())
        try {
            val missing = scope.batchLoader<String, String>(
                BatchLoader {
                    missingCalls += 1
                    emptyMap()
                },
            )
            assertNull(missing.load("missing"))
            assertNull(missing.load("missing"))
            assertEquals(1, missingCalls)

            val failing = scope.batchLoader<String, String>(
                BatchLoader {
                    failingCalls += 1
                    error("batch failed")
                },
            )
            assertFailsWith<IllegalStateException> { failing.load("a") }
            assertFailsWith<IllegalStateException> { failing.load("a") }
            assertEquals(1, failingCalls)

            failing.clear("a")
            assertFailsWith<IllegalStateException> { failing.load("a") }
            assertEquals(2, failingCalls)
        } finally {
            scope.close()
        }
    }

    @Test
    fun `closed request loaders reject later work`() = runTest {
        val scope = DefaultGraphQLRequestScope(currentCoroutineContext())
        val loader = scope.batchLoader<String, String>(BatchLoader { emptyMap() })

        scope.close()

        assertFailsWith<IllegalStateException> { loader.load("a") }
        assertFailsWith<IllegalStateException> { loader.clear("a") }
        assertFailsWith<IllegalStateException> { loader.prime("a", "value") }
    }
}
