package io.github.user4ooksk.stopcard.module

import io.github.user4ooksk.stopcard.model.RunningApp
import io.github.user4ooksk.stopcard.policy.AppKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * File contract between the app and the LSPosed module.
 *
 * The module runs in system_server and writes [STATE] and [EVENTS] as the system user. The app is
 * the only writer of [CONFIG], [PAUSE] and [COMMAND_DIR], and does so through KernelSU/root. The
 * directory is root/system owned; it is not a general-purpose IPC surface.
 */
object ModuleProtocol {
    const val VERSION = 2
    const val DIR = "/data/system/stop_card"
    const val CONFIG = "$DIR/config.json"
    const val STATE = "$DIR/state.json"
    const val EVENTS = "$DIR/events.jsonl"
    const val PAUSE = "$DIR/paused"
    const val COMMAND_DIR = "$DIR/commands"
    const val EMERGENCY_DISABLED = "$DIR/emergency_disabled"
    const val RECOVERY_STATE = "$DIR/recovery.json"

    fun commandPath(id: Long): String = "$COMMAND_DIR/$id.json"

    /** Generation order comes from the persisted control document, never from the wall clock. */
    fun nextConfig(previous: Config?, diskGeneration: Long, confirmed: Long, desired: Config): Config {
        if (previous != null && previous.copy(configGeneration = 0) == desired.copy(configGeneration = 0) && diskGeneration >= confirmed) return previous
        require(diskGeneration <= desired.configGeneration && confirmed <= desired.configGeneration) { "系统配置已更新，请先重新载入系统配置" }
        val latest = maxOf(0, diskGeneration, confirmed, desired.configGeneration)
        require(latest < Long.MAX_VALUE) { "Configuration generation exhausted" }
        return desired.copy(configGeneration = latest + 1)
    }

    fun stateIssue(state: ModuleState, boot: String?, elapsed: Long, version: Int): String? = when {
        boot.isNullOrBlank() || state.bootId != boot -> "different_boot"
        state.heartbeatElapsed <= 0 || elapsed - state.heartbeatElapsed !in 0..15_000 -> "stale_heartbeat"
        state.versionCode != version || state.uid != 1000 || state.pid <= 0 -> "different_agent"
        else -> null
    }

    data class Config(
        val enabled: Boolean = false,
        val observeOnly: Boolean = true,
        val reviewMs: Long = 500,
        val recentVisibleMs: Long = 30_000,
        val extraKeepAlive: Boolean = false,
        val userId: Int = 0,
        val configGeneration: Long = 0,
        val exceptions: Set<String> = emptySet(),
        val essential: Set<String> = emptySet(),
        val selfPackage: String = "",
        val exceptionIdentities: Set<AppKey> = emptySet(),
    ) {
        fun toJson(): String = JSONObject().apply {
            put("protocol", VERSION)
            put("enabled", enabled)
            put("observeOnly", observeOnly)
            put("reviewMs", reviewMs)
            put("recentVisibleMs", recentVisibleMs)
            put("extraKeepAlive", extraKeepAlive)
            put("userId", userId)
            put("configGeneration", configGeneration)
            put("exceptions", JSONArray(exceptions.toList()))
            put("essential", JSONArray(essential.toList()))
            put("selfPackage", selfPackage)
            put("exceptionIdentities", JSONArray().apply { exceptionIdentities.forEach { put(it.toJson()) } })
        }.toString()

        companion object {
            fun fromJson(text: String): Config? = runCatching {
                val o = JSONObject(text)
                if (o.get("protocol") !is Int || o.getInt("protocol") != VERSION) return null
                if (o.get("enabled") !is Boolean) return null
                if (o.get("observeOnly") !is Boolean) return null
                if (o.get("reviewMs") !is Int && o.get("reviewMs") !is Long) return null
                if (o.get("recentVisibleMs") !is Int && o.get("recentVisibleMs") !is Long) return null
                if (o.get("extraKeepAlive") !is Boolean) return null
                if (o.get("userId") !is Int || o.getInt("userId") != 0) return null
                val generation = o.get("configGeneration")
                if ((generation !is Int && generation !is Long) || o.getLong("configGeneration") <= 0) return null
                if (o.get("selfPackage") !is String) return null
                if (o.get("exceptions") !is JSONArray || o.get("essential") !is JSONArray) return null
                val exceptions = o.getJSONArray("exceptions").strictStringSet() ?: return null
                val essential = o.getJSONArray("essential").strictStringSet() ?: return null
                val selfPackage = o.getString("selfPackage")
                if (exceptions.size > 2_000 || essential.size > 4_000) return null
                if (!PACKAGE_NAME.matches(selfPackage)) return null
                if ((exceptions + essential).any { !PACKAGE_NAME.matches(it) }) return null
                val identities = o.getJSONArray("exceptionIdentities").let { array ->
                    require(array.length() <= 2_000)
                    (0 until array.length()).map { appKey(array.getJSONObject(it)) }.toSet()
                }
                if (identities.any { it.userId != 0 || it.packageName !in exceptions }) return null
                Config(
                    enabled = o.getBoolean("enabled"),
                    observeOnly = o.getBoolean("observeOnly"),
                    reviewMs = o.getLong("reviewMs").coerceIn(200, 30_000),
                    recentVisibleMs = o.getLong("recentVisibleMs").coerceIn(0, 10 * 60_000),
                    extraKeepAlive = o.getBoolean("extraKeepAlive"),
                    userId = 0,
                    configGeneration = o.getLong("configGeneration"),
                    exceptions = exceptions,
                    essential = essential,
                    selfPackage = selfPackage,
                    exceptionIdentities = identities,
                )
            }.getOrNull()
        }
    }

