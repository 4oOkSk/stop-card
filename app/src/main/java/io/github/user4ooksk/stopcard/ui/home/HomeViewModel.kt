package io.github.user4ooksk.stopcard.ui.home

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.user4ooksk.stopcard.AppGraph
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.model.InstalledApp
import io.github.user4ooksk.stopcard.model.StopEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val graph: AppGraph) : ViewModel() {

    data class Running(
        val app: InstalledApp?,
        val packageName: String,
        val cards: Int,
        val inUse: Boolean,
        /** elapsedRealtime at which the module stops it, or null. */
        val stopsAt: Long?,
        val state: String = "managed",
    )

    data class Recent(val event: StopEvent, val label: String)

    data class State(
        val blockedCount: Int = 0,
        val running: List<Running> = emptyList(),
        val recent: List<Recent> = emptyList(),
        val cards: Int = 0,
        val operations: Int = 0,
        val stopped: Int = 0,
        val observeOnly: Boolean = true,
        val module: ModuleClient.State = ModuleClient.State.Waiting,
    )

    val state: StateFlow<State> = combine(
        graph.apps.apps.filterNotNull(),
        graph.rules.rules,
        graph.settings.settings,
        graph.events.recent(RECENT_COUNT),
        graph.module.state,
    ) { apps, rules, settings, recent, module ->
        val polledAt = SystemClock.elapsedRealtime()
        val moduleApps = (module as? ModuleClient.State.Connected)?.module?.apps.orEmpty()
        val running = moduleApps
            .filter { it.running }
            .map { app ->
                Running(
                    app = apps[app.packageName],
                    packageName = app.packageName,
                    cards = app.cards,
                    inUse = app.visible,
                    stopsAt = if (app.stopInMs >= 0) polledAt + app.stopInMs else null,
                    state = app.state,
                )
            }
            .sortedWith(compareBy({ it.stopsAt == null }, { it.stopsAt }))
        State(
            blockedCount = apps.values.count {
                !it.isSystem && it.packageName != graph.context.packageName && !(rules[it.packageName]?.exempt == true && rules[it.packageName]?.identity == it.identity)
            },
            running = running,
            recent = recent.map { Recent(it, apps[it.packageName]?.label ?: it.packageName) },
            cards = moduleApps.sumOf { it.cards },
            operations = moduleApps.count { it.state == "operation" },
            stopped = moduleApps.count { it.stopped },
            observeOnly = (module as? ModuleClient.State.Connected)?.module?.observeOnly ?: settings.observeOnly,
            module = module,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun stopNow(packageName: String) {
        viewModelScope.launch { graph.module.stopNow(listOf(packageName)) }
    }

    fun stopAll() {
        viewModelScope.launch { graph.module.stopAllCards() }
    }

    private companion object {
        const val RECENT_COUNT = 5
    }
}
