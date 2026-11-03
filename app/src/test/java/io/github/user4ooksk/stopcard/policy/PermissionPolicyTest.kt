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
        val p = PermissionPolicy().apply {
            setApps(listOf(a, b, c), 0); configure(rule, 0); pause(false, 0)
            reconcile(world(task()), 100)
        }
        assertTrue(p.stops(4000).isEmpty())
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 4000).allow)
    }

    @Test fun `idle inventory remains managed without inventing running state`() {
        val p = policy()
        val plugin = c.copy(packageName = "plugin.without.launcher")
        p.setApps(listOf(a, b, plugin), 10)
        assertTrue(p.views().isEmpty())
        assertTrue(p.stops(100_000).isEmpty())
        assertFalse(p.entry(0, CallTarget(plugin.packageName, "plugin/Service", EntryKind.BIND), 100_000).allow)
        p.configure(rule.copy(exceptions = setOf(plugin)), 100_001)
        assertTrue(p.entry(0, CallTarget(plugin.packageName, kind = EntryKind.PROCESS), 100_001).allow)
    }

    @Test fun `reinstall cancels old operation identity and reviews only its remaining participants`() {
        val p = policy()
        p.beginFlow(a.packageName, "source", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 100)
        p.reconcile(world(), 4_000)
        val replacement = a.copy(installedAt = 99, uid = 10099)
        p.setApps(listOf(replacement, b, c), 4_001)
        assertNull(p.flowFor(b.packageName))
        assertTrue(p.stops(4_001).isEmpty())
        val tickets = p.stops(4_501)
        assertEquals(listOf(b), tickets.map { it.app })
        assertFalse(p.entry(0, CallTarget(replacement.packageName, kind = EntryKind.NOTIFICATION), 4_501).allow)
    }

    @Test fun `last card closes after short review and success is explicit`() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        assertTrue(p.valid(ticket))
        assertNotEquals("stopped", p.views().first { it.app == a }.state)
        p.completed(ticket, success = true, observed = false, now = 701)
        assertEquals("stopped", p.views().first { it.app == a }.state)
    }

    @Test fun `manual stop revokes a retained thumbnail until a new verified open`() {
        val p = policy(); opened(p)
        val ticket = p.manual(a.packageName)!!
        assertTrue(p.valid(ticket))
        p.completed(ticket, success = true, observed = false, now = 200)
        p.reconcile(world(task()), 201)
        assertEquals("stopped", p.views().single().state)
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.PROCESS), 202).allow)
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 202).allow)
        p.opened(a.packageName, t1, OpenSource.RECENTS, 203)
        p.reconcile(world(task()), 204)
        assertEquals("card", p.views().single().state)
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

    @Test fun `another package opening in a shared UID cancels native stop permission`() {
        val sibling = b.copy(uid = a.uid)
        val p = policy()
        p.setApps(listOf(a, sibling, c), 2)
        opened(p)
        val ticket = closing(p)
        val group = setOf(a.packageName, sibling.packageName)
        assertTrue(p.valid(ticket, group))
        p.opened(sibling.packageName, t2, OpenSource.LAUNCHER, 701)
        assertTrue("target revision did not change", p.valid(ticket))
        assertFalse("the shared process now has a new user session", p.valid(ticket, group))
    }

    @Test fun `a new operation in a shared UID also protects the whole process`() {
        val sibling = b.copy(uid = a.uid)
        val p = policy()
        p.setApps(listOf(a, sibling, c), 2)
        opened(p)
        val ticket = closing(p)
        p.observeFlow(c.packageName, "source", CallTarget(sibling.packageName, "pay.app/Pay", EntryKind.ACTIVITY), 701)
        assertFalse(p.valid(ticket, setOf(a.packageName, sibling.packageName)))
        assertFalse("a group member with unknown identity is not safe", p.valid(ticket, setOf(a.packageName, "unknown.app")))
    }

    @Test fun `cached priority applies only to a completely permitted group and stops on pause`() {
        val p = policy(); opened(p)
        val group = setOf(a.packageName, b.packageName)
        assertFalse(p.keepAlive(group))
        p.beginFlow(a.packageName, "source", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 200)
        assertTrue(p.keepAlive(group))
        p.pause(true, 201)
        assertFalse(p.keepAlive(group))
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

    @Test fun `visible operation survives timeout and hidden operation has a short fallback`() {
        val p = policy(); opened(p)
        p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 150)!!
        p.removed(t1, a.packageName, true, 200)
        p.reconcile(world(visible = setOf(b.packageName)), 201)
        p.reconcile(world(visible = setOf(b.packageName)), 700_000)
        assertTrue(p.stops(700_000).isEmpty())
        assertEquals("visible", p.views().first { it.app == b }.state)
        p.reconcile(world(), 700_001)
        assertFalse(p.flowDetails().isEmpty())
        p.reconcile(world(), 730_001)
        assertTrue(p.flowDetails().isEmpty())
    }

    @Test fun `binding lifecycle holds a hidden operation until release within fallback`() {
        val p = policy(); opened(p)
        val target = CallTarget(b.packageName, "pay/Service", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, OpenSource.INTERACTION, 150)!!
        assertTrue(p.bind(flow, "connection", target, 151))
        p.finishFlow(flow, canceled = false, now = 200)
        p.reconcile(world(), 20_000)
        assertEquals("operation", p.views().first { it.app == b }.state)
        p.unbind("connection", 20_001)
        p.reconcile(world(), 20_002)
        assertNotEquals("operation", p.views().first { it.app == b }.state)
        assertTrue(p.stops(20_002).isEmpty())
        assertTrue(p.stops(20_502).any { it.app == b })
    }

    @Test fun `an already permitted callee still protects the source operation lifecycle`() {
        val p = policy()
        p.opened(a.packageName, t1, OpenSource.LAUNCHER, 100)
        p.opened(b.packageName, t2, OpenSource.LAUNCHER, 100)
        p.reconcile(world(task(t1), task(t2, b.packageName)), 101)
        val flow = p.observeFlow(a.packageName, "source", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), 150)!!
        p.removed(t1, a.packageName, true, 200)
        p.reconcile(world(task(t2, b.packageName)), 201)
        assertTrue(p.stops(20_000).isEmpty())
        p.finishFlow(flow, false, 20_001)
        assertTrue(p.stops(20_003).isEmpty())
        assertEquals(listOf(a), p.stops(20_503).map { it.app })
    }

    @Test fun `shared task participants stay protected until Activity lifecycle ends`() {
        val p = policy(); opened(p)
        val flow = p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 150)!!
        p.flowActivity(flow, "pay-activity", true, 151)
        p.finishFlow(flow, false, 200)
        p.reconcile(world(task(t1)), 20_000)
        assertEquals("operation", p.views().first { it.app == b }.state)
        p.flowActivity(flow, "pay-activity", false, 20_001)
        p.reconcile(world(task(t1)), 20_002)
        assertTrue(p.stops(20_002).isEmpty())
        assertTrue(p.stops(20_502).any { it.app == b })
        assertFalse(p.stops(20_502).any { it.app == a })
    }

    @Test fun `operation target cannot grant unrelated services or unrelated packages`() {
        val p = policy()
        val target = CallTarget(b.packageName, "pay/Service", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, OpenSource.SYSTEM_CHOOSER, 100)!!
        p.reconcile(world(), 4000)
        assertTrue(p.entry(0, target, 4000, a).allow)
        assertFalse("an unrelated caller cannot reuse another operation", p.entry(0, target, 4000, c).allow)
        assertFalse("missing caller identity is not a grant", p.entry(0, target, 4000).allow)
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
        val flow = p.beginFlow(a.packageName, "shop-activity", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 150)!!
        p.flowActivity(flow, "pay-activity", true, 151)
        p.flowActivity(flow, "pay-activity", false, 200)
        p.finishFlow(flow, false, 201)
        p.reconcile(world(task(visible = true)), 40_000)
        assertTrue(p.flowDetails().isEmpty())
        assertTrue(p.stops(40_000).isEmpty())
        assertTrue(p.stops(40_500).any { it.app == b })
        assertFalse(p.stops(40_500).any { it.app == a })
    }

    @Test fun `unknown visible launch is observed without gaining a persistent card`() {
        val p = policy()
        val flow = p.observeFlow(a.packageName, "shop-activity", CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), 100)!!
        p.reconcile(world(task(t2, b.packageName, true)), 101)
        assertEquals(0, p.views().first { it.app == b }.cards)
        assertTrue(p.flowDetails().single().endsWith("observed"))
        p.flowActivity(flow, "pay-activity", true, 102)
        p.reconcile(world(task(t2, b.packageName)), 4000)
        assertTrue(p.entry(0, CallTarget(b.packageName, "pay/Confirm", EntryKind.PROCESS), 4000).allow)
        assertFalse(p.entry(0, CallTarget(b.packageName, "pay/Other", EntryKind.PROCESS), 4000).allow)
        assertFalse(p.extendFlow(flow, b.packageName, CallTarget(c.packageName, "bank/Confirm", EntryKind.ACTIVITY), true, 4001))
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
        assertTrue(p.entry(0, service, 4000, a).allow)
        assertFalse(p.entry(0, service.copy(component = "pay/Unrelated"), 4000).allow)
    }

    @Test fun `one removed binding does not release another connection in the operation`() {
        val p = policy()
        val target = CallTarget(b.packageName, "pay/ResultService", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, OpenSource.INTERACTION, 100)!!
        p.bind(flow, "connection-1", target, 101); p.bind(flow, "connection-2", target, 102)
        p.finishFlow(flow, false, 103)
        p.unbind("connection-1", 104)
        p.reconcile(world(), 20_000)
        assertFalse(p.flowDetails().isEmpty())
        p.unbind("connection-2", 20_001)
        p.reconcile(world(), 20_002)
        assertTrue(p.flowDetails().isEmpty())
    }

    @Test fun `another users visible task cannot protect the primary users installation`() {
        val p = policy()
        p.reconcile(world(task(t1.copy(userId = 10), visible = true)), 100)
        assertFalse(p.views().any { it.app == a && it.visible })
        assertFalse(p.entry(0, CallTarget(a.packageName, kind = EntryKind.NOTIFICATION), 100).allow)
    }

    @Test fun `manual stop can target background card but not visible work or an operation`() {
        val p = policy(); opened(p)
        assertTrue(p.valid(p.manual(a.packageName)!!))
        p.visible(a.packageName, t1, true, 200)
        assertNull(p.manual(a.packageName))
        p.visible(a.packageName, t1, false, 201)
        p.beginFlow(a.packageName, null, CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY), OpenSource.INTERACTION, 202)
        assertNull(p.manual(b.packageName))
        assertNull(p.manual(a.packageName))
    }

    @Test fun `pip eligibility changes retain the original task activation`() {
        val p = policy(); opened(p)
        p.reconcile(world(task(visible = true, eligible = false)), 200)
        assertEquals(0, p.views().single().cards)
        p.reconcile(world(task()), 300)
        assertEquals(1, p.views().single().cards)
        assertTrue(p.stops(4000).isEmpty())
    }

    @Test fun `unconfirmed task provenance is never resolved by a kill timer`() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.reconcile(world(task(t2)), 701)
        p.reconcile(world(task(t2)), 100_000)
        assertFalse(p.valid(ticket))
        assertTrue(p.stops(100_001).isEmpty())
        assertEquals("uncertain", p.views().single().state)
    }

    @Test fun `concurrent operations require a unique activity association`() {
        val p = policy()
        val target = CallTarget(b.packageName, "pay/Confirm", EntryKind.ACTIVITY)
        val first = p.beginFlow(a.packageName, "source-1", target, OpenSource.INTERACTION, 100)!!
        p.beginFlow(a.packageName, "source-2", target, OpenSource.INTERACTION, 101)!!
        assertNull(p.flowFor(a.packageName))
        assertEquals(first, p.flowFor(a.packageName, "source-1"))
    }

    @Test fun `revoking a running exception schedules a fresh targeted review`() {
        val p = policy(); opened(p)
        p.configure(rule.copy(exceptions = setOf(a), generation = 2), 150)
        p.removed(t1, a.packageName, true, 200); p.reconcile(world(), 201)
        p.configure(rule.copy(generation = 3), 400)
        assertTrue(p.stops(400).isEmpty())
        assertEquals("exception_revoked", p.stops(900).single().reason)
    }
}
