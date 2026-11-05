package io.github.user4ooksk.stopcard.module

import org.junit.Assert.*
import org.junit.Test

class TaskRemovalTest {
    @Test fun recognizedNativeUserRemovalRoutes() {
        for (reason in listOf("remove-task", "removeTask", "remove-all-visible-recent-tasks", "remove-by-pid#1234"))
            assertTrue(reason, explicitTaskRemoval(true, reason))
    }

    @Test fun appFinishAndSystemCleanupAreNotUserRemoval() {
        for (reason in listOf("finish-and-remove-task", "remove-root-task", "trim-recents", "removeTask:cleanup", ""))
            assertFalse(reason, explicitTaskRemoval(true, reason))
    }

    @Test fun requiresActualRecentRemovalAndKnownPidFormat() {
        assertFalse(explicitTaskRemoval(false, "remove-by-pid#1234"))
        assertFalse(explicitTaskRemoval(false, "remove-task"))
        for (reason in listOf("remove-by-pid#", "remove-by-pid#unknown", "remove-by-pid#1234-extra"))
            assertFalse(reason, explicitTaskRemoval(true, reason))
    }
}
