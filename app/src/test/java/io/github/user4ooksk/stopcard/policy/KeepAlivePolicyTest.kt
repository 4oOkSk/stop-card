package io.github.user4ooksk.stopcard.policy

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class KeepAlivePolicyTest {
    private fun app(i: Int) = AppKey(0, "app.$i", 10000+i, 1, "signer")
    private fun view(i: Int) = PermissionView(app(i), 1, false, 0, "card", emptySet(), null, true, i.toLong())
    private fun process(i: Int, rss: Long = 100_000) = PriorityProcess(app(i), i, "app.$i", rss)
    private val memory = MemoryPressure(10_000_000, 5_000_000, 100_000, false)

    @Test fun nativeServiceHostingKindsAreCoveredWithoutClassifyingPagesOrUnknownKindsAsBackground() {
        for (type in listOf("started-service", "bound-service", "service", "broadcast", "content provider", "restart"))
            assertEquals(type, ProcessHosting.BACKGROUND, ProcessHosting.of(type))
        for (type in listOf("activity", "top-activity", "next-activity", "next-top-activity"))
            assertEquals(type, ProcessHosting.ACTIVITY, ProcessHosting.of(type))
        for (type in listOf("", "added application", "backup", "unknown-service-kind"))
            assertEquals(type, ProcessHosting.UNKNOWN, ProcessHosting.of(type))
    }

    @Test fun recentCardsReceiveTieredBoundedProtection() {
        val result = KeepAlivePolicy.select((1..12).map(::view), (1..12).map { process(it) }, memory)
        assertEquals((5..12).toSet(), result.keys)
        assertEquals(3, result.values.count { it.adj == 700 })
        assertEquals(700, result[12]!!.adj)
        assertEquals(800, result[5]!!.adj)
    }

    @Test fun pressureReleasesGrantsAndModeratePressureLowersProtection() {
        val views = (1..6).map(::view); val processes = (1..6).map { process(it) }
        assertTrue(KeepAlivePolicy.select(views, processes, memory.copy(lowMemory = true)).isEmpty())
        assertTrue(KeepAlivePolicy.select(views, processes, memory.copy(availableKb = 900_000)).isEmpty())
        val limited = KeepAlivePolicy.select(views, processes, memory.copy(availableKb = 1_500_000))
        assertEquals(3, limited.size); assertTrue(limited.values.all { it.adj == 800 })
    }

    @Test fun memoryBudgetUnknownRssIdentityAndAmbiguousProcessesDoNotGetGrants() {
        val result = KeepAlivePolicy.select((1..5).map(::view), listOf(process(1, 1_100_000), process(2, 1_100_000),
            process(3, 0), process(4), process(4).copy(pid = 99), process(5).copy(app = app(5).copy(installedAt = 2))), memory)
        assertEquals(setOf(2), result.keys)
        assertTrue(KeepAlivePolicy.select(listOf(view(1).copy(cards = 0)), listOf(process(1)), memory).isEmpty())
        assertTrue(KeepAlivePolicy.select(listOf(view(1).copy(state = "essential")), listOf(process(1)), memory).isEmpty())
    }

    @Test fun strongerNativePrioritiesArePreserved() {
        val grant = PriorityGrant(process(1), 700)
        for (adj in listOf(-1000, 0, 100, 600, 700, 800, 1001)) assertEquals(adj, KeepAlivePolicy.adjust(adj, grant))
        assertEquals(700, KeepAlivePolicy.adjust(950, grant))
        assertEquals(950, KeepAlivePolicy.adjust(950, null))
    }

    @Test fun singleAndBatchPacketsOnlyChangeSelectedCachedScoresAndKeepFlagsAndBuffers() {
        val single = ByteBuffer.allocate(24).apply { putInt(1); putInt(1); putInt(10001); putInt(950); putInt(17); putInt(1) }
        val rewritten = LmkdPriority.rewrite(single) { pid, uid, adj -> if (pid == 1 && uid == 10001) 700 else adj }!!
        assertEquals(950, single.getInt(12)); assertEquals(700, rewritten.getInt(12))
        assertEquals(17, rewritten.getInt(16)); assertEquals(1, rewritten.getInt(20))
        assertEquals(24, single.position()); assertEquals(24, rewritten.position())
        val batch = ByteBuffer.allocate(64).apply {
            putInt(11)
            for ((pid, adj) in listOf(1 to 950, 2 to 100, 3 to 999)) { putInt(pid); putInt(10000+pid); putInt(adj); putInt(19); putInt(0) }
        }
        val changed = LmkdPriority.rewrite(batch) { pid, _, adj -> if (pid == 1 || pid == 2) 700 else adj }!!
        assertEquals(700, changed.getInt(12)); assertEquals(100, changed.getInt(32)); assertEquals(999, changed.getInt(52))
        assertEquals(19, changed.getInt(16)); assertEquals(950, batch.getInt(12))
    }

    @Test fun unknownMalformedAndOtherByteOrdersAreUntouched() {
        for (packet in listOf(ByteBuffer.allocate(16).apply { putInt(2); putInt(1); putInt(10001); putInt(950) },
            ByteBuffer.allocate(20).apply { putInt(11); putInt(1); putInt(10001); putInt(950); putInt(0) },
            ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { putInt(1); putInt(1); putInt(10001); putInt(950) })) {
            assertNull(LmkdPriority.rewrite(packet) { _, _, _ -> fail("Invalid packet was interpreted"); 700 })
        }
    }

    @Test fun wakeRelationsRemainPerSourcePerComponentAndDropReinstalledIdentities() {
        val ledger = WakeRelations(2)
        val target = CallTarget(app(2).packageName, "app.2/Service", EntryKind.SERVICE)
        ledger.record(app(1), app(2), target, true, 1)
        ledger.record(app(1), app(2), target, true, 2)
        assertEquals(2L, ledger.views().single().denied)
        ledger.record(app(3), app(2), target, false, 3)
        assertEquals(2, ledger.views().size)
        ledger.record(app(1), app(2), target.copy(component = "Other"), true, 4)
        assertEquals(2, ledger.views().size)
        ledger.retain(mapOf(app(1).packageName to app(1).copy(installedAt = 99), app(2).packageName to app(2), app(3).packageName to app(3)))
        assertEquals(app(3), ledger.views().single().key.source)
    }
}
