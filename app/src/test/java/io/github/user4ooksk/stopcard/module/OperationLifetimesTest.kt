package io.github.user4ooksk.stopcard.module

import io.github.user4ooksk.stopcard.module.OperationLifetimes.Kind
import io.github.user4ooksk.stopcard.policy.*
import org.junit.Assert.*
import org.junit.Test

class OperationLifetimesTest {
    private class NativeRecord(var alive: Boolean = true)
    private val a = AppKey(0, "source.app", 10001, 1, "a")
    private val b = AppKey(0, "target.app", 10002, 2, "b")
    private fun policy() = PermissionPolicy().apply {
        setApps(listOf(a, b), 0)
        configure(AccessRule(enabled = true, observe = false), 0)
        pause(false, 0)
        reconcile(WorldSnapshot(emptyList(), emptySet(), true, "boot"), 1)
    }
    private fun close(p: PermissionPolicy) = p.removed(TaskKey(0, 1, "boot"), b.packageName, true, 103)
    private fun end(p: PermissionPolicy, flow: Long, token: String, now: Long) {
        p.flowActivity(flow, token, false, now)
        p.finishFlow(flow, now)
    }

    @Test fun nativeProviderReleaseRepairsMissingEndCallbackWithoutWaitingForProcessDeath() {
        val p = policy(); val lifetimes = OperationLifetimes()
        val target = CallTarget(b.packageName, "target.app/Provider", EntryKind.PROVIDER)
        val flow = p.beginFlow(a.packageName, null, target, 100)!!
        val record = NativeRecord()
        lifetimes.started("provider", record, Kind.PROVIDER)
        p.flowActivity(flow, "provider", true, 101); close(p)
        assertTrue(p.stops(100_000).isEmpty())
        record.alive = false // Native refcount reached zero; no hook notification was delivered.
        lifetimes.review(Kind.PROVIDER, p.operationTokens(), { r, _ -> (r as NativeRecord).alive }) {
            end(p, flow, it, 100_001)
        }
        assertTrue(p.liveFlowIds().isEmpty())
        assertTrue(p.stops(100_001).isEmpty())
        assertEquals(b, p.stops(100_501).single().app)
    }

    @Test fun liveBindingAndDestructionSurviveTimeoutAndOnlyNativeCompletionReleasesThem() {
        val p = policy(); val lifetimes = OperationLifetimes()
        val target = CallTarget(b.packageName, "target.app/Service", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, 100)!!
        val connection = NativeRecord(); val service = NativeRecord()
        lifetimes.started("connection", connection, Kind.BINDING)
        lifetimes.started("service", service, Kind.SERVICE)
        p.bind(flow, "connection", target, 101, a); p.flowActivity(flow, "service", true, 102)
        close(p); p.finishFlow(flow, 104)
        lifetimes.review(Kind.BINDING, p.operationTokens(), { r, _ -> (r as NativeRecord).alive }) { p.unbind(it, 100_000) }
        lifetimes.review(Kind.SERVICE, p.operationTokens(), { r, _ -> (r as NativeRecord).alive }) { end(p, flow, it, 100_000) }
        assertTrue(p.stops(100_000).isEmpty())
        connection.alive = false
        lifetimes.review(Kind.BINDING, p.operationTokens(), { r, _ -> (r as NativeRecord).alive }) { p.unbind(it, 100_001) }
        assertTrue(p.stops(150_000).isEmpty()) // onDestroy still has a native record.
        service.alive = false
        lifetimes.review(Kind.SERVICE, p.operationTokens(), { r, _ -> (r as NativeRecord).alive }) { end(p, flow, it, 150_001) }
        assertTrue(p.stops(150_001).isEmpty())
        assertEquals(b, p.stops(150_501).single().app)
    }

    @Test fun acceptedConnectionsDoNotBecomePermanentUnknownProtectionAtMetadataLimit() {
        val p = policy(); val target = CallTarget(b.packageName, "target.app/Service", EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, target, 100)!!
        repeat(40) { assertTrue(p.bind(flow, "connection$it", target, 101, a)) }
        close(p); p.finishFlow(flow, 104)
        assertTrue(p.stops(100_000).isEmpty())
        repeat(40) { p.unbind("connection$it", 100_001) }
        assertTrue(p.liveFlowIds().isEmpty())
        assertTrue(p.stops(100_001).isEmpty())
        assertEquals(b, p.stops(100_501).single().app)
    }
}
