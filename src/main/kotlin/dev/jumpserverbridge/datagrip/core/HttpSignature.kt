package dev.jumpserverbridge.datagrip.core

import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HttpSignature(private val clock: Clock) {
    fun headers(method: String, uri: URI, keyId: String, secret: CharArray): Map<String, String> {
        if (!keyId.matches(Regex("[A-Za-z0-9._-]{1,128}")) || secret.isEmpty()) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneOffset.UTC).format(clock.instant())
        val target = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
        val canonical = "(request-target): ${method.lowercase(Locale.ROOT)} $target\ndate: $date"
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(bytes, "HmacSHA256"))
            val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))
            return mapOf("Date" to date, "Authorization" to "Signature keyId=\"$keyId\",algorithm=\"hmac-sha256\",headers=\"(request-target) date\",signature=\"$signature\"")
        } finally {
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }
}
