package dev.jumpserverbridge.datagrip.core

import java.net.URI
import java.util.UUID

class ServerSettings(val baseUrl: URI, val keyId: String, val orgId: String?) {
    init {
        if (baseUrl.scheme != "https" || baseUrl.host.isNullOrBlank() || baseUrl.userInfo != null ||
            baseUrl.query != null || baseUrl.fragment != null || baseUrl.port !in -1..65535 || baseUrl.port == 0 ||
            !keyId.matches(Regex("[A-Za-z0-9._-]{1,128}")) ||
            (orgId != null && (!orgId.matches(Regex("[A-Za-z0-9_-]{1,128}"))))) {
            throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
        }
    }

    fun apiUri(path: String): URI = URI(baseUrl.toASCIIString().trimEnd('/') + "/" + path.trimStart('/'))
    override fun toString() = "ServerSettings(redacted)"
}

data class ConnectionRequest(val assetId: UUID, val accountAlias: String, val database: String?)

class ConnectionGrant(val host: String, val port: Int, val username: String, val password: CharArray) : AutoCloseable {
    override fun close() { password.fill('\u0000') }
    override fun toString() = "ConnectionGrant(redacted)"
}

data class GrantedAsset(val id: UUID, val name: String, val orgId: String?, val type: String)
data class GrantedAccount(val alias: String, val name: String, val hasSecret: Boolean, val hasUsername: Boolean, val username: String? = null)
data class GrantedAssetDetails(val database: String?, val accounts: List<GrantedAccount>)
