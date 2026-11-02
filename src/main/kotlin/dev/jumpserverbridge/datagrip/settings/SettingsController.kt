package dev.jumpserverbridge.datagrip.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import dev.jumpserverbridge.datagrip.core.*
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import javax.swing.SwingUtilities
import kotlin.concurrent.withLock

sealed interface SecretEdit {
    data object Keep : SecretEdit
    data object Remove : SecretEdit
    class Replace(value: CharArray) : SecretEdit, AutoCloseable {
        val value = value.copyOf()
        override fun close() { value.fill('\u0000') }
        override fun toString() = "Replace(redacted)"
    }
}

class SettingsDraft(val values: SettingsValues, val secretEdit: SecretEdit) : AutoCloseable {
    fun duplicate() = SettingsDraft(values, when (val edit = secretEdit) {
        is SecretEdit.Replace -> SecretEdit.Replace(edit.value)
        else -> edit
    })
    override fun close() { (secretEdit as? SecretEdit.Replace)?.close() }
    override fun toString() = "SettingsDraft(redacted)"
}

class RuntimeSettings(val settings: ServerSettings, val secret: CharArray) : AutoCloseable {
    override fun close() { secret.fill('\u0000') }
    override fun toString() = "RuntimeSettings(redacted)"
}

class SettingsController(
    private val repository: SettingsRepository,
    private val secrets: SecretStore,
    private val executor: Executor,
    private val transportFactory: () -> HttpTransport = { JdkHttpTransport() }
) {
    private val lock = ReentrantLock()

    fun validateDraft(draft: SettingsDraft): ServerSettings {
        val settings = validateValues(draft.values)
        val current = repository.read()
        when (val edit = draft.secretEdit) {
            SecretEdit.Keep -> if (!sameIdentity(current, draft.values)) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            is SecretEdit.Replace -> if (edit.value.isEmpty()) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            SecretEdit.Remove -> Unit
        }
        return settings
    }

    fun submit(draft: SettingsDraft): CompletableFuture<Unit> {
        val owned = draft.duplicate()
        return CompletableFuture.supplyAsync({
            owned.use {
                requireBackground()
                lock.withLock {
                    validateDraft(owned)
                    try {
                        when (val edit = owned.secretEdit) {
                            is SecretEdit.Replace -> secrets.replace(edit.value)
                            SecretEdit.Remove -> secrets.remove()
                            SecretEdit.Keep -> Unit
                        }
                        repository.write(normalized(owned.values))
                    } catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_CONFIGURATION) }
                }
            }
        }, executor)
    }

    fun snapshot(): RuntimeSettings {
        requireBackground()
        return lock.withLock {
            val config = validateValues(repository.read())
            val secret = try { secrets.load() } catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_CONFIGURATION) }
            if (secret == null || secret.isEmpty()) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            RuntimeSettings(config, secret)
        }
    }

    fun verify(draft: SettingsDraft): CompletableFuture<Unit> {
        val owned = draft.duplicate()
        return CompletableFuture.supplyAsync({
            owned.use {
                requireBackground()
                val runtime = lock.withLock {
                    val settings = validateDraft(owned)
                    val secret = when (val edit = owned.secretEdit) {
                        is SecretEdit.Replace -> edit.value.copyOf()
                        SecretEdit.Keep -> try { secrets.load() } catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_CONFIGURATION) }
                        SecretEdit.Remove -> null
                    } ?: throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
                    RuntimeSettings(settings, secret)
                }
                runtime.use { current -> JumpServerClient(current.settings, current.secret, transportFactory()).use { it.checkAccess() } }
            }
        }, executor)
    }

    private fun normalized(values: SettingsValues) = SettingsValues(values.baseUrl.trim().trimEnd('/'), values.keyId.trim(), values.orgId.trim())
    private fun sameIdentity(a: SettingsValues, b: SettingsValues): Boolean {
        val first = normalized(a)
        val second = normalized(b)
        return first.baseUrl == second.baseUrl && first.keyId == second.keyId
    }
    private fun validateValues(raw: SettingsValues): ServerSettings {
        try {
            val values = normalized(raw)
            val uri = URI(values.baseUrl)
            if (uri.host == "jumpserver.example.com") throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            return ServerSettings(uri, values.keyId, values.orgId.ifBlank { null })
        } catch (e: ApiFailure) { throw e }
        catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_CONFIGURATION) }
    }
    private fun requireBackground() { check(!SwingUtilities.isEventDispatchThread()) { "JumpServer credential operations require a background thread." } }
}

@Service(Service.Level.APP)
class JumpServerRuntime {
    val settings = JumpServerSettings.getInstance()
    val controller = SettingsController(settings, PasswordSafeSecretStore(), AppExecutorUtil.getAppExecutorService())
    companion object {
        fun getInstance(): JumpServerRuntime = ApplicationManager.getApplication().getService(JumpServerRuntime::class.java)
    }
}
