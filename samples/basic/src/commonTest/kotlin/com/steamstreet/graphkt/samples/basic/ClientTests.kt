package com.steamstreet.graphkt.samples.basic

import com.steamstreet.graphkt.client.ktor.GraphQLKtorClient
import com.steamstreet.graphkt.samples.basic.client.query
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClientTests {
    @Test
    fun basics() = runTest {
        val engine = MockEngine { request ->
            val query = request.url.parameters.get("query")
            query.shouldBeEqualTo(
                """query  {
                |  aStr
                |  }""".trimMargin()
            )

            respond(
                """{"data":{"aStr":"123"}}"""
            )
        }

        val client = GraphQLKtorClient(
            "http://test.com/graphql",
            HttpClient(engine)
        )

        client.query {
            aStr
        }.aStr.shouldBeEqualTo("123")
    }
}