package io.github.user4ooksk.stopcard.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.user4ooksk.stopcard.AppGraph
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.data.db.PackageCount
import io.github.user4ooksk.stopcard.data.RulesRepository
import kotlinx.coroutines.CancellationException
import io.github.user4ooksk.stopcard.model.AppRule
import io.github.user4ooksk.stopcard.model.InstalledApp
import io.github.user4ooksk.stopcard.model.RuleMode
import io.github.user4ooksk.stopcard.ui.daysBefore
import io.github.user4ooksk.stopcard.ui.dayStarts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppsViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Filter { ALL, MANAGED, EXEMPT, CARD }

    data class Item(
        val app: InstalledApp,
        val rule: AppRule?,
        val isProtected: Boolean,
        val stopsToday: Int,
        val cards: Int = 0,
        val visible: Boolean = false,
        val state: String = "managed",
        val reasons: Set<String> = emptySet(),
        val running: Boolean = false,
        val stopped: Boolean = false,
        val statusKnown: Boolean = false,
    ) {
        /** True when the global rule applies; an ALLOW row or a system role exempts it. */
        val managed: Boolean get() = !isProtected && rule?.exempt != true
        val exempt: Boolean get() = !isProtected && rule?.exempt == true
    }

    data class State(
        val loading: Boolean = true,
        val items: List<Item> = emptyList(),
        /** Every listed app regardless of search and filter, for the details sheet. */
        val all: Map<String, Item> = emptyMap(),
        val counts: Map<Filter, Int> = emptyMap(),
        val showSystem: Boolean = false,
    )

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(Filter.ALL)
    data class Notice(val message: String, val undo: RulesRepository.UndoBatch? = null)
    val notice = MutableStateFlow<Notice?>(null)
    val editing = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val countsToday = dayStarts().flatMapLatest { graph.events.countsSince(it) }

    private val base = combine(graph.apps.apps.filterNotNull(), graph.rules.rules) { apps, rules ->
        apps to rules
    }
    private val extra = combine(countsToday, graph.settings.settings, graph.module.state) { counts, settings, module ->
        Triple(counts, settings, module)
    }

    val state: StateFlow<State> = combine(
        base,
        extra,
        query,
        filter,
    ) { (apps, rules), (counts, settings, module), query, filter ->
        val moduleApps = (module as? ModuleClient.State.Connected)?.module?.apps.orEmpty().associateBy { it.packageName }
        val essential = (module as? ModuleClient.State.Connected)?.module?.essentialReasons.orEmpty()
        val all = apps.values
            .filter { it.packageName != graph.context.packageName }
            .filter { settings.showSystemApps || !it.isSystem || rules[it.packageName]?.exempt == true }
            .map { app -> item(app, rules, counts, moduleApps, essential,
                (module as? ModuleClient.State.Connected)?.module?.ready == true) }
        val matching = if (query.isBlank()) all else {
            val q = query.trim()
            all.filter { it.app.label.contains(q, ignoreCase = true) || it.app.packageName.contains(q, ignoreCase = true) }
        }
        State(
            loading = false,
            all = all.associateBy { it.app.packageName },
            items = matching.filter { it.matches(filter) }
                .sortedWith(compareByDescending<Item> { it.cards > 0 }.thenBy { it.app.label.lowercase() }),
            counts = Filter.entries.associateWith { f -> matching.count { it.matches(f) } },
            showSystem = settings.showSystemApps,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun setAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch { graph.rules.setAllowed(packageName, allowed) }
    }

    fun decide(packageNames: List<String>, mode: RuleMode) {
        if (editing.value) return
        editing.value = true
        viewModelScope.launch {
            try {
                val batch = graph.rules.decide(packageNames, mode)
                notice.value = Notice(if (batch.count == 0) "没有需要修改的应用" else "已修改 ${batch.count} 个应用", batch.takeIf { it.count > 0 })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice.value = Notice("修改失败，请重试")
            } finally {
                editing.value = false
            }
        }
    }

    fun dismissNotice(value: Notice) {
        if (notice.value === value) notice.value = null
    }

    fun undo(value: Notice) {
        val batch = value.undo ?: return
        if (editing.value) return
        editing.value = true
        dismissNotice(value)
        viewModelScope.launch {
            try {
                val count = graph.rules.undo(batch)
                notice.value = Notice(when (count) {
                    batch.count -> "已撤销 ${count} 个应用的修改"
                    0 -> "设置已变化，无可撤销的修改"
                    else -> "已撤销 ${count} 个应用，其余设置已变化"
                })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice.value = Notice("撤销失败，请重试", batch)
            } finally {
                editing.value = false
            }
        }
    }

    fun setShowSystem(show: Boolean) {
        viewModelScope.launch { graph.settings.update { it.copy(showSystemApps = show) } }
    }

    fun stopNow(packageName: String) {
        viewModelScope.launch { graph.module.stopNow(listOf(packageName)) }
    }

    fun history(packageName: String) = graph.events.forPackage(packageName, HISTORY_LIMIT)

    fun weekStart(): Long = daysBefore(System.currentTimeMillis(), 6)

    private fun item(
        app: InstalledApp,
        rules: Map<String, AppRule>,
        counts: Map<String, PackageCount>,
        moduleApps: Map<String, io.github.user4ooksk.stopcard.model.RunningApp>,
        essential: Map<String, Set<String>>,
        statusKnown: Boolean,
    ): Item {
        val module = moduleApps[app.packageName]
        return Item(
            app = app,
            rule = rules[app.packageName]?.takeIf { it.identity == app.identity && app.identity != null },
            isProtected = app.isSystem || essential[app.packageName]?.isNotEmpty() == true,
            stopsToday = counts[app.packageName]?.count ?: 0,
            cards = module?.cards ?: 0,
            visible = module?.visible ?: false,
            state = module?.state ?: "managed",
            reasons = essential[app.packageName].orEmpty() + if (app.isSystem) setOf("system_package") else emptySet(),
            running = module?.running == true,
            stopped = module?.stopped == true,
            statusKnown = statusKnown,
        )
    }

    private fun Item.matches(filter: Filter) = when (filter) {
        Filter.ALL -> true
        Filter.MANAGED -> managed
        Filter.EXEMPT -> exempt
        Filter.CARD -> cards > 0 || visible
    }

    private companion object {
        const val HISTORY_LIMIT = 200
    }
}
