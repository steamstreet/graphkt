package com.steamstreet.graphkt.client.fetch

import com.steamstreet.graphkt.GraphQLError
import com.steamstreet.graphkt.client.AppendableQueryWriter
import com.steamstreet.graphkt.client.GraphQLClient
import com.steamstreet.graphkt.client.GraphQLClientException
import com.steamstreet.graphkt.client.QueryWriter
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.fetch.RequestInit

external fun decodeURIComponent(encodedURI: String): String
external fun encodeURIComponent(encodedURI: String): String

class GraphQLJsClient(val endpoint: String,
                      val headerInitializer: suspend () -> Map<String, String> = { emptyMap() }) : GraphQLClient {
    private val json = Json {}

    /**
     * Execute a query. The query can optionally be named.
     */
    override suspend fun execute(name: String?, json: Json, block: QueryWriter.() -> Unit): String {
        val writer = AppendableQueryWriter(json)
        writer.block()

        val query = writer.toString()

        val variables = writer.variables.let {
            if (it.isEmpty()) {
                null
            } else {
                buildJsonObject {
                    it.forEach {
                        put(it.key, it.value.value)
                    }
                }
            }
        }

        return if (writer.type == "mutation") {
            post(query, null, variables)
        } else {
            get(query, variables)
        }
    }

    suspend fun get(query: String, variables: JsonObject?): String {
        val headers = headerInitializer()

        @OptIn(ExperimentalStdlibApi::class)
        val queryParameters = buildList<String> {
            add("query=${encodeURIComponent(query)}")

            if (variables != null) {
                add("variables=${encodeURIComponent(json.encodeToString(JsonObject.serializer(), variables))}")
            }
        }

        val headerPairs = headers.map { it.key to it.value } + ("Accept" to "application/json")

        val result = window.fetch("${endpoint}?${queryParameters.joinToString("&")}", RequestInit(
                headers = kotlin.js.json(*(headerPairs.toTypedArray()))
        )).await()

        return result.text().await()
    }

    suspend fun post(query: String, operationName: String?, variables: JsonObject?): String {
        val headers = headerInitializer()

        val headerPairs = headers.map { it.key to it.value } + ("Accept" to "application/json") + ("Content-Type" to "application/graphql")

        val envelope = buildJsonObject {
            this.put("query", query)
            this.put("operationName", operationName)
            if (variables != null) {
                this.put("variables", variables)
            }
        }
        val gql = json.encodeToString(JsonObject.serializer(), envelope)

        val result = window.fetch(endpoint, RequestInit(
                method = "POST",
                headers = kotlin.js.json(*(headerPairs.toTypedArray())),
                body = gql
        )).await()

        if (result.status >= 500) {
            throw GraphQLClientException(listOf(GraphQLError(result.statusText, null, null, null)), null)
        }
        return result.text().await()
    }
}