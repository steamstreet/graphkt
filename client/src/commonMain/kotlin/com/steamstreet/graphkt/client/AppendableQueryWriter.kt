package com.steamstreet.graphkt.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Query writer that uses the Appendable interface.
 */
class AppendableQueryWriter(
        private val json: Json,
        private var indent: Int = 0,
) : QueryWriter {
    private val appender = StringBuilder()

    private var name: String? = null
    override var type: String = "query"

    val variables = HashMap<String, TypeAndValue>()

    data class TypeAndValue(val type: String,
                            val value: JsonElement)

    override fun named(name: String): QueryWriter {
        this.name = name
        return this
    }

    override fun <T : Any> variable(name: String, type: String, value: T?): String {
        var realKey = name
        var index = 1
        while (variables.containsKey(realKey)) {
            index++
            realKey = "$name$index"
        }

        val el = when (value) {
            null -> JsonNull
            is Number -> JsonPrimitive(value as? Number)
            is String -> JsonPrimitive(value as? String)
            is Boolean -> JsonPrimitive(value as? Boolean)
            else -> throw IllegalArgumentException()
        }

        variables[realKey] = TypeAndValue(type, el)

        return realKey
    }

    override fun <T : Any> variable(name: String, type: String, serializer: KSerializer<T>, value: T): String {
        var realKey = name
        var index = 1
        while (variables.containsKey(realKey)) {
            index++
            realKey = "$name$index"
        }

        val jsonValue = json.encodeToJsonElement(serializer, value)
        variables[realKey] = TypeAndValue(type, jsonValue)
        return realKey
    }

    private var isIndented = false
    override fun print(input: String) {
        indent()
        appender.append(input)
    }

    override fun println(input: String) {
        indent()
        appender.append(input)
        endOfLine()
    }

    private fun endOfLine() {
        appender.append('\n')
        isIndented = false
    }

    override fun println() {
        indent()
        endOfLine()
    }

    override fun indent(block: (QueryWriter) -> Unit) {
        indent += 2
        block(this)
        indent -= 2
    }

    private fun indent() {
        if (!isIndented) {
            repeat(indent) {
                appender.append(' ')
            }
            isIndented = true
        }
    }

    override fun toString(): String {
        val builder = StringBuilder()
        writeTo(builder)
        return builder.toString()
    }

    override fun writeTo(target: Appendable) {
        target.append(type)
        target.append(' ')
        if (variables.isNotEmpty()) {
            target.append(name ?: "Q${Random.nextInt(0, Int.MAX_VALUE)}")

            target.append('(')
            target.append(variables.entries.joinToString(",") {
                "\$${it.key}: ${it.value.type}"
            })
            target.append(')')
        }
        target.append(' ')
        target.append('{')
        target.append('\n')
        target.append(appender.toString().prependIndent("  "))
        target.append('}')
    }
}