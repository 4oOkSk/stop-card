package io.github.user4ooksk.stopcard.ui.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.user4ooksk.stopcard.AppGraph
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.data.SystemRoles
import io.github.user4ooksk.stopcard.data.db.PackageCount
import io.github.user4ooksk.stopcard.model.AppRule
import io.github.user4ooksk.stopcard.model.InstalledApp
import io.github.user4ooksk.stopcard.model.RuleMode
import io.github.user4ooksk.stopcard.ui.daysBefore
import io.github.user4ooksk.stopcard.ui.dayStarts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppsViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Filter { ALL, MANAGED, EXEMPT, CARD }

    data class Item(
        val app: InstalledApp,
        val rule: AppRule?,
        val isProtected: Boolean,
        val integration: SystemRoles.Integration?,
        val stopsToday: Int,
        val cards: Int = 0,
        val visible: Boolean = false,
        val state: String = "managed",
        val reasons: Set<String> = emptySet(),
    ) {
        /** True when the global rule applies; an ALLOW row or a system role exempts it. */
        val managed: Boolean get() = !isProtected && rule?.exempt != true
        val exempt: Boolean get() = !isProtected && rule?.exempt == true
        val isNew: Boolean get() = false
    }

    data class State(
        val loading: Boolean = true,
        val items: List<Item> = emptyList(),
        /** Every listed app regardless of search and filter, for the details sheet. */
        val all: Map<String, Item> = emptyMap(),
        val counts: Map<Filter, Int> = emptyMap(),
        val showSystem: Boolean = false,
        val defaultGraceSeconds: Int = 0,
    )

    private data class Roles(val protected: Set<String>, val integrations: Map<String, SystemRoles.Integration>)

    val query = MutableStateFlow("")
    val filter = MutableStateFlow(Filter.ALL)

    private val roles = combine(graph.apps.apps.filterNotNull(), graph.rolesChanged) { _, _ -> }
        .map { Roles(graph.roles.protectedPackages(), graph.roles.integrations()) }
        .flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val countsToday = dayStarts().flatMapLatest { graph.events.countsSince(it) }

    private val base = combine(graph.apps.apps.filterNotNull(), graph.rules.rules, roles) { apps, rules, roles ->
        Triple(apps, rules, roles)
    }
    private val extra = combine(countsToday, graph.settings.settings, graph.module.state) { counts, settings, module ->
        Triple(counts, settings, module)
    }

    val state: StateFlow<State> = combine(
        base,
        extra,
        query,
        filter,
    ) { (apps, rules, roles), (counts, settings, module), query, filter ->
        val moduleApps = (module as? ModuleClient.State.Connected)?.module?.apps.orEmpty().associateBy { it.packageName }
        val essential = (module as? ModuleClient.State.Connected)?.module?.essentialReasons.orEmpty()
        val all = apps.values
            .filter { it.packageName != graph.context.packageName }
            .filter { settings.showSystemApps || it.isUserFacing || rules[it.packageName]?.exempt == true }
            .map { app -> item(app, rules, roles, counts, moduleApps, essential) }
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
            defaultGraceSeconds = 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /** Kept for the reused switch: on means "exception", off means "global card rule". */
    fun setBlocked(packageName: String, blocked: Boolean) = setAllowed(packageName, !blocked)

    fun setAllowed(packageName: String, allowed: Boolean) {
        viewModelScope.launch { graph.rules.setAllowed(packageName, allowed) }
    }

    fun decide(packageNames: List<String>, mode: RuleMode) {
        viewModelScope.launch { graph.decide(packageNames, mode) }
    }

    fun setGrace(packageName: String, seconds: Int?) {
        // The card rule has no per-app grace period; retained for the old detail sheet.
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
        roles: Roles,
        counts: Map<String, PackageCount>,
        moduleApps: Map<String, io.github.user4ooksk.stopcard.model.RunningApp>,
        essential: Map<String, Set<String>>,
    ): Item {
        val module = moduleApps[app.packageName]
        return Item(
            app = app,
            rule = rules[app.packageName]?.takeIf { it.identity == app.identity && app.identity != null },
            isProtected = app.isSystem || essential[app.packageName]?.isNotEmpty() == true || app.packageName in roles.protected,
            integration = roles.integrations[app.packageName],
            stopsToday = counts[app.packageName]?.count ?: 0,
            cards = module?.cards ?: 0,
            visible = module?.visible ?: false,
            state = module?.state ?: "managed",
            reasons = essential[app.packageName].orEmpty() + if (app.isSystem) setOf("system_package") else emptySet(),
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
