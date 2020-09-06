package com.steamstreet.graphkt.samples.basic

import kotlinx.serialization.*

class SampleScalar(val str: String) {

}

object SampleScalarSerializer : KSerializer<SampleScalar> {
    override val descriptor: SerialDescriptor = PrimitiveDescriptor("SampleScalar", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SampleScalar {
        return SampleScalar(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: SampleScalar) {
        encoder.encodeString(value.str)
    }
}