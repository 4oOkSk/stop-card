package io.github.user4ooksk.stopcard.policy

import org.junit.Assert.*
import org.junit.Test

class PermissionPolicyTest {
    private val a = AppKey(0, "shop.app", 10001, 1, "a")
    private val b = AppKey(0, "pay.app", 10002, 2, "b")
    private val c = AppKey(0, "bank.app", 10003, 3, "c")
    private val t1 = TaskKey(0, 1, "boot")
    private val t2 = TaskKey(0, 2, "boot")
    private val rule = AccessRule(enabled = true, observe = false, generation = 1)
    private fun policy() = PermissionPolicy().apply {
        setApps(listOf(a, b, c), 0); configure(rule, 0); pause(false, 0); reconcile(world(), 1)
    }
    private fun task(key: TaskKey = t1, pkg: String = a.packageName, visible: Boolean = false, eligible: Boolean = true) =
        TaskFact(key, pkg, setOf(pkg), if (visible) setOf(pkg) else emptySet(), eligible)
    private fun world(vararg tasks: TaskFact, complete: Boolean = true, boot: String = "boot", visible: Set<String> = emptySet()) =
        WorldSnapshot(tasks.toList(), visible, complete, boot)
    private fun target(app: AppKey = a, kind: EntryKind = EntryKind.SERVICE, name: String = "Component") =
        CallTarget(app.packageName, "${app.packageName}/$name", kind)
    private fun opened(p: PermissionPolicy) {
        p.opened(a.packageName, t1, OpenSource.ACTIVITY, 100)
        p.reconcile(world(task(visible = true)), 100)
        p.reconcile(world(task()), 101)
    }
    private fun closing(p: PermissionPolicy): StopTicket {
        p.removed(t1, a.packageName, true, 200); p.reconcile(world(), 201)
        assertTrue(p.stops(201).isEmpty())
        return p.stops(701).single()
    }
    private fun stopped(p: PermissionPolicy, app: AppKey = a, at: Long = 10) {
        val ticket = p.manual(app.packageName)!!
        assertTrue(p.valid(ticket))
        p.completed(ticket, true, false, at)
    }

    @Test fun freshInventoryAndNewProcessesAreNotPermissionViolations() {
        val p = policy()
        for (kind in EntryKind.entries) assertTrue(p.entry(0, target(kind = kind), 2, b).allow)
        p.running(setOf(a.packageName), 3, baseline = true)
        p.running(setOf(a.packageName, b.packageName), 4, baseline = false)
        p.runningWithoutPermission(c.packageName, 5)
        assertTrue(p.stops(100_000).isEmpty())
        assertTrue(p.views().all { it.running })
    }

    @Test fun installerOpenAndAllOtherActivitiesBelongToAndroidEvenAfterAStop() {
        val p = policy(); stopped(p)
        for (caller in listOf(null, b, a)) assertTrue(p.entry(0, target(kind = EntryKind.ACTIVITY), 11, caller).allow)
        assertTrue(p.isRestricted(a.packageName)) // Admission alone does not pretend a page started.
        p.starting(a.packageName, 12)
        assertTrue(p.entry(0, target(kind = EntryKind.PROVIDER), 13, b).allow)
        p.opened(a.packageName, null, OpenSource.ACTIVITY, 14)
        assertFalse(p.isRestricted(a.packageName))
    }

