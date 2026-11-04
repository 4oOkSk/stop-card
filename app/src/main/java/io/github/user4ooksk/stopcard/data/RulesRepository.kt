package io.github.user4ooksk.stopcard.data

import io.github.user4ooksk.stopcard.data.db.RuleDao
import io.github.user4ooksk.stopcard.data.db.RuleEntity
import io.github.user4ooksk.stopcard.model.AppRule
import io.github.user4ooksk.stopcard.model.RuleMode
import io.github.user4ooksk.stopcard.policy.AppKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stores identity-bound exceptions. An absent row follows the card rule. */
class RulesRepository(private val dao: RuleDao, private val identity: (String) -> AppKey?) {
    private val mutations = Mutex()
    private val revisions = mutableMapOf<String, Long>()
    private var revision = 0L

    class UndoBatch internal constructor(internal val changes: List<Change>) {
        val count: Int get() = changes.size
    }

    internal data class Change(
        val app: AppKey,
        val before: RuleEntity?,
        val after: RuleEntity?,
        val revision: Long,
    )

    val rules: Flow<Map<String, AppRule>> =
        dao.observeAll().map { list -> list.associate { it.packageName to it.toModel() } }

    suspend fun all(): Map<String, AppRule> = dao.all().associate { it.packageName to it.toModel() }

    suspend fun decide(packageNames: Collection<String>, mode: RuleMode): UndoBatch = mutations.withLock {
        val names = packageNames.distinct()
        val existing = dao.all().associateBy { it.packageName }
        val apps = withContext(Dispatchers.IO) { names.mapNotNull(identity) }
        val nextRevision = ++revision
        val changes = apps.mapNotNull { app ->
            // A stale row must never be restored to a different installation.
            val before = existing[app.packageName]?.takeIf { it.toModel().identity == app }
            val wasAllowed = before?.mode == RuleMode.ALLOW.name
            if (wasAllowed == (mode == RuleMode.ALLOW)) return@mapNotNull null
            Change(app, before, if (mode == RuleMode.ALLOW) app.toEntity() else null, nextRevision)
        }
        dao.applyChanges(changes.filter { it.after == null }.map { it.app.packageName }, changes.mapNotNull { it.after })
        // Even a repeated choice supersedes an older undo for this package.
        names.forEach { revisions[it] = nextRevision }
        UndoBatch(changes)
    }

    suspend fun undo(batch: UndoBatch): Int = mutations.withLock {
        val existing = dao.all().associateBy { it.packageName }
        val valid = withContext(Dispatchers.IO) {
            batch.changes.filter {
                revisions[it.app.packageName] == it.revision &&
                    identity(it.app.packageName) == it.app && existing[it.app.packageName] == it.after
            }
        }
        dao.applyChanges(valid.filter { it.before == null }.map { it.app.packageName }, valid.mapNotNull { it.before })
        val nextRevision = ++revision
        valid.forEach { revisions[it.app.packageName] = nextRevision }
        valid.size
    }

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        decide(listOf(packageName), if (allowed) RuleMode.ALLOW else RuleMode.BLOCK)
    }

    suspend fun delete(packageNames: Collection<String>) = mutations.withLock {
        if (packageNames.isNotEmpty()) dao.delete(packageNames.toList())
        val nextRevision = ++revision
        packageNames.forEach { revisions[it] = nextRevision }
    }

    suspend fun restoreExceptions(apps: Set<AppKey>) = mutations.withLock {
        val valid = withContext(Dispatchers.IO) { apps.filter { identity(it.packageName) == it } }
        dao.replaceAll(valid.map { it.toEntity() })
        // Reloading the full configuration supersedes every outstanding undo.
        revisions.clear()
    }

    suspend fun clearExceptions() = mutations.withLock {
        dao.deleteAll()
        // A full reset also supersedes batches that removed rows already absent from the table.
        revisions.clear()
    }

    private fun AppKey.toEntity() = RuleEntity(
        packageName, RuleMode.ALLOW.name, true, null, System.currentTimeMillis(),
        userId, uid, installedAt, signer,
    )

    private fun RuleEntity.toModel() = AppRule(
        packageName = packageName,
        mode = RuleMode.valueOf(mode),
        identity = if (userId != null && uid != null && installedAt != null && signer != null)
            AppKey(userId, packageName, uid, installedAt, signer) else null,
    )
}
