package com.steamstreet.steamql.client.fetch


import com.steamstreet.steamql.client.*
import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.json
import org.w3c.fetch.RequestInit
import kotlin.browser.window
import kotlin.js.json

external fun decodeURIComponent(encodedURI: String): String
external fun encodeURIComponent(encodedURI: String): String

class GraphQLJsClient(val endpoint: String,
                      val headerInitializer: suspend () -> Map<String, String> = { emptyMap() }) : GraphQLClient {
    private val json = Json(JsonConfiguration.Stable)

    /**
     * Execute a query. The query can optionally be named.
     */
    override suspend fun execute(name: String?, block: QueryWriter.() -> Unit): String {
        val writer = AppendableQueryWriter()
        writer.block()

        val query = writer.toString()

        val variables = writer.variables.let {
            if (it.isEmpty()) {
                null
            } else {
                json {
                    it.forEach {
                        it.key to it.value.value
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
                add("variables=${encodeURIComponent(json.stringify(JsonObject.serializer(), variables))}")
            }
        }

        val headerPairs = headers.map { it.key to it.value } + ("Accept" to "application/json")

        val result = window.fetch("${endpoint}?${queryParameters.joinToString("&")}", RequestInit(
                headers = json(*(headerPairs.toTypedArray()))
        )).await()
        return result.text().await()
    }

    suspend fun post(query: String, operationName: String?, variables: JsonObject?): String {
        val headers = headerInitializer()

        val headerPairs = headers.map { it.key to it.value } + ("Accept" to "application/json") + ("Content-Type" to "application/graphql")

        val envelope = json {
            "query" to query
            "operationName" to operationName
            if (variables != null) {
                "variables" to variables
            }
        }
        val gql = json.stringify(JsonObject.serializer(), envelope)

        val result = window.fetch(endpoint, RequestInit(
                method = "POST",
                headers = json(*(headerPairs.toTypedArray())),
                body = gql
        )).await()

        if (result.status >= 500) {
            throw GraphQLClientException(listOf(GraphQLError(result.statusText, null, null, null)), null)
        }
        return result.text().await()
    }
}