package io.github.user4ooksk.stopcard.policy

import org.junit.Assert.*
import org.junit.Test

class PermissionPolicyTest {
    private val a = AppKey(0, "shop.app", 10001, 1, "signer-a")
    private val b = AppKey(0, "pay.app", 10002, 2, "signer-b")
    private val c = AppKey(0, "bank.app", 10003, 3, "signer-c")
    private val t1 = TaskKey(0, 1, "boot")
    private val t2 = TaskKey(0, 2, "boot")
    private val rule = AccessRule(enabled = true, observe = false, generation = 1)

    private fun policy(): PermissionPolicy = PermissionPolicy().apply {
        setApps(listOf(a, b, c), 0)
        configure(rule, 0)
        pause(false, 0)
        reconcile(world(), 1)
    }

    private fun task(key: TaskKey = t1, pkg: String = a.packageName, visible: Boolean = false, eligible: Boolean = true) =
        TaskFact(key, pkg, setOf(pkg), if (visible) setOf(pkg) else emptySet(), eligible)

    private fun world(vararg tasks: TaskFact, complete: Boolean = true, boot: String = "boot", visible: Set<String> = emptySet()) =
        WorldSnapshot(tasks.toList(), visible, complete, boot)

    private fun opened(p: PermissionPolicy, key: TaskKey = t1, pkg: String = a.packageName, now: Long = 100) {
        p.opened(pkg, key, OpenSource.LAUNCHER, now)
        p.reconcile(world(task(key, pkg, true)), now)
        p.reconcile(world(task(key, pkg)), now + 1)
    }

    private fun closing(p: PermissionPolicy, now: Long = 200): StopTicket {
        p.removed(t1, a.packageName, true, now)
        p.reconcile(world(), now)
        assertTrue(p.stops(now).isEmpty())
        return p.stops(now + 500).single()
    }