    data class Card(
        val taskId: Int,
        val packageName: String,
        val visible: Boolean,
        val excluded: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("taskId", taskId)
            put("packageName", packageName)
            put("visible", visible)
            put("excluded", excluded)
        }
    }

    data class ModuleState(
        val protocol: Int = VERSION,
        val versionCode: Int = 0,
        val heartbeat: Long = 0,
        val bootEpoch: Long = 0,
        val startedAt: Long = 0,
        val taskSource: String = "unknown",
        val configGeneration: Long = 0,
        val configLoaded: Boolean = false,
        val appliedGeneration: Long = -1,
        val observeOnly: Boolean = false,
        val paused: Boolean = false,
        val lastError: String? = null,
        val cards: List<Card> = emptyList(),
        val apps: List<RunningApp> = emptyList(),
        val stops: Int = 0,
        val pid: Int = 0,
        val uid: Int = 0,
        val sdk: Int = 0,
        val cardControl: String = "active",
        val notificationControl: String = "unavailable",
        val wakeControl: String = "unavailable",
        val ready: Boolean = false,
        val bootId: String = "",
        val heartbeatElapsed: Long = 0,
        val hooks: Map<String, String> = emptyMap(),
        val essentialReasons: Map<String, Set<String>> = emptyMap(),
        val flows: List<String> = emptyList(),
        val recovery: String = "unknown",
        val commandResults: Map<String, String> = emptyMap(),
        val extraKeepAlive: Boolean = false,
        val diagnosticDetails: Map<String, String> = emptyMap(),
    ) {
        /** Keep one atomic status document below the bounded root reader's output limit. */
        fun toTransportJson(maxBytes: Int = 192 * 1024): String {
            require(maxBytes in 16 * 1024..192 * 1024)
            val full = toJson()
            if (full.toByteArray(Charsets.UTF_8).size <= maxBytes) return full
            val totals = "apps=${apps.size}; cards=${cards.size}; flows=${flows.size}; roles=${essentialReasons.size}"
            var compact = copy(
                lastError = lastError?.take(256),
                hooks = hooks.entries.take(64).associate { it.key to it.value.take(128) },
                diagnosticDetails = diagnosticDetails.entries.take(8).associate { it.key to it.value.take(256) } +
                    ("truncated_state" to "Lists and method details shortened; $totals"),
                commandResults = commandResults.entries.toList().takeLast(4).associate { it.key to it.value.take(512) },
            )
            while (true) {
                val text = compact.toJson()
                if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
                if (compact.apps.isEmpty() && compact.cards.isEmpty() && compact.flows.isEmpty() && compact.essentialReasons.isEmpty()) {
                    return compact.copy(hooks = emptyMap(), commandResults = emptyMap(),
                        diagnosticDetails = mapOf("truncated_state" to totals)).toJson().also {
                        check(it.toByteArray(Charsets.UTF_8).size <= maxBytes) { "Status header exceeds transport limit" }
                    }
                }
                compact = compact.copy(
                    apps = compact.apps.take(compact.apps.size / 2),
                    cards = compact.cards.take(compact.cards.size / 2),
                    flows = compact.flows.take(compact.flows.size / 2),
                    essentialReasons = compact.essentialReasons.entries.take(compact.essentialReasons.size / 2).associate { it.key to it.value },
                )
            }
        }

        fun toJson(): String = JSONObject().apply {
            put("protocol", protocol)
            put("versionCode", versionCode)
            put("heartbeat", heartbeat)
            put("bootEpoch", bootEpoch)
            put("startedAt", startedAt)
            put("taskSource", taskSource)
            put("configGeneration", configGeneration)
            put("appliedGeneration", appliedGeneration)
            put("configLoaded", configLoaded)
            put("observeOnly", observeOnly)
            put("paused", paused)
            lastError?.let { put("lastError", it) }
            put("capabilities", JSONObject().apply {
                put("cards", cardControl)
                put("notifications", notificationControl)
                put("wakeups", wakeControl)
            })
            put("cards", JSONArray().apply { cards.forEach { put(it.toJson()) } })
            put("apps", JSONArray().apply {
                apps.forEach { app ->
                    put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("cards", app.cards)
                        put("visible", app.visible)
                        put("stopped", app.stopped)
                        put("stopInMs", app.stopInMs)
                        app.reason?.let { put("reason", it) }
                        put("state", app.state)
                        put("recentlyVisible", app.recentlyVisible)
                        put("running", app.running)
                    })
                }
            })
            put("stops", stops)
            put("pid", pid)
            put("uid", uid)
            put("sdk", sdk)
            put("ready", ready)
            put("bootId", bootId)
            put("heartbeatElapsed", heartbeatElapsed)
            put("hooks", JSONObject(hooks))
            put("essentialReasons", JSONObject().apply { essentialReasons.forEach { (pkg, reasons) -> put(pkg, JSONArray(reasons.toList())) } })
            put("flows", JSONArray(flows))
            put("recovery", recovery)
            put("commandResults", JSONObject(commandResults))
            put("extraKeepAlive", extraKeepAlive)
            put("diagnosticDetails", JSONObject(diagnosticDetails))
        }.toString()

        companion object {
            fun fromJson(text: String): ModuleState? = runCatching {
                val o = JSONObject(text)
                if (o.optInt("protocol", -1) != VERSION) return null
                val cards = o.optJSONArray("cards").toCards()
                val apps = o.optJSONArray("apps").toRunningApps()
                ModuleState(
                    protocol = o.optInt("protocol", VERSION),
                    versionCode = o.optInt("versionCode", 0),
                    heartbeat = o.optLong("heartbeat", 0),
                    bootEpoch = o.optLong("bootEpoch", 0),
                    startedAt = o.optLong("startedAt", 0),
                    taskSource = o.optString("taskSource", "unknown"),
                    configGeneration = o.optLong("configGeneration", 0),
                    appliedGeneration = o.optLong("appliedGeneration", -1),
                    configLoaded = o.optBoolean("configLoaded", false),
                    observeOnly = o.optBoolean("observeOnly", false),
                    paused = o.optBoolean("paused", false),
                    lastError = o.optString("lastError").takeIf { it.isNotBlank() },
                    cards = cards,
                    apps = apps,
                    stops = o.optInt("stops", 0),
                    pid = o.optInt("pid", 0),
                    uid = o.optInt("uid", 0),
                    sdk = o.optInt("sdk", 0),
                    cardControl = o.optJSONObject("capabilities")?.optString("cards", "active") ?: "active",
                    notificationControl = o.optJSONObject("capabilities")?.optString("notifications", "unavailable") ?: "unavailable",
                    wakeControl = o.optJSONObject("capabilities")?.optString("wakeups", "unavailable") ?: "unavailable",
                    ready = o.optBoolean("ready", false),
                    bootId = o.optString("bootId", ""),
                    heartbeatElapsed = o.optLong("heartbeatElapsed", 0),
                    hooks = o.optJSONObject("hooks").stringMap(),
                    essentialReasons = o.optJSONObject("essentialReasons")?.let { reasons -> reasons.keys().asSequence().associateWith { reasons.getJSONArray(it).strictStringSet().orEmpty() } }.orEmpty(),
                    flows = o.optJSONArray("flows")?.strictStringList().orEmpty(),
                    recovery = o.optString("recovery", "unknown"),
                    commandResults = o.optJSONObject("commandResults").stringMap(),
                    extraKeepAlive = o.optBoolean("extraKeepAlive", false),
                    diagnosticDetails = o.optJSONObject("diagnosticDetails").stringMap(),
                )
            }.getOrNull()
        }
    }

    data class Event(
        val id: Long,
        val time: Long,
        val packageName: String,
        val reason: String,
        val detail: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("time", time)
            put("pkg", packageName)
            put("reason", reason)
            put("detail", detail)
        }

        companion object {
            fun listFromJsonLines(text: String): List<Event> = text.lineSequence()
                .mapNotNull { line -> runCatching { fromJson(JSONObject(line)) }.getOrNull() }
                .toList()

            fun fromJson(o: JSONObject): Event = Event(
                id = o.getLong("id"),
                time = o.getLong("time"),
                packageName = o.getString("pkg"),
                reason = o.optString("reason", "error"),
                detail = o.optString("detail", ""),
            )
        }
    }

    data class Command(
        val id: Long = 0,
        val configGeneration: Long = 0,
        val expiresAt: Long = 0,
        val stop: List<String> = emptyList(),
        val stopAll: Boolean = false,
        val clearLog: Boolean = false,
        val identities: Set<AppKey> = emptySet(),
    ) {
        fun toJson(): String = JSONObject().apply {
            put("protocol", VERSION)
            put("id", id)
            put("configGeneration", configGeneration)
            put("expiresAt", expiresAt)
            put("stop", JSONArray(stop))
            put("stopAll", stopAll)
            put("clearLog", clearLog)
            put("identities", JSONArray().apply { identities.forEach { put(it.toJson()) } })
        }.toString()

        companion object {
            fun fromJson(text: String): Command? = runCatching {
                val o = JSONObject(text)
                if (o.get("protocol") !is Int || o.getInt("protocol") != VERSION) return null
                if (o.get("id") !is Int && o.get("id") !is Long) return null
                if (o.getLong("id") <= 0) return null
                val generation = o.get("configGeneration")
                if ((generation !is Int && generation !is Long) || o.getLong("configGeneration") <= 0) return null
                val expiry = o.get("expiresAt")
                if ((expiry !is Int && expiry !is Long) || o.getLong("expiresAt") <= 0) return null
                if (o.get("stopAll") !is Boolean) return null
                if (o.get("stop") !is JSONArray) return null
                val stop = o.getJSONArray("stop").strictStringList() ?: return null
                if (stop.size > 512 || stop.any { !PACKAGE_NAME.matches(it) }) return null
                val identities = o.getJSONArray("identities").let { array ->
                    require(array.length() <= 512)
                    (0 until array.length()).map { appKey(array.getJSONObject(it)) }.toSet()
                }
                if (identities.map { it.packageName }.toSet().size != identities.size ||
                    stop.any { pkg -> identities.none { it.packageName == pkg } }) return null
                if (o.get("clearLog") !is Boolean) return null
                if (o.getBoolean("clearLog") && (stop.isNotEmpty() || o.getBoolean("stopAll"))) return null
                Command(
                    id = o.getLong("id"),
                    configGeneration = o.getLong("configGeneration"),
                    expiresAt = o.getLong("expiresAt"),
                    stop = stop,
                    stopAll = o.getBoolean("stopAll"),
                    clearLog = o.getBoolean("clearLog"),
                    identities = identities,
                )
            }.getOrNull()
        }
    }

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

    fun AppKey.toJson(): JSONObject = JSONObject().apply {
        put("userId", userId); put("packageName", packageName); put("uid", uid)
        put("installedAt", installedAt); put("signer", signer)
    }

    private fun appKey(o: JSONObject): AppKey {
        require(o.get("userId") is Int && o.getInt("userId") == 0)
        require(o.get("uid") is Int && o.getInt("uid") >= 10000)
        require(o.get("installedAt") is Long || o.get("installedAt") is Int)
        require(o.getLong("installedAt") > 0)
        require(o.get("packageName") is String && PACKAGE_NAME.matches(o.getString("packageName")))
        require(o.get("signer") is String && o.getString("signer").matches(Regex("[a-f0-9]{64}(:[a-f0-9]{64})*")))
        return AppKey(0, o.getString("packageName"), o.getInt("uid"), o.getLong("installedAt"), o.getString("signer"))
    }

    private fun JSONObject?.stringMap(): Map<String, String> = this?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()

    private fun JSONArray.strictStringSet(): Set<String>? {
        val result = HashSet<String>(length())
        for (index in 0 until length()) {
            val value = opt(index)
            if (value !is String) return null
            result += value
        }
        return result
    }

    private fun JSONArray.strictStringList(): List<String>? {
        val result = ArrayList<String>(length())
        for (index in 0 until length()) {
            val value = opt(index)
            if (value !is String) return null
            result += value
        }
        return result
    }

    private fun JSONArray?.toCards(): List<Card> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            runCatching {
                val o = getJSONObject(index)
                Card(
                    taskId = o.getInt("taskId"),
                    packageName = o.getString("packageName"),
                    visible = o.optBoolean("visible", false),
                    excluded = o.optBoolean("excluded", false),
                )
            }.getOrNull()
        }
    }

    private fun JSONArray?.toRunningApps(): List<RunningApp> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            runCatching {
                val o = getJSONObject(index)
                RunningApp(
                    packageName = o.getString("packageName"),
                    cards = o.optInt("cards", 0),
                    visible = o.optBoolean("visible", false),
                    stopped = o.optBoolean("stopped", false),
                    stopInMs = o.optLong("stopInMs", -1),
                    reason = o.optString("reason").takeIf { it.isNotBlank() },
                    state = o.optString("state", "managed"),
                    recentlyVisible = o.optBoolean("recentlyVisible", false),
                    running = o.optBoolean("running", false),
                )
            }.getOrNull()
        }
    }
}
