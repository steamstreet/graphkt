package com.steamstreet.steamql

import kotlinx.serialization.json.JsonElement

interface RequestSelection {
    val name: String
    val children: List<RequestSelection>
    val parameters: Map<String, String>

    fun inputParameter(key: String): JsonElement

    /**
     * The variables that were passed to the request.
     */
    fun variable(key: String): JsonElement
}