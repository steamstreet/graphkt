package com.steamstreet.graphkt.samples.server

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class PhoneNumber(val number: String) {
}

object PhoneNumberSerializer : KSerializer<PhoneNumber> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PhoneNumber", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PhoneNumber) {
        encoder.encodeString(value.number)
    }

    override fun deserialize(decoder: Decoder): PhoneNumber {
        return PhoneNumber(decoder.decodeString())
    }
}