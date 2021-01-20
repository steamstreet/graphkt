package com.steamstreet.graphkt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
class Location(
        val line: Int,
        val column: Int
)

/**
 * Encapsulates an error response
 */
@Serializable
class GraphQLError(
    val message: String,
    val locations: List<Location>? = null,
    val path: List<String>? = null,
    val extensions: JsonObject? = null
)