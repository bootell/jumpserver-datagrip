package dev.jumpserverbridge.datagrip.catalog

import dev.jumpserverbridge.datagrip.auth.MysqlConnectionRewriter
import dev.jumpserverbridge.datagrip.core.ConnectionGrant
import dev.jumpserverbridge.datagrip.core.GrantedAccount
import dev.jumpserverbridge.datagrip.core.GrantedAsset
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.DataSourceSchemaMapping
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class DataSourceDefaultsTest {
    private val selection = CatalogSelection(
        CatalogIdentity("https://gateway.example.com:8443/jump/", "fixture-key", null),
        GrantedAsset(UUID.fromString("00000000-0000-4000-8000-000000000011"), "Orders MySQL", null, "mysql"),
        GrantedAccount("reader-alias", "Display account", true, true, "reader"),
        "orders"
    )

    @Test fun newDraftAutomaticallyLoadsTheSelectedDefaultDatabaseOnly() {
        val source = LocalDataSource()
        DataSourceDefaults.from(selection, emptySet()).applyTo(source, selection)
        assertTrue(source.isAutoSynchronize, "New sources must populate the native database tree automatically")
        assertTrue(DataSourceSchemaMapping.matches(source.introspectionScope, ObjectPath.create("orders", ObjectKind.SCHEMA)))
        assertFalse(DataSourceSchemaMapping.matches(source.introspectionScope, ObjectPath.create("another_database", ObjectKind.SCHEMA)))
        assertFalse(source.isReadOnly, "Read-only must remain a native user choice")
        assertEquals(LocalDataSource.Storage.NO, source.passwordStorage)
        assertEquals("jumpserver", source.authProviderId)
    }

    @Test fun newDraftEnablesThirtySecondNativeKeepAlive() {
        val source = LocalDataSource()
        DataSourceDefaults.from(selection, emptySet()).applyTo(source, selection)
        assertTrue(source.isKeepAlive, "Idle connections should use DataGrip's native keep-alive")
        assertEquals(30, source.keepAliveTimeout, "The native keep-alive interval is measured in seconds")
    }

    @Test fun databaseNamesWithPatternCharactersDoNotSelectOtherSchemas() {
        val chosen = selection.copy(database = "orders:archive|*")
        val source = LocalDataSource()
        DataSourceDefaults.from(chosen, emptySet()).applyTo(source, chosen)
        assertTrue(DataSourceSchemaMapping.matches(source.introspectionScope, ObjectPath.create("orders:archive|*", ObjectKind.SCHEMA)))
        assertFalse(DataSourceSchemaMapping.matches(source.introspectionScope, ObjectPath.create("orders", ObjectKind.SCHEMA)))
    }

    @Test fun missingDefaultDatabaseDoesNotIntrospectEveryAccessibleSchema() {
        val chosen = selection.copy(database = null)
        val source = LocalDataSource()
        DataSourceDefaults.from(chosen, emptySet()).applyTo(source, chosen)
        assertTrue(source.isAutoSynchronize)
        assertTrue(source.introspectionScope.isEmpty)
    }

    @Test fun nativeDraftDisplaysTheConfiguredJumpServerHostAndDatabase() {
        val defaults = DataSourceDefaults.from(selection, emptySet())
        assertEquals("jdbc:mysql://gateway.example.com:33061/orders", defaults.url)
        assertEquals("Orders MySQL@reader", defaults.name)
    }

    @Test fun duplicateNamesAreDisambiguatedAndMissingUsernamesUseTheAccountAlias() {
        val defaults = DataSourceDefaults.from(selection.copy(account = selection.account.copy(username = null)),
            setOf("Orders MySQL@reader-alias", "Orders MySQL@reader-alias (2)"))
        assertEquals("Orders MySQL@reader-alias (3)", defaults.name)
    }

    @Test fun missingDatabaseLeavesTheNativeDatabaseBlank() {
        assertEquals("jdbc:mysql://gateway.example.com:33061/", DataSourceDefaults.from(selection.copy(database = null), emptySet()).url)
    }

    @Test fun ipv6HostsAndDatabaseNamesAreEncodedWithoutLosingTheirMeaning() {
        val chosen = selection.copy(identity = selection.identity.copy(server = "https://[2001:db8::1]:8443/"), database = "订单 库&x")
        assertEquals("jdbc:mysql://[2001:db8::1]:33061/%E8%AE%A2%E5%8D%95%20%E5%BA%93%26x", DataSourceDefaults.from(chosen, emptySet()).url)
    }

    @Test fun authenticationUsesTheFreshEndpointAndPreservesTheNativeDatabase() {
        val defaults = DataSourceDefaults.from(selection, emptySet())
        ConnectionGrant("proxy.example.com", 43061, "fixture-token", "fixture-password".toCharArray()).use { grant ->
            val prepared = MysqlConnectionRewriter.rewrite(defaults.url, emptyMap(), grant, null)
            assertEquals("jdbc:mysql://proxy.example.com:43061/orders", prepared.url)
            assertEquals("fixture-token", prepared.properties["user"])
        }
    }
}
