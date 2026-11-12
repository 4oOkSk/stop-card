package io.github.user4ooksk.stopcard.ui

import io.github.user4ooksk.stopcard.bridge.ModuleClient
import io.github.user4ooksk.stopcard.model.AppSettings
import io.github.user4ooksk.stopcard.module.ModuleProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionTest {
    private val settings = AppSettings(protectionEnabled = true, observeOnly = false)
    private val core = ModuleProtocol.ModuleState(configLoaded = true, ready = true, observeOnly = false,
        recovery = "not_installed", notificationControl = "unavailable", wakeControl = "partial")

    @Test fun `optional hooks and recovery heartbeat do not disable the ready card core`() {
        assertEquals(Protection.Active, Protection.of(settings, ModuleClient.State.Connected(core)))
    }

    @Test fun `missing card core and explicit observe still report their actual states`() {
        assertTrue(Protection.of(settings, ModuleClient.State.Connected(core.copy(ready = false))) is Protection.Degraded)
        assertEquals(Protection.Observe, Protection.of(settings, ModuleClient.State.Connected(core.copy(observeOnly = true))))
    }

    @Test fun `emergency lock and module degradation are never masked by pending sync`() {
        val pending = ModuleClient.Synchronization(pending = true, generation = 9)
        assertTrue(Protection.of(settings, ModuleClient.State.Connected(core.copy(ready = false)), pending) is Protection.Degraded)
        assertEquals(
            Protection.EmergencyPaused("manual_emergency_disable"),
            Protection.of(
                settings,
                ModuleClient.State.Connected(
                    core.copy(paused = true, emergencyDisabled = true, diagnosticDetails = mapOf("emergency_disabled" to "manual_emergency_disable")),
                ),
                pending,
            ),
        )
    }
}
