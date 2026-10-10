// The multifile facade keeps buildResponse in the JVM class Jvm_serverKt, where it was declared
// before it moved to common code, so existing JVM callers stay binary compatible.
@file:OptIn(kotlin.ExperimentalMultiplatform::class)
@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("Jvm_serverKt")

package com.steamstreet.graphkt.server

import com.steamstreet.graphkt.GraphQLError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the response envelope: `data`, and `errors` when [errors] is not empty. */
public fun buildResponse(data: JsonElement, errors: List<GraphQLError>): JsonObject {
    return buildJsonObject {
        put("data", data)
        if (errors.isNotEmpty()) {
            put("errors", buildJsonArray {
                errors.forEach {
                    add(Json.encodeToJsonElement(GraphQLError.serializer(), it))
                }
            })
        }
    }
}
