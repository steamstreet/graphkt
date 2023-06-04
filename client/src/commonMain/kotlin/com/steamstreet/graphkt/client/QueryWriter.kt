package com.steamstreet.graphkt.client

import kotlinx.serialization.KSerializer

public interface QueryWriter {
    public var type: String
    public fun named(name: String): QueryWriter

    /**
     * Add a variable. Returns the actual variable name, which might be named differently
     */
    public fun <T : Any> variable(name: String, type: String, value: T?): String

    /**
     * Add a variable for a complex type that will need to be serialized.
     */
    public fun <T : Any> variable(name: String, type: String, serializer: KSerializer<T>, value: T): String

    public fun print(input: String)
    public fun println(input: String)
    public fun println()

    public fun indent(block: (QueryWriter) -> Unit)

    public fun writeTo(target: Appendable)
}

