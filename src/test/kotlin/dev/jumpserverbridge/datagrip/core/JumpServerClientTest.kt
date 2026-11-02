package dev.jumpserverbridge.datagrip.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.util.Base64
import java.util.UUID

internal val assetId: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
internal val tokenId = "00000000-0000-4000-8000-000000000002"
internal fun grantJson(asset: String = assetId.toString(), protocol: String = "mysql", port: Int = 33061): String {
    val source = object {}.javaClass.getResource("/fixtures/mysql-client-url.json")!!.readText()
    val data = JsonParser.parseString(source).asJsonObject
    data.addProperty("protocol", protocol)
    data.getAsJsonObject("asset").addProperty("id", asset)
    data.getAsJsonObject("endpoint").addProperty("port", port)
    return data.toString()
}
internal fun clientUrl(data: String = grantJson()): String = """{"url":"jms://${Base64.getEncoder().encodeToString(data.toByteArray())}"}"""

internal class RecordingTransport(val handler: (String, URI, Map<String,String>, ByteArray?) -> HttpResponse) : HttpTransport {
    val calls = mutableListOf<Pair<String, URI>>()
    override fun execute(method: String, uri: URI, headers: Map<String,String>, body: ByteArray?): HttpResponse {
        calls.add(method to uri)
        return handler(method, uri, headers, body)
    }
}

class JumpServerClientTest {
    private fun client(transport: HttpTransport) = JumpServerClient(ServerSettings(URI("https://jumpserver.example.com"), "fixture-key", null), "fixture-secret".toCharArray(), transport, Clock.systemUTC())

    @Test fun fetchGrantUsesOrdinaryTokenApi() {
        val transport = RecordingTransport { method, uri, headers, body ->
            assertTrue(headers.getValue("Authorization").startsWith("Signature "))
            when (method) {
                "POST" -> {
                    assertEquals("/api/v1/authentication/connection-token/", uri.path)
                    val json = JsonParser.parseString(String(body!!)).asJsonObject
                    assertEquals(assetId.toString(), json["asset"].asString)
                    assertEquals("fixture-readonly", json["account"].asString)
                    assertEquals("mysql", json["protocol"].asString)
                    assertEquals("db_client", json["connect_method"].asString)
                    HttpResponse(201, """{"id":"$tokenId","is_active":true,"from_ticket":null}""".toByteArray())
                }
                else -> {
                    assertEquals("/api/v1/authentication/connection-token/$tokenId/client-url/", uri.path)
                    HttpResponse(200, clientUrl().toByteArray())
                }
            }
        }
        val grant = client(transport).fetchGrant(ConnectionRequest(assetId, "fixture-readonly", null))
        assertEquals("mysql.example.com", grant.host)
        assertEquals(33061, grant.port)
        assertEquals(tokenId, grant.username)
        assertArrayEquals("fixture-password".toCharArray(), grant.password)
        assertEquals(2, transport.calls.size)
        assertFalse(grant.toString().contains("fixture-password"))
    }

    @Test fun pendingApprovalStopsBeforeClientUrl() {
        val transport = RecordingTransport { _, _, _, _ -> HttpResponse(201, """{"id":"$tokenId","is_active":false,"from_ticket":"fixture-ticket"}""".toByteArray()) }
        assertEquals(FailureKind.APPROVAL_REQUIRED, assertThrows(ApiFailure::class.java) { client(transport).fetchGrant(ConnectionRequest(assetId, "fixture-readonly", null)) }.kind)
        assertEquals(1, transport.calls.size)
    }

    @Test fun apiFailureNeverContainsCredentialsOrBody() {
        val transport = RecordingTransport { _, _, _, _ -> HttpResponse(403, "fixture-secret private-response".toByteArray()) }
        val error = assertThrows(ApiFailure::class.java) { client(transport).checkAccess() }
        assertEquals(FailureKind.FORBIDDEN, error.kind)
        assertFalse(error.toString().contains("fixture-secret"))
        assertFalse(error.toString().contains("private-response"))
        assertNull(error.cause)
    }

    @Test fun foreignOrCyclicNextIsRejected() {
        for (next in listOf("https://outside.example.com/api/v1/perms/users/my/assets/", "/api/v1/perms/users/my/assets/?limit=100&offset=0")) {
            val transport = RecordingTransport { _, _, _, _ -> HttpResponse(200, """{"results":[],"next":"$next"}""".toByteArray()) }
            assertThrows(ApiFailure::class.java) { client(transport).listAssets("") }
            assertTrue(transport.calls.size <= 2)
        }
    }

    @Test fun duplicateNamesKeepDistinctIds() {
        val transport = RecordingTransport { _, _, _, _ -> HttpResponse(200, """[{"id":"$assetId","name":"same","type":{"value":"mysql","label":"MySQL"},"org_id":"fixture-org"},{"id":"00000000-0000-4000-8000-000000000003","name":"same","type":"mysql"}]""".toByteArray()) }
        val assets = client(transport).listAssets("")
        assertEquals(2, assets.size)
        assertNotEquals(assets[0].id, assets[1].id)
    }

