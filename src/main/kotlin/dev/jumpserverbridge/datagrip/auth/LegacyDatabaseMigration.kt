package dev.jumpserverbridge.datagrip.auth

import com.intellij.database.dataSource.DatabaseAuthParameterBuilderHelper
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.url.template.MutableParametersHolder
import com.intellij.database.dataSource.url.template.ParametersHolder
import java.net.URI

/** Migrate on the native editor's URL update, so Cancel keeps the original saved source. */
class LegacyDatabaseMigration(config: DatabaseConnectionConfig) : DatabaseAuthParameterBuilderHelper.UrlHandler<String?> {
    private var nativeAtOpen: String? = null
    private var legacy: String? = null
    private var migrated = false

    init { reset(config) }

    fun reset(point: DatabaseConnectionPoint) {
        val url = point.url
        // Auth-provider switches can reset controls from an empty temporary accumulator.
        if (url.isNullOrBlank()) return
        nativeAtOpen = try {
            val uri = URI(url.removePrefix("jdbc:"))
            if (uri.scheme != "mysql" || uri.isOpaque) null else uri.path.orEmpty().removePrefix("/")
        } catch (_: Exception) { null }
        legacy = point.getAdditionalProperty(JumpServerAuthProvider.DATABASE_KEY)?.takeIf { it.isNotBlank() }
        migrated = false
    }

    fun save(target: DatabaseConnectionConfig) {
        if (migrated && target.getAdditionalProperty(JumpServerAuthProvider.DATABASE_KEY) == legacy) {
            target.setAdditionalProperty(JumpServerAuthProvider.DATABASE_KEY, null)
        }
    }

    override fun fromUrl(holder: ParametersHolder): String? = null

    override fun toUrl(v: String?, model: MutableParametersHolder) {
        val value = legacy ?: return
        if (migrated || nativeAtOpen == null) return
        val current = model.getParameter("database").orEmpty()
        // The model belongs to the native editor; the captured config may be the saved source.
        migrated = true
        if (current == nativeAtOpen) model.setParameter("database", value)
        model.setParameter(JumpServerAuthProvider.DATABASE_KEY, "")
    }
}
