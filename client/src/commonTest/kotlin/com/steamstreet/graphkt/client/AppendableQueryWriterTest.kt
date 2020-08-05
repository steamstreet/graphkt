package com.steamstreet.graphkt.client

import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.json
import kotlinx.serialization.stringify
import kotlin.test.Test

@Serializable
class SomeSerializable(val name: String, val id: Int)

class AppendableQueryWriterTest {

    @ImplicitReflectionSerializer
    @Test
    fun testSerialization() {
        val writer = AppendableQueryWriter()

        writer.variable("MyVariable", "MyVariableType", SomeSerializable.serializer(), SomeSerializable("Jon", 12))

        val data = json {
            writer.variables.forEach { entry ->
                entry.key.to(entry.value.value)
            }
        }
        println(Json(JsonConfiguration.Stable).stringify(data))
    }
}