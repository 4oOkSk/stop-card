package io.github.user4ooksk.stopcard.module

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ModuleGuardTest {
    @Test fun nativeLinkageFailureDoesNotReachTheThreadUncaughtHandler() {
        val reported = AtomicReference<Throwable>()
        val uncaught = AtomicReference<Throwable>()
        val error = NoSuchMethodError("changed platform contract")
        val worker = Thread {
            guardModule({ reported.set(it) }) { throw error }
        }.apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, cause -> uncaught.set(cause) } }
        worker.start()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertSame(error, reported.get())
        assertNull(uncaught.get())
    }

    @Test fun reportingFailureIsContainedAndTheNextTaskCanRun() {
        val uncaught = AtomicReference<Throwable>()
        var nextTaskRan = false
        val worker = Thread {
            guardModule({ throw AssertionError("reporting also failed") }) {
                throw IllegalStateException("storage or maintenance failed")
            }
            guardModule({ fail("unexpected failure") }) { nextTaskRan = true }
        }.apply { uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, cause -> uncaught.set(cause) } }
        worker.start()
        worker.join(2_000)
        assertFalse(worker.isAlive)
        assertNull(uncaught.get())
        assertTrue(nextTaskRan)
    }
}
