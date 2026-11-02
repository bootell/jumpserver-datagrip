package dev.jumpserverbridge.datagrip.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException

class JumpServerClient(
    private val settings: ServerSettings,
    secret: CharArray,
    private val transport: HttpTransport = JdkHttpTransport(),
    clock: Clock = Clock.systemUTC()
) : AutoCloseable {
    private val secret = secret.copyOf()
    private val signature = HttpSignature(clock)

    fun checkAccess() {
        listItems(parseJson(request("GET", settings.apiUri("/api/v1/perms/users/my/assets/?limit=1&offset=0"))))
    }

    fun listAssets(search: String): List<GrantedAsset> {
        val result = mutableListOf<GrantedAsset>()
        var next: URI? = settings.apiUri("/api/v1/perms/users/my/assets/?limit=100&offset=0" + if (search.isBlank()) "" else "&search=" + URLEncoder.encode(search, Charsets.UTF_8).replace("+", "%20"))
        val visited = mutableSetOf<URI>()
        while (next != null) {
            val uri = next
            if (visited.size >= 100 || !visited.add(uri) || !sameApi(uri)) throw ApiFailure(FailureKind.INVALID_RESPONSE)
            val data = parseJson(request("GET", uri))
            val items = listItems(data)
            try {
                items.forEach { element ->
                    val item = element.asJsonObject
                    val typeNode = item["type"]
                    val type = if (typeNode.isJsonObject) typeNode.asJsonObject.text("value") else typeNode.asString
                    if (type == "mysql") result.add(GrantedAsset(UUID.fromString(item.text("id")), item.text("name") ?: "", item.text("org_id"), type))
                }
                val following = if (data.isJsonObject) data.asJsonObject.text("next") else null
                next = following?.takeIf { it.isNotBlank() }?.let { uri.resolve(it) }
            } catch (e: ApiFailure) { throw e }
            catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_RESPONSE) }
        }
        return result.distinctBy { it.id }
    }

    fun listAccounts(assetId: UUID): List<GrantedAccount> = assetDetails(assetId).accounts

    fun assetDetails(assetId: UUID): GrantedAssetDetails {
        val data = parseJson(request("GET", settings.apiUri("/api/v1/perms/users/my/assets/$assetId/")))
        try {
            val detail = data.asJsonObject
            if (UUID.fromString(detail.text("id")) != assetId || detail.getAsJsonArray("permed_protocols").none { it.asJsonObject.text("name") == "mysql" }) throw ApiFailure(FailureKind.FORBIDDEN)
            val accounts = detail.getAsJsonArray("permed_accounts").map { element ->
                val item = element.asJsonObject
                GrantedAccount(item.text("alias") ?: throw ApiFailure(FailureKind.INVALID_RESPONSE), item.text("name") ?: "", item["has_secret"].asBoolean, item["has_username"].asBoolean, item.text("username")?.takeIf { it.isNotBlank() })
            }.filter { it.hasSecret && it.hasUsername && it.alias !in setOf("@INPUT", "@USER") }
            val spec = detail["spec_info"]?.takeUnless { it.isJsonNull }?.asJsonObject
            val database = spec?.text("db_name")?.takeIf { it.isNotBlank() }
            if (database?.any { it.isISOControl() } == true) throw ApiFailure(FailureKind.INVALID_RESPONSE)
            return GrantedAssetDetails(database, accounts)
        } catch (e: ApiFailure) { throw e }
        catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_RESPONSE) }
    }

    fun fetchGrant(connection: ConnectionRequest): ConnectionGrant {
        if (connection.accountAlias.isBlank() || connection.accountAlias in setOf("@INPUT", "@USER")) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
        val body = JsonObject().apply {
            addProperty("asset", connection.assetId.toString())
            addProperty("account", connection.accountAlias)
            addProperty("protocol", "mysql")
            addProperty("connect_method", "db_client")
        }.toString().toByteArray(Charsets.UTF_8)
        val token = parseJson(request("POST", settings.apiUri("/api/v1/authentication/connection-token/"), body))
        try {
            val data = token.asJsonObject
            if (data["is_active"]?.asBoolean != true || !data.text("from_ticket").isNullOrEmpty()) throw ApiFailure(FailureKind.APPROVAL_REQUIRED)
            val tokenId = UUID.fromString(data.text("id")).toString()
            return ClientUrlParser.parse(request("GET", settings.apiUri("/api/v1/authentication/connection-token/$tokenId/client-url/")), connection.assetId, tokenId)
        } catch (e: ApiFailure) { throw e }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_RESPONSE) }
    }

    private fun listItems(data: JsonElement): com.google.gson.JsonArray = when {
        data.isJsonArray -> data.asJsonArray
        data.isJsonObject -> data.asJsonObject["results"]?.takeIf { it.isJsonArray }?.asJsonArray ?: throw ApiFailure(FailureKind.INVALID_RESPONSE)
        else -> throw ApiFailure(FailureKind.INVALID_RESPONSE)
    }

    private fun sameApi(uri: URI): Boolean {
        val base = settings.apiUri("/api/v1/")
        fun port(value: URI) = if (value.port == -1) 443 else value.port
        return uri.scheme == "https" && uri.host?.equals(base.host, true) == true && port(uri) == port(base) &&
            uri.userInfo == null && uri.fragment == null && uri.path.startsWith(base.path) && uri.path.split('/').none { it == "." || it == ".." }
    }

    private fun request(method: String, uri: URI, body: ByteArray? = null): ByteArray {
        if (Thread.currentThread().isInterrupted) throw CancellationException("JumpServer request cancelled.")
        if (!sameApi(uri)) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
        val headers = signature.headers(method, uri, settings.keyId, secret).toMutableMap()
        headers["Accept"] = "application/json"
        if (body != null) headers["Content-Type"] = "application/json"
        settings.orgId?.let { headers["X-JMS-ORG"] = it }
        val response = try { transport.execute(method, uri, headers, body) }
        catch (e: ApiFailure) { throw e }
        catch (e: CancellationException) { throw e }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw CancellationException("JumpServer request cancelled.") }
        catch (_: HttpTimeoutException) { throw ApiFailure(FailureKind.TIMEOUT) }
        catch (_: Exception) { throw ApiFailure(FailureKind.NETWORK) }
        if (response.body.size > MAX_RESPONSE_BYTES) throw ApiFailure(FailureKind.INVALID_RESPONSE)
        if (response.status !in 200..299) throw ApiFailure(when (response.status) {
            401 -> FailureKind.UNAUTHENTICATED
            403 -> FailureKind.FORBIDDEN
            else -> FailureKind.INVALID_RESPONSE
        }, response.status)
        return response.body
    }

    private fun parseJson(body: ByteArray): JsonElement = try { JsonParser.parseString(body.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_RESPONSE) }

    override fun close() { secret.fill('\u0000') }
    override fun toString() = "JumpServerClient(redacted)"
}
