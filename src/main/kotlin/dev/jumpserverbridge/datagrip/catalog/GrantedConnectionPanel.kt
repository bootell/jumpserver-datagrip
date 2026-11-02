package dev.jumpserverbridge.datagrip.catalog

import dev.jumpserverbridge.datagrip.core.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.swing.*

class GrantedConnectionPanel(
    private val catalog: ConnectionCatalog,
    private val executor: Executor,
    private val onSelectionChanged: () -> Unit
) : JPanel(BorderLayout(0, 10)) {
    private val search = JTextField().apply { accessibleContext.accessibleName = "Search JumpServer assets"; toolTipText = "按资产名称、地址或备注搜索" }
    private val refresh = JButton("搜索 / 刷新")
    private val assets = JList<GrantedAsset>(DefaultListModel()).apply {
        accessibleContext.accessibleName = "JumpServer assets"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = renderer { value ->
            val asset = value as GrantedAsset
            // A short ID distinguishes duplicate names; users never need to enter it.
            "${asset.name.ifBlank { "未命名资产" }}  ·  MySQL  ·  ${asset.id.toString().takeLast(8)}"
        }
    }
    private val accounts = JComboBox<GrantedAccount>().apply {
        accessibleContext.accessibleName = "JumpServer accounts"
        renderer = renderer { value ->
            val account = value as GrantedAccount
            if (account.name.isBlank() || account.name == account.alias) account.alias else "${account.name} (${account.alias})"
        }
    }
    private val status = JLabel(" ").apply { accessibleContext.accessibleName = "JumpServer status" }
    private var identity: CatalogIdentity? = null
    private var database: String? = null
    private var assetGeneration = 0L
    private var accountGeneration = 0L
    private var closed = false
    private val pending = mutableSetOf<CompletableFuture<*>>()

    init {
        preferredSize = Dimension(680, 390)
        val searchRow = JPanel(BorderLayout(8, 0)).apply { add(search, BorderLayout.CENTER); add(refresh, BorderLayout.EAST) }
        add(searchRow, BorderLayout.NORTH)
        add(JScrollPane(assets), BorderLayout.CENTER)
        val bottom = JPanel(BorderLayout(0, 8))
        val accountRow = JPanel(BorderLayout(8, 0)).apply {
            add(JLabel("授权账号"), BorderLayout.WEST)
            add(accounts, BorderLayout.CENTER)
        }
        bottom.add(accountRow, BorderLayout.NORTH)
        bottom.add(status, BorderLayout.SOUTH)
        add(bottom, BorderLayout.SOUTH)
        accounts.isEnabled = false
        refresh.addActionListener { refreshAssets() }
        search.addActionListener { refreshAssets() }
        assets.addListSelectionListener { if (!it.valueIsAdjusting) loadAccounts() }
        accounts.addActionListener { onSelectionChanged() }
    }

    fun refreshAssets() {
        if (closed) return
        val generation = ++assetGeneration
        accountGeneration++
        identity = null
        assets.clearSelection()
        assets.setListData(emptyArray())
        clearAccounts()
        status.text = "正在加载有权限的 MySQL 资产…"
        onSelectionChanged()
        val query = search.text
        submit({ catalog.loadAssets(query) }) { result ->
            if (generation != assetGeneration) return@submit
            result.fold({ loaded ->
                identity = loaded.identity
                assets.setListData(loaded.assets.sortedWith(compareBy<GrantedAsset> { it.name.lowercase() }.thenBy { it.id }).toTypedArray())
                status.text = if (loaded.assets.isEmpty()) "没有匹配的 MySQL 资产，可修改搜索条件后刷新。" else "已加载 ${loaded.assets.size} 个 MySQL 资产，请选择资产和账号。"
            }, { status.text = failureText(it) })
            onSelectionChanged()
        }
    }

    private fun loadAccounts() {
        val generation = ++accountGeneration
        clearAccounts()
        onSelectionChanged()
        val asset = assets.selectedValue ?: return
        val expected = identity ?: return
        status.text = "正在加载授权账号…"
        submit({ catalog.loadDetails(asset, expected) }) { result ->
            if (generation != accountGeneration) return@submit
            result.fold({ loaded ->
                database = loaded.database
                accounts.model = DefaultComboBoxModel(loaded.accounts.sortedBy { it.name.ifBlank { it.alias }.lowercase() }.toTypedArray())
                accounts.selectedIndex = -1
                accounts.isEnabled = loaded.accounts.isNotEmpty()
                status.text = if (loaded.accounts.isEmpty()) "没有可自动连接的授权账号，需要已有用户名和密码的账号。" else "请选择账号，然后创建连接；此处不会获取数据库密码。"
            }, { status.text = failureText(it) })
            onSelectionChanged()
        }
    }

    fun selection(): CatalogSelection? {
        if (closed) return null
        val current = identity ?: return null
        val asset = assets.selectedValue ?: return null
        val account = accounts.selectedItem as? GrantedAccount ?: return null
        return CatalogSelection(current, asset, account, database)
    }

    fun prepareSelection(onFinished: () -> Unit, onReady: (CatalogSelection) -> Unit) {
        val chosen = selection() ?: return
        val assetVersion = assetGeneration
        val accountVersion = accountGeneration
        status.text = "正在检查 JumpServer 配置…"
        submit({ catalog.verifyIdentity(chosen.identity) }) { result ->
            onFinished()
            if (assetVersion != assetGeneration || accountVersion != accountGeneration || selection() != chosen) return@submit
            result.fold({ if (selection() == chosen) onReady(chosen) }, {
                identity = null
                clearAccounts()
                status.text = failureText(it) + " 请刷新资产列表。"
                onSelectionChanged()
            })
        }
    }

    fun dispose() {
        closed = true
        pending.toList().forEach { it.cancel(true) }
        pending.clear()
    }

    private fun clearAccounts() {
        database = null
        accounts.model = DefaultComboBoxModel()
        accounts.isEnabled = false
    }

    private fun <T> submit(work: () -> T, done: (Result<T>) -> Unit) {
        val future = CompletableFuture.supplyAsync({ runCatching(work) }, executor)
        pending.add(future)
        future.whenComplete { result, error -> SwingUtilities.invokeLater {
            pending.remove(future)
            if (!closed && error == null) done(result)
        } }
    }

    private fun failureText(error: Throwable): String = when (error) {
        is ApiFailure -> "加载失败：${error.kind.description}" + (error.status?.let { " (HTTP $it)" } ?: "")
        else -> "加载失败，请检查 Settings | Tools | JumpServer 后刷新。"
    }

    private fun renderer(label: (Any) -> String) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component =
            super.getListCellRendererComponent(list, value?.let(label).orEmpty(), index, selected, focus)
    }
}
