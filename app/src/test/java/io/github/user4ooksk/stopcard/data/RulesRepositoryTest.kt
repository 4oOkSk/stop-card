package io.github.user4ooksk.stopcard.data

import io.github.user4ooksk.stopcard.data.db.RuleDao
import io.github.user4ooksk.stopcard.data.db.RuleEntity
import io.github.user4ooksk.stopcard.model.RuleMode
import io.github.user4ooksk.stopcard.policy.AppKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RulesRepositoryTest {
    private val a = AppKey(0, "a", 10001, 1, "signer")
    private val b = AppKey(0, "b", 10002, 1, "signer")
    private val c = AppKey(0, "c", 10003, 1, "signer")
    private val installed = mutableMapOf("a" to a, "b" to b, "c" to c)
    private val dao = MemoryRules()
    private val rules = RulesRepository(dao) { installed[it] }

    @Test fun undoOnlyChangesFromThisBatch() = runBlocking {
        rules.setAllowed("a", true)
        val batch = rules.decide(listOf("a", "b", "b", "missing"), RuleMode.ALLOW)
        assertEquals(1, batch.count)
        rules.setAllowed("c", true)
        assertEquals(1, rules.undo(batch))
        assertEquals(setOf("a", "c"), rules.all().keys)
        assertEquals(0, rules.undo(batch))
    }

    @Test fun undoRemovalRestoresOriginalIdentity() = runBlocking {
        rules.decide(listOf("a", "b"), RuleMode.ALLOW)
        val batch = rules.decide(listOf("a", "b"), RuleMode.BLOCK)
        assertTrue(rules.all().isEmpty())
        assertEquals(2, rules.undo(batch))
        assertEquals(a, rules.all().getValue("a").identity)
        assertEquals(b, rules.all().getValue("b").identity)
    }

    @Test fun laterChoiceSupersedesUndoEvenIfItReturnsToSameValue() = runBlocking {
        val batch = rules.decide(listOf("a", "b"), RuleMode.ALLOW)
        rules.setAllowed("a", false)
        rules.setAllowed("a", true)
        assertEquals(1, rules.undo(batch))
        assertEquals(setOf("a"), rules.all().keys)
    }

    @Test fun reinstallAndUninstallPreventRestoringExceptions() = runBlocking {
        rules.decide(listOf("a", "b"), RuleMode.ALLOW)
        val batch = rules.decide(listOf("a", "b"), RuleMode.BLOCK)
        installed["a"] = a.copy(installedAt = 2)
        installed.remove("b")
        assertEquals(0, rules.undo(batch))
        assertTrue(rules.all().isEmpty())
    }

    @Test fun staleExceptionIsNeverRestoredToNewInstallation() = runBlocking {
        rules.setAllowed("a", true)
        installed["a"] = a.copy(installedAt = 2)
        val batch = rules.decide(listOf("a"), RuleMode.ALLOW)
        assertEquals(1, batch.count)
        assertEquals(1, rules.undo(batch))
        assertTrue(rules.all().isEmpty())
    }

    @Test fun configurationReloadInvalidatesUndo() = runBlocking {
        val batch = rules.decide(listOf("a", "b"), RuleMode.ALLOW)
        rules.restoreExceptions(setOf(c))
        assertEquals(0, rules.undo(batch))
        assertEquals(setOf("c"), rules.all().keys)
    }

    @Test fun failedWriteDoesNotInvalidateEarlierUndo() = runBlocking {
        val batch = rules.decide(listOf("a"), RuleMode.ALLOW)
        dao.failWrite = true
        try {
            rules.decide(listOf("a"), RuleMode.BLOCK)
            fail("Expected a failed write")
        } catch (_: IllegalStateException) { }
        dao.failWrite = false
        assertEquals(1, rules.undo(batch))
        assertTrue(rules.all().isEmpty())
    }

    @Test fun clearAllSupersedesUndoEvenWhenBatchAlreadyRemovedEveryRow() = runBlocking {
        rules.setAllowed("a", true)
        val batch = rules.decide(listOf("a"), RuleMode.BLOCK)
        rules.clearExceptions()
        assertEquals(0, rules.undo(batch))
        assertTrue(rules.all().isEmpty())
    }

    private class MemoryRules : RuleDao {
        private val records = MutableStateFlow<List<RuleEntity>>(emptyList())
        var failWrite = false
        override fun observeAll() = records
        override suspend fun all() = records.value
        override suspend fun upsert(rules: List<RuleEntity>) {
            check(!failWrite)
            records.value = (records.value.associateBy { it.packageName } + rules.associateBy { it.packageName }).values.toList()
        }
        override suspend fun delete(packageNames: List<String>) {
            check(!failWrite)
            records.value = records.value.filterNot { it.packageName in packageNames }
        }
        override suspend fun deleteAll() { records.value = emptyList() }
    }
}
