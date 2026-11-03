package io.github.user4ooksk.stopcard.module

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.AlarmClock
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.user4ooksk.stopcard.policy.AppKey
import java.security.MessageDigest

data class SystemFactSnapshot(
    val apps: Map<String, AppKey>,
    val groups: Map<Int, Set<String>>,
    val essential: Map<String, Set<String>>,
    val providers: Map<String, Pair<String, Int>>,
    val launcherUids: Set<Int>,
    val systemUiUid: Int,
)

/** Worker-only reads. Failure is unknown; callers retain previous facts and suspend enforcement. */
class SystemFacts(private val context: Context) {
    private val pm = context.packageManager

    fun read(): SystemFactSnapshot {
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PROVIDERS)
        check(packages.isNotEmpty()) { "Empty package inventory" }
        val apps = packages.mapNotNull { pkg -> identity(pkg)?.let { pkg.packageName to it } }.toMap()
        val reasons = HashMap<String, MutableSet<String>>()
        fun protect(pkg: String?, reason: String) {
            if (!pkg.isNullOrBlank()) reasons.getOrPut(pkg) { HashSet() } += reason
        }
        fun setting(name: String, reason: String, multiple: Boolean = false) {
            val value = Settings.Secure.getString(context.contentResolver, name).orEmpty()
            for (item in if (multiple) value.split(':') else listOf(value)) {
                if (item.isBlank()) continue
                protect(ComponentName.unflattenFromString(item)?.packageName ?: item, reason)
            }
        }
        for (pkg in packages) if (((pkg.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0) protect(pkg.packageName, "system_package")
        for (pkg in ROOT_PACKAGES) protect(pkg, "root_manager")
        protect("io.github.user4ooksk.stopcard", "controller")
        val roles = context.getSystemService(RoleManager::class.java) ?: error("Role service unavailable")
        for ((role, reason) in listOf(RoleManager.ROLE_HOME to "launcher", RoleManager.ROLE_DIALER to "dialer", RoleManager.ROLE_SMS to "sms", RoleManager.ROLE_ASSISTANT to "assistant")) {
            if (roles.isRoleAvailable(role)) {
                val holders = roles.javaClass.getMethod("getRoleHolders", String::class.java).invoke(roles, role) as? List<*> ?: error("Unknown role holders")
                holders.filterIsInstance<String>().forEach { protect(it, reason) }
            }
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val homePackage = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        protect(homePackage, "launcher")
        val ime = context.getSystemService(InputMethodManager::class.java) ?: error("Input service unavailable")
        ime.enabledInputMethodList.forEach { protect(it.packageName, "input_method") }
        setting("default_input_method", "input_method")
        setting("enabled_accessibility_services", "accessibility", true)
        setting("enabled_notification_listeners", "notification_listener", true)
        setting("autofill_service", "autofill")
        setting("always_on_vpn_app", "vpn")
        val admins = context.getSystemService(DevicePolicyManager::class.java) ?: error("Device policy unavailable")
        admins.activeAdmins.orEmpty().forEach { protect(it.packageName, "device_admin") }
        protect(context.getSystemService(WallpaperManager::class.java)?.wallpaperInfo?.packageName, "live_wallpaper")
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(AlarmClock.ACTION_SHOW_ALARMS), PackageManager.MATCH_DEFAULT_ONLY).forEach { protect(it.activityInfo?.packageName, "alarm_clock") }
        val networks = context.getSystemService(ConnectivityManager::class.java) ?: error("Connectivity unavailable")
        @Suppress("DEPRECATION")
        for (network in networks.allNetworks) {
            val caps = networks.getNetworkCapabilities(network) ?: error("Unknown network capabilities")
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val uid = caps.ownerUid
            check(uid >= 0) { "Unknown VPN owner" }
            val owners = pm.getPackagesForUid(uid) ?: error("Unknown VPN package")
            owners.forEach { protect(it, "vpn") }
        }
        val authorities = HashMap<String, Pair<String, Int>>()
        packages.forEach { pkg ->
            pkg.providers.orEmpty().forEach { provider ->
                val app = apps[provider.packageName] ?: return@forEach
                val names = android.content.pm.ProviderInfo::class.java.getField("auth" + "ority").get(provider) as? String
                names.orEmpty().split(';').filter { it.isNotBlank() }.forEach { authorities[it] = app.packageName to app.uid }
            }
        }
        val homes = reasons.filterValues { "launcher" in it }.keys.mapNotNull { apps[it]?.uid }.toSet()
        return SystemFactSnapshot(apps, apps.values.groupBy { it.uid }.mapValues { (_, group) -> group.mapTo(HashSet()) { it.packageName } },
            reasons.mapValues { it.value.toSet() }, authorities, homes, apps["com.android.systemui"]?.uid ?: -1)
    }

    fun currentIdentity(pkg: String): AppKey? {
        @Suppress("DEPRECATION")
        val info = try { pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES) }
        catch (_: PackageManager.NameNotFoundException) { return null }
        return identity(info)
    }

    fun identity(pkg: PackageInfo): AppKey? {
        val app = pkg.applicationInfo ?: return null
        if (app.uid / 100000 != 0) return null
        val signatures = pkg.signingInfo?.apkContentsSigners ?: error("Missing signing identity: ${pkg.packageName}")
        check(signatures.isNotEmpty()) { "Missing signer: ${pkg.packageName}" }
        val digest = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }.sorted().joinToString(":")
        return AppKey(0, pkg.packageName, app.uid, pkg.firstInstallTime, digest)
    }

    companion object {
        val ROOT_PACKAGES = setOf("me.weishu.kernelsu", "com.topjohnwu.magisk", "io.github.vvb2060.magisk")
        val WATCHED_SETTINGS = listOf("default_input_method", "enabled_input_methods", "enabled_accessibility_services", "enabled_notification_listeners", "autofill_service", "always_on_vpn_app")
    }
}
