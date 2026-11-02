package dev.jumpserverbridge.datagrip.auth

import dev.jumpserverbridge.datagrip.core.ApiFailure
import dev.jumpserverbridge.datagrip.core.ConnectionGrant
import dev.jumpserverbridge.datagrip.core.FailureKind
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class PreparedConnection(val url: String, val properties: Map<String, String>) {
    override fun toString() = "PreparedConnection(redacted)"
}

object MysqlConnectionRewriter {
    fun rewrite(url: String, properties: Map<String, String>, grant: ConnectionGrant, database: String?): PreparedConnection {
        try {
            if (!url.startsWith("jdbc:mysql://")) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            val original = URI(url.removePrefix("jdbc:"))
            if (original.host == null || original.userInfo != null || original.fragment != null) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            if (database?.any { it.isISOControl() } == true) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            val path = database?.takeIf { it.isNotBlank() }?.let { "/" + URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
                ?: original.rawPath.ifEmpty { "/" }
            val query = original.rawQuery?.split('&')?.filterNot {
                URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8).lowercase() in setOf("user", "password")
            }?.joinToString("&")?.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
            val host = if (grant.host.contains(':')) "[${grant.host}]" else grant.host
            val resultUrl = "jdbc:mysql://$host:${grant.port}$path$query"
            val resultProperties = properties.filterKeys { !it.startsWith("jumpserver.") && it.lowercase() !in setOf("user", "password") }.toMutableMap()
            resultProperties["user"] = grant.username
            resultProperties["password"] = String(grant.password)
            return PreparedConnection(resultUrl, resultProperties)
        } catch (e: ApiFailure) { throw e }
        catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_CONFIGURATION) }
    }
}
