package dev.jumpserverbridge.datagrip.catalog

import com.intellij.database.dataSource.DatabaseConnectionConfig
import dev.jumpserverbridge.datagrip.core.GrantedAccount
import dev.jumpserverbridge.datagrip.core.GrantedAsset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class DataSourceBindingTest {
    private val asset = GrantedAsset(UUID.fromString("00000000-0000-4000-8000-000000000011"), "Fixture MySQL", null, "mysql")
    private val selection = CatalogSelection(CatalogIdentity("https://gateway.test", "fixture-key", null), asset, GrantedAccount("fixture-alias", "Display account", true, true))
    private val properties = mutableMapOf<String, String>()
    private var provider: String? = null
    private val config = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseConnectionConfig::class.java)) { _, method, args ->
        when (method.name) {
            "setAdditionalProperty" -> { properties[args[0] as String] = args[1] as String; null }
            "getAdditionalProperty" -> properties[args[0] as String]
            "setAuthProviderId" -> { provider = args[0] as String; null }
            "getAuthProviderId" -> provider
            else -> error("Unexpected configuration operation: ${method.name}")
        }
    } as DatabaseConnectionConfig

    @Test fun bindingPersistsOnlyStableAssetAndAccountIdentifiers() {
        DataSourceBinding.apply(config, selection)
        assertEquals("jumpserver", provider)
        assertEquals(mapOf("jumpserver.assetId" to "00000000-0000-4000-8000-000000000011", "jumpserver.accountAlias" to "fixture-alias", "jumpserver.assetName" to "Fixture MySQL"), properties)
        assertTrue(DataSourceBinding.matches(config, selection))
    }

    @Test fun duplicateDetectionUsesAssetIdAndAccountAliasRatherThanDisplayNames() {
        DataSourceBinding.apply(config, selection)
        assertFalse(DataSourceBinding.matches(config, selection.copy(account = selection.account.copy(alias = "other-alias"))))
        assertFalse(DataSourceBinding.matches(config, selection.copy(asset = asset.copy(id = UUID.fromString("00000000-0000-4000-8000-000000000012")))))
        assertTrue(DataSourceBinding.matches(config, selection.copy(account = selection.account.copy(name = "Renamed"), asset = asset.copy(name = "Renamed"))))
        provider = "user-pass"
        assertFalse(DataSourceBinding.matches(config, selection))
    }
}
