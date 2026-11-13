package io.github.user4ooksk.stopcard.module

import android.os.FileObserver
import org.junit.Assert.*
import org.junit.Test

class ModuleFileObserverTest {
    @Test fun callbackFailureIsReportedAndLaterFileChangesStillArrive() {
        val error = IllegalStateException("pause update failed")
        val reported = mutableListOf<Throwable>()
        val delivered = mutableListOf<String?>()
        val observer = ModuleFileObserver("unused", FileObserver.CREATE, reported::add) { _, path ->
            if (path == "paused") throw error
            delivered += path
        }
        observer.onEvent(FileObserver.CREATE, "paused")
        observer.onEvent(FileObserver.CREATE, "config.json")
        observer.onEvent(FileObserver.CREATE, null)
        assertEquals(listOf(error), reported)
        assertEquals(listOf("config.json", null), delivered)
    }

    @Test fun linkageAndReportingErrorsCannotEscapeTheFileObserverThread() {
        val observer = ModuleFileObserver("unused", FileObserver.MOVED_TO,
            { throw AssertionError("report failed") }) { _, _ -> throw NoSuchMethodError("platform changed") }
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread = Thread { observer.onEvent(FileObserver.MOVED_TO, "config.json") }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, cause -> uncaught.set(cause) }
        }
        thread.start()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertNull(uncaught.get())
    }
}
