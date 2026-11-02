package dev.jumpserverbridge.datagrip.auth

import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseAuthParameterBuilderHelper
import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.DatabaseConnectionPoint
import dev.jumpserverbridge.datagrip.core.*
import dev.jumpserverbridge.datagrip.settings.RuntimeSettings
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer
import javax.swing.SwingUtilities

object AssetCompletion {
    fun assetId(value: String): UUID {
        try {
            val trimmed = value.trim()
            val id = if (trimmed.endsWith(']')) trimmed.substringAfterLast('[').removeSuffix("]") else trimmed
            val parsed = UUID.fromString(id)
            if (!parsed.toString().equals(id, true)) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
            return parsed
        } catch (_: Exception) { throw ApiFailure(FailureKind.INVALID_CONFIGURATION) }
    }

    /** One editor owns its choices: no credentials or cross-profile global cache. */
    class Editor(
        point: DatabaseConnectionPoint,
        private val snapshotProvider: () -> RuntimeSettings,
        private val transportFactory: () -> HttpTransport,
        private val executor: Executor
    ) {
        private val originalId = parseId(point.getAdditionalProperty(JumpServerAuthProvider.ASSET_KEY).orEmpty())
        private val originalName = point.getAdditionalProperty(JumpServerAuthProvider.ASSET_NAME_KEY).orEmpty()
        private val assets = CopyOnWriteArrayList<String>()
        private val accounts = CopyOnWriteArrayList<String>()
        @Volatile private var index: Map<String, GrantedAsset> = emptyMap()
        @Volatile private var selectedId = originalId
        @Volatile private var accountAssetId: UUID? = null
        @Volatile private var identity: List<String?>? = null
        private val started = AtomicBoolean()
        private val accountGeneration = AtomicLong()
        @Volatile private var report: Consumer<String> = Consumer { }

        val assetProvider = object : DatabaseAuthParameterBuilderHelper.CompletionProvider {
            override fun getItems(point: DatabaseConnectionPoint, error: Consumer<String>): List<String> {
                start(error)
                // DataGrip retains this collection and reads it again when completion opens.
                return assets
            }
        }
        val accountProvider = object : DatabaseAuthParameterBuilderHelper.CompletionProvider {
            override fun getItems(point: DatabaseConnectionPoint, error: Consumer<String>): List<String> {
                start(error)
                return accounts
            }
        }
        val assetSerialiser = object : DatabaseAuthParameterBuilderHelper.Serialiser<String?> {
            override fun load(point: DatabaseConnectionPoint, credentials: DatabaseCredentials?): String {
                val raw = point.getAdditionalProperty(JumpServerAuthProvider.ASSET_KEY).orEmpty()
                val id = parseId(raw)
                return index.entries.firstOrNull { it.value.id == id }?.key
                    ?: point.getAdditionalProperty(JumpServerAuthProvider.ASSET_NAME_KEY)?.takeIf { it.isNotBlank() }
                    ?: raw
            }
            override fun save(v: String?, config: DatabaseConnectionConfig, credentials: DatabaseCredentials?) {
                val value = v.orEmpty().trim()
                val id = resolve(value)
                config.setAdditionalProperty(JumpServerAuthProvider.ASSET_KEY, id?.toString() ?: value)
                val name = index.values.firstOrNull { it.id == id }?.name
                    ?: originalName.takeIf { id != null && id == originalId && it.isNotBlank() }
                config.setAdditionalProperty(JumpServerAuthProvider.ASSET_NAME_KEY, name)
                select(value)
            }
        }
        val accountSerialiser = object : DatabaseAuthParameterBuilderHelper.Serialiser<String?> {
            override fun load(point: DatabaseConnectionPoint, credentials: DatabaseCredentials?) = point.getAdditionalProperty(JumpServerAuthProvider.ACCOUNT_KEY).orEmpty()
            override fun save(v: String?, config: DatabaseConnectionConfig, credentials: DatabaseCredentials?) {
                val value = v.orEmpty().trim()
                // Changing assets must not silently reuse an unverified old account alias.
                val valid = selectedId == originalId || (accountAssetId == selectedId && value in accounts)
                config.setAdditionalProperty(JumpServerAuthProvider.ACCOUNT_KEY, if (valid) value else "")
            }
        }

        @Synchronized fun select(value: String?) {
            val next = resolve(value.orEmpty().trim())
            if (next == selectedId) return
            selectedId = next
            accountGeneration.incrementAndGet()
            accountAssetId = null
            accounts.clear()
            if (started.get() && next != null) loadAccounts(next)
        }

        private fun resolve(value: String): UUID? {
            if (value.isNotBlank() && value == originalName && originalId != null) return originalId
            return index[value]?.id ?: parseId(value)
        }
        private fun parseId(value: String) = try { assetId(value) } catch (_: ApiFailure) { null }
        private fun start(error: Consumer<String>) {
            report = error
            if (!started.compareAndSet(false, true)) return
            executor.execute {
                try {
                    snapshotProvider().use { runtime ->
                        identity = identityOf(runtime.settings)
                        val loaded = JumpServerClient(runtime.settings, runtime.secret, transportFactory()).use { it.listAssets("") }
                        val counts = loaded.groupingBy { it.name }.eachCount()
                        var choices = loaded.sortedWith(compareBy<GrantedAsset> { it.name }.thenBy { it.id.toString() }).map {
                            val label = if (counts[it.name] == 1 && it.name.isNotBlank() && (it.name != originalName || it.id == originalId)) it.name else "${it.name} [${it.id}]"
                            label to it
                        }
                        // A literal name can equal another asset's generated label.
                        // Resolve repeated collisions before building the lookup map.
                        while (true) {
                            val collisions = choices.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys.toMutableSet()
                            if (originalName.isNotBlank() && choices.any { it.first == originalName && it.second.id != originalId }) {
                                collisions.add(originalName)
                            }
                            if (collisions.isEmpty()) break
                            choices = choices.map { (label, asset) ->
                                (if (label in collisions) "$label [${asset.id}]" else label) to asset
                            }
                        }
                        index = choices.toMap()
                        assets.addAll(index.keys)
                    }
                    selectedId?.let { loadAccounts(it) }
                } catch (_: Exception) { reportFailure() }
            }
        }
        private fun loadAccounts(id: UUID) {
            val generation = accountGeneration.incrementAndGet()
            executor.execute {
                try {
                    val loaded = snapshotProvider().use { runtime ->
                        if (identity != identityOf(runtime.settings)) throw ApiFailure(FailureKind.INVALID_CONFIGURATION)
                        JumpServerClient(runtime.settings, runtime.secret, transportFactory()).use { it.listAccounts(id).map { a -> a.alias } }
                    }
                    synchronized(this@Editor) {
                        if (generation == accountGeneration.get() && selectedId == id) {
                            accounts.clear()
                            accounts.addAll(loaded)
                            accountAssetId = id
                        }
                    }
                } catch (_: Exception) { if (generation == accountGeneration.get()) reportFailure() }
            }
        }
        private fun identityOf(settings: ServerSettings) = listOf(settings.baseUrl.toString(), settings.keyId, settings.orgId)
        private fun reportFailure() {
            SwingUtilities.invokeLater { report.accept("无法加载候选项，请检查 JumpServer 设置后重新打开连接。也可使用“从 JumpServer 添加连接”向导。") }
        }
    }
}
