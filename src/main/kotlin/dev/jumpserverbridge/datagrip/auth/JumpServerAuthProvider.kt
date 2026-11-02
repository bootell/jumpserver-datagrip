package dev.jumpserverbridge.datagrip.auth

import com.intellij.database.Dbms
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseAuthParameterBuilderHelper
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseAuthProviderParam
import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseConnectionPoint
import dev.jumpserverbridge.datagrip.core.*
import dev.jumpserverbridge.datagrip.settings.JumpServerRuntime
import dev.jumpserverbridge.datagrip.settings.RuntimeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible

class JumpServerAuthProvider(
    private val snapshotProvider: () -> RuntimeSettings = { JumpServerRuntime.getInstance().controller.snapshot() },
    private val transportFactory: () -> HttpTransport = { JdkHttpTransport() },
    private val completionExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute(it) }
) : DatabaseAuthProvider {
    override fun getId() = ID
    override fun getDisplayName() = "JumpServer"
    override fun getApplicability(point: DatabaseConnectionPoint, level: DatabaseAuthProvider.ApplicabilityLevel) =
        if (supportsDbms(point.dbms)) DatabaseAuthProvider.ApplicabilityLevel.Result.APPLICABLE else DatabaseAuthProvider.ApplicabilityLevel.Result.NOT_APPLICABLE

    override fun getAuthParams(config: DatabaseConnectionConfig): List<DatabaseAuthProviderParam<*>> {
        val editor = AssetCompletion.Editor(config, snapshotProvider, transportFactory, completionExecutor)
        val migration = if (!config.getAdditionalProperty(DATABASE_KEY).isNullOrBlank()) LegacyDatabaseMigration(config) else null
        val assetSerialiser = object : DatabaseAuthParameterBuilderHelper.Serialiser<String?> {
            override fun load(point: DatabaseConnectionPoint, credentials: DatabaseCredentials?): String {
                migration?.reset(point)
                return editor.assetSerialiser.load(point, credentials).orEmpty()
            }
            override fun save(v: String?, config: DatabaseConnectionConfig, credentials: DatabaseCredentials?) {
                editor.assetSerialiser.save(v, config, credentials)
                migration?.save(config)
            }
        }
        return DatabaseAuthProviderParam.buildParams {
            val removeAsset = DatabaseAuthParameterBuilderHelper.removeParameterHandler(ASSET_KEY)
            val assetUrl = object : DatabaseAuthParameterBuilderHelper.UrlHandler<String?> {
                override fun fromUrl(holder: com.intellij.database.dataSource.url.template.ParametersHolder): String? = removeAsset.fromUrl(holder)
                override fun toUrl(v: String?, model: com.intellij.database.dataSource.url.template.MutableParametersHolder) {
                    editor.select(v)
                    removeAsset.toUrl(v, model)
                    model.setParameter(ASSET_NAME_KEY, "")
                }
            }
            textParam(ASSET_KEY, "JumpServer 资产", editor.assetProvider, assetSerialiser, assetUrl)
            textParam(ACCOUNT_KEY, "授权账号", editor.accountProvider, editor.accountSerialiser, DatabaseAuthParameterBuilderHelper.removeParameterHandler(ACCOUNT_KEY))
            if (migration != null) transientParam("jumpserver.databaseMigration", migration)
        }
    }

    override suspend fun interceptConnection(proto: DatabaseConnectionInterceptor.ProtoConnection, silent: Boolean): Boolean {
        val point = proto.connectionPoint
        if (point.authProviderId != ID || !supportsDbms(point.dbms)) return false
        val request = ConnectionRequest(AssetCompletion.assetId(point.getAdditionalProperty(ASSET_KEY).orEmpty()), point.getAdditionalProperty(ACCOUNT_KEY).orEmpty(), point.getAdditionalProperty(DATABASE_KEY))
        val originalUrl = proto.url
        val originalProperties = proto.connectionProperties.filterValues { it != null }.mapValues { it.value!! }
        val prepared = runInterruptible(Dispatchers.IO) {
            snapshotProvider().use { runtime ->
                JumpServerClient(runtime.settings, runtime.secret, transportFactory()).use { client ->
                    client.fetchGrant(request).use { grant -> MysqlConnectionRewriter.rewrite(originalUrl, originalProperties, grant, request.database) }
                }
            }
        }
        currentCoroutineContext().ensureActive()
        proto.url = prepared.url
        proto.connectionProperties.clear()
        proto.connectionProperties.putAll(prepared.properties)
        return true
    }

    companion object {
        private fun supportsDbms(dbms: Dbms) = dbms == Dbms.MYSQL || dbms == Dbms.MYSQL_AURORA
        const val ID = "jumpserver"
        const val ASSET_KEY = "jumpserver.assetId"
        const val ASSET_NAME_KEY = "jumpserver.assetName"
        const val ACCOUNT_KEY = "jumpserver.accountAlias"
        const val DATABASE_KEY = "jumpserver.database"
    }
}
