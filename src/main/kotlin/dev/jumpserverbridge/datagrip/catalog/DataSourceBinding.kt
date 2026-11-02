package dev.jumpserverbridge.datagrip.catalog

import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.DatabaseConnectionPoint
import dev.jumpserverbridge.datagrip.auth.AssetCompletion
import dev.jumpserverbridge.datagrip.auth.JumpServerAuthProvider

object DataSourceBinding {
    fun apply(config: DatabaseConnectionConfig, selection: CatalogSelection) {
        config.authProviderId = JumpServerAuthProvider.ID
        config.setAdditionalProperty(JumpServerAuthProvider.ASSET_KEY, selection.asset.id.toString())
        config.setAdditionalProperty(JumpServerAuthProvider.ACCOUNT_KEY, selection.account.alias)
        config.setAdditionalProperty(JumpServerAuthProvider.ASSET_NAME_KEY, selection.asset.name)
    }

    fun matches(point: DatabaseConnectionPoint, selection: CatalogSelection): Boolean {
        if (point.authProviderId != JumpServerAuthProvider.ID || point.getAdditionalProperty(JumpServerAuthProvider.ACCOUNT_KEY) != selection.account.alias) return false
        return try { AssetCompletion.assetId(point.getAdditionalProperty(JumpServerAuthProvider.ASSET_KEY).orEmpty()) == selection.asset.id }
        catch (_: Exception) { false }
    }
}
