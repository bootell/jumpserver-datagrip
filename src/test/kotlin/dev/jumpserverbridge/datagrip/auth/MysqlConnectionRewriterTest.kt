package dev.jumpserverbridge.datagrip.auth

import dev.jumpserverbridge.datagrip.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MysqlConnectionRewriterTest {
    @Test fun rewritePreservesTlsAndDatabaseEncoding() {
        val properties = mapOf("sslMode" to "VERIFY_IDENTITY", "user" to "old-user", "password" to "old-password", "jumpserver.key" to "must-not-forward")
        val grant = ConnectionGrant("mysql.example.com", 33061, "fixture-token", "fixture-password".toCharArray())
        val prepared = MysqlConnectionRewriter.rewrite("jdbc:mysql://old.example.com:3306/original?sslMode=VERIFY_IDENTITY&serverTimezone=UTC&user=old&%70assword=old", properties, grant, "db 甲")
        assertEquals("jdbc:mysql://mysql.example.com:33061/db%20%E7%94%B2?sslMode=VERIFY_IDENTITY&serverTimezone=UTC", prepared.url)
        assertEquals("VERIFY_IDENTITY", prepared.properties["sslMode"])
        assertEquals("fixture-token", prepared.properties["user"])
        assertEquals("fixture-password", prepared.properties["password"])
        assertFalse(prepared.properties.containsKey("jumpserver.key"))
        assertEquals("old-password", properties["password"])
        assertFalse(prepared.toString().contains("fixture-password"))
    }

    @Test fun ipv6EndpointIsBracketed() {
        val grant = ConnectionGrant("2001:db8::1", 33061, "fixture", "fixture-password".toCharArray())
        assertEquals("jdbc:mysql://[2001:db8::1]:33061/original", MysqlConnectionRewriter.rewrite("jdbc:mysql://old.example.com/original", emptyMap(), grant, null).url)
    }

    @Test fun ambiguousMultiHostUrlIsRejected() {
        val grant = ConnectionGrant("mysql.example.com", 33061, "fixture", "fixture-password".toCharArray())
        assertThrows(ApiFailure::class.java) { MysqlConnectionRewriter.rewrite("jdbc:mysql://one.example.com,two.example.com/db", emptyMap(), grant, null) }
    }

    @Test fun duplicateAssetNameCannotChooseWrongUuid() {
        val first = "00000000-0000-4000-8000-000000000001"
        val second = "00000000-0000-4000-8000-000000000002"
        assertEquals(first, AssetCompletion.assetId("same [$first]").toString())
        assertEquals(second, AssetCompletion.assetId("same [$second]").toString())
        assertThrows(ApiFailure::class.java) { AssetCompletion.assetId("same") }
    }
}
