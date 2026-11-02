package dev.jumpserverbridge.datagrip.catalog

import com.intellij.database.dataSource.DatabaseDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class MySqlDriverSelectionTest {
    private fun driver(id: String, driverClass: String) = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DatabaseDriver::class.java)) { _, method, _ ->
        when (method.name) {
            "getId" -> id
            "getDriverClass" -> driverClass
            "isPredefined" -> true
            // Bundled mysql-drivers.xml declares a dialect and constraints, not a forced DBMS.
            "getForcedDbms" -> null
            else -> error("Unexpected driver operation: ${method.name}")
        }
    } as DatabaseDriver

    @Test fun builtinMySqlDriverDoesNotNeedAnOptionalForcedDbms() {
        val selected = AddJumpServerDataSourceAction.findMySqlDriver(listOf(
            driver("mysql.cloud_sql.8", "com.mysql.cj.jdbc.Driver"),
            driver("mysql.8", "com.mysql.cj.jdbc.Driver")
        ))
        assertNotNull(selected)
        assertEquals("mysql.8", selected!!.id)
    }

    @Test fun cloudSqlAndReplacedDriverClassesAreNotUsedAsTheNativeMySqlDriver() {
        assertNull(AddJumpServerDataSourceAction.findMySqlDriver(listOf(
            driver("mysql.cloud_sql.8", "com.mysql.cj.jdbc.Driver"),
            driver("mysql.8", "example.UnrelatedDriver")
        )))
    }
}
