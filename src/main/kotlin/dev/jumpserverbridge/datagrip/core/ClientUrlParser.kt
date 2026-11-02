package dev.jumpserverbridge.datagrip.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.Base64
import java.util.UUID

internal fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.let {
    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw ApiFailure(FailureKind.INVALID_RESPONSE)
    it.asString
}

object ClientUrlParser {
    fun parse(response: ByteArray, expectedAsset: UUID, expectedToken: String): ConnectionGrant {
        try {
            if (response.size > MAX_RESPONSE_BYTES) throw ApiFailure(FailureKind.INVALID_RESPONSE)
            val url = JsonParser.parseString(response.toString(Charsets.UTF_8)).asJsonObject.text("url") ?: error("missing")
            if (!url.startsWith("jms://")) error("invalid")
            val decoded = Base64.getDecoder().decode(url.substring(6))
            val data = JsonParser.parseString(decoded.toString(Charsets.UTF_8)).asJsonObject
            if (data["version"].asInt != 2 || data.text("protocol") != "mysql") error("invalid")
            if (UUID.fromString(data.getAsJsonObject("asset").text("id")) != expectedAsset) error("invalid")
            val token = data.getAsJsonObject("token")
            val id = token.text("id") ?: error("missing")
            if (id != expectedToken || UUID.fromString(id).toString() != id) error("invalid")
            val password = token.text("value") ?: error("missing")
            if (password.isEmpty() || password.length > 4096 || password.any { it.isISOControl() }) error("invalid")
            val endpoint = data.getAsJsonObject("endpoint")
            val host = endpoint.text("host")?.removePrefix("[")?.removeSuffix("]") ?: error("missing")
            if (host.length !in 1..253 || !host.matches(Regex("[A-Za-z0-9._:-]+")) || URI("http", null, host, 1, null, null, null).host == null) error("invalid")
            val portText = endpoint["port"].asString
            if (!portText.matches(Regex("[0-9]{1,5}"))) error("invalid")
            val port = portText.toInt()
            if (port !in 1..65535) error("invalid")
            return ConnectionGrant(host, port, id, password.toCharArray())
        } catch (e: ApiFailure) { throw e }
        catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_RESPONSE) }
    }
}
