package app.polybot.data.api.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/** Server mengirim `int64` sebagai string, tapi angka juga harus diterima. Dikirim balik sebagai string. */
object LenientLong : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val element = primitive(decoder)
        // Nilai sengaja tidak disertakan: pesan ini bisa tampil di layar dan di log.
        return element.content.toLongOrNull()
            ?: throw SerializationException("nilai bukan int64 (panjang ${element.content.length} karakter)")
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
}

/**
 * Id uint64: nilai di atas batas int64 (20 digit) dibungkus ke 64 bit yang sama, sehingga tetap unik dan stabil
 * sebagai kunci. Tidak dikirim balik ke server.
 */
object LenientId : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("LenientId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val text = primitive(decoder).content
        return text.toLongOrNull()
            ?: text.toBigIntegerOrNull()?.toLong()
            ?: throw SerializationException("id bukan angka (panjang ${text.length} karakter)")
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
}

/** Waktu dalam nanodetik; menerima juga string RFC 3339 (mis. `2026-11-01T10:00:00Z`). */
object LenientTimeNs : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("LenientTimeNs", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val text = primitive(decoder).content
        text.toLongOrNull()?.let { return it }
        val instant = runCatching { java.time.Instant.parse(text) }.getOrNull()
            ?: throw SerializationException("waktu tidak dikenali (panjang ${text.length} karakter)")
        return instant.epochSecond * 1_000_000_000L + instant.nano
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeString(value.toString())
}

/** Angka besar (uint64/U128) disimpan sebagai teks desimal; diparse ke BigInteger saat dipakai. */
object NumberText : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("NumberText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = primitive(decoder).content

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

private fun primitive(decoder: Decoder): JsonPrimitive =
    (decoder as JsonDecoder).decodeJsonElement() as? JsonPrimitive
        ?: throw SerializationException("Diharapkan angka atau string, bukan objek/array")

val ApiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = true
}
