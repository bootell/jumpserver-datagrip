package dev.jumpserverbridge.datagrip.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.Messages
import dev.jumpserverbridge.datagrip.core.ApiFailure
import dev.jumpserverbridge.datagrip.core.FailureKind
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.concurrent.CompletableFuture
import javax.swing.*

class JumpServerConfigurable(
    private val settings: SettingsRepository = JumpServerRuntime.getInstance().settings,
    private val controller: SettingsController = JumpServerRuntime.getInstance().controller,
    private val confirmRemoval: (JComponent) -> Boolean = { owner ->
        Messages.showDialog(owner,
            "点击“应用”后，将清除本机保存的 Secret，下次连接前需要重新填写。\n此操作不会撤销 JumpServer 服务器上的 API Key。",
            "清除本机保存的密钥", arrayOf("清除", "取消"), 1, Messages.getWarningIcon()) == 0
    }
) : SearchableConfigurable {
    private val address = JTextField(36)
    private val keyId = JTextField(36)
    private val secret = JPasswordField(36)
    private val orgId = JTextField(36)
    private val remove = JButton("清除本机保存的密钥…")
    private val verify = JButton("验证 API 连接")
    private val advanced = JToggleButton("▸ 高级设置").apply { accessibleContext.accessibleName = "高级设置" }
    private val advancedContent = JPanel(GridBagLayout())
    private val status = note(" ").apply { accessibleContext.accessibleName = "操作状态" }
    private var panel: JPanel? = null
    private var disposed = false
    private var busy = false
    private var removalPending = false

    init {
        verify.addActionListener { if (!busy && !removalPending) startVerification() }
        advanced.addActionListener { updateAdvanced() }
        remove.addActionListener {
            if (!busy && (removalPending || confirmRemoval(panel ?: remove))) {
                removalPending = !removalPending
                updateControls()
                status.text = if (removalPending) "已计划清除本机密钥，点击“应用”后生效；也可取消清除。" else "已取消清除，继续保留本机保存的密钥。"
            }
        }
    }

    override fun getId() = "jumpserver.database.auth"
    override fun getDisplayName() = "JumpServer"
    override fun createComponent(): JComponent {
        disposed = false
        val content = JPanel(GridBagLayout())
        val form = JPanel(GridBagLayout())
        address.toolTipText = "请输入自己的 JumpServer HTTPS 地址，例如 https://jumpserver.example.com"
        secret.toolTipText = "留空保留已保存的密钥；首次配置或更换服务器、Key ID 时填写。"
        orgId.toolTipText = "用于指定 JumpServer 组织范围，通常留空；仅在管理员要求时填写。"
        var row = 0
        fun add(label: String, field: JComponent) {
            val text = JLabel(label).apply { labelFor = field }
            field.accessibleContext.accessibleName = label
            form.add(text, GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(4, 4, 4, 10) })
            form.add(field, GridBagConstraints().apply { gridx = 1; gridy = row++; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 4, 4, 4) })
        }
        fun fullWidth(component: JComponent, fill: Int = GridBagConstraints.HORIZONTAL) {
            form.add(component, GridBagConstraints().apply { gridx = 0; gridy = row++; gridwidth = 2; weightx = 1.0; this.fill = fill; anchor = GridBagConstraints.WEST; insets = Insets(6, 4, 4, 4) })
        }
        add("服务器地址", address)
        add("访问密钥 ID", keyId)
        add("密钥 Secret", secret)
        fullWidth(note("Secret 留空会保留已保存的密钥。\n仅首次配置或更换服务器、Key ID 时需要填写。"))
        fullWidth(verify, GridBagConstraints.NONE)
        fullWidth(status)
        fullWidth(advanced, GridBagConstraints.NONE)
        advancedContent.removeAll()
        advancedContent.border = BorderFactory.createEmptyBorder(0, 12, 0, 0)
        val orgLabel = JLabel("组织 ID（可选）").apply { labelFor = orgId }
        orgId.accessibleContext.accessibleName = orgLabel.text
        advancedContent.add(orgLabel, GridBagConstraints().apply { gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; insets = Insets(4, 0, 4, 10) })
        advancedContent.add(orgId, GridBagConstraints().apply { gridx = 1; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
        advancedContent.add(note("用于指定 JumpServer 组织范围，通常留空。\n只有管理员要求访问特定组织时才需要填写。"), GridBagConstraints().apply { gridx = 0; gridy = 1; gridwidth = 2; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL; insets = Insets(4, 0, 8, 0) })
        advancedContent.add(remove, GridBagConstraints().apply { gridx = 0; gridy = 2; gridwidth = 2; anchor = GridBagConstraints.WEST })
        fullWidth(advancedContent)
        // Keep the form at its natural width instead of stretching it across Settings.
        content.add(form, GridBagConstraints().apply { gridx = 0; gridy = 0; weightx = 1.0; weighty = 1.0; anchor = GridBagConstraints.NORTHWEST })
        panel = content
        reset()
        return content
    }

    override fun isModified(): Boolean {
        val password = secret.password
        return try { values() != settings.read() || password.isNotEmpty() || removalPending }
        finally { password.fill('\u0000') }
    }

    override fun apply() {
        if (busy) throw ConfigurationException("请等待当前 JumpServer 操作完成。")
        draft().use { pending ->
            try { controller.validateDraft(pending) }
            catch (_: ApiFailure) { throw ConfigurationException("请检查 HTTPS 地址、密钥 ID 和 Secret。更换服务器或 Key ID 时需要重新填写 Secret。") }
            setBusy(true)
            val message = if (removalPending) "本机保存的密钥已清除，下次连接前请重新填写 Secret。" else "配置已保存，Secret 无需重复填写。"
            complete(controller.submit(pending), message) { reset() }
        }
    }

    override fun reset() {
        val saved = settings.read()
        address.text = saved.baseUrl; keyId.text = saved.keyId; orgId.text = saved.orgId
        secret.text = ""; removalPending = false
        advanced.isSelected = saved.orgId.isNotBlank()
        updateAdvanced()
        updateControls()
        status.text = "密钥由 IDE 密码库保管，不会在此回显。"
    }

    override fun disposeUIResources() {
        disposed = true
        secret.text = ""
        removalPending = false
        panel = null
    }

    private fun values() = SettingsValues(address.text, keyId.text, orgId.text)
    private fun draft(): SettingsDraft {
        val password = secret.password
        return try { SettingsDraft(values(), when {
            removalPending -> SecretEdit.Remove
            password.isNotEmpty() -> SecretEdit.Replace(password)
            else -> SecretEdit.Keep
        }) } finally { password.fill('\u0000') }
    }
    private fun startVerification() {
        draft().use { pending ->
            setBusy(true)
            complete(controller.verify(pending), "API 认证成功，数据库连接权限将在连接时检查。")
        }
    }
    private fun setBusy(value: Boolean) {
        busy = value
        updateControls()
        if (value) status.text = "正在处理…"
    }
    private fun updateControls() {
        listOf(address, keyId, orgId, remove, advanced).forEach { it.isEnabled = !busy }
        secret.isEnabled = !busy && !removalPending
        verify.isEnabled = !busy && !removalPending
        remove.text = if (removalPending) "取消清除" else "清除本机保存的密钥…"
    }
    private fun updateAdvanced() {
        advanced.text = if (advanced.isSelected) "▾ 高级设置" else "▸ 高级设置"
        advancedContent.isVisible = advanced.isSelected
        panel?.revalidate()
        panel?.repaint()
    }
    private fun complete(operation: CompletableFuture<Unit>, success: String, onSuccess: () -> Unit = {}) {
        operation.whenComplete { _, error -> SwingUtilities.invokeLater {
            val failure = generateSequence(error) { it.cause }.filterIsInstance<ApiFailure>().firstOrNull()
            val message = if (error == null) success else failure?.let { localizedFailure(it) } ?: "JumpServer 设置处理失败，请检查配置后重试。"
            if (!disposed) {
                setBusy(false)
                if (error == null) onSuccess()
                status.text = message
            }
            if (error != null && disposed) NotificationGroupManager.getInstance().getNotificationGroup("JumpServer Database Auth")
                .createNotification("JumpServer 设置", message, NotificationType.ERROR).notify(null)
        } }
    }

    private fun localizedFailure(failure: ApiFailure): String {
        val description = when (failure.kind) {
            FailureKind.INVALID_CONFIGURATION -> "请检查服务器地址、密钥 ID 和 Secret；更换身份时需要重新填写 Secret。"
            FailureKind.INVALID_RESPONSE -> "JumpServer 返回的数据格式不符合预期。"
            FailureKind.UNAUTHENTICATED -> "JumpServer 拒绝了 API 密钥，请检查密钥是否有效。"
            FailureKind.FORBIDDEN -> "JumpServer 拒绝访问，请检查账号权限和 IP 限制。"
            FailureKind.APPROVAL_REQUIRED -> "此连接需要先在 JumpServer 中完成审批。"
            FailureKind.TIMEOUT -> "JumpServer API 请求超时，请稍后重试。"
            FailureKind.NETWORK -> "JumpServer API 请求失败，请检查网络和 HTTPS 证书配置。"
        }
        return description + (failure.status?.let { "（HTTP $it）" } ?: "")
    }

    private fun note(text: String) = JTextArea(text, 2, 34).apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
    }
}
