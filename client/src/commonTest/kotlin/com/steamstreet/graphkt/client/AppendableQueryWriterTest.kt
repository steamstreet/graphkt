package com.steamstreet.graphkt.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test

@Serializable
class SomeSerializable(val name: String, val id: Int)

class AppendableQueryWriterTest {
    @Test
    fun testSerialization() {
        val writer = AppendableQueryWriter()

        writer.variable("MyVariable", "MyVariableType", SomeSerializable.serializer(), SomeSerializable("Jon", 12))

        val data = buildJsonObject {
            writer.variables.forEach { entry ->
                put(entry.key, entry.value.value)
            }
        }
        println(Json.encodeToString(data))
    }
}