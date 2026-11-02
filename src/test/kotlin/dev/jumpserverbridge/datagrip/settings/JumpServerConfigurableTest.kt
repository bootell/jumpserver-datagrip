package dev.jumpserverbridge.datagrip.settings

import com.intellij.openapi.options.ConfigurationException
import dev.jumpserverbridge.datagrip.core.HttpResponse
import dev.jumpserverbridge.datagrip.core.HttpTransport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Container
import java.util.ArrayDeque
import java.util.concurrent.Executor
import javax.swing.*

class JumpServerConfigurableTest {
    private val initial = SettingsValues("https://gateway.test", "fixture-key", "")
    private val settings = object : SettingsRepository {
        private var saved = initial
        override fun read() = saved
        override fun write(values: SettingsValues) { saved = values }
    }
    private val secrets = object : SecretStore {
        private var saved: CharArray? = "fixture-secret".toCharArray()
        override fun load() = saved?.copyOf()
        override fun replace(secret: CharArray) { saved?.fill('\u0000'); saved = secret.copyOf() }
        override fun remove() { saved?.fill('\u0000'); saved = null }
    }
    private val work = ArrayDeque<Runnable>()
    private var confirmRemoval = false
    private var apiStatus = 200
    private val controller = SettingsController(settings, secrets, Executor { work.addLast(it) }) {
        HttpTransport { _, _, _, _ -> HttpResponse(apiStatus, if (apiStatus == 200) "[]".toByteArray() else "private-response-body".toByteArray()) }
    }
    private val configurable = JumpServerConfigurable(settings, controller) { confirmRemoval }
    private lateinit var component: Container

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
    private fun all(parent: Container): List<JComponent> = parent.components.filterIsInstance<JComponent>().flatMap { listOf(it) + all(it) }
    private inline fun <reified T> field(name: String): T = all(component)
        .filterIsInstance<T>().first { (it as JComponent).accessibleContext.accessibleName == name }
    private fun drain() {
        while (work.isNotEmpty()) work.removeFirst().run()
        onEdt { }
    }
    private fun create() = onEdt { component = configurable.createComponent() }

    private fun layoutTree(parent: Container) {
        parent.doLayout()
        parent.components.filterIsInstance<Container>().forEach { layoutTree(it) }
    }

    @Test fun wideSettingsWindowDoesNotStretchTheInputsOrVerificationButton() {
        create()
        onEdt {
            component.setSize(1400, 800)
            layoutTree(component)
            val address = field<JTextField>("服务器地址")
            val verify = field<JButton>("验证 API 连接")
            assertTrue(address.width <= address.preferredSize.width + 8, "The address field must not stretch across a wide settings window")
            assertTrue(verify.width <= verify.preferredSize.width + 8, "The verify button should keep its natural width")
            configurable.disposeUIResources()
        }
    }

    @Test fun savedSecretCannotBeReusedWhenIdentityChanges() {
        create()
        onEdt {
            field<JTextField>("服务器地址").text = "  https://gateway.test/  "
            field<JPasswordField>("密钥 Secret").text = "fixture-replacement"
            configurable.apply()
        }
        drain()
        onEdt {
            assertEquals(0, field<JPasswordField>("密钥 Secret").password.size)
            assertEquals("https://gateway.test", field<JTextField>("服务器地址").text)
            assertFalse(configurable.isModified)
            field<JTextField>("服务器地址").text = "https://other.test"
            assertThrows(ConfigurationException::class.java) { configurable.apply() }
            assertTrue(work.isEmpty())
            configurable.disposeUIResources()
        }
        assertEquals(initial, settings.read())
        assertArrayEquals("fixture-replacement".toCharArray(), secrets.load())
    }

    @Test fun confirmedRemovalWaitsForApplyAndThenClearsOnlyTheLocalSecret() {
        create()
        confirmRemoval = true
        onEdt {
            field<JToggleButton>("高级设置").doClick()
            field<JButton>("清除本机保存的密钥…").doClick()
            assertTrue(configurable.isModified)
            assertFalse(field<JPasswordField>("密钥 Secret").isEnabled)
            assertFalse(field<JButton>("验证 API 连接").isEnabled)
        }
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
        assertTrue(work.isEmpty())
        onEdt {
            configurable.apply()
        }
        drain()
        onEdt {
            assertEquals(0, field<JPasswordField>("密钥 Secret").password.size)
            assertTrue(field<JPasswordField>("密钥 Secret").isEnabled)
            assertNotNull(field<JButton>("清除本机保存的密钥…"))
            assertFalse(configurable.isModified)
            assertTrue(field<JTextArea>("操作状态").text.contains("已清除"))
            configurable.disposeUIResources()
        }
        assertNull(secrets.load())
    }

