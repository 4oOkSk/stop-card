package io.github.user4ooksk.stopcard.policy

import org.junit.Assert.*
import org.junit.Test

class StopTransactionTest {
    private val app = AppKey(0, "test.app", 10001, 1, "signer")

    private fun pending(): Pair<PermissionPolicy, StopTicket> {
        val p = PermissionPolicy()
        p.setApps(listOf(app), 0)
        p.configure(AccessRule(true, false, 1), 0)
        p.pause(false, 0)
        p.reconcile(WorldSnapshot(emptyList(), emptySet(), true, "boot"), 1)
        p.runningWithoutPermission(app.packageName, 2)
        p.stops(2)
        return p to p.stops(502).single()
    }

    @Test fun `reopen after PM update restores flag and prevents native kill`() {
        val (p, ticket) = pending()
        var stopped = false
        var killed = false
        try {
            StopTransaction.run({ p.valid(ticket) }, false, {
                stopped = true
                p.opened(app.packageName, TaskKey(0, 2, "boot"), OpenSource.RECENTS, 503)
                check(p.valid(ticket))
                killed = true
            }, { stopped = false })
            fail("Native fence must reject the old ticket")
        } catch (_: IllegalStateException) { }
        assertFalse(stopped)
        assertFalse(killed)
        assertFalse(p.valid(ticket))
    }

    @Test fun `pause before entry has no package state side effect`() {
        val (p, ticket) = pending()
        p.pause(true, 503)
        var touched = false
        try {
            StopTransaction.run({ p.valid(ticket) }, false, { touched = true }, { touched = true })
            fail("Paused ticket must not enter Android")
        } catch (_: IllegalStateException) { }
        assertFalse(touched)
    }

    @Test fun `native failure undoes only this operation package state change`() {
        var stopped = false
        try {
            StopTransaction.run({ true }, false, { stopped = true; error("native failure") }, { stopped = false })
        } catch (_: IllegalStateException) { }
        assertFalse(stopped)
        stopped = true
        try {
            StopTransaction.run({ true }, true, { error("native failure") }, { stopped = false })
        } catch (_: IllegalStateException) { }
        assertTrue(stopped)
    }

    @Test fun `successful native stop remains stopped until user reopens`() {
        val (p, ticket) = pending()
        var stopped = false
        StopTransaction.run({ p.valid(ticket) }, false, { stopped = true }, { stopped = false })
        p.completed(ticket, stopped, false, 503)
        assertTrue(stopped)
        assertEquals("stopped", p.views().single().state)
    }
}
