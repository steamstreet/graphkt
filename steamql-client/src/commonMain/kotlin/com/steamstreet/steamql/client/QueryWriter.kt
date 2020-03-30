package com.steamstreet.steamql.client

import kotlinx.serialization.KSerializer

interface QueryWriter {
    var type: String
    fun named(name: String): QueryWriter

    /**
     * Add a variable. Returns the actual variable name, which might be named differently
     */
    fun <T : Any> variable(name: String, type: String, value: T?): String

    /**
     * Add a variable for a complex type that will need to be serialized.
     */
    fun <T : Any> variable(name: String, type: String, serializer: KSerializer<T>, value: T): String

    fun print(input: String)
    fun println(input: String)
    fun println()

    fun indent(block: (QueryWriter) -> Unit)

    fun writeTo(target: Appendable)
}

