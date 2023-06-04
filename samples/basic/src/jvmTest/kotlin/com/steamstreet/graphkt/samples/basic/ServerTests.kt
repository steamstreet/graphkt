package com.steamstreet.graphkt.samples.basic

import com.steamstreet.graphkt.samples.basic.server.Query
import com.steamstreet.graphkt.samples.basic.server.gqlSelect
import com.steamstreet.graphkt.server.ktor.graphQL
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import kotlin.test.Test

class ServerTests {
    @Test
    fun testBasics() {
        val server = mockk<Query>()

        every {
            server.aStr
        } returns "123"

        testApplication {
            routing {
                route("/graphql") {
                    graphQL(server::gqlSelect, null)
                }
            }

            val result = client.get("/graphql") {
                parameter("query", """query { aStr }""")
            }
            val response = result.bodyAsText()
            response.shouldBeEqualTo("""{"data":{"aStr":"123"}}""")
        }
    }
}