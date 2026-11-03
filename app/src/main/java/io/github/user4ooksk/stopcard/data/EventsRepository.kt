package io.github.user4ooksk.stopcard.data

import android.content.Context
import androidx.core.content.edit
import io.github.user4ooksk.stopcard.data.db.EventDao
import io.github.user4ooksk.stopcard.data.db.EventEntity
import io.github.user4ooksk.stopcard.data.db.PackageCount
import io.github.user4ooksk.stopcard.model.StopEvent
import io.github.user4ooksk.stopcard.model.StopReason
import io.github.user4ooksk.stopcard.module.ModuleProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class EventsRepository(context: Context, private val dao: EventDao) {
    private val prefs = context.getSharedPreferences("events", Context.MODE_PRIVATE)

    /** Ids up to this one were cleared by the user and must not be imported again. */
    private var clearedUpTo: Long
        get() = prefs.getLong(KEY_CLEARED_UP_TO, 0)
        set(value) = prefs.edit { putLong(KEY_CLEARED_UP_TO, value) }

    fun since(time: Long): Flow<List<StopEvent>> = dao.observeSince(time).map { list -> list.map { it.toModel() } }

    fun recent(limit: Int): Flow<List<StopEvent>> = dao.observeRecent(limit).map { list -> list.map { it.toModel() } }

    fun forPackage(packageName: String, limit: Int): Flow<List<StopEvent>> =
        dao.observeForPackage(packageName, limit).map { list -> list.map { it.toModel() } }

    /** Number of real stops per package since [time]. */
    fun countsSince(time: Long): Flow<Map<String, PackageCount>> =
        dao.observeCounts(time).map { list -> list.associateBy { it.packageName } }

    suspend fun add(events: List<ModuleProtocol.Event>) {
        val cleared = clearedUpTo
        val fresh = events.filter { it.id > cleared }
        if (fresh.isEmpty()) return
        dao.insert(fresh.map {
            EventEntity(it.id, it.time, it.packageName, it.reason, 0, it.detail)
        })
    }

    suspend fun latestId(): Long = maxOf(dao.maxId() ?: 0, clearedUpTo)

    suspend fun prune() {
        dao.deleteOlderThan(System.currentTimeMillis() - RETENTION_MS)
        dao.trimTo(MAX_EVENTS)
    }

    suspend fun clear() {
        clearedUpTo = latestId()
        dao.deleteAll()
    }

    private fun EventEntity.toModel() = StopEvent(
        id = id,
        time = time,
        packageName = packageName,
        reason = StopReason.of(reason),
        sinceLeftMs = sinceLeftMs,
        detail = detail,
    )

    companion object {
        /** The design keeps seven days of local diagnostic history. */
        val RETENTION_MS = TimeUnit.DAYS.toMillis(7)
        private const val KEY_CLEARED_UP_TO = "cleared_up_to"
        private const val MAX_EVENTS = 5_000
    }
}