    @Test fun authorizedAccountChoicesExcludeAccountsNeedingInteractiveCredentials() {
        val transport = RecordingTransport { _, _, _, _ -> HttpResponse(200, """{
          "id":"$assetId","name":"fixture-mysql","type":{"value":"mysql","label":"MySQL"},
          "permed_protocols":[{"name":"mysql","port":3306}],
          "permed_accounts":[
            {"alias":"fixture-reader","name":"Reader","username":"reader","has_secret":true,"has_username":true,"secret_type":{"value":"password"},"actions":[]},
            {"alias":"missing-user","name":"Input user","username":"","has_secret":true,"has_username":false,"secret_type":{"value":"password"},"actions":[]},
            {"alias":"missing-secret","name":"Input password","username":"reader","has_secret":false,"has_username":true,"secret_type":{"value":"password"},"actions":[]},
            {"alias":"@INPUT","name":"Manual input","username":"","has_secret":true,"has_username":true,"secret_type":{"value":"password"},"actions":[]}
          ]
        }""".toByteArray()) }
        val accounts = client(transport).listAccounts(assetId)
        assertEquals(listOf("fixture-reader"), accounts.map { it.alias })
        assertEquals("Reader", accounts.single().name)
        assertEquals("reader", accounts.single().username)
    }

    @Test fun authorizedAssetDetailsIncludeTheConfiguredDatabaseWithoutRequestingCredentials() {
        val transport = RecordingTransport { method, uri, _, _ ->
            assertEquals("GET", method)
            assertEquals("/api/v1/perms/users/my/assets/$assetId/", uri.path)
            HttpResponse(200, """{"id":"$assetId","spec_info":{"db_name":"orders"},
                "permed_protocols":[{"name":"mysql","port":3306}],
                "permed_accounts":[{"alias":"fixture-reader","name":"Read only","username":"reader","has_username":true,"has_secret":true}]}""".toByteArray())
        }
        val details = client(transport).assetDetails(assetId)
        assertEquals("orders", details.database)
        assertEquals("reader", details.accounts.single().username)
        assertEquals(1, transport.calls.size)
    }

    @Test fun missingDatabaseOrUsernameMetadataDoesNotInventConnectionValues() {
        for (spec in listOf("", "\"spec_info\":null,", "\"spec_info\":{\"db_name\":\"\"},")) {
            val transport = RecordingTransport { _, _, _, _ -> HttpResponse(200, """{
                "id":"$assetId",$spec "permed_protocols":[{"name":"mysql"}],
                "permed_accounts":[{"alias":"fixture-reader","name":"Read only","has_username":true,"has_secret":true}]}""".toByteArray()) }
            val details = client(transport).assetDetails(assetId)
            assertNull(details.database)
            assertNull(details.accounts.single().username)
        }
    }

    @Test fun assetSearchFollowsPaginationAndKeepsOnlyMySqlAssets() {
        val otherId = "00000000-0000-4000-8000-000000000003"
        val transport = RecordingTransport { _, uri, _, _ ->
            assertEquals("/api/v1/perms/users/my/assets/", uri.path)
            assertTrue(uri.rawQuery.contains("search=qa%20mysql%26reader"))
            val body = if (uri.rawQuery.contains("offset=0")) """{
              "results":[{"id":"$assetId","name":"Same name","type":{"value":"mysql","label":"MySQL"},"org_id":"fixture-org"}],
              "next":"?limit=100&offset=100&search=qa%20mysql%26reader"
            }""" else """{
              "results":[{"id":"$otherId","name":"Same name","type":{"value":"mysql","label":"MySQL"}},
              {"id":"00000000-0000-4000-8000-000000000004","name":"Other database","type":{"value":"postgresql","label":"PostgreSQL"}}],"next":null
            }"""
            HttpResponse(200, body.toByteArray())
        }
        assertEquals(listOf(assetId.toString(), otherId), client(transport).listAssets("qa mysql&reader").map { it.id.toString() })
        assertEquals(2, transport.calls.size)
    }

    @Test fun oversizedResponseAndTimeoutFailSafely() {
        val large = RecordingTransport { _, _, _, _ -> HttpResponse(200, ByteArray(1048577)) }
        assertEquals(FailureKind.INVALID_RESPONSE, assertThrows(ApiFailure::class.java) { client(large).checkAccess() }.kind)
        val slow = RecordingTransport { _, _, _, _ -> throw java.net.http.HttpTimeoutException("private-server") }
        val error = assertThrows(ApiFailure::class.java) { client(slow).checkAccess() }
        assertEquals(FailureKind.TIMEOUT, error.kind)
        assertFalse(error.toString().contains("private-server"))
    }

    @Test fun malformedJsonShapeNeverLeaksResponse() {
        val transport = RecordingTransport { _, _, _, _ -> HttpResponse(200, "\"fixture-secret\"".toByteArray()) }
        val error = assertThrows(ApiFailure::class.java) { client(transport).listAssets("") }
        assertFalse(error.toString().contains("fixture-secret"))
        assertNull(error.cause)
    }

    @Test fun checkAccessRejectsUnexpectedHtmlResponse() {
        val transport = RecordingTransport { _, _, _, _ -> HttpResponse(200, "<html>fixture-secret</html>".toByteArray()) }
        assertEquals(FailureKind.INVALID_RESPONSE, assertThrows(ApiFailure::class.java) { client(transport).checkAccess() }.kind)
    }

    @Test fun cancellationDuringClientUrlRequestIsPropagated() {
        val transport = RecordingTransport { method, _, _, _ ->
            if (method == "POST") HttpResponse(201, """{"id":"$tokenId","is_active":true,"from_ticket":null}""".toByteArray())
            else throw java.util.concurrent.CancellationException("cancelled")
        }
        assertThrows(java.util.concurrent.CancellationException::class.java) { client(transport).fetchGrant(ConnectionRequest(assetId, "fixture-readonly", null)) }
    }
}
