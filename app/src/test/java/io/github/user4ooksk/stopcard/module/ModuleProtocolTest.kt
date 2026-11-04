package io.github.user4ooksk.stopcard.module

import io.github.user4ooksk.stopcard.model.RunningApp
import io.github.user4ooksk.stopcard.model.StopReason
import io.github.user4ooksk.stopcard.policy.AppKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleProtocolTest {

    @Test
    fun `large status retains health metadata and explicitly marks omitted details`() {
        val state = ModuleProtocol.ModuleState(
            versionCode = 2, bootId = "boot", heartbeatElapsed = 100, pid = 10, uid = 1000,
            configGeneration = 7, configLoaded = true, ready = true, observeOnly = true,
            apps = (0..511).map { RunningApp("example." + "a".repeat(180) + it, state = "operation") },
            diagnosticDetails = (0..100).associate { "method$it" to "细节".repeat(4096) },
            flows = (0..127).map { "flow$it:" + "参与者".repeat(1000) },
        )
        val text = state.toTransportJson(32 * 1024)
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        val parsed = ModuleProtocol.ModuleState.fromJson(text)!!
        assertEquals("boot", parsed.bootId)
        assertEquals(7L, parsed.configGeneration)
        assertTrue(parsed.ready && parsed.observeOnly)
        assertTrue(parsed.diagnosticDetails.containsKey("truncated_state"))
    }

    @Test
    fun `config round-trips`() {
        val config = ModuleProtocol.Config(
            enabled = true,
            observeOnly = false,
            reviewMs = 1_500,
            recentVisibleMs = 30_000,
            userId = 0,
            configGeneration = 42,
            selfPackage = "io.github.user4ooksk.stopcard",
        )
        val parsed = ModuleProtocol.Config.fromJson(config.toJson())
        assertEquals(config, parsed)
    }

    @Test
    fun `existing version 2 configuration loads without redundant name and role fields`() {
        val key = AppKey(0, "example.app", 10001, 10, "a".repeat(64))
        val config = config().copy(exceptionIdentities = setOf(key))
        val oldDocument = JSONObject(config.toJson())
            .put("exceptions", org.json.JSONArray(listOf(key.packageName)))
            .put("essential", org.json.JSONArray(listOf("android")))
        assertEquals(config, ModuleProtocol.Config.fromJson(oldDocument.toString()))
        assertTrue(!JSONObject(config.toJson()).has("exceptions"))
        assertTrue(!JSONObject(config.toJson()).has("essential"))
    }

    @Test
    fun `state round-trips cards and app rows`() {
        val state = ModuleProtocol.ModuleState(
            versionCode = 7,
            heartbeat = 123,
            taskSource = "ok",
            configGeneration = 5,
            observeOnly = true,
            paused = false,
            cards = listOf(ModuleProtocol.Card(3, "com.example.a", visible = true, excluded = false)),
            apps = listOf(
                RunningApp(
                    packageName = "com.example.a",
                    cards = 1,
                    visible = true,
                    stopped = false,
                    stopInMs = -1,
                    reason = null,
                    state = "card",
                ),
            ),
            stops = 2,
            pid = 1234,
            uid = 1000,
            sdk = 37,
            notificationControl = "unavailable",
        )
        val parsed = ModuleProtocol.ModuleState.fromJson(state.toJson())
        assertNotNull(parsed)
        assertEquals(1, parsed!!.cards.size)
        assertEquals("com.example.a", parsed.cards.single().packageName)
        assertEquals(1, parsed.apps.single().cards)
        assertEquals("unavailable", parsed.notificationControl)
    }

    @Test
    fun `event lines ignore a torn tail`() {
        val good = ModuleProtocol.Event(10, 1_000, "com.example.a", "stopped", "last_card_removed").toJson().toString()
        val parsed = ModuleProtocol.Event.listFromJsonLines("$good\n{\"id\":11")
        assertEquals(1, parsed.size)
        assertEquals(10, parsed.single().id)
    }

    @Test
    fun `command round-trips`() {
        val command = ModuleProtocol.Command(
            id = 9,
            configGeneration = 1,
            expiresAt = System.currentTimeMillis() + 10_000,
            stop = listOf("com.example.a"),
            identities = setOf(AppKey(0, "com.example.a", 10001, 1, "a".repeat(64))),
            stopAll = false,
        )
        assertEquals(command, ModuleProtocol.Command.fromJson(command.toJson()))
        val all = ModuleProtocol.Command(
            id = 10,
            configGeneration = 1,
            expiresAt = System.currentTimeMillis() + 10_000,
            stopAll = true,
        )
        assertTrue(ModuleProtocol.Command.fromJson(all.toJson())!!.stopAll)
    }

    @Test
    fun `runtime reason codes match the UI contract`() {
        assertEquals(StopReason.CARD_REMOVED, StopReason.of("card_removed"))
        assertEquals(StopReason.LAST_VISIBLE, StopReason.of("last_visible"))
        assertEquals(StopReason.FLOW_FINISHED, StopReason.of("flow_finished"))
        assertEquals(StopReason.UNLICENSED_RUNNING, StopReason.of("unlicensed_running"))
        assertEquals(StopReason.REVIEW, StopReason.of("review"))
        assertEquals(StopReason.BLOCKED, StopReason.of("blocked"))
        assertEquals(StopReason.CANCELED, StopReason.of("canceled"))
    }

    @Test
    fun `config rejects non-primary users and incomplete safety fields`() {
        val valid = ModuleProtocol.Config(
            enabled = true,
            selfPackage = "io.github.user4ooksk.stopcard",
            configGeneration = 1,
        )
        val nonPrimary = valid.toJson().replace("\"userId\":0", "\"userId\":10")
        assertEquals(null, ModuleProtocol.Config.fromJson(nonPrimary))
        val incomplete = """{"protocol":1,"enabled":true,"userId":0,"selfPackage":"io.github.user4ooksk.stopcard"}"""
        assertEquals(null, ModuleProtocol.Config.fromJson(incomplete))
    }

    @Test
    fun `unknown protocol is rejected`() {
        assertEquals(null, ModuleProtocol.Config.fromJson("""{"protocol":99,"enabled":true}"""))
    }

    private fun config() = ModuleProtocol.Config(enabled = true, selfPackage = "io.github.user4ooksk.stopcard", configGeneration = 10)

    @Test fun `reconnect reuses a matching durable document without advancing generation`() {
        assertEquals(config(), ModuleProtocol.nextConfig(config(), 10, 10, config().copy(configGeneration = 0)))
    }

    @Test fun `changed settings follow the latest durable generation even after clock reversal`() {
        val changed = config().copy(observeOnly = false, configGeneration = 500)
        assertEquals(501L, ModuleProtocol.nextConfig(config().copy(configGeneration = 500), 500, 300, changed).configGeneration)
    }

    @Test(expected = IllegalArgumentException::class) fun `a stale reconnect cannot overwrite a newer acknowledged configuration`() {
        ModuleProtocol.nextConfig(config(), 10, 200, config())
    }

    @Test(expected = IllegalArgumentException::class) fun `a stale local setting cannot replace a newer durable setting`() {
        ModuleProtocol.nextConfig(config().copy(configGeneration = 500), 500, 300, config().copy(observeOnly = false))
    }

    @Test fun `manual commands bind the complete installation identity`() {
        val key = AppKey(0, "example.app", 10001, 10, "a".repeat(64))
        val command = ModuleProtocol.Command(1, 10, 1000, stop = listOf(key.packageName), identities = setOf(key))
        assertEquals(command, ModuleProtocol.Command.fromJson(command.toJson()))
        assertNull(ModuleProtocol.Command.fromJson(command.copy(identities = emptySet()).toJson()))
        assertNotEquals(key, ModuleProtocol.Command.fromJson(command.copy(identities = setOf(key.copy(installedAt = 11))).toJson())!!.identities.single())
    }

    @Test fun `freshness uses monotonic time and boot identity`() {
        val state = ModuleProtocol.ModuleState(versionCode = 2, bootId = "boot", heartbeatElapsed = 1000, heartbeat = -5000, uid = 1000, pid = 42)
        assertEquals(null, ModuleProtocol.stateIssue(state, "boot", 2000, 2))
        assertEquals("different_boot", ModuleProtocol.stateIssue(state, "old", 2000, 2))
        assertEquals("stale_heartbeat", ModuleProtocol.stateIssue(state, "boot", 999, 2))
        assertEquals("stale_heartbeat", ModuleProtocol.stateIssue(state, "boot", 16001, 2))
        assertEquals("different_agent", ModuleProtocol.stateIssue(state, "boot", 2000, 3))
        assertEquals("different_agent", ModuleProtocol.stateIssue(state.copy(uid = 0), "boot", 2000, 2))
    }

    @Test fun `configuration rejects coerced numbers and malformed identity sets`() {
        for ((field, value) in listOf("protocol" to "2", "userId" to 0.5, "configGeneration" to 10.5, "enabled" to "true")) {
            val o = JSONObject(config().toJson()).put(field, value)
            assertEquals(field, null, ModuleProtocol.Config.fromJson(o.toString()))
        }
        val app = AppKey(0, "com.example.a", 10001, 10, "a".repeat(64))
        val c = config().copy(exceptionIdentities = setOf(app))
        assertEquals(c, ModuleProtocol.Config.fromJson(c.toJson()))
        assertEquals(null, ModuleProtocol.Config.fromJson(c.copy(exceptionIdentities = setOf(app, app.copy(installedAt = 11))).toJson()))
        assertEquals(null, ModuleProtocol.Config.fromJson(c.copy(exceptionIdentities = setOf(app.copy(signer = "unknown"))).toJson()))
    }

    @Test fun `log clear is exclusive and commands require valid identities and positive IDs`() {
        val c = ModuleProtocol.Command(id = 1, configGeneration = 10, expiresAt = 10000, clearLog = true)
        assertEquals(c, ModuleProtocol.Command.fromJson(c.toJson()))
        assertEquals(null, ModuleProtocol.Command.fromJson(c.copy(stopAll = true).toJson()))
        assertEquals(null, ModuleProtocol.Command.fromJson(c.copy(id = 0).toJson()))
        assertEquals(null, ModuleProtocol.Command.fromJson(c.copy(clearLog = false, stop = listOf("bad/pkg")).toJson()))
    }

    @Test fun `state preserves capability failures flow and command outcomes`() {
        val state = ModuleProtocol.ModuleState(ready = true, bootId = "boot", hooks = mapOf("provider" to "degraded:signature"),
            flows = listOf("12:com.example.a:active"), commandResults = mapOf("42" to "{\"com.example.a\":\"protected\"}"),
            essentialReasons = mapOf("com.example.keyboard" to setOf("input_method")), diagnosticDetails = mapOf("rom" to "test/rom"))
        assertEquals(state, ModuleProtocol.ModuleState.fromJson(state.toJson()))
    }
}
