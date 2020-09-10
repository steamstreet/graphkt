package com.steamstreet.graphkt.samples.server

import kotlinx.serialization.*

class PhoneNumber(val number: String) {
}

object PhoneNumberSerializer : KSerializer<PhoneNumber> {
    override val descriptor: SerialDescriptor = PrimitiveDescriptor("PhoneNumber", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PhoneNumber) {
        encoder.encodeString(value.number)
    }

    override fun deserialize(decoder: Decoder): PhoneNumber {
        return PhoneNumber(decoder.decodeString())
    }
}