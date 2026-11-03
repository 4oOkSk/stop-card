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
    val decided: Boolean,
    /** Kept for database compatibility; the card rule has no grace period. */
    val graceSeconds: Int?,
    val updatedAt: Long,
    val identity: AppKey? = null,
) {
    val blocked: Boolean get() = mode == RuleMode.BLOCK
    val exempt: Boolean get() = mode == RuleMode.ALLOW && identity != null
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
    val category: Int,
    val firstInstallTime: Long,
    val identity: AppKey? = null,
) {
    /** Apps with a launcher entry are the default management surface. */
    val isUserFacing: Boolean get() = !isSystem && isLaunchable
}

data class AppSettings(
    val onboarded: Boolean = false,
    val protectionEnabled: Boolean = true,
    /** Log decisions without rejecting or stopping anything. */
    val observeOnly: Boolean = true,
    /** Race window between losing the last card and the re-check. */
    val reviewMs: Long = 500,
    /** Review delay for a hidden app without a card. Active flows use completion conditions. */
    val recentVisibleSeconds: Int = 30,
    val clearNotifications: Boolean = true,
    /** Optional cached process priority adjustment. Disabled by default. */
    val extraKeepAlive: Boolean = false,
    val showSystemApps: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    // Legacy fields kept so the reused settings database and older screens compile.
    val graceSeconds: Int = 0,
    val allowOngoing: Boolean = false,
    val askOnInstall: Boolean = false,
    val newAppMode: RuleMode = RuleMode.BLOCK,
    val extraRestrictions: Boolean = false,
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

    /** State was rebuilt after boot; no app was stopped. */
    BOOT("boot"),

    /** A task or policy read failed. */
    ERROR("error"),

    /** A card removal is in the short review window. */
    REVIEW("review"),

    /** A stop was blocked by shared-UID or system protection. */
    BLOCKED("blocked"),

    /** A pending stop was cancelled by a new card, pause or exception. */
    CANCELED("canceled"),

    // Codes written by the upstream Stop app; kept so old rows still render.
    WOKE("woke"),
    LEFT("left"),
    RUNNING("running");

    companion object {
        fun of(code: String): StopReason = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

/** Events that represent an actual force-stop, not a review or a cancelled decision. */
val StopReason.isStop: Boolean
    get() = this in setOf(
        StopReason.CARD_REMOVED, StopReason.LAST_VISIBLE, StopReason.MANUAL,
        StopReason.FLOW_FINISHED, StopReason.UNLICENSED_RUNNING,
        StopReason.WOKE, StopReason.LEFT, StopReason.RUNNING,
    )

data class StopEvent(
    val id: Long,
    val time: Long,
    val packageName: String,
    val reason: StopReason,
    val sinceLeftMs: Long = 0,
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
    val recentlyVisible: Boolean = false,
) {
    // Names used by the reused home screen.
    val inUse: Boolean get() = visible
    val procState: Int get() = -1
}
