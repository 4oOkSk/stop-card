package io.github.user4ooksk.stopcard.model

import io.github.user4ooksk.stopcard.policy.AppKey

/**
 * What the user decided for one app.
 *
 * [BLOCK] is the global rule: the app may run while a recent-task card exists. It is not a
 * per-app setting, so most apps have no row at all. An [ALLOW] row is a user exception.
 */
enum class RuleMode { BLOCK, ALLOW }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppRule(
    val packageName: String,
    val mode: RuleMode,
    val identity: AppKey? = null,
) {
    val exempt: Boolean get() = mode == RuleMode.ALLOW && identity != null
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val identity: AppKey? = null,
)

data class AppSettings(
    val onboarded: Boolean = false,
    val protectionEnabled: Boolean = true,
    /** Log decisions without rejecting or stopping anything. */
    val observeOnly: Boolean = true,
    /** Race window between losing the last card and the re-check. */
    val reviewMs: Long = 500,
    /** Review delay for a hidden app without a card. Active flows use completion conditions. */
    val recentVisibleSeconds: Int = 30,
    /** Optional cached process priority adjustment. Disabled by default. */
    val extraKeepAlive: Boolean = false,
    val showSystemApps: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
)

/** Why the module recorded an event. */
enum class StopReason(val code: String) {
    /** The last recent-task card for the app disappeared. */
    CARD_REMOVED("card_removed"),

    /** A visible app stopped being visible, had no card, and its review window expired. */
    LAST_VISIBLE("last_visible"),

    /** The user asked to stop it now. */
    MANUAL("manual"),

    FLOW_FINISHED("flow_finished"),
    UNLICENSED_RUNNING("unlicensed_running"),

    /** Observe mode: the module would have stopped it. */
    OBSERVE("observe"),

    /** A task or policy read failed. */
    ERROR("error"),

    /** A card removal is in the short review window. */
    REVIEW("review"),

    /** A stop was blocked by shared-UID or system protection. */
    BLOCKED("blocked"),

    /** A pending stop was cancelled by a new card, pause or exception. */
    CANCELED("canceled");

    companion object {
        fun of(code: String): StopReason = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

/** Events that represent an actual force-stop, not a review or a cancelled decision. */
val StopReason.isStop: Boolean
    get() = when (this) {
        StopReason.CARD_REMOVED, StopReason.LAST_VISIBLE, StopReason.MANUAL,
        StopReason.FLOW_FINISHED, StopReason.UNLICENSED_RUNNING -> true
        else -> false
    }

data class StopEvent(
    val id: Long,
    val time: Long,
    val packageName: String,
    val reason: StopReason,
    val detail: String = "",
)

/** One app as the module currently sees it. */
data class RunningApp(
    val packageName: String,
    val cards: Int = 0,
    val visible: Boolean = false,
    val stopped: Boolean = false,
    /** Milliseconds until the pending stop, or -1. */
    val stopInMs: Long = -1,
    val reason: String? = null,
    val state: String = "managed",
    val running: Boolean = false,
)
