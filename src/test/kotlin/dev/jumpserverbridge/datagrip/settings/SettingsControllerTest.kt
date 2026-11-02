package dev.jumpserverbridge.datagrip.settings

import com.google.gson.Gson
import dev.jumpserverbridge.datagrip.core.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

class SettingsControllerTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val initial = SettingsValues("https://gateway.test", "fixture-key", "")
    private val repository = MemorySettings(initial)
    private val secrets = MemorySecrets("fixture-secret".toCharArray())
    private var requests = 0
    private val controller = SettingsController(repository, secrets, executor) {
        HttpTransport { _, _, _, _ ->
            assertFalse(SwingUtilities.isEventDispatchThread())
            requests++
            HttpResponse(200, "[]".toByteArray())
        }
    }

    @AfterEach fun close() { executor.shutdownNow() }
    private fun draft(values: SettingsValues = initial, edit: SecretEdit = SecretEdit.Keep) = SettingsDraft(values, edit)

    @Test fun emptyAddressCannotConnect() {
        for (address in listOf("", "https://jumpserver.example.com")) {
            assertThrows(Exception::class.java) { controller.verify(draft(SettingsValues(address, "fixture-key", ""))).join() }
        }
        assertEquals(0, requests)
    }

    @Test fun blankSecretKeepsExistingForSameIdentity() {
        controller.submit(draft()).join()
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
        assertEquals(initial, repository.read())
    }

    @Test fun changedIdentityRequiresExplicitSecret() {
        for (values in listOf(SettingsValues("https://other.test", "fixture-key", ""), SettingsValues(initial.baseUrl, "another-key", ""))) {
            assertThrows(Exception::class.java) { controller.submit(draft(values)).join() }
            assertThrows(Exception::class.java) { controller.verify(draft(values)).join() }
        }
        assertEquals(initial, repository.read())
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
        assertEquals(0, requests)
    }

    @Test fun cancelDoesNotPersist() {
        val edits = draft(SettingsValues("https://other.test", "new-key", ""), SecretEdit.Replace("replacement".toCharArray()))
        edits.close()
        assertEquals(initial, repository.read())
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
    }

    @Test fun removeRequiresApply() {
        val pending = draft(edit = SecretEdit.Remove)
        assertNotNull(secrets.load())
        controller.submit(pending).join()
        assertNull(secrets.load())
        assertThrows(ApiFailure::class.java) { controller.snapshot() }
    }

    @Test fun failedSecretSaveDoesNotCommitSettings() {
        secrets.failWrites = true
        assertThrows(Exception::class.java) { controller.submit(draft(SettingsValues("https://other.test", "new-key", ""), SecretEdit.Replace("replacement".toCharArray()))).join() }
        assertEquals(initial, repository.read())
    }

    @Test fun networkAndPasswordSafeRunOffEdt() {
        var operation: CompletableFuture<Unit>? = null
        SwingUtilities.invokeAndWait {
            operation = controller.verify(draft())
        }
        operation!!.join()
        assertEquals(1, requests)
        assertTrue(secrets.accesses > 0)
    }

    @Test fun persistedStateContainsNoSecret() {
        val realRepository = JumpServerSettings()
        val realController = SettingsController(realRepository, secrets, executor)
        realController.submit(draft(edit = SecretEdit.Replace("not-for-xml".toCharArray()))).join()
        val json = Gson().toJson(realRepository.getState())
        assertFalse(json.contains("not-for-xml"))
        val restored = JumpServerSettings()
        restored.loadState(Gson().fromJson(json, JumpServerSettings.State::class.java))
        assertEquals(initial, restored.read())
    }

    @Test fun configurationSnapshotOwnsItsSecret() {
        val snapshot = controller.snapshot()
        snapshot.close()
        assertTrue(snapshot.secret.all { it == '\u0000' })
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
    }
}

private class MemorySettings(private var current: SettingsValues) : SettingsRepository {
    override fun read() = current
    override fun write(values: SettingsValues) { current = values }
}

private class MemorySecrets(private var current: CharArray?) : SecretStore {
    var failWrites = false
    var accesses = 0
    override fun load(): CharArray? {
        assertFalse(SwingUtilities.isEventDispatchThread())
        accesses++
        return current?.copyOf()
    }
    override fun replace(secret: CharArray) {
        assertFalse(SwingUtilities.isEventDispatchThread())
        if (failWrites) throw IllegalStateException("fixture storage error")
        current?.fill('\u0000')
        current = secret.copyOf()
    }
    override fun remove() { current?.fill('\u0000'); current = null }
}
