package io.github.user4ooksk.stopcard.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.user4ooksk.stopcard.AppGraph
import io.github.user4ooksk.stopcard.bridge.ModuleClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OnboardingViewModel(private val graph: AppGraph) : ViewModel() {

    enum class Step { WELCOME, SETUP, READY }

    private val _step = MutableStateFlow(Step.WELCOME)
    val step: StateFlow<Step> = _step.asStateFlow()

    val module: StateFlow<ModuleClient.State> = graph.module.state

    val rootAvailable: StateFlow<Boolean> = graph.module.rootAvailable

    fun next() {
        _step.value = when (_step.value) {
            Step.WELCOME -> Step.SETUP
            Step.SETUP -> Step.READY
            Step.READY -> Step.READY
        }
        if (_step.value == Step.SETUP) refresh()
    }

    fun back(): Boolean {
        _step.value = when (_step.value) {
            Step.WELCOME -> return false
            Step.SETUP -> Step.WELCOME
            Step.READY -> Step.SETUP
        }
        return true
    }

    fun refresh() {
        viewModelScope.launch { graph.module.refresh(forceRoot = true) }
    }

    fun finish(observeOnly: Boolean) {
        viewModelScope.launch {
            graph.settings.update {
                it.copy(onboarded = true, protectionEnabled = true, observeOnly = observeOnly)
            }
            if ((graph.module.state.value as? ModuleClient.State.Connected)?.module?.emergencyDisabled != true) {
                graph.module.setPaused(false)
            }
            graph.module.refresh()
        }
    }
}
