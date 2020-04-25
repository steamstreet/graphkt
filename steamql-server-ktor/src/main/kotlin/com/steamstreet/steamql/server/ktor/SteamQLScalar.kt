package com.steamstreet.steamql.server.ktor

import graphql.language.StringValue
import graphql.schema.Coercing
import graphql.schema.CoercingParseLiteralException
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialDescriptor
import kotlinx.serialization.builtins.AbstractDecoder
import kotlinx.serialization.builtins.AbstractEncoder

/**
 * Implementation of the coercing scalar that calls our native serializer.
 */
class SteamQLCoercingScalar<I : Any>(val serializer: KSerializer<I>) : Coercing<I, String> {
    override fun parseValue(input: Any?): I? {
        return if (input == null) {
            return null
        } else
            if (input is String) {
                serializer.deserialize(StringDecoder(input))
            } else {
                throw CoercingParseValueException("Unknown type)")
            }
    }

    override fun parseLiteral(input: Any?): I? {
        if (!(input is StringValue)) {
            throw CoercingParseLiteralException("String value is required")
        }
        return serializer.deserialize(StringDecoder(input.value))
    }

    override fun serialize(dataFetcherResult: Any): String {
        @Suppress("UNCHECKED_CAST")
        val value = (dataFetcherResult as? I) ?: throw CoercingSerializeException("Type is unexpected")
        val encoder = StringEncoder()

        serializer.serialize(encoder, value)
        return encoder.encoded!!
    }

    class StringDecoder(val str: String) : AbstractDecoder() {
        override fun decodeString(): String {
            return str
        }

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            TODO("not implemented")
        }
    }

    class StringEncoder() : AbstractEncoder() {
        var encoded: String? = null
        override fun encodeString(value: String) {
            encoded = value
        }

        override fun endStructure(descriptor: SerialDescriptor) {
            TODO("not implemented")
        }
    }
}