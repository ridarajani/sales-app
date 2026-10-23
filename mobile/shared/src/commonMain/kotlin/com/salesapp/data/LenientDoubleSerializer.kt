package com.salesapp.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Reads a number sent as 17.5, "17.50" or null. Anything else that is not a number becomes null.
@OptIn(ExperimentalSerializationApi::class)
object LenientDoubleSerializer : KSerializer<Double?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientDouble", PrimitiveKind.DOUBLE).nullable

    override fun deserialize(decoder: Decoder): Double? {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonNull || element !is JsonPrimitive) return null
            return element.contentOrNull?.trim()?.toDoubleOrNull()
        }
        return if (decoder.decodeNotNullMark()) decoder.decodeDouble() else decoder.decodeNull()
    }

    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}
