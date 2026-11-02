package dev.jumpserverbridge.datagrip.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import javax.swing.SwingUtilities

interface SecretStore {
    fun load(): CharArray?
    fun replace(secret: CharArray)
    fun remove()
}

class PasswordSafeSecretStore : SecretStore {
    private val attributes = CredentialAttributes("IntelliJ Platform JumpServer Database Auth - API Key")
    private fun requireBackground() { check(!SwingUtilities.isEventDispatchThread()) { "Credential access requires a background thread." } }
    override fun load(): CharArray? {
        requireBackground()
        return PasswordSafe.instance.getPassword(attributes)?.toCharArray()
    }
    override fun replace(secret: CharArray) {
        requireBackground()
        PasswordSafe.instance.setPassword(attributes, String(secret))
    }
    override fun remove() {
        requireBackground()
        PasswordSafe.instance.setPassword(attributes, null)
    }
}
