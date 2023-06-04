package com.steamstreet.graphkt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public class Location(
    public val line: Int,
    public val column: Int
)

/**
 * Encapsulates an error response
 */
@Serializable
public class GraphQLError(
    public val message: String,
    public val locations: List<Location>? = null,
    public val path: List<String>? = null,
    public val extensions: JsonObject? = null
)