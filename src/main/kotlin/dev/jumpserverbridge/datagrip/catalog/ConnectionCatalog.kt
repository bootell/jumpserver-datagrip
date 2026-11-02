package dev.jumpserverbridge.datagrip.catalog

import dev.jumpserverbridge.datagrip.core.*
import dev.jumpserverbridge.datagrip.settings.RuntimeSettings

data class CatalogIdentity(val server: String, val keyId: String, val orgId: String?) {
    override fun toString() = "CatalogIdentity(redacted)"
    companion object {
        fun from(settings: ServerSettings) = CatalogIdentity(settings.baseUrl.toASCIIString().trimEnd('/'), settings.keyId, settings.orgId)
    }
}

data class AuthorizedAssets(val identity: CatalogIdentity, val assets: List<GrantedAsset>)
data class CatalogSelection(val identity: CatalogIdentity, val asset: GrantedAsset, val account: GrantedAccount, val database: String? = null)

class ConnectionCatalog(
    private val snapshotProvider: () -> RuntimeSettings,
    private val transportFactory: () -> HttpTransport = { JdkHttpTransport() }
) {
    fun loadAssets(search: String): AuthorizedAssets = snapshotProvider().use { runtime ->
        JumpServerClient(runtime.settings, runtime.secret, transportFactory()).use { client ->
            AuthorizedAssets(CatalogIdentity.from(runtime.settings), client.listAssets(search.trim()))
        }
    }

    fun loadDetails(asset: GrantedAsset, identity: CatalogIdentity): GrantedAssetDetails = snapshotProvider().use { runtime ->
        requireIdentity(runtime, identity)
        JumpServerClient(runtime.settings, runtime.secret, transportFactory()).use { it.assetDetails(asset.id) }
    }

    fun verifyIdentity(identity: CatalogIdentity) = snapshotProvider().use { requireIdentity(it, identity) }

    private fun requireIdentity(runtime: RuntimeSettings, expected: CatalogIdentity) {
        if (CatalogIdentity.from(runtime.settings) != expected) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
    }
}
