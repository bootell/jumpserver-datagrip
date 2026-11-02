package dev.jumpserverbridge.datagrip.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import java.net.URI
import java.net.URLEncoder

data class DataSourceDefaults(val url: String, val name: String) {
    fun applyTo(source: LocalDataSource, selection: CatalogSelection) {
        source.name = name
        source.isReadOnly = false
        source.isKeepAlive = true
        source.keepAliveTimeout = 30
        source.isAutoSynchronize = true
        // Populate only the asset's default database; users can select more in native Schemas.
        selection.database?.takeIf { it.isNotBlank() }?.let { database ->
            source.setIntrospectionScope(TreePattern(TreePatternUtils.create(ObjectName.quoted(database), ObjectKind.SCHEMA)))
        }
        source.passwordStorage = LocalDataSource.Storage.NO
        DataSourceBinding.apply(source, selection)
    }

    companion object {
        fun from(selection: CatalogSelection, usedNames: Set<String>): DataSourceDefaults {
            val host = URI(selection.identity.server).host
            val database = selection.database?.takeIf { it.isNotBlank() }
                ?.let { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }.orEmpty()
            val username = selection.account.username?.takeIf { it.isNotBlank() } ?: selection.account.alias
            val baseName = "${selection.asset.name.ifBlank { "MySQL" }}@$username"
            var name = baseName
            var suffix = 2
            while (name in usedNames) name = "$baseName (${suffix++})"
            return DataSourceDefaults("jdbc:mysql://$host:33061/$database", name)
        }
    }
}
