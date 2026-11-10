package io.github.user4ooksk.stopcard.module

/** An uncaught exception on any module thread would terminate the hosting system_server. */
internal inline fun guardModule(failed: (Throwable) -> Unit, action: () -> Unit) {
    try { action() }
    catch (error: Throwable) {
        // Failure reporting is part of the same boundary, including secondary failures.
        try { failed(error) } catch (_: Throwable) { }
    }
}
