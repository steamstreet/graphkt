package com.steamstreet.graphkt.client.ktor

import com.steamstreet.graphkt.client.AppendableQueryWriter
import com.steamstreet.graphkt.client.GraphQLClient
import com.steamstreet.graphkt.client.QueryWriter
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.content.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GraphQL query client that uses Ktor to execute
 */
public class GraphQLKtorClient(
    private val endpoint: String,
    public val engine: HttpClientEngine? = null,
    public val headerInitializer: suspend () -> Map<String, String> = { emptyMap() }
) : GraphQLClient {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val http by lazy {
        if (engine != null) {
            HttpClient(engine)
        } else {
            HttpClient()
        }
    }

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

    private suspend fun get(query: String, variables: JsonObject?): String {
        val headers = headerInitializer()

        val response = http.request {
            headers.forEach { (key, value) ->
                this.header(key, value)
            }

            url(URLBuilder(endpoint).apply {
                parameters["query"] = query
                if (variables != null) {
                    parameters["variables"] = json.encodeToString(JsonObject.serializer(), variables)
                }
            }.build())

            method = HttpMethod.Get
            accept(ContentType.Application.Json)
        }
        return response.body()
    }

    private suspend fun post(query: String, operationName: String?, variables: JsonObject?): String {
        val headers = headerInitializer()
        val response = http.request {
            url(endpoint)
            method = HttpMethod.Post

            headers.forEach { (key, value) ->
                this.header(key, value)
            }

            val envelope = buildJsonObject {
                put("query", query)
                put("operationName", operationName)
                if (variables != null) {
                    put("variables", variables)
                }
            }
            val gql = json.encodeToString(JsonObject.serializer(), envelope)

            setBody(TextContent(gql, ContentType.parse("application/graphql")))
            accept(ContentType.Application.Json)
        }
        return response.body()
    }
}