    @Test fun `no initialization sweep or historical card permission`() {
        val p = policy()
        p.reconcile(world(task()), 100)
        assertTrue(p.stops(4000).isEmpty())
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 4000).allow)
    }

    @Test fun `last card closes after short review and success is explicit`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        assertTrue(p.valid(ticket))
        assertNotEquals("stopped", p.views().first { it.app == a }.state)
        p.completed(ticket, success = true, observed = false, now = 701)
        assertEquals("stopped", p.views().first { it.app == a }.state)
    }

    @Test fun `open between polls invalidates stop before task reconciliation`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        p.opened(a.packageName, t2, OpenSource.RECENTS, 701)
        assertFalse(p.valid(ticket))
        p.reconcile(world(task(t2)), 702)
        assertEquals(1, p.views().first { it.app == a }.cards)
        assertTrue(p.stops(5000).isEmpty())
    }

    @Test fun `unseen task in final read cancels old decision without activating it`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        p.reconcile(world(task(t2)), 701)
        assertFalse(p.valid(ticket))
        assertEquals(0, p.views().first { it.app == a }.cards)
        assertTrue(p.stops(702).isEmpty())
    }

    @Test fun `two tasks opened between snapshots both retain activation`() {
        val p = policy(); opened(p)
        p.opened(a.packageName, t2, OpenSource.INTERACTION, 150)
        p.reconcile(world(task(t1), task(t2)), 151)
        p.removed(t1, a.packageName, true, 200)
        p.reconcile(world(task(t2)), 201)
        assertEquals(1, p.views().first { it.app == a }.cards)
        assertTrue(p.stops(5000).isEmpty())
    }

    @Test fun `pause preserves verified cards and invalidates pending tickets`() {
        val p = policy(); opened(p)
        p.pause(true, 150); p.pause(false, 160)
        p.reconcile(world(task()), 170)
        assertEquals(1, p.views().first { it.app == a }.cards)
        val ticket = closing(p)
        p.pause(true, 701)
        assertFalse(p.valid(ticket))
    }

    @Test fun `unknown snapshot clears old deadline and recovery needs new review`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        p.unknown(701)
        assertFalse(p.valid(ticket))
        p.reconcile(world(), 5000)
        assertTrue(p.stops(5000).isEmpty())
        assertEquals(1, p.stops(5500).size)
    }

    @Test fun `historic close during initialization is retained`() {
        val p = PermissionPolicy()
        p.setApps(listOf(a), 0); p.configure(rule, 0); p.pause(false, 0)
        p.removed(t1, a.packageName, true, 1)
        assertTrue(p.stops(2).isEmpty())
        p.reconcile(world(), 100)
        assertTrue(p.stops(100).isEmpty())
        assertEquals("user_closed", p.stops(600).single().reason)
    }

    @Test fun `flow survives card removal and timeout with pending callback`() {
        val p = policy(); opened(p)
        p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 150, "result")!!
        p.removed(t1, a.packageName, true, 200)
        p.reconcile(world(), 201)
        assertTrue(p.stops(700_000).isEmpty())
        assertEquals("operation", p.views().first { it.app == b }.state)
        assertTrue(p.flowDetails().single().endsWith("needs_review"))
    }

    @Test fun `result and binding must both finish before flow release`() {
        val p = policy(); opened(p)
        val target = CallTarget(b.packageName, "pay/Service", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, OpenSource.INTERACTION, 150, "result")!!
        assertTrue(p.bind(flow, "connection", target, 151))
        p.finishFlow(flow, canceled = false, now = 200)
        p.callbackDelivered("result", 201)
        p.reconcile(world(), 40_000)
        assertEquals("operation", p.views().first { it.app == b }.state)
        p.unbind("connection", 40_001)
        p.reconcile(world(), 80_001)
        assertNotEquals("operation", p.views().first { it.app == b }.state)
        assertTrue(p.stops(80_001).isEmpty())
        assertTrue(p.stops(80_501).any { it.app == b })
    }

    @Test fun `shared task participants stay protected until Activity and result finish`() {
        val p = policy(); opened(p)
        val flow = p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 150, "result")!!
        p.flowActivity(flow, "pay-activity", true, 151)
        p.finishFlow(flow, false, 200); p.callbackDelivered("result", 201)
        p.reconcile(world(task(t1)), 40_000)
        assertEquals("operation", p.views().first { it.app == b }.state)
        p.flowActivity(flow, "pay-activity", false, 40_001)
        p.reconcile(world(task(t1)), 80_001)
        assertTrue(p.stops(80_001).isEmpty())
        assertTrue(p.stops(80_501).any { it.app == b })
        assertFalse(p.stops(80_501).any { it.app == a })
    }

    @Test fun `operation target cannot grant unrelated services or unrelated packages`() {
        val p = policy()
        val target = CallTarget(b.packageName, "pay/Service", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, OpenSource.SYSTEM_CHOOSER, 100)!!
        p.reconcile(world(), 4000)
        assertTrue(p.entry(0, target, 4000).allow)
        assertFalse(p.entry(0, target.copy(component = "pay/Unrelated"), 4000).allow)
        assertFalse(p.entry(0, target.copy(packageName = c.packageName), 4000).allow)
        assertFalse(p.extendFlow(flow, b.packageName, CallTarget(c.packageName, "bank/Confirm", EntryKind.ACTIVITY), false, 4001))
        assertTrue(p.extendFlow(flow, b.packageName, CallTarget(c.packageName, "bank/Confirm", EntryKind.ACTIVITY), true, 4002))
    }

    @Test fun `visible app without trusted open protects window but does not activate hidden siblings`() {
        val p = policy()
        p.reconcile(world(task(t1, visible = true), task(t2)), 100)
        assertEquals(0, p.views().first { it.app == a }.cards)
        assertTrue(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 100).allow)
    }

    @Test fun `excluded visible activity gets no card permission`() {
        val p = policy()
        p.opened(a.packageName, t1, OpenSource.LAUNCHER, 100)
        p.reconcile(world(task(visible = true, eligible = false)), 101)
        assertEquals(0, p.views().first { it.app == a }.cards)
        assertEquals("visible", p.views().first { it.app == a }.state)
    }

    @Test fun `reinstall and UID reuse cannot inherit exception or ticket`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        p.configure(rule.copy(exceptions = setOf(a), generation = 2), 701)
        val replacement = a.copy(installedAt = 999, signer = "different")
        p.setApps(listOf(replacement, b, c), 702)
        p.reconcile(world(), 703)
        assertFalse(p.valid(ticket))
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 703).allow)
    }

    @Test fun `observe does not stop and disabling observe permits a fresh stop`() {
        val p = policy(); opened(p)
        p.configure(rule.copy(observe = true, generation = 2), 150)
        val ticket = closing(p)
        assertFalse(p.valid(ticket))
        p.completed(ticket, false, true, 701)
        assertTrue(p.stops(900).isEmpty())
        p.configure(rule.copy(generation = 3), 1000)
        assertTrue(p.stops(1000).isEmpty())
        assertTrue(p.valid(p.stops(1500).single()))
    }

    @Test fun `failed stop retries and does not mark a batch stopped in advance`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        p.completed(ticket, false, false, 701)
        assertNotEquals("stopped", p.views().first { it.app == a }.state)
        assertTrue(p.stops(30_700).isEmpty())
        assertEquals(1, p.stops(30_701).size)
    }

    @Test fun `other users and new boot cannot inherit task permission`() {
        val p = policy(); opened(p)
        assertTrue(p.entry(10, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 200).allow)
        p.reconcile(world(task(t1.copy(boot = "new")), boot = "new"), 300)
        assertEquals(0, p.views().first { it.app == a }.cards)
        assertTrue(p.stops(10_000).isEmpty())
    }

    @Test fun `new config and newly visible split or pip invalidate old work`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        p.reconcile(world(visible = setOf(a.packageName, b.packageName)), 701)
        assertFalse(p.valid(ticket))
        assertTrue(p.stops(10_000).isEmpty())
        assertEquals("visible", p.views().first { it.app == b }.state)
    }

    @Test fun `return to visible shop does not retain a completed payment process`() {
        val p = policy(); opened(p)
        val flow = p.beginFlow(a.packageName, "shop-activity", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 150, "result")!!
        p.flowActivity(flow, "pay-activity", true, 151)
        p.flowActivity(flow, "pay-activity", false, 200)
        p.finishFlow(flow, false, 201)
        p.callbackDelivered("result", 202)
        p.reconcile(world(task(visible = true)), 40_000)
        assertTrue(p.flowDetails().isEmpty())
        assertTrue(p.stops(40_000).isEmpty())
        assertTrue(p.stops(40_500).any { it.app == b })
        assertFalse(p.stops(40_500).any { it.app == a })
    }

    @Test fun `unknown visible launch is observed without gaining a persistent card`() {
        val p = policy()
        val flow = p.observeFlow(a.packageName, "shop-activity", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), 100, "result")!!
        p.reconcile(world(task(t2, b.packageName, true)), 101)
        assertEquals(0, p.views().first { it.app == b }.cards)
        assertTrue(p.flowDetails().single().endsWith("observed"))
        p.flowActivity(flow, "pay-activity", true, 102)
        p.reconcile(world(task(t2, b.packageName)), 4000)
        assertTrue(p.entry(0, CallTarget(b.packageName, kind = EntryKind.PROCESS), 4000).allow)
        assertFalse(p.entry(0, CallTarget(b.packageName, "pay/Other", EntryKind.SERVICE), 4000).allow)
    }

    @Test fun `dependency grants remain inside actual flow participants and components`() {
        val p = policy()
        val flow = p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 100)!!
        val service = CallTarget(b.packageName, "pay/ResultService", EntryKind.BIND)
        assertTrue(p.dependency(flow, a.packageName, service, 101))
        assertFalse(p.dependency(flow, a.packageName, service.copy(packageName = c.packageName), 102))
        assertFalse(p.dependency(flow, a.packageName, service.copy(component = null), 103))
        p.reconcile(world(), 4000)
        assertTrue(p.entry(0, service, 4000).allow)
        assertFalse(p.entry(0, service.copy(component = "pay/Unrelated"), 4000).allow)
    }

    @Test fun `one removed binding does not release another connection in the operation`() {
        val p = policy()
        val target = CallTarget(b.packageName, "pay/ResultService", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, OpenSource.INTERACTION, 100)!!
        p.bind(flow, "connection-1", target, 101); p.bind(flow, "connection-2", target, 102)
        p.finishFlow(flow, false, 103)
        p.unbind("connection-1", 104)
        p.reconcile(world(), 40_000)
        assertFalse(p.flowDetails().isEmpty())
        p.unbind("connection-2", 40_001)
        p.reconcile(world(), 80_000)
        assertTrue(p.flowDetails().isEmpty())
    }

    @Test fun `another users visible task cannot protect the primary users installation`() {
        val p = policy()
        p.reconcile(world(task(t1.copy(userId = 10), visible = true)), 100)
        assertFalse(p.views().first { it.app == a }.visible)
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 100).allow)
    }

    @Test fun `manual stop can target background card but not visible work or callbacks`() {
        val p = policy(); opened(p)
        assertTrue(p.valid(p.manual(a.packageName)!!))
        p.visible(a.packageName, t1, true, 200)
        assertNull(p.manual(a.packageName))
        p.visible(a.packageName, t1, false, 201)
        p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 202, "result")
        assertNull(p.manual(b.packageName))
        assertNull(p.manual(a.packageName))
    }
}
