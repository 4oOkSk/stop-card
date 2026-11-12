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
            if command -v setsid >/dev/null 2>&1; then
                nohup setsid /system/bin/sh /data/adb/modules/stop_card_guard/service.sh </dev/null >/data/adb/stop_card_recovery/monitor.log 2>&1 &
            else
                nohup /system/bin/sh /data/adb/modules/stop_card_guard/service.sh </dev/null >/data/adb/stop_card_recovery/monitor.log 2>&1 &
            fi
        """.trimIndent()).ok
    }

    suspend fun disable(): Boolean = withContext(Dispatchers.IO) {
        if (context.applicationInfo.uid / 100000 != 0) return@withContext false
        val root = RootShell()
        val script = context.assets.open("recovery/disable.sh").bufferedReader().use { it.readText() }
        if (!root.write("/data/adb/stop_card_recovery/disable.sh", script)) return@withContext false
        root.exec("""
            set -eu
            chmod 0700 /data/adb/stop_card_recovery /data/adb/stop_card_recovery/disable.sh
            /system/bin/sh /data/adb/stop_card_recovery/disable.sh
        """.trimIndent()).ok
    }

    /** Clears an emergency lock only when the current boot's agent heartbeat is fresh. Pause remains. */
    suspend fun rearm(): Boolean = withContext(Dispatchers.IO) {
        if (context.applicationInfo.uid / 100000 != 0) return@withContext false
        val root = RootShell()
        if (!root.pause()) return@withContext false
        root.exec("""
            set -eu
            mkdir -p /data/adb/stop_card_recovery
            chmod 0700 /data/adb/stop_card_recovery
            monitor_alive=0
            if [ -r /data/adb/stop_card_recovery/monitor.pid ]; then
                read old_pid old_start < /data/adb/stop_card_recovery/monitor.pid || true
                if [ -n "${'$'}{old_pid:-}" ] && [ -r "/proc/${'$'}old_pid/stat" ] && [ -r "/proc/${'$'}old_pid/cmdline" ]; then
                    actual_start=${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}old_pid/stat")
                    if [ "${'$'}actual_start" = "${'$'}{old_start:-}" ] && tr '\000' ' ' < "/proc/${'$'}old_pid/cmdline" | grep -q 'stop_card_guard/service.sh'; then
                        monitor_alive=1
                    fi
                fi
            fi
            if [ "${'$'}monitor_alive" = 1 ]; then
                printf 'requested\n' > /data/adb/stop_card_recovery/rearm
                chmod 0600 /data/adb/stop_card_recovery/rearm
                for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
                    [ ! -e /data/system/stop_card/emergency_disabled ] && exit 0
                    sleep 0.5
                done
                exit 1
            fi
            [ -r /data/system/stop_card/ready.json ] || exit 1
            boot=${'$'}(cat /proc/sys/kernel/random/boot_id)
            sys_pid=${'$'}(pidof system_server)
            sys_pid=${'$'}{sys_pid%% *}
            [ -n "${'$'}sys_pid" ] || exit 1
            read up _ < /proc/uptime
            now_ms=${'$'}(( ${'$'}{up%%.*} * 1000 ))
            ready_pid=${'$'}(sed -n 's/.*"pid":\([0-9]*\).*/\1/p' /data/system/stop_card/ready.json)
            ready_elapsed=${'$'}(sed -n 's/.*"elapsed":\([0-9]*\).*/\1/p' /data/system/stop_card/ready.json)
            ready_boot=${'$'}(sed -n 's/.*"boot":"\([^"]*\)".*/\1/p' /data/system/stop_card/ready.json)
            ready_status=${'$'}(sed -n 's/.*"status":"\([^"]*\)".*/\1/p' /data/system/stop_card/ready.json)
            [ "${'$'}ready_boot" = "${'$'}boot" ] && [ "${'$'}ready_pid" = "${'$'}sys_pid" ] && [ "${'$'}ready_status" != failed ] || exit 1
            case "${'$'}ready_elapsed" in ''|*[!0-9]*) exit 1 ;; esac
            age=${'$'}((now_ms - ready_elapsed))
            [ "${'$'}age" -ge -5000 ] && [ "${'$'}age" -le 20000 ] || exit 1
            rm -f /data/system/stop_card/emergency_disabled /data/adb/stop_card_recovery/rearm /data/adb/stop_card_recovery/last-boot
        """.trimIndent(), timeoutMs = 10_000).ok
    }
}
