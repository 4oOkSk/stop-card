package io.github.user4ooksk.stopcard.policy

data class PriorityProcess(val app: AppKey, val pid: Int, val processName: String, val rssKb: Long, val epoch: Long = 0)
data class MemoryPressure(val totalKb: Long, val availableKb: Long, val thresholdKb: Long, val lowMemory: Boolean)
data class PriorityGrant(val process: PriorityProcess, val adj: Int)

/** A bounded LMKD preference, independent of Android's process-state and freezer decisions. */
object KeepAlivePolicy {
    fun select(views: List<PermissionView>, processes: List<PriorityProcess>, memory: MemoryPressure): Map<Int, PriorityGrant> {
        if (memory.totalKb <= 0 || memory.lowMemory ||
            memory.availableKb < maxOf(memory.thresholdKb * 2, memory.totalKb / 10)) return emptyMap()
        val tight = memory.availableKb < memory.totalKb / 5
        val maxApps = if (tight) 3 else 8
        val budget = minOf(memory.totalKb / 5, 3L * 1024 * 1024)
        var used = 0L
        val result = LinkedHashMap<Int, PriorityGrant>()
        for (view in views.filter { it.cards > 0 && it.state in setOf("card", "visible") }
            .sortedWith(compareByDescending<PermissionView> { it.lastActiveAt }.thenBy { it.app.packageName })) {
            if (result.size >= maxApps) break
            val candidates = processes.filter { it.app == view.app && it.pid > 0 && it.rssKb > 0 }
            // A primary UI process is resolved by the adapter; ambiguity keeps native priority.
            val process = candidates.singleOrNull() ?: continue
            if (process.rssKb > budget - used || process.pid in result) continue
            result[process.pid] = PriorityGrant(process, if (!tight && result.size < 3) 700 else 800)
            used += process.rssKb
        }
        return result
    }

    fun adjust(nativeAdj: Int, grant: PriorityGrant?): Int =
        if (nativeAdj in 900..999 && grant != null) grant.adj else nativeAdj
}
