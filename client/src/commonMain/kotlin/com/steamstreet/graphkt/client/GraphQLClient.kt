package com.steamstreet.graphkt.client

import com.steamstreet.graphkt.GraphQLError
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class DefaultGraphQLResponse(
    val path: String,
    override val errors: List<GraphQLError>?,
    private val errorsByPath: Map<String, GraphQLError>
) : GraphQLResponse {
    override fun forElement(name: String): GraphQLResponse {
        return DefaultGraphQLResponse(
            listOfNotNull(path.ifBlank { null }, name).joinToString("."), errors, errorsByPath
        )
    }

    override fun throwIfError(name: String) {
        val elementPath = listOfNotNull(path.ifBlank { null }, name).joinToString(".")
        errorsByPath[elementPath]?.let {
            throw GraphQLClientException(it)
        }
    }
}

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
    suspend fun <T> executeAndParse(
        name: String? = null,
        json: Json,
        init: (GraphQLResponse, JsonObject) -> T,
        block: QueryWriter.() -> Unit
    ): T {
        val result = execute(name, json, block)
        val resultElement = json.parseToJsonElement(result)
        val errors = resultElement.jsonObject["errors"]?.jsonArray

        val errorList = errors?.let {
            json.decodeFromJsonElement(ListSerializer(GraphQLError.serializer()), it)
        }
        val response = DefaultGraphQLResponse("", errorList, errorList?.associateBy {
            it.path?.joinToString(".") ?: ""
        } ?: emptyMap())

        val data = resultElement.jsonObject["data"]
        return init(response, data as? JsonObject ?: JsonObject(emptyMap()))
    }
}