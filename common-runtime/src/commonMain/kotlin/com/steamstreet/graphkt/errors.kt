package com.steamstreet.graphkt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    public val message: String? = null,
    public val locations: List<Location>? = null,
    public val path: List<GraphQLPathSegment>? = null,
    public val extensions: JsonObject? = null
)

/** One field name or list index in a GraphQL response path. */
@Serializable(with = GraphQLPathSegmentSerializer::class)
public sealed interface GraphQLPathSegment {
    public data class Field(public val name: String) : GraphQLPathSegment
    public data class Index(public val index: Int) : GraphQLPathSegment
}

/** Encodes path segments in the scalar format required by the GraphQL response specification. */
public object GraphQLPathSegmentSerializer : KSerializer<GraphQLPathSegment> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GraphQLPathSegment")

    override fun serialize(encoder: Encoder, value: GraphQLPathSegment) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("GraphQL path segments require JSON")
        jsonEncoder.encodeJsonElement(
            when (value) {
                is GraphQLPathSegment.Field -> JsonPrimitive(value.name)
                is GraphQLPathSegment.Index -> JsonPrimitive(value.index)
            },
        )
    }

    override fun deserialize(decoder: Decoder): GraphQLPathSegment {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("GraphQL path segments require JSON")
        val value = jsonDecoder.decodeJsonElement().jsonPrimitive
        return if (value.isString) {
            GraphQLPathSegment.Field(value.content)
        } else {
            value.intOrNull?.let { GraphQLPathSegment.Index(it) }
                ?: throw SerializationException("GraphQL path segment must be a string or integer")
        }
    }
}
