package io.github.user4ooksk.stopcard.bridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only the independent health monitor is persistent root code. It never manages applications. */
class RecoveryInstaller(private val context: Context) {
    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        if (context.applicationInfo.uid / 100000 != 0) return@withContext false
        val root = RootShell()
        val module = "/data/adb/modules/stop_card_guard"
        for (name in listOf("module.prop", "service.sh", "disable.sh")) {
            val text = context.assets.open("recovery/$name").bufferedReader().use { it.readText() }
            if (!root.write("$module/$name", text)) return@withContext false
        }
        root.exec("""
            set -eu
            chown -R 0:0 /data/adb/modules/stop_card_guard
            chmod 0700 /data/adb/modules/stop_card_guard
            chmod 0600 /data/adb/modules/stop_card_guard/module.prop
            chmod 0700 /data/adb/modules/stop_card_guard/service.sh /data/adb/modules/stop_card_guard/disable.sh
            mkdir -p /data/adb/stop_card_recovery
            chmod 0700 /data/adb/stop_card_recovery
            cp /data/adb/modules/stop_card_guard/disable.sh /data/adb/stop_card_recovery/disable.sh
            chmod 0700 /data/adb/stop_card_recovery/disable.sh
            rm -f /data/adb/modules/stop_card_guard/disable
            nohup /system/bin/sh /data/adb/modules/stop_card_guard/service.sh </dev/null >/data/adb/stop_card_recovery/monitor.log 2>&1 &
        """.trimIndent()).ok
    }

    suspend fun disable(): Boolean = withContext(Dispatchers.IO) {
        context.applicationInfo.uid / 100000 == 0 && RootShell().exec("/system/bin/sh /data/adb/stop_card_recovery/disable.sh").ok
    }

    /** The monitor clears a lock only after it sees a fresh agent on this boot. Pause remains. */
    suspend fun rearm(): Boolean = withContext(Dispatchers.IO) {
        if (context.applicationInfo.uid / 100000 != 0) return@withContext false
        val root = RootShell()
        if (!root.pause()) return@withContext false
        root.exec("""
            set -eu
            test -d /data/adb/stop_card_recovery
            printf 'requested\n' > /data/adb/stop_card_recovery/rearm
            chmod 0600 /data/adb/stop_card_recovery/rearm
        """.trimIndent()).ok
    }
}
