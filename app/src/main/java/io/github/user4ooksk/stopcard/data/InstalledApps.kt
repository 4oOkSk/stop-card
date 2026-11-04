package io.github.user4ooksk.stopcard.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.user4ooksk.stopcard.model.InstalledApp
import io.github.user4ooksk.stopcard.module.SystemFacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Cached list of installed apps for the current user, reloaded when packages change. */
class InstalledApps(private val context: Context) {
    private val pm = context.packageManager
    private val mutex = Mutex()
    private val _apps = MutableStateFlow<Map<String, InstalledApp>?>(null)

    /** Null until the first load finishes. */
    val apps: StateFlow<Map<String, InstalledApp>?> = _apps.asStateFlow()

    suspend fun get(): Map<String, InstalledApp> = _apps.value ?: refresh()

    suspend fun refresh(): Map<String, InstalledApp> = mutex.withLock {
        withContext(Dispatchers.IO) { load() }.also { _apps.value = it }
    }

    fun versionName(packageName: String): String? = try {
        packageInfo(packageName).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun load(): Map<String, InstalledApp> {
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
        val identities = SystemFacts(context)
        return packages.mapNotNull { pkg ->
            val info = pkg.applicationInfo ?: return@mapNotNull null
            pkg.packageName to InstalledApp(
                packageName = pkg.packageName,
                label = info.loadLabel(pm).toString().ifBlank { pkg.packageName },
                isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                identity = identities.identity(pkg),
            )
        }.toMap()
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String) = pm.getPackageInfo(packageName, 0)

    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)

    val userId: Int get() = context.applicationInfo.uid / PER_USER_RANGE

    private companion object {
        const val PER_USER_RANGE = 100_000
    }
}