    @Test fun verificationKeepsTheUnsavedSecretDraft() {
        create()
        onEdt {
            field<JPasswordField>("密钥 Secret").text = "fixture-unsaved"
            field<JButton>("验证 API 连接").doClick()
        }
        drain()
        onEdt {
            assertArrayEquals("fixture-unsaved".toCharArray(), field<JPasswordField>("密钥 Secret").password)
            assertTrue(configurable.isModified)
            configurable.disposeUIResources()
        }
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
    }

    @Test fun leavingSecretBlankPreservesTheSavedSecret() {
        create()
        onEdt { configurable.apply() }
        drain()
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
        onEdt { assertFalse(configurable.isModified); configurable.disposeUIResources() }
    }

    @Test fun rejectingTheClearConfirmationKeepsTheUnsavedAndSavedSecrets() {
        create()
        onEdt {
            field<JPasswordField>("密钥 Secret").text = "fixture-unsaved"
            field<JToggleButton>("高级设置").doClick()
            field<JButton>("清除本机保存的密钥…").doClick()
            assertArrayEquals("fixture-unsaved".toCharArray(), field<JPasswordField>("密钥 Secret").password)
            assertTrue(field<JPasswordField>("密钥 Secret").isEnabled)
            assertTrue(work.isEmpty())
            configurable.disposeUIResources()
        }
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
    }

    @Test fun pendingClearCanBeCancelledAndClosingSettingsNeverAppliesIt() {
        create()
        confirmRemoval = true
        onEdt {
            field<JToggleButton>("高级设置").doClick()
            field<JButton>("清除本机保存的密钥…").doClick()
            field<JButton>("取消清除").doClick()
            assertFalse(configurable.isModified)
            assertTrue(field<JPasswordField>("密钥 Secret").isEnabled)
            field<JButton>("清除本机保存的密钥…").doClick()
            configurable.disposeUIResources()
        }
        assertTrue(work.isEmpty())
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
    }

    @Test fun cancellingPendingClearPreservesTheUnsavedSecretDraft() {
        create()
        confirmRemoval = true
        onEdt {
            field<JPasswordField>("密钥 Secret").text = "fixture-unsaved"
            field<JToggleButton>("高级设置").doClick()
            field<JButton>("清除本机保存的密钥…").doClick()
            field<JButton>("取消清除").doClick()
            assertArrayEquals("fixture-unsaved".toCharArray(), field<JPasswordField>("密钥 Secret").password)
            assertTrue(configurable.isModified)
            configurable.disposeUIResources()
        }
        assertTrue(work.isEmpty())
        assertArrayEquals("fixture-secret".toCharArray(), secrets.load())
    }

    @Test fun organizationAndClearActionAreHiddenUntilAdvancedSettingsIsExpanded() {
        create()
        fun visibleInPanel(control: JComponent) = generateSequence<Container>(control) { it.parent }.all { it.isVisible }
        onEdt {
            assertFalse(visibleInPanel(field<JTextField>("组织 ID（可选）")))
            assertFalse(visibleInPanel(field<JButton>("清除本机保存的密钥…")))
            field<JToggleButton>("高级设置").doClick()
            assertTrue(visibleInPanel(field<JTextField>("组织 ID（可选）")))
            assertTrue(visibleInPanel(field<JButton>("清除本机保存的密钥…")))
            configurable.disposeUIResources()
        }
    }

    @Test fun inputsStayInsideThePageWhenTheSettingsWindowIsNarrow() {
        create()
        onEdt {
            component.setSize(400, 800)
            layoutTree(component)
            val address = field<JTextField>("服务器地址")
            val point = SwingUtilities.convertPoint(address.parent, address.x, address.y, component)
            assertTrue(address.width > 0)
            assertTrue(point.x >= 0 && point.x + address.width <= component.width)
            configurable.disposeUIResources()
        }
    }

    @Test fun failedVerificationUsesChineseFeedbackWithoutTheRawResponse() {
        create()
        apiStatus = 403
        onEdt { field<JButton>("验证 API 连接").doClick() }
        drain()
        onEdt {
            val text = field<JTextArea>("操作状态").text
            assertTrue(text.contains("拒绝"))
            assertTrue(text.contains("403"))
            assertFalse(text.contains("private-response-body"))
            configurable.disposeUIResources()
        }
    }
}
