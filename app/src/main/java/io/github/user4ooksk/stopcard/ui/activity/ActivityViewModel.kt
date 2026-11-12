package io.github.user4ooksk.stopcard.ui.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.user4ooksk.stopcard.AppGraph
import io.github.user4ooksk.stopcard.data.EventsRepository
import io.github.user4ooksk.stopcard.model.StopEvent
import io.github.user4ooksk.stopcard.model.StopReason
import io.github.user4ooksk.stopcard.model.isStop
import io.github.user4ooksk.stopcard.ui.dayStarts
import io.github.user4ooksk.stopcard.ui.daysBefore
import io.github.user4ooksk.stopcard.ui.startOfDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivityViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Filter { ALL, CARD, BLOCKED, OBSERVE, MANUAL }

    /** One line in the list; consecutive identical stops of the same app are folded together. */
    data class Row(val event: StopEvent, val label: String, val count: Int)

    data class Day(val start: Long, val rows: List<Row>)

    data class ChartDay(val start: Long, val stops: Int)

    data class State(
        val loading: Boolean = true,
        val days: List<Day> = emptyList(),
        val week: List<ChartDay> = emptyList(),
        val weekTotal: Int = 0,
        val isEmpty: Boolean = true,
    )

    val filter = MutableStateFlow(Filter.ALL)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentEvents = dayStarts().flatMapLatest { today ->
        val cutoff = minOf(daysBefore(today, 6), System.currentTimeMillis() - EventsRepository.RETENTION_MS)
        graph.events.since(cutoff).map { events -> today to events }
    }

    val state: StateFlow<State> = combine(
        recentEvents,
        graph.apps.apps.filterNotNull(),
        filter,
    ) { (today, events), apps, filter ->
        val weekStart = daysBefore(today, 6)
        val week = (6 downTo 0).map { daysAgo ->
            val start = daysBefore(today, daysAgo)
            ChartDay(start, events.count { startOfDay(it.time) == start && it.reason.isStop })
        }
        val shown = events.filter { it.matches(filter) }
        State(
            loading = false,
            days = shown.groupBy { startOfDay(it.time) }
                .map { (start, dayEvents) -> Day(start, fold(dayEvents) { apps[it]?.label ?: it }) }
                .sortedByDescending { it.start },
            week = week,
            weekTotal = events.count { it.time >= weekStart && it.reason.isStop },
            isEmpty = events.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun clearHistory() {
        viewModelScope.launch {
            graph.events.clear()
            graph.module.clearModuleLog()
        }
    }

    private fun StopEvent.matches(filter: Filter) = when (filter) {
        Filter.ALL -> true
        Filter.CARD -> reason == StopReason.CARD_REMOVED || reason == StopReason.LAST_VISIBLE || reason == StopReason.FLOW_FINISHED
        Filter.BLOCKED -> reason == StopReason.BLOCKED || reason == StopReason.CLOSED_APP_RESTARTED
        Filter.OBSERVE -> reason == StopReason.OBSERVE
        Filter.MANUAL -> reason == StopReason.MANUAL
    }

    /** [events] are newest first; runs of the same app and reason within an hour become one row. */
    private fun fold(events: List<StopEvent>, label: (String) -> String): List<Row> {
        val rows = ArrayList<Row>()
        for (event in events) {
            val last = rows.lastOrNull()
            if (last != null &&
                last.event.packageName == event.packageName &&
                last.event.reason == event.reason &&
                last.event.time - event.time < FOLD_WINDOW_MS
            ) {
                rows[rows.lastIndex] = last.copy(count = last.count + 1)
            } else {
                rows += Row(event, label(event.packageName), 1)
            }
        }
        return rows
    }

    private companion object {
        const val FOLD_WINDOW_MS = 60 * 60_000L
    }
}
