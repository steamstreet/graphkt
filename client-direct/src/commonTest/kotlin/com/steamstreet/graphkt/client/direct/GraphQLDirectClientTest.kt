package com.steamstreet.graphkt.client.direct

import com.steamstreet.graphkt.client.QueryWriter
import com.steamstreet.graphkt.server.GraphQLRootFieldResolver
import com.steamstreet.graphkt.server.GraphQLRootResolverFactory
import com.steamstreet.graphkt.server.GraphQLServer
import com.steamstreet.graphkt.server.GraphQLSubscriptionEventResolver
import com.steamstreet.graphkt.server.GraphQLSubscriptionRootFieldResolver
import com.steamstreet.graphkt.server.GraphQLSubscriptionRootResolverFactory
import com.steamstreet.graphkt.server.execution.GraphQLFieldDefinition
import com.steamstreet.graphkt.server.execution.GraphQLObjectType
import com.steamstreet.graphkt.server.execution.GraphQLScalarType
import com.steamstreet.graphkt.server.execution.GraphQLSchemaDefinition
import com.steamstreet.graphkt.server.execution.GraphQLTypeRef
import com.steamstreet.graphkt.server.resolveFieldValue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphQLDirectClientTest {
    @Test
    fun executesQueriesWithoutAPlatformTransport() = runTest {
        val client = GraphQLDirectClient(directServer(), Unit)

        val response = client.execute("ReadValue", Json) {
            type = "query"
            valueField()
        }

        assertEquals(
            "query-value",
            Json.parseToJsonElement(response).jsonObject
                .getValue("data").jsonObject
                .getValue("value").jsonPrimitive.content,
        )
    }

    @Test
    fun streamsSubscriptionsWithoutAPlatformTransport() = runTest {
        val client = GraphQLDirectClient(directServer(), Unit)

        val responses = client.subscribe("WatchValue", Json) {
            type = "subscription"
            valueField()
        }.toList()

        assertEquals(listOf("first", "second"), responses.map { response ->
            Json.parseToJsonElement(response).jsonObject
                .getValue("data").jsonObject
                .getValue("value").jsonPrimitive.content
        })
    }

    private fun QueryWriter.valueField() {
        println("value")
    }
}

private fun directServer(): GraphQLServer<Unit> = GraphQLServer(
    schema = GraphQLSchemaDefinition(
        queryType = "Query",
        subscriptionType = "Subscription",
        types = listOf(
            GraphQLScalarType("String"),
            GraphQLObjectType(
                name = "Query",
                fields = listOf(GraphQLFieldDefinition("value", GraphQLTypeRef.Named("String"))),
            ),
            GraphQLObjectType(
                name = "Subscription",
                fields = listOf(GraphQLFieldDefinition("value", GraphQLTypeRef.Named("String"))),
            ),
        ),
    ),
    query = GraphQLRootResolverFactory {
        GraphQLRootFieldResolver { selection ->
            selection.resolveFieldValue(nonNull = false) { JsonPrimitive("query-value") }
        }
    },
    subscription = GraphQLSubscriptionRootResolverFactory {
        GraphQLSubscriptionRootFieldResolver { selection ->
            flowOf("first", "second").map { value ->
                GraphQLSubscriptionEventResolver {
                    selection.resolveFieldValue(nonNull = false) { JsonPrimitive(value) }
                }
            }
        }
    },
)
