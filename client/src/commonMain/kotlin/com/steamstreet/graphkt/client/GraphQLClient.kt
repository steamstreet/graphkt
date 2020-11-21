package com.steamstreet.graphkt.client

import com.steamstreet.graphkt.GraphQLError
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Interface for a GraphQL client.
 */
interface GraphQLClient {
    /**
     * Execute a query. Variables will be automatically included in the query based on values passed
     * when building the query. If no name is provided, a random name will be generated.
     */
    suspend fun execute(name: String? = null, json: Json, block: QueryWriter.() -> Unit): String

    /**
     * Execute a query and parse the response envelope.
     */
    suspend fun <T> executeAndParse(name: String? = null, json: Json, init: (JsonObject) -> T, block: QueryWriter.() -> Unit): T {
        val result = execute(name, json, block)
        val resultElement = json.parseToJsonElement(result)
        val data = resultElement.jsonObject["data"]?.jsonObject
        val errors = resultElement.jsonObject["errors"]?.jsonArray

        if (errors != null) {
            throw GraphQLClientException(json.decodeFromJsonElement(ListSerializer(GraphQLError.serializer()), errors),
                    data?.let(init))
        }

        return init(data!!)
    }
}