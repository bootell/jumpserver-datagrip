package dev.jumpserverbridge.datagrip.auth

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseAuthProvider
import com.intellij.database.dataSource.DatabaseConnectionConfig
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.DatabaseAuthProviderParam
import com.intellij.database.dataSource.url.template.MutableParametersHolder
import com.google.gson.JsonParser
import dev.jumpserverbridge.datagrip.core.*
import dev.jumpserverbridge.datagrip.settings.RuntimeSettings
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JumpServerAuthProviderTest {
    private val sequence = AtomicInteger()
    private fun runtime(key: String = "fixture-key") = RuntimeSettings(ServerSettings(URI("https://gateway.test"), key, null), "fixture-secret".toCharArray())
    private fun transport() = HttpTransport { method, uri, _, _ ->
        if (method == "POST") {
            val id = "00000000-0000-4000-8000-%012d".format(sequence.incrementAndGet())
            HttpResponse(201, """{"id":"$id","is_active":true,"from_ticket":null}""".toByteArray())
        } else {
            val id = uri.path.split('/').filter { it.isNotBlank() }.let { it[it.size - 2] }
            val data = """{"version":2,"protocol":"mysql","asset":{"id":"00000000-0000-4000-8000-000000000001"},"token":{"id":"$id","value":"password-$id"},"endpoint":{"host":"mysql.example.com","port":33061}}"""
            HttpResponse(200, """{"url":"jms://${Base64.getEncoder().encodeToString(data.toByteArray())}"}""".toByteArray())
        }
    }

    @Test fun savedAssetDisplaysItsNameButKeepsItsCanonicalId() {
        val context = TestProto()
        context.parameters["jumpserver.assetName"] = "Fixture MySQL"
        val asset = JumpServerAuthProvider({ runtime() }, { transport() }).getAuthParams(context.config)
            .first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam
        assertEquals("Fixture MySQL", asset.load(context.config, null))
        asset.save("Fixture MySQL", context.config, null)
        assertEquals("00000000-0000-4000-8000-000000000001", context.parameters["jumpserver.assetId"])
    }

    @Test fun legacyOverrideMigratesOnceAndSubsequentNativeDatabaseChangesWin() = runBlocking {
        val context = TestProto()
        context.parameters["jumpserver.database"] = "legacy 库"
        val params = JumpServerAuthProvider({ runtime() }, { transport() }).getAuthParams(context.config)
        assertEquals("legacy 库", context.parameters["jumpserver.database"], "Opening the editor must not mutate the saved source")
        val native = NativeParameters(mutableMapOf("database" to "fixture"))
        params.filterIsInstance<DatabaseAuthProviderParam.TransientParam>().forEach { it.toUrl(null, native.holder) }
        assertEquals("legacy 库", native.values["database"])
        assertEquals("legacy 库", context.parameters["jumpserver.database"], "Cancel must retain the persisted override even after editing the URL")
        val edited = TestProto()
        edited.parameters["jumpserver.database"] = "legacy 库"
        (params.first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam)
            .save("00000000-0000-4000-8000-000000000001", edited.config, null)
        assertNull(edited.parameters["jumpserver.database"])
        assertEquals("legacy 库", context.parameters["jumpserver.database"])
        native.values["database"] = "new_native"
        params.filterIsInstance<DatabaseAuthProviderParam.TransientParam>().forEach { it.toUrl(null, native.holder) }
        assertEquals("new_native", native.values["database"])
        edited.url = "jdbc:mysql://unused.example.com:3306/new_native?sslMode=VERIFY_IDENTITY"
        JumpServerAuthProvider({ runtime() }, { transport() }).interceptConnection(edited.proto, false)
        assertEquals("jdbc:mysql://mysql.example.com:33061/new_native?sslMode=VERIFY_IDENTITY", edited.url)
    }

    @Test fun revertingTheNativeEditorRearmsMigrationWithoutOverridingLaterNativeEdits() {
        val original = TestProto()
        original.parameters["jumpserver.database"] = "orders"
        val params = JumpServerAuthProvider({ runtime() }, { transport() }).getAuthParams(original.config)
        val asset = params.first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam
        val migrations = params.filterIsInstance<DatabaseAuthProviderParam.TransientParam>()
        val native = NativeParameters(mutableMapOf("database" to "fixture"))
        migrations.forEach { it.toUrl(null, native.holder) }
        assertEquals("orders", native.values["database"])
        // Native Widget.reset reloads controls from the saved source as well as resetting the URL.
        asset.load(original.config, null)
        native.values["database"] = "fixture"
        migrations.forEach { it.toUrl(null, native.holder) }
        assertEquals("orders", native.values["database"])
        // Explicitly choosing the original native database after migration is still allowed.
        native.values["database"] = "fixture"
        migrations.forEach { it.toUrl(null, native.holder) }
        assertEquals("fixture", native.values["database"])
        val target = TestProto()
        target.parameters["jumpserver.database"] = "orders"
        asset.save("00000000-0000-4000-8000-000000000001", target.config, null)
        assertNull(target.parameters["jumpserver.database"])
        assertEquals("orders", original.parameters["jumpserver.database"])
    }

    @Test fun emptyTemporaryAuthAccumulatorDoesNotDiscardPendingLegacyMigration() {
        val original = TestProto()
        original.parameters["jumpserver.database"] = "orders"
        val params = JumpServerAuthProvider({ runtime() }, { transport() }).getAuthParams(original.config)
        val temporary = TestProto().also { it.url = ""; it.parameters.clear() }
        (params.first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam).load(temporary.config, null)
        val native = NativeParameters(mutableMapOf("database" to "fixture"))
        params.filterIsInstance<DatabaseAuthProviderParam.TransientParam>().forEach { it.toUrl(null, native.holder) }
        assertEquals("orders", native.values["database"])
        assertEquals("orders", original.parameters["jumpserver.database"])
    }

    @Test fun nativeDatabaseEditBeforeMigrationIsRespected() {
        val context = TestProto()
        context.parameters["jumpserver.database"] = "legacy"
        val params = JumpServerAuthProvider({ runtime() }, { transport() }).getAuthParams(context.config)
        val native = NativeParameters(mutableMapOf("database" to "user_chosen"))
        params.filterIsInstance<DatabaseAuthProviderParam.TransientParam>().forEach { it.toUrl(null, native.holder) }
        assertEquals("user_chosen", native.values["database"])
        assertEquals("legacy", context.parameters["jumpserver.database"])
        val edited = TestProto()
        edited.parameters["jumpserver.database"] = "legacy"
        (params.first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam)
            .save("00000000-0000-4000-8000-000000000001", edited.config, null)
        assertNull(edited.parameters["jumpserver.database"])
    }

    @Test fun freshConnectionsDoNotExposeADuplicateDatabaseOverride() {
        val params = JumpServerAuthProvider({ runtime() }, { transport() }).getAuthParams(TestProto().config)
        assertFalse(params.any { it.id == "jumpserver.database" })
    }

    @Test fun asynchronouslyLoadedNamesRemainVisibleAndResolveToStableIds() {
        val pending = java.util.ArrayDeque<Runnable>()
        val provider = JumpServerAuthProvider({ runtime() }, { catalogTransport() }, java.util.concurrent.Executor { pending.add(it) })
        val context = TestProto()
        val asset = provider.getAuthParams(context.config).first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam
        val choices = asset.completion!!.getItems(context.config) { fail<Unit>(it) }
        assertTrue(choices.isEmpty())
        while (pending.isNotEmpty()) pending.remove().run()
        assertEquals(listOf("Fixture MySQL", "Same [00000000-0000-4000-8000-000000000002]", "Same [00000000-0000-4000-8000-000000000003]"), choices)
        asset.save("Same", context.config, null)
        assertThrows(ApiFailure::class.java) { AssetCompletion.assetId(context.parameters["jumpserver.assetId"].orEmpty()) }
        asset.save("Same [00000000-0000-4000-8000-000000000003]", context.config, null)
        assertEquals("00000000-0000-4000-8000-000000000003", context.parameters["jumpserver.assetId"])
        assertEquals("Same", context.parameters["jumpserver.assetName"])
    }

    @Test fun reusingAnOldAssetNameRequiresAnUnambiguousChoiceWithoutRebindingUntouchedSources() {
        val pending = java.util.ArrayDeque<Runnable>()
        val original = TestProto()
        original.parameters["jumpserver.assetName"] = "Orders"
        val source = catalogTransport()
        val provider = JumpServerAuthProvider({ runtime() }, { HttpTransport { method, uri, headers, body ->
            val response = source.execute(method, uri, headers, body)
            HttpResponse(response.status, response.body.toString(Charsets.UTF_8)
                .replace("Fixture MySQL", "Archive")
                .replace("\"name\":\"Same\",\"type\":\"mysql\"}", "\"name\":\"Orders\",\"type\":\"mysql\"}", true)
                .replace("000000000003\",\"name\":\"Orders", "000000000003\",\"name\":\"Other")
                .toByteArray())
        } }, java.util.concurrent.Executor { pending.add(it) })
        val asset = provider.getAuthParams(original.config).first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam
        val choices = asset.completion!!.getItems(original.config) { fail<Unit>(it) }
        while (pending.isNotEmpty()) pending.remove().run()
        asset.save("Orders", original.config, null)
        assertEquals("00000000-0000-4000-8000-000000000001", original.parameters["jumpserver.assetId"])
        asset.save(choices.single { it.startsWith("Orders") }, original.config, null)
        assertEquals("00000000-0000-4000-8000-000000000002", original.parameters["jumpserver.assetId"])
    }

    @Test fun collidingCompletionLabelsNeverHideOrRebindDistinctAssets() {
        val first = "00000000-0000-4000-8000-000000000001"
        val second = "00000000-0000-4000-8000-000000000002"
        val third = "00000000-0000-4000-8000-000000000003"
        val fourth = "00000000-0000-4000-8000-000000000004"
        val expected = linkedMapOf(first to "Same", second to "Same",
            third to "Same [$first]", fourth to "Same [$first] [$first]")
        for (entries in listOf(expected.entries.toList(), expected.entries.reversed())) {
            val pending = java.util.ArrayDeque<Runnable>()
            val context = TestProto()
            val catalog = entries.joinToString(prefix = "[", postfix = "]") { (id, name) ->
                """{"id":"$id","name":"$name","type":"mysql"}"""
            }
            val provider = JumpServerAuthProvider({ runtime() }, { catalogTransport(catalog) }, java.util.concurrent.Executor { pending.add(it) })
            val asset = provider.getAuthParams(context.config).first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam
            val choices = asset.completion!!.getItems(context.config) { fail<Unit>(it) }
            while (pending.isNotEmpty()) pending.remove().run()
            assertEquals(4, choices.size, "Every distinct asset must remain selectable")
            assertEquals(4, choices.toSet().size, "Labels must be unique even after repeated collisions")
            val saved = choices.associate { label ->
                val edited = TestProto()
                asset.save(label, edited.config, null)
                assertEquals(label, asset.load(edited.config, null), "A selected label must round-trip to the same asset")
                edited.parameters["jumpserver.assetId"] to edited.parameters["jumpserver.assetName"]
            }
            assertEquals(expected, saved, "All four unique UUIDs must retain their own asset names")
        }
    }

    @Test fun generatedLabelsCannotShadowTheNameOfAnUntouchedSavedAsset() {
        val first = "00000000-0000-4000-8000-000000000001"
        val second = "00000000-0000-4000-8000-000000000002"
        val original = "00000000-0000-4000-8000-000000000004"
        val context = TestProto().also {
            it.parameters["jumpserver.assetId"] = original
            it.parameters["jumpserver.assetName"] = "Same [$first]"
        }
        val pending = java.util.ArrayDeque<Runnable>()
        val catalog = """[
          {"id":"$first","name":"Same","type":"mysql"},
          {"id":"$second","name":"Same","type":"mysql"}
        ]"""
        val provider = JumpServerAuthProvider({ runtime() }, { catalogTransport(catalog) }, java.util.concurrent.Executor { pending.add(it) })
        val asset = provider.getAuthParams(context.config).first { it.id == "jumpserver.assetId" } as DatabaseAuthProviderParam.TextParam
        val choices = asset.completion!!.getItems(context.config) { fail<Unit>(it) }
        while (pending.isNotEmpty()) pending.remove().run()
        assertFalse("Same [$first]" in choices, "A new choice must not resolve to the untouched saved asset")
        val selected = choices.map { label ->
            val edited = TestProto()
            asset.save(label, edited.config, null)
            edited.parameters["jumpserver.assetId"]
        }.toSet()
        assertEquals(setOf(first, second), selected)
        asset.save("Same [$first]", context.config, null)
        assertEquals(original, context.parameters["jumpserver.assetId"], "An untouched saved name must keep its original UUID")
    }

    private fun catalogTransport(assets: String = """[
      {"id":"00000000-0000-4000-8000-000000000001","name":"Fixture MySQL","type":"mysql"},
      {"id":"00000000-0000-4000-8000-000000000002","name":"Same","type":"mysql"},
      {"id":"00000000-0000-4000-8000-000000000003","name":"Same","type":"mysql"}
    ]""") = HttpTransport { _, uri, _, _ ->
        if (uri.path.endsWith("/assets/")) HttpResponse(200, assets.toByteArray())
        else HttpResponse(200, """{"id":"${uri.path.trimEnd('/').substringAfterLast('/')}","permed_protocols":[{"name":"mysql"}],"permed_accounts":[{"alias":"fixture-readonly","name":"Read only","has_secret":true,"has_username":true}]}""".toByteArray())
    }

    @Test fun detectingAuroraKeepsJumpServerAuthenticationAndFreshCredentialsOnReconnect() = runBlocking {
        val provider = JumpServerAuthProvider({ runtime() }, { transport() })
        val context = TestProto()
        assertTrue(provider.interceptConnection(context.proto, false))
        val firstUser = context.properties["user"]
        context.dbms = Dbms.MYSQL_AURORA
        assertEquals(DatabaseAuthProvider.ApplicabilityLevel.Result.APPLICABLE,
            provider.getApplicability(context.config, DatabaseAuthProvider.ApplicabilityLevel.APPLICABLE))
        assertTrue(provider.interceptConnection(context.proto, false))
        assertEquals(2, sequence.get())
        assertNotEquals(firstUser, context.properties["user"])
        assertEquals("password-${context.properties["user"]}", context.properties["password"])
    }

    @Test fun unrelatedDatabaseEnginesNeverReadJumpServerCredentials() = runBlocking {
        val provider = JumpServerAuthProvider({ fail<RuntimeSettings>("Must not read credentials") }, { fail<HttpTransport>("Must not request API") })
        val context = TestProto().also { it.dbms = Dbms.POSTGRES }
        assertEquals(DatabaseAuthProvider.ApplicabilityLevel.Result.NOT_APPLICABLE,
            provider.getApplicability(context.config, DatabaseAuthProvider.ApplicabilityLevel.APPLICABLE))
        assertFalse(provider.interceptConnection(context.proto, false))
        assertTrue(context.properties.isEmpty())
    }

    @Test fun everyPhysicalConnectionFetchesNewGrant() = runBlocking {
        val provider = JumpServerAuthProvider({ runtime() }, { transport() })
        val first = TestProto(); val second = TestProto()
        assertTrue(provider.interceptConnection(first.proto, false))
        assertTrue(provider.interceptConnection(second.proto, false))
        assertEquals(2, sequence.get())
        assertNotEquals(first.properties["user"], second.properties["user"])
        assertEquals("password-${first.properties["user"]}", first.properties["password"])
        assertEquals("jdbc:mysql://mysql.example.com:33061/fixture?sslMode=VERIFY_IDENTITY", first.url)
    }

    @Test fun concurrentConnectionsNeverShareGrant() = runBlocking {
        val provider = JumpServerAuthProvider({ runtime() }, { transport() })
        val contexts = listOf(TestProto(), TestProto())
        contexts.map { async(Dispatchers.Default) { provider.interceptConnection(it.proto, false) } }.awaitAll()
        assertEquals(2, sequence.get())
        assertNotEquals(contexts[0].properties["user"], contexts[1].properties["user"])
        for (context in contexts) assertEquals("password-${context.properties["user"]}", context.properties["password"])
    }

    @Test fun otherAuthProvidersRemainUntouched() = runBlocking {
        val provider = JumpServerAuthProvider({ fail<RuntimeSettings>("Must not read credentials") }, { fail<HttpTransport>("Must not request API") })
        val context = TestProto("other")
        val oldUrl = context.url
        assertFalse(provider.interceptConnection(context.proto, false))
        assertEquals(oldUrl, context.url)
        assertTrue(context.properties.isEmpty())
    }

    @Test fun cancellationStopsConnection() = runBlocking {
        val entered = CountDownLatch(1)
        val provider = JumpServerAuthProvider({ runtime() }, { HttpTransport { _, _, _, _ ->
            entered.countDown()
            CountDownLatch(1).await()
            error("Cancelled request must not complete")
        } })
        val context = TestProto()
        val job = launch(Dispatchers.Default) { provider.interceptConnection(context.proto, false) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        withTimeout(3000) { job.cancelAndJoin() }
        assertTrue(context.properties.isEmpty())
        assertEquals("jdbc:mysql://unused.example.com:3306/fixture?sslMode=VERIFY_IDENTITY", context.url)
    }

    @Test fun settingsChangeDoesNotMixCredentials() = runBlocking {
        val snapshots = AtomicInteger()
        val headers = mutableListOf<String>()
        val underlying = transport()
        val provider = JumpServerAuthProvider({ runtime("fixture-key-${snapshots.incrementAndGet()}") }, { HttpTransport { method, uri, values, body ->
            headers.add(values.getValue("Authorization"))
            underlying.execute(method, uri, values, body)
        } })
        provider.interceptConnection(TestProto().proto, false)
        assertEquals(1, snapshots.get())
        assertEquals(2, headers.size)
        assertTrue(headers.all { it.contains("keyId=\"fixture-key-1\"") })
    }

    @Test fun persistentDatasourceContainsNoGrantOrApiSecret() = runBlocking {
        val provider = JumpServerAuthProvider({ runtime() }, { transport() })
        val context = TestProto()
        val before = context.parameters.toMap()
        provider.interceptConnection(context.proto, false)
        assertEquals(before, context.parameters)
        assertFalse(context.properties.values.contains("fixture-secret"))
        assertFalse(context.url.contains("password"))
    }
}

private class TestProto(authId: String = "jumpserver") {
    var dbms = Dbms.MYSQL
    var url = "jdbc:mysql://unused.example.com:3306/fixture?sslMode=VERIFY_IDENTITY"
    val properties = linkedMapOf<String, String>()
    val parameters = linkedMapOf("jumpserver.assetId" to "00000000-0000-4000-8000-000000000001", "jumpserver.accountAlias" to "fixture-readonly", "jumpserver.database" to "")
    val config = Proxy.newProxyInstance(DatabaseConnectionConfig::class.java.classLoader, arrayOf(DatabaseConnectionConfig::class.java)) { _, method, args ->
        when (method.name) {
            "getAuthProviderId" -> authId
            "getDbms" -> dbms
            "getAdditionalProperty" -> parameters[args!![0]]
            "setAdditionalProperty" -> { if (args!![1] == null) parameters.remove(args[0] as String) else parameters[args[0] as String] = args[1] as String; null }
            "getUrl" -> url
            "getAdditionalPropertiesNames" -> parameters.keys
            "toString" -> "TestConfig"
            else -> null
        }
    } as DatabaseConnectionConfig
    val proto = Proxy.newProxyInstance(DatabaseConnectionInterceptor.ProtoConnection::class.java.classLoader, arrayOf(DatabaseConnectionInterceptor.ProtoConnection::class.java)) { _, method, args ->
        when (method.name) {
            "getConnectionPoint" -> config
            "getUrl" -> url
            "setUrl" -> { url = args!![0] as String; null }
            "getConnectionProperties" -> properties
            "toString" -> "TestProto"
            else -> null
        }
    } as DatabaseConnectionInterceptor.ProtoConnection
}

private class NativeParameters(val values: MutableMap<String, String>) {
    val holder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MutableParametersHolder::class.java)) { _, method, args ->
        when (method.name) {
            "getParameter" -> values[args!![0]]
            "setParameter" -> { if (args!![1] == null) values.remove(args[0] as String) else values[args[0] as String] = args[1] as String; null }
            "getParameterNames" -> values.keys
            else -> null
        }
    } as MutableParametersHolder
}