    @Test fun onlySuccessfulStopRestrictsKnownBackgroundCallers() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        assertTrue(p.entry(0, target(), 702, b).allow)
        p.completed(ticket, false, false, 703)
        assertTrue(p.entry(0, target(), 704, b).allow)
        p.completed(ticket, true, false, 705)
        for (kind in EntryKind.entries.filter { it != EntryKind.ACTIVITY }) {
            assertFalse(p.entry(0, target(kind = kind), 706, b).allow)
        }
        assertTrue(p.entry(0, target(c), 707, b).allow)
    }

    @Test fun unknownCallerSystemAndOtherUsersFailOpen() {
        val p = policy(); stopped(p)
        assertTrue(p.entry(0, target(), 11).allow)
        assertTrue(p.entry(0, target(), 11, b.copy(installedAt = 99)).allow)
        val system = AppKey(0, "android", 1000, 1, "platform")
        p.setApps(listOf(a, b, c, system), 12)
        assertTrue(p.entry(0, target(), 13, system).allow)
        assertTrue(p.entry(10, target(), 13, b).allow)
    }

    @Test fun lastCardUserCloseWaitsForReviewAndExplicitStopSuccess() {
        val p = policy(); opened(p)
        val ticket = closing(p)
        assertTrue(p.valid(ticket))
        assertNotEquals("stopped", p.views().single().state)
        p.completed(ticket, true, false, 702)
        assertEquals("stopped", p.views().single().state)
        assertTrue(p.isRestricted(a.packageName))
    }

    @Test fun ordinaryFinishOrProcessAppearanceCannotCreateCloseIntent() {
        val p = policy(); opened(p)
        p.removed(t1, a.packageName, false, 200)
        p.reconcile(world(), 201)
        p.runningWithoutPermission(a.packageName, 202)
        p.visible(b.packageName, t2, true, 203)
        p.visible(b.packageName, t2, false, 204)
        p.reconcile(world(), 205)
        assertTrue(p.stops(100_000).isEmpty())
    }

    @Test fun multipleDocumentCardsSurviveOriginalCardRemoval() {
        val p = policy(); opened(p)
        val t3 = t2.copy(taskId = 3)
        p.visible(a.packageName, t2, true, 150, "doc2")
        p.visible(a.packageName, t2, false, 151, "doc2")
        p.visible(a.packageName, t3, true, 152, "doc3")
        p.visible(a.packageName, t3, false, 153, "doc3")
        p.reconcile(world(task(), task(t2), task(t3)), 154)
        assertEquals(3, p.views().single().cards)
        p.removed(t1, a.packageName, true, 200)
        p.reconcile(world(task(t2), task(t3)), 201)
        assertEquals(2, p.views().single().cards)
        assertTrue(p.stops(701).isEmpty())
        p.removed(t2, a.packageName, true, 702)
        p.reconcile(world(task(t3)), 703)
        assertTrue(p.stops(1203).isEmpty())
        p.removed(t3, a.packageName, true, 1204)
        p.reconcile(world(), 1205)
        assertTrue(p.stops(1205).isEmpty())
        assertTrue(p.valid(p.stops(1705).single()))
    }

    @Test fun actualVisiblePageActivatesOnlyItsOwnEligibleTask() {
        val p = policy()
        p.reconcile(world(task(t1, visible = true), task(t2)), 100)
        assertEquals(1, p.views().single().cards)
        p.reconcile(world(task(t1), task(t2)), 101)
        assertEquals(1, p.views().single().cards)
    }

    @Test fun excludedPagesAndPipAreProtectedWithoutInventingCards() {
        val p = policy(); opened(p)
        p.reconcile(world(task(visible = true, eligible = false)), 200)
        assertEquals(0, p.views().single().cards)
        assertEquals("visible", p.views().single().state)
        p.reconcile(world(task()), 201)
        assertEquals(1, p.views().single().cards)
        assertTrue(p.stops(10_000).isEmpty())
    }

    @Test fun reopeningBeforeProcessStartCancelsOldStopWithoutCallerProof() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.starting(a.packageName, 702)
        assertFalse(p.valid(ticket))
        p.runningWithoutPermission(a.packageName, 703)
        p.reconcile(world(), 10_000)
        assertTrue(p.stops(10_000).isEmpty())
        p.opened(a.packageName, t2, OpenSource.ACTIVITY, 10_001)
        p.reconcile(world(task(t2)), 10_002)
        assertEquals(1, p.views().single().cards)
    }

    @Test fun rejectedNativeActivityCannotRevokeAnExistingRestriction() {
        val p = policy(); stopped(p)
        p.starting(a.packageName, 11)
        p.reconcile(world(), 100_000)
        assertTrue(p.isRestricted(a.packageName))
        assertFalse(p.entry(0, target(), 100_001, b).allow)
        assertTrue(p.stops(100_501).isEmpty()) // It is still stopped; no extra force-stop.
    }

    @Test fun historicalTasksAndInitializationAreNeverSwept() {
        val p = PermissionPolicy().apply {
            setApps(listOf(a), 0); configure(rule, 0); pause(false, 0)
            reconcile(world(task()), 100)
        }
        assertEquals(0, p.views().single().cards)
        assertTrue(p.entry(0, target(), 101, a).allow)
        assertTrue(p.stops(100_000).isEmpty())
    }

    @Test fun initialExplicitCloseIsRetainedUntilFactsBecomeReady() {
        val p = PermissionPolicy()
        p.setApps(listOf(a), 0); p.configure(rule, 0); p.pause(false, 0)
        p.removed(t1, a.packageName, true, 1)
        assertTrue(p.stops(2).isEmpty())
        p.reconcile(world(), 100)
        assertTrue(p.stops(100).isEmpty())
        assertEquals("user_closed", p.stops(600).single().reason)
    }

    @Test fun activeCardCallerCanUseStoppedDependenciesWithoutComponentOrTouchProof() {
        val p = policy(); stopped(p, b)
        opened(p)
        for (kind in listOf(EntryKind.SERVICE, EntryKind.BIND, EntryKind.PROVIDER, EntryKind.BROADCAST)) {
            assertTrue(p.entry(0, target(b, kind, "Different"), 102, a).allow)
        }
        assertFalse(p.entry(0, target(b), 103, c).allow)
        val flow = p.beginFlow(a.packageName, "source", target(b), 104, observed = true)!!
        assertTrue(p.entry(0, target(b, EntryKind.BIND, "Other"), 105, c).allow)
        assertTrue(p.extendFlow(flow, b.packageName, target(c, EntryKind.SERVICE), 106))
        assertEquals("operation", p.views().first { it.app == c }.state)
    }

    @Test fun completedOperationAloneDoesNotStopItsParticipants() {
        val p = policy()
        val flow = p.beginFlow(a.packageName, "source", target(b), 100)!!
        p.flowActivity(flow, "service", true, 101)
        p.flowActivity(flow, "service", false, 102)
        p.finishFlow(flow, 103)
        assertTrue(p.liveFlowIds().isEmpty())
        assertTrue(p.stops(100_000).isEmpty())
        assertTrue(p.entry(0, target(b), 100_001, c).allow)
    }

    @Test fun pendingUserCloseWaitsForLastUnbindAndActualDestructionBeyondTimeout() {
        val p = policy()
        val service = target(b, EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, "source", service, 100)!!
        assertTrue(p.bind(flow, "connection", service, 101, a))
        p.flowActivity(flow, "service-record", true, 102)
        p.removed(t2, b.packageName, true, 103)
        p.finishFlow(flow, 104)
        p.reconcile(world(), 100_000)
        assertTrue(p.stops(100_000).isEmpty())
        p.unbind("connection", 100_001)
        assertTrue(p.stops(150_000).isEmpty())
        p.flowActivity(flow, "service-record", false, 150_001)
        p.reconcile(world(), 150_002)
        assertTrue(p.stops(150_002).isEmpty())
        assertEquals(b, p.stops(150_502).single().app)
    }

    @Test fun orderedBroadcastRecordProtectsGapBetweenReceivers() {
        val p = policy()
        val flow = p.beginFlow(a.packageName, null, target(b, EntryKind.BROADCAST), 100)!!
        p.flowActivity(flow, "record", true, 101)
        p.flowActivity(flow, "receiver0", true, 102)
        p.removed(t2, b.packageName, true, 103)
        p.flowActivity(flow, "receiver0", false, 104); p.finishFlow(flow, 105)
        p.reconcile(world(), 100_000)
        assertTrue(p.stops(100_000).isEmpty())
        p.flowActivity(flow, "receiver1", true, 100_001)
        p.flowActivity(flow, "receiver1", false, 100_002)
        p.flowActivity(flow, "record", false, 100_003)
        p.reconcile(world(), 100_004)
        assertTrue(p.stops(100_004).isEmpty())
        assertEquals(b, p.stops(100_504).single().app)
    }

    @Test fun idleOperationExpiresWithoutCreatingCloseIntent() {
        val p = policy()
        p.beginFlow(a.packageName, null, target(b), 100)
        p.reconcile(world(), 30_100)
        assertTrue(p.liveFlowIds().isEmpty())
        assertTrue(p.stops(30_600).isEmpty())
    }

    @Test fun systemBindingCreatedPausedSurvivesResumeAndHiddenTimeout() {
        val p = policy(); p.pause(true, 100)
        val tile = target(b, EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, null, tile, 101, systemBinding = true)!!
        assertEquals(flow, p.beginFlow(a.packageName, null, tile, 102, systemBinding = true))
        p.bind(flow, "tile", tile, 103, a); p.flowActivity(flow, "service", true, 104)
        p.removed(t2, b.packageName, true, 105); p.finishFlow(flow, 106)
        p.pause(false, 100_000); p.reconcile(world(), 100_001)
        assertTrue(p.stops(100_001).isEmpty())
        assertTrue(p.systemBound(b))
        assertTrue(p.entry(0, target(b, EntryKind.SERVICE, "Helper"), 100_002, b).allow)
    }

    @Test fun widgetVpnAndExceptionsCancelPendingStopAndRemovalDoesNotInventOne() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.configure(rule.copy(essential = mapOf(a.packageName to setOf("app_widget", "vpn"))), 702)
        assertFalse(p.valid(ticket)); assertNull(p.manual(a.packageName))
        assertTrue(p.stops(1000).isEmpty())
        p.configure(rule, 1001)
        assertTrue(p.stops(1001).isEmpty())
        assertTrue(p.valid(p.stops(1501).single())) // Existing close intent still applies.
        val q = policy(); q.running(setOf(a.packageName), 2, baseline = false)
        q.configure(rule.copy(exceptions = setOf(a)), 3); q.configure(rule, 4)
        assertTrue(q.stops(100_000).isEmpty())
    }

    @Test fun uncertainCallerDefersResurrectionStopUntilFreshReview() {
        val p = policy(); stopped(p)
        p.reviewLater(a.packageName, 11); p.runningWithoutPermission(a.packageName, 12)
        p.reconcile(world(), 30_010); assertTrue(p.stops(30_010).isEmpty())
        p.reconcile(world(), 30_011); assertTrue(p.stops(30_011).isEmpty())
        assertTrue(p.valid(p.stops(30_511).single()))
    }

    @Test fun completedCloseRestrictsResurrectionButNotUnrelatedProcesses() {
        val p = policy(); stopped(p)
        p.running(setOf(a.packageName, b.packageName), 11, baseline = false)
        assertTrue(p.stops(11).isEmpty())
        assertEquals(listOf(a), p.stops(511).map { it.app })
    }

    @Test fun manualStopRevokesOldThumbnailUntilAnyNativePageOpen() {
        val p = policy(); opened(p); stopped(p, at = 200)
        p.reconcile(world(task()), 201)
        assertEquals("stopped", p.views().single().state)
        assertFalse(p.entry(0, target(), 202, b).allow)
        p.opened(a.packageName, t1, OpenSource.ACTIVITY, 203)
        p.reconcile(world(task()), 204)
        assertEquals("card", p.views().single().state)
        assertFalse(p.isRestricted(a.packageName))
    }

    @Test fun pauseConfigurationAndUnknownFactsInvalidateOldTickets() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.pause(true, 702); assertFalse(p.valid(ticket))
        p.pause(false, 703); p.configure(rule.copy(generation = 2), 704)
        assertFalse(p.valid(ticket)); assertTrue(p.stops(704).isEmpty())
        val next = p.stops(1204).single(); p.unknown(1205)
        assertFalse(p.valid(next)); p.reconcile(world(), 5000)
        assertTrue(p.stops(5000).isEmpty()); assertTrue(p.valid(p.stops(5500).single()))
    }

    @Test fun newUnobservedTaskDefersStopWithoutDeadlineBasedKill() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.reconcile(world(task(t2)), 702)
        assertFalse(p.valid(ticket)); p.reconcile(world(task(t2)), 100_000)
        assertTrue(p.stops(100_000).isEmpty())
    }

    @Test fun installationChangeRevokesRestrictionsExceptionsAndOldTickets() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.completed(ticket, true, false, 702)
        p.configure(rule.copy(exceptions = setOf(a)), 703)
        val replacement = a.copy(installedAt = 99, signer = "new")
        p.setApps(listOf(replacement, b, c), 704); p.reconcile(world(), 705)
        assertFalse(p.valid(ticket)); assertFalse(p.isRestricted(a.packageName))
        assertTrue(p.entry(0, target(), 706, b).allow)
    }

    @Test fun reinstallingSourceDropsOperationWithoutStoppingOtherParticipants() {
        val p = policy(); p.beginFlow(a.packageName, "source", target(b), 100)
        p.setApps(listOf(a.copy(installedAt = 99), b, c), 101)
        assertTrue(p.liveFlowIds().isEmpty()); assertTrue(p.stops(100_000).isEmpty())
    }

    @Test fun sharedUidNewSessionAndUnknownMemberBlockNativeStop() {
        val sibling = b.copy(uid = a.uid)
        val p = policy(); p.setApps(listOf(a, sibling, c), 2); opened(p)
        val ticket = closing(p)
        assertTrue(p.valid(ticket, setOf(a.packageName, b.packageName)))
        p.starting(b.packageName, 702)
        assertFalse(p.valid(ticket, setOf(a.packageName, b.packageName)))
        assertFalse(p.valid(ticket, setOf(a.packageName, "unknown")))
    }

    @Test fun otherUserVisibilityAndNewBootDoNotReuseOldPolicyState() {
        val p = policy(); stopped(p)
        p.reconcile(world(task(t1.copy(userId = 10), visible = true)), 11)
        assertFalse(p.views().any { it.visible })
        p.reconcile(world(boot = "next"), 12)
        assertFalse(p.isRestricted(a.packageName)); assertTrue(p.stops(100_000).isEmpty())
    }

    @Test fun observeOnlyRecordsExplicitCloseAndNeverCreatesRestrictions() {
        val p = policy(); opened(p)
        p.configure(rule.copy(observe = true, generation = 2), 150)
        val ticket = closing(p)
        assertFalse(p.valid(ticket)); p.completed(ticket, false, true, 702)
        assertTrue(p.stops(10_000).isEmpty()); assertFalse(p.isRestricted(a.packageName))
        p.configure(rule.copy(generation = 3), 10_001)
        assertTrue(p.stops(10_001).isEmpty()); assertTrue(p.valid(p.stops(10_501).single()))
    }

    @Test fun failedStopRetriesWithoutRestrictingOrFalselyMarkingStopped() {
        val p = policy(); opened(p); val ticket = closing(p)
        p.completed(ticket, false, false, 702)
        assertFalse(p.isRestricted(a.packageName)); assertNotEquals("stopped", p.views().single().state)
        assertTrue(p.stops(30_701).isEmpty()); assertEquals(1, p.stops(30_702).size)
    }

    @Test fun manualStopCannotInterruptVisibleOperationOrSystemRole() {
        val p = policy(); opened(p); assertNotNull(p.manual(a.packageName))
        p.visible(a.packageName, t1, true, 200); assertNull(p.manual(a.packageName))
        p.visible(a.packageName, t1, false, 201)
        p.beginFlow(a.packageName, null, target(b), 202)
        assertNull(p.manual(a.packageName)); assertNull(p.manual(b.packageName))
    }

    @Test fun cachedPriorityProtectsActiveSessionsAndEndsOnPause() {
        val p = policy(); opened(p)
        val group = setOf(a.packageName, b.packageName)
        assertFalse(p.keepAlive(group)); p.beginFlow(a.packageName, null, target(b), 150)
        assertTrue(p.keepAlive(group)); p.pause(true, 151); assertFalse(p.keepAlive(group))
    }

    @Test fun unrelatedUnbindAndActivityFinishDoNotMutateFlowsWhilePendingWorkTracksDeadlines() {
        val p = policy()
        assertFalse(p.hasPendingWork())
        assertFalse(p.unbind("unrelated-conn", 50))
        assertFalse(p.sourceActivityRemoved("unrelated-activity", 51))
        val service = target(b, EntryKind.BIND)
        val flow = p.beginFlow(a.packageName, "source-act", service, 100)!!
        assertTrue(p.hasPendingWork())
        assertTrue(p.bind(flow, "real-conn", service, 101, a))
        assertFalse(p.unbind("unrelated-conn", 102))
        assertTrue(p.sourceActivityRemoved("source-act", 103))
        assertTrue(p.unbind("real-conn", 104))
        assertFalse(p.hasPendingWork())
    }
}
