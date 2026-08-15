package com.steamstreet.graphkt.samples.basic

import com.steamstreet.graphkt.client.ktor.GraphQLKtorClient
import com.steamstreet.graphkt.samples.basic.client.query
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientTests {
    @Test
    fun basics() = runTest {
        val engine = MockEngine { request ->
            val query = request.url.parameters["query"]!!
            assertTrue(query.contains("aStr"), "query was: $query")
            assertTrue(query.contains("ListOfAnother"), "query was: $query")
            respond(
                """{"data":{"aStr":"123","aEnum":"ANOTHER_VALUE","aScalar":"scalar-value","ListOfAnother":[{"name":"n1","anotherString":"x"}]}}"""
            )
        }

        val client = GraphQLKtorClient("http://test.com/graphql", engine)

        val result = client.query {
            aStr
            aEnum
            aScalar
            ListOfAnother {
                name
                anotherString
            }
        }
        assertEquals("123", result.aStr)
        assertEquals(SomeEnum.ANOTHER_VALUE, result.aEnum)
        assertEquals("scalar-value", result.aScalar?.str)
        assertEquals(listOf("n1"), result.ListOfAnother?.map { it.name })
    }
}
