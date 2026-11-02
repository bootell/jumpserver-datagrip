package dev.jumpserverbridge.datagrip.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

data class SettingsValues(val baseUrl: String = "", val keyId: String = "", val orgId: String = "") {
    override fun toString() = "SettingsValues(redacted)"
}

interface SettingsRepository {
    fun read(): SettingsValues
    fun write(values: SettingsValues)
}

@Service(Service.Level.APP)
@State(name = "JumpServerDatabaseAuth", storages = [Storage(value = "jumpserver-database-auth.xml", roamingType = RoamingType.DISABLED)])
class JumpServerSettings : PersistentStateComponent<JumpServerSettings.State>, SettingsRepository {
    class State(var baseUrl: String = "", var keyId: String = "", var orgId: String = "")
    @Volatile private var values = SettingsValues()
    override fun getState() = State(values.baseUrl, values.keyId, values.orgId)
    override fun loadState(state: State) { values = SettingsValues(state.baseUrl, state.keyId, state.orgId) }
    override fun read() = values
    override fun write(values: SettingsValues) { this.values = values }
    companion object {
        fun getInstance(): JumpServerSettings = ApplicationManager.getApplication().getService(JumpServerSettings::class.java)
    }
}
