package com.steamstreet.steamql.client.ktor

import com.steamstreet.steamql.client.AppendableQueryWriter
import com.steamstreet.steamql.client.GraphQLClient
import com.steamstreet.steamql.client.QueryWriter
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.json

/**
 * GraphQL query client that uses Ktor to execute
 */
class GraphQLKtorClient(val endpoint: String,
                        val http: HttpClient = HttpClient(),
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
        val response = http.request<String> {
            headers.forEach { (key, value) ->
                this.header(key, value)
            }

            url(URLBuilder(endpoint).apply {
                parameters["query"] = query
                if (variables != null) {
                    parameters["variables"] = json.stringify(JsonObject.serializer(), variables)
                }
            }.build())

            method = HttpMethod.Get
            accept(ContentType.Application.Json)
        }
        return response
    }

    suspend fun post(query: String, operationName: String?, variables: JsonObject?): String {
        val headers = headerInitializer()
        val response = http.request<String> {
            url(endpoint)
            method = HttpMethod.Post

            headers.forEach { (key, value) ->
                this.header(key, value)
            }

            val envelope = json {
                "query" to query
                "operationName" to operationName
                if (variables != null) {
                    "variables" to variables
                }
            }
            val gql = json.stringify(JsonObject.serializer(), envelope)
            body = TextContent(gql, ContentType.parse("application/graphql"))
            accept(ContentType.Application.Json)
        }
        return response
    }
}