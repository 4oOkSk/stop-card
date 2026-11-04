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

    /** An [RuleMode.ALLOW] row is an exception; [RuleMode.BLOCK] removes the exception. */
    suspend fun decide(packageNames: Collection<String>, mode: RuleMode) {
        if (packageNames.isEmpty()) return
        if (mode == RuleMode.BLOCK) {
            dao.delete(packageNames.toList())
            return
        }
        val now = System.currentTimeMillis()
        val records = withContext(Dispatchers.IO) { packageNames.mapNotNull { pkg ->
            val app = identity(pkg) ?: return@mapNotNull null
            RuleEntity(pkg, RuleMode.ALLOW.name, decided = true, graceSeconds = null, updatedAt = now,
                userId = app.userId, uid = app.uid, installedAt = app.installedAt, signer = app.signer)
        } }
        dao.upsert(records)
    }

    suspend fun setAllowed(packageName: String, allowed: Boolean) {
        decide(listOf(packageName), if (allowed) RuleMode.ALLOW else RuleMode.BLOCK)
    }

    suspend fun delete(packageNames: Collection<String>) {
        if (packageNames.isNotEmpty()) dao.delete(packageNames.toList())
    }

    suspend fun restoreExceptions(apps: Set<AppKey>) {
        val valid = withContext(Dispatchers.IO) { apps.filter { identity(it.packageName) == it } }
        dao.replaceAll(valid.map { app -> RuleEntity(app.packageName, RuleMode.ALLOW.name, true, null, System.currentTimeMillis(),
            app.userId, app.uid, app.installedAt, app.signer) })
    }

    private fun RuleEntity.toModel() = AppRule(
        packageName = packageName,
        mode = RuleMode.valueOf(mode),
        identity = if (userId != null && uid != null && installedAt != null && signer != null)
            AppKey(userId, packageName, uid, installedAt, signer) else null,
    )
}
