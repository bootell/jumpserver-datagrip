package dev.jumpserverbridge.datagrip.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class HttpSignatureTest {
    @Test
    fun signatureCoversEncodedRequestTargetAndDate() {
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val headers = HttpSignature(clock).headers("GET", URI("https://jumpserver.example.com/api/v1/perms/users/my/assets/?search=a%20b&limit=20"), "fixture-key", "fixture-secret".toCharArray())
        assertEquals("Thu, 1 Jan 2026 00:00:00 GMT", headers["Date"])
        val canonical = "(request-target): get /api/v1/perms/users/my/assets/?search=a%20b&limit=20\ndate: Thu, 1 Jan 2026 00:00:00 GMT"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("fixture-secret".toByteArray(), "HmacSHA256"))
        val expected = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray()))
        assertEquals("Signature keyId=\"fixture-key\",algorithm=\"hmac-sha256\",headers=\"(request-target) date\",signature=\"$expected\"", headers["Authorization"])
    }
}
