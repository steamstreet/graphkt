package com.steamstreet.graphkt.samples.native

import com.steamstreet.graphkt.client.direct.GraphQLDirectClient
import com.steamstreet.graphkt.samples.native.client.query
import com.steamstreet.graphkt.samples.native.client.subscription
import com.steamstreet.graphkt.samples.native.server.Events
import com.steamstreet.graphkt.samples.native.server.Query
import com.steamstreet.graphkt.samples.native.server.graphKtServer
import com.steamstreet.graphkt.server.ResolverFactory
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeDirectTest {
    private val client = GraphQLDirectClient(
        server = graphKtServer(
            query = ResolverFactory { QueryResolver },
            subscription = ResolverFactory { EventsResolver },
        ),
        context = Unit,
    )

    @Test
    fun executesGeneratedQuery() = runTest {
        val response = client.query(name = "NativeGreeting") {
            greeting(name = "Native")
        }

        assertEquals("Hello, Native", response.greeting)
    }

    @Test
    fun collectsGeneratedSubscription() = runTest {
        val values = client.subscription(name = "NativeTicks") {
            ticker
        }.take(3).map { response -> response.ticker }.toList()

        assertEquals(listOf(1, 2, 3), values)
    }
}

private data object QueryResolver : Query {
    override suspend fun greeting(name: String): String = "Hello, $name"
}

private data object EventsResolver : Events {
    override suspend fun ticker() = flowOf(1, 2, 3)
}
