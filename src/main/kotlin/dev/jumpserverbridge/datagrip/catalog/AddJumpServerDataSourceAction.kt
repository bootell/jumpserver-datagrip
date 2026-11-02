package dev.jumpserverbridge.datagrip.catalog

import com.intellij.database.actions.showDataSourcesConfigurationDialog
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.util.concurrency.AppExecutorUtil
import dev.jumpserverbridge.datagrip.settings.JumpServerRuntime
import javax.swing.JComponent

class AddJumpServerDataSourceAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project?.let { !it.isDisposed && !it.isDefault } == true
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val dialog = GrantedConnectionDialog(project)
        if (!dialog.showAndGet() || project.isDisposed) return
        val selected = dialog.chosen ?: return
        val manager = LocalDataSourceManager.getInstance(project)
        val existing = manager.dataSources.firstOrNull { DataSourceBinding.matches(it, selected) }
        if (existing != null) {
            val choice = Messages.showDialog(project,
                "此资产和账号已有连接：${existing.name}", "JumpServer 连接已存在",
                arrayOf("打开已有连接", "创建副本", "取消"), 0, Messages.getQuestionIcon())
            if (choice == 0) {
                val selectedSource = DbPsiFacade.getInstance(project).findDataSource(existing.uniqueId)
                val settingsAction = event.actionManager.getAction("DatabaseView.PropertiesAction")
                if (selectedSource == null || settingsAction == null) {
                    Messages.showErrorDialog(project, "数据源设置尚未就绪，请稍后再试。", "JumpServer")
                    return
                }
                // The built-in action owns the full database editor and its controller.
                ActionUtil.performAction(settingsAction, event.withDataContext(existingDataSourceContext(project, selectedSource)))
                return
            }
            if (choice != 1) return
        }
        val driver = findMySqlDriver(DatabaseDriverManager.getInstance().drivers)
        if (driver == null) {
            Messages.showErrorDialog(project, "未找到官方 MySQL 驱动，请先在 Data Sources and Drivers 中启用 MySQL 驱动。", "JumpServer")
            return
        }
        val defaults = DataSourceDefaults.from(selected, manager.dataSources.map { it.name }.toSet())
        val source = LocalDataSource.fromDriver(driver, defaults.url, true)
        defaults.applyTo(source, selected)
        // The native dialog owns this draft. Cancel does not register a data source.
        showDataSourcesConfigurationDialog(project, listOf(source))
    }

    companion object {
        internal fun findMySqlDriver(drivers: Collection<DatabaseDriver>): DatabaseDriver? =
            drivers.firstOrNull { it.id == "mysql.8" && it.driverClass == "com.mysql.cj.jdbc.Driver" }
    }
}

internal fun existingDataSourceContext(project: Project, source: DbDataSource): DataContext = DataContext { key ->
    when (key) {
        CommonDataKeys.PROJECT.name -> project
        CommonDataKeys.PSI_ELEMENT.name -> source
        else -> null
    }
}

private class GrantedConnectionDialog(project: Project) : DialogWrapper(project) {
    private var preparing = false
    private val picker = GrantedConnectionPanel(
        ConnectionCatalog({ JumpServerRuntime.getInstance().controller.snapshot() }),
        AppExecutorUtil.getAppExecutorService()
    ) { updateOk() }
    var chosen: CatalogSelection? = null
        private set

    init {
        title = "从 JumpServer 添加 MySQL 连接"
        setOKButtonText("创建连接…")
        init()
        isOKActionEnabled = false
        picker.refreshAssets()
    }

    override fun createCenterPanel(): JComponent = picker
    override fun getPreferredFocusedComponent(): JComponent? = picker.components.firstOrNull() as? JComponent
    override fun doOKAction() {
        if (preparing) return
        preparing = true
        updateOk()
        picker.prepareSelection({ preparing = false; updateOk() }) { selection ->
            chosen = selection
            super.doOKAction()
        }
    }

    private fun updateOk() { isOKActionEnabled = !preparing && picker.selection() != null }
    override fun dispose() { picker.dispose(); super.dispose() }
}
