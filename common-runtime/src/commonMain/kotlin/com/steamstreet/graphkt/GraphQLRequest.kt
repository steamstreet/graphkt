package com.steamstreet.graphkt

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A transport-neutral GraphQL request envelope. */
@Serializable
public data class GraphQLRequest(
    public val query: String,
    public val operationName: String? = null,
    public val variables: JsonObject? = null,
    public val extensions: JsonObject? = null,
)

/** Identifies the phase that produced a GraphQL response. */
public enum class GraphQLResponseKind {
    DOCUMENT_ERROR,
    REQUEST_ERROR,
    EXECUTION_RESULT,
}

/** A transport-neutral GraphQL response envelope. */
@Serializable(with = GraphQLResponseEnvelopeSerializer::class)
public data class GraphQLResponseEnvelope(
    public val data: JsonObject? = null,
    public val errors: List<GraphQLError>? = null,
    public val extensions: JsonObject? = null,
    @Transient
    public val kind: GraphQLResponseKind = if (data == null) {
        GraphQLResponseKind.REQUEST_ERROR
    } else {
        GraphQLResponseKind.EXECUTION_RESULT
    },
)

/** Preserves the difference between an absent `data` member and `data: null`. */
public object GraphQLResponseEnvelopeSerializer : KSerializer<GraphQLResponseEnvelope> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GraphQLResponseEnvelope")

    override fun serialize(encoder: Encoder, value: GraphQLResponseEnvelope) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("GraphQL response envelopes require JSON")
        val element = buildJsonObject {
            if (value.kind == GraphQLResponseKind.EXECUTION_RESULT) {
                put("data", value.data ?: JsonNull)
            }
            value.errors?.let { errors ->
                put(
                    "errors",
                    jsonEncoder.json.encodeToJsonElement(ListSerializer(GraphQLError.serializer()), errors),
                )
            }
            value.extensions?.let { extensions -> put("extensions", extensions) }
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): GraphQLResponseEnvelope {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("GraphQL response envelopes require JSON")
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val dataElement = element["data"]
        return GraphQLResponseEnvelope(
            data = dataElement?.takeUnless { it is JsonNull }?.jsonObject,
            errors = element["errors"]?.let { errors ->
                jsonDecoder.json.decodeFromJsonElement(ListSerializer(GraphQLError.serializer()), errors)
            },
            extensions = element["extensions"]?.jsonObject,
            kind = if ("data" in element) {
                GraphQLResponseKind.EXECUTION_RESULT
            } else {
                GraphQLResponseKind.REQUEST_ERROR
            },
        )
    }
}
