package com.steamstreet.steamql.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class GraphQLError(
        val message: String?,
        val locations: List<ErrorLocation>?,
        val path: List<String>?,
        val extensions: JsonObject?
)

@Serializable
class ErrorLocation(
        val line: Int,
        val column: Int
)

/**
 * Exception thrown from a GraphQL request. The data is dependent on the request type, but is either
 * the query or mutation.
 */
class GraphQLClientException(val errors: List<GraphQLError>, val data: Any?) : Exception(errors.first().message)