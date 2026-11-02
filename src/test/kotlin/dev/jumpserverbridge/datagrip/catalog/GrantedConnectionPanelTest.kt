package dev.jumpserverbridge.datagrip.catalog

import dev.jumpserverbridge.datagrip.core.*
import dev.jumpserverbridge.datagrip.settings.RuntimeSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Container
import java.net.URI
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import javax.swing.*

class GrantedConnectionPanelTest {
    private val firstId = "00000000-0000-4000-8000-000000000011"
    private val secondId = "00000000-0000-4000-8000-000000000012"
    private val work = ArrayDeque<Runnable>()
    private var server = "https://gateway.test"
    private var failAssets = false
    private var accountRequests = 0
    private val catalog = ConnectionCatalog({
        RuntimeSettings(ServerSettings(URI(server), "fixture-key", null), "fixture-secret".toCharArray())
    }) {
        HttpTransport { _, uri, _, _ ->
            if (uri.path.endsWith("/assets/")) {
                if (failAssets) HttpResponse(403, "private-response-body".toByteArray())
                else HttpResponse(200, """[
                    {"id":"$firstId","name":"Same name","type":{"value":"mysql","label":"MySQL"}},
                    {"id":"$secondId","name":"Same name","type":{"value":"mysql","label":"MySQL"}}
                ]""".toByteArray())
            } else {
                accountRequests++
                val id = if (uri.path.contains(firstId)) firstId else secondId
                val alias = if (id == firstId) "first-account" else "second-account"
                val spec = if (id == firstId) "\"spec_info\":{\"db_name\":\"orders\"}," else ""
                HttpResponse(200, """{"id":"$id",$spec "permed_protocols":[{"name":"mysql"}],
                    "permed_accounts":[{"alias":"$alias","name":"Fixture account","username":"reader","has_username":true,"has_secret":true}]}""".toByteArray())
            }
        }
    }
    private lateinit var panel: GrantedConnectionPanel
    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
    private fun all(parent: Container): List<JComponent> = parent.components.filterIsInstance<JComponent>().flatMap {
        listOf(it) + all(it)
    }
    private inline fun <reified T : JComponent> control(name: String): T {
        return all(panel).filterIsInstance<T>().first { it.accessibleContext.accessibleName == name }
    }
    private fun drainOne() { work.removeFirst().run(); edt { } }
    private fun start() {
        edt {
            panel = GrantedConnectionPanel(catalog, Executor { work.addLast(it) }) { }
            panel.refreshAssets()
            assertNull(panel.selection())
        }
        drainOne()
    }

    @Test fun changingAssetRejectsLateAccountsAndRequiresAnExplicitAccountChoice() {
        start()
        edt {
            val assets = control<JList<*>>("JumpServer assets")
            assertEquals(2, assets.model.size)
            assets.selectedIndex = 0
            assets.selectedIndex = 1
        }
        drainOne()
        edt { assertEquals(0, control<JComboBox<*>>("JumpServer accounts").itemCount); assertNull(panel.selection()) }
        drainOne()
        edt {
            val accounts = control<JComboBox<*>>("JumpServer accounts")
            assertEquals(1, accounts.itemCount)
            assertEquals(-1, accounts.selectedIndex)
            assertNull(panel.selection())
            accounts.selectedIndex = 0
            assertEquals(UUID.fromString(secondId), panel.selection()!!.asset.id)
            assertEquals("second-account", panel.selection()!!.account.alias)
            panel.dispose()
        }
    }

    @Test fun failedRefreshDropsPreviousSelectionAndDoesNotExposeTheResponseBody() {
        start()
        edt { control<JList<*>>("JumpServer assets").selectedIndex = 0 }
        drainOne()
        edt { control<JComboBox<*>>("JumpServer accounts").selectedIndex = 0; assertNotNull(panel.selection()) }
        failAssets = true
        edt { panel.refreshAssets(); assertNull(panel.selection()) }
        drainOne()
        edt {
            assertEquals(0, control<JList<*>>("JumpServer assets").model.size)
            assertEquals(0, control<JComboBox<*>>("JumpServer accounts").itemCount)
            val status = control<JLabel>("JumpServer status").text
            assertTrue(status.contains("403"))
            assertFalse(status.contains("private-response-body"))
            panel.dispose()
        }
    }

    @Test fun assetDatabaseAndUsernameReachTheDraftWithoutLeakingAcrossAssetChanges() {
        start()
        edt { control<JList<*>>("JumpServer assets").selectedIndex = 0 }
        drainOne()
        edt {
            control<JComboBox<*>>("JumpServer accounts").selectedIndex = 0
            assertEquals("orders", panel.selection()!!.database)
            assertEquals("reader", panel.selection()!!.account.username)
            control<JList<*>>("JumpServer assets").selectedIndex = 1
            assertNull(panel.selection())
        }
        drainOne()
        edt {
            control<JComboBox<*>>("JumpServer accounts").selectedIndex = 0
            assertNull(panel.selection()!!.database)
            panel.dispose()
        }
    }

    @Test fun closingPickerIgnoresInFlightResults() {
        edt {
            panel = GrantedConnectionPanel(catalog, Executor { work.addLast(it) }) { }
            panel.refreshAssets()
            panel.dispose()
        }
        drainOne()
        edt { assertEquals(0, control<JList<*>>("JumpServer assets").model.size); assertNull(panel.selection()) }
    }

    @Test fun changingServerCannotLoadAccountsForAssetsFromThePreviousServer() {
        val loaded = catalog.loadAssets("")
        server = "https://another-gateway.test"
        val error = assertThrows(ApiFailure::class.java) { catalog.loadDetails(loaded.assets.first(), loaded.identity) }
        assertEquals(FailureKind.INVALID_CONFIGURATION, error.kind)
        assertEquals(0, accountRequests)
    }

    @Test fun lateCreationCheckCannotEraseARefreshedSelection() {
        start()
        edt { control<JList<*>>("JumpServer assets").selectedIndex = 0 }
        drainOne()
        var opened = false
        edt {
            control<JComboBox<*>>("JumpServer accounts").selectedIndex = 0
            panel.prepareSelection({ }) { opened = true }
        }
        val oldCheck = work.removeFirst()
        server = "https://another-gateway.test"
        edt { panel.refreshAssets() }
        drainOne()
        edt { control<JList<*>>("JumpServer assets").selectedIndex = 1 }
        drainOne()
        edt { control<JComboBox<*>>("JumpServer accounts").selectedIndex = 0; assertNotNull(panel.selection()) }
        oldCheck.run()
        edt {
            assertFalse(opened)
            assertNotNull(panel.selection(), "A late check must preserve the refreshed asset and account")
            assertEquals(UUID.fromString(secondId), panel.selection()!!.asset.id)
            assertEquals("https://another-gateway.test", panel.selection()!!.identity.server)
            panel.dispose()
        }
    }
}
