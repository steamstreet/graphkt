package com.steamstreet.graphkt

/**
 * Interface to be implemented by scalar serializers.
 */
interface GraphKTScalar<T : Any> {
    /**
     * Parse a JsonObject into the type
     */
    fun parse(str: String): T

    /**
     * Serialize a value into a JsonObject for writing.
     */
    fun serialize(value: T): String
}