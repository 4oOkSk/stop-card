package io.github.user4ooksk.stopcard.data

import io.github.user4ooksk.stopcard.data.db.RuleDao
import io.github.user4ooksk.stopcard.data.db.RuleEntity
import io.github.user4ooksk.stopcard.model.AppRule
import io.github.user4ooksk.stopcard.model.RuleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.user4ooksk.stopcard.policy.AppKey

/**
 * Stores only user exceptions. An absent row means "follow the global card rule", so a new install
 * is managed automatically without writing a row for every app.
 */
class RulesRepository(private val dao: RuleDao, private val identity: (String) -> AppKey?) {

    val rules: Flow<Map<String, AppRule>> =
        dao.observeAll().map { list -> list.associate { it.packageName to it.toModel() } }

    suspend fun all(): Map<String, AppRule> = dao.all().associate { it.packageName to it.toModel() }

    suspend fun get(packageName: String): AppRule? = dao.get(packageName)?.toModel()

    /** An [RuleMode.ALLOW] row is an exception; [RuleMode.BLOCK] removes the exception. */
    suspend fun decide(packageNames: Collection<String>, mode: RuleMode) {
        if (packageNames.isEmpty()) return
        if (mode == RuleMode.BLOCK) {
            dao.delete(packageNames.toList())
            return
        }
        val existing = dao.all().associateBy { it.packageName }
        val now = System.currentTimeMillis()
        val records = withContext(Dispatchers.IO) { packageNames.mapNotNull { pkg ->
            val app = identity(pkg) ?: return@mapNotNull null
            RuleEntity(pkg, RuleMode.ALLOW.name, decided = true, graceSeconds = existing[pkg]?.graceSeconds, updatedAt = now,
                userId = app.userId, uid = app.uid, installedAt = app.installedAt, signer = app.signer)
        } }
        dao.upsert(records)
    }

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        decide(listOf(packageName), if (allowed) RuleMode.ALLOW else RuleMode.BLOCK)
    }

    /** Kept for the reused detail sheet; the card rule has no per-app grace period. */
    suspend fun setGrace(packageName: String, graceSeconds: Int?) {
        val rule = dao.get(packageName) ?: return
        dao.upsert(listOf(rule.copy(graceSeconds = graceSeconds, updatedAt = System.currentTimeMillis())))
    }

    suspend fun delete(packageNames: Collection<String>) {
        if (packageNames.isNotEmpty()) dao.delete(packageNames.toList())
    }

    private fun RuleEntity.toModel() = AppRule(
        packageName = packageName,
        mode = runCatching { RuleMode.valueOf(mode) }.getOrDefault(RuleMode.ALLOW),
        decided = decided,
        graceSeconds = graceSeconds,
        updatedAt = updatedAt,
        identity = if (userId != null && uid != null && installedAt != null && signer != null)
            AppKey(userId, packageName, uid, installedAt, signer) else null,
    )
}
