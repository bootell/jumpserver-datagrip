package dev.jumpserverbridge.datagrip.catalog

import com.intellij.database.psi.DbDataSource
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class ExistingDataSourceContextTest {
    @Test fun nativeSettingsTargetTheChosenConnectionWithoutInheritingTheActiveConsole() {
        val project = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
            error("Must not inspect the project: ${method.name}")
        } as Project
        val selected = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DbDataSource::class.java)) { _, method, _ ->
            error("Must not inspect the data source: ${method.name}")
        } as DbDataSource
        val context = existingDataSourceContext(project, selected)
        assertSame(project, CommonDataKeys.PROJECT.getData(context))
        assertSame(selected, CommonDataKeys.PSI_ELEMENT.getData(context))
        assertNull(CommonDataKeys.PSI_FILE.getData(context))
        assertNull(context.getData("unrelated.context"))
    }
}
