package dev.jumpserverbridge.datagrip.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClientUrlParserTest {
    @Test fun malformedClientUrlIsRejected() {
        val bad = listOf("{}", """{"url":"jms://!!"}""", clientUrl(grantJson(protocol = "ssh")), clientUrl(grantJson(port = 0)), clientUrl(grantJson(asset = "00000000-0000-4000-8000-000000000099")))
        for (data in bad) assertThrows(ApiFailure::class.java) { ClientUrlParser.parse(data.toByteArray(), assetId, tokenId) }
    }
}
