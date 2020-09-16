package com.steamstreet.graphkt.samples.basic

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class SampleScalar(val str: String) {

}

object SampleScalarSerializer : KSerializer<SampleScalar> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SampleScalar", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): SampleScalar {
        return SampleScalar(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: SampleScalar) {
        encoder.encodeString(value.str)
    }
}