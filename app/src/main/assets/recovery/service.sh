#!/system/bin/sh
# KernelSU/Magisk late_start service. It has no notification/start/stop policy duties.
MODDIR=${0%/*}
SC_DIR=/data/system/stop_card
SC_ROOT=/data/adb/stop_card_recovery
mkdir -p "$SC_DIR" "$SC_ROOT" || exit 1
chown 0:1000 "$SC_DIR" || exit 1
chmod 0770 "$SC_DIR" || exit 1
chmod 0700 "$SC_ROOT" || exit 1

# One monitor survives system_server restarts. A prior PID must match both its start time and
# command; a reused PID never prevents recovery from starting.
if [ -r "$SC_ROOT/monitor.pid" ]; then
    read SC_OLD_PID SC_OLD_START < "$SC_ROOT/monitor.pid"
    if [ -r "/proc/$SC_OLD_PID/stat" ] && [ -r "/proc/$SC_OLD_PID/cmdline" ]; then
        SC_OLD_ACTUAL=$(awk '{print $22}' "/proc/$SC_OLD_PID/stat")
        if [ "$SC_OLD_ACTUAL" = "$SC_OLD_START" ] && tr '\000' ' ' < "/proc/$SC_OLD_PID/cmdline" | grep -q 'stop_card_guard/service.sh'; then exit 0; fi
    fi
fi
SC_SELF_START=$(awk '{print $22}' "/proc/$$/stat")
printf '%s %s\n' "$$" "$SC_SELF_START" > "$SC_ROOT/monitor.pid"
SC_BOOT=$(cat /proc/sys/kernel/random/boot_id) || exit 1
SC_PID=
SC_LAST_FAIL=0
SC_FAILURES=0
SC_STALE_REPORTED=0
SC_AGENT_SEEN_AT=
SC_SHUTTING_DOWN=0
SC_PREVIOUS_BOOT=
SC_PREVIOUS_WALL=0
SC_PREVIOUS_CLEAN=0
SC_PREVIOUS_FAILURES=0
SC_PREVIOUS_STARTED=0
SC_PREVIOUS_PID=
SC_PREVIOUS_FAIL_AT=0
if [ -r "$SC_ROOT/last-boot" ]; then
    read SC_PREVIOUS_BOOT SC_PREVIOUS_WALL SC_PREVIOUS_CLEAN SC_PREVIOUS_FAILURES SC_PREVIOUS_STARTED SC_PREVIOUS_PID SC_PREVIOUS_FAIL_AT < "$SC_ROOT/last-boot"
fi
sc_number() { case "$1" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }
for SC_VALUE in "$SC_PREVIOUS_WALL" "$SC_PREVIOUS_CLEAN" "$SC_PREVIOUS_FAILURES" "$SC_PREVIOUS_STARTED" "$SC_PREVIOUS_FAIL_AT"; do
    if ! sc_number "$SC_VALUE"; then SC_PREVIOUS_BOOT=; break; fi
done
SC_WALL=$(date +%s)
read SC_INITIAL_UPTIME SC_IGNORE < /proc/uptime
SC_STARTED=$((SC_WALL - ${SC_INITIAL_UPTIME%%.*}))
SC_TIME_UNKNOWN=0
SC_REASON=$(getprop ro.boot.bootreason)
if [ "$SC_PREVIOUS_BOOT" = "$SC_BOOT" ]; then
    SC_FAILURES=$SC_PREVIOUS_FAILURES
    SC_PID=$SC_PREVIOUS_PID
    if [ "$SC_PID" = 0 ]; then SC_PID=; fi
    SC_LAST_FAIL=$SC_PREVIOUS_FAIL_AT
elif [ -n "$SC_PREVIOUS_BOOT" ] && [ "$SC_PREVIOUS_CLEAN" != 1 ]; then
    case "$SC_REASON" in
        *watchdog*|*panic*|*kernel*|*hard_reset*)
            if [ "$SC_WALL" -lt "$SC_PREVIOUS_WALL" ]; then
                SC_TIME_UNKNOWN=1
            elif [ $((SC_WALL - SC_PREVIOUS_WALL)) -le 300 ] && [ $((SC_PREVIOUS_WALL - SC_PREVIOUS_STARTED)) -le 300 ]; then
                SC_FAILURES=$((SC_PREVIOUS_FAILURES + 1))
            else SC_FAILURES=1; fi ;;
        *) SC_FAILURES=0 ;;
    esac
fi

sc_disable() {
    printf '%s\n' "$1" > "$SC_DIR/emergency_disabled.tmp" || return
    chown 0:1000 "$SC_DIR/emergency_disabled.tmp" && chmod 0640 "$SC_DIR/emergency_disabled.tmp" || return
    mv -f "$SC_DIR/emergency_disabled.tmp" "$SC_DIR/emergency_disabled"
    printf '%s\n' "$1" > "$SC_ROOT/last-failure"
}
if [ "$SC_TIME_UNKNOWN" = 1 ]; then sc_disable unclean_boot_time_unknown; fi

while [ ! -e "$MODDIR/disable" ]; do
    read SC_UPTIME SC_IGNORE < /proc/uptime
    SC_NOW=${SC_UPTIME%%.*}
    SC_CURRENT_PID=$(pidof system_server)
    SC_CURRENT_PID=${SC_CURRENT_PID%% *}
    if [ -n "$(getprop sys.shutdown.requested)" ] || [ -n "$(getprop sys.powerctl)" ]; then SC_SHUTTING_DOWN=1; fi
    SC_CLEAN=$SC_SHUTTING_DOWN
    if [ -n "$SC_CURRENT_PID" ] && [ "$SC_CURRENT_PID" != "$SC_PID" ]; then
        if [ -n "$SC_PID" ] && [ "$SC_CLEAN" != 1 ]; then
            if [ $((SC_NOW - SC_LAST_FAIL)) -le 300 ]; then SC_FAILURES=$((SC_FAILURES + 1)); else SC_FAILURES=1; fi
            SC_LAST_FAIL=$SC_NOW
        fi
        SC_PID=$SC_CURRENT_PID
        SC_STALE_REPORTED=0
        SC_AGENT_SEEN_AT=
    fi
    # A stale agent heartbeat is an independent reason to withdraw control. During initial boot
    # or module installation there is no heartbeat yet, so absence alone is not a crash.
    SC_READY_PID=
    SC_READY_ELAPSED=
    SC_READY_BOOT=
    SC_READY_STATUS=
    if [ -r "$SC_DIR/ready.json" ]; then
        SC_READY_PID=$(sed -n 's/.*"pid":\([0-9]*\).*/\1/p' "$SC_DIR/ready.json")
        SC_READY_ELAPSED=$(sed -n 's/.*"elapsed":\([0-9]*\).*/\1/p' "$SC_DIR/ready.json")
        SC_READY_BOOT=$(sed -n 's/.*"boot":"\([^"]*\)".*/\1/p' "$SC_DIR/ready.json")
        SC_READY_STATUS=$(sed -n 's/.*"status":"\([^"]*\)".*/\1/p' "$SC_DIR/ready.json")
    fi
    if [ "$SC_READY_BOOT" = "$SC_BOOT" ] && [ -n "$SC_PID" ] && [ "$SC_READY_PID" = "$SC_PID" ] && [ "$SC_STALE_REPORTED" = 0 ]; then
        if [ -z "$SC_AGENT_SEEN_AT" ]; then SC_AGENT_SEEN_AT=$SC_NOW; fi
        if [ "$SC_READY_STATUS" = failed ]; then sc_disable repeated_agent_control_failure; fi
        if [ -z "$SC_READY_ELAPSED" ]; then SC_READY_ELAPSED=$((SC_AGENT_SEEN_AT * 1000)); fi
        if [ $((SC_NOW * 1000 - SC_READY_ELAPSED)) -gt 45000 ] || [ "$SC_READY_ELAPSED" -gt "$((SC_NOW * 1000 + 5000))" ]; then
            SC_STALE_REPORTED=1
            sc_disable agent_heartbeat_stale
        fi
    fi
    if [ -e "$SC_ROOT/rearm" ] && [ "$SC_READY_BOOT" = "$SC_BOOT" ] && [ "$SC_READY_PID" = "$SC_PID" ] && sc_number "$SC_READY_ELAPSED"; then
        SC_AGE=$((SC_NOW * 1000 - SC_READY_ELAPSED))
        if [ "$SC_AGE" -ge 0 ] && [ "$SC_AGE" -le 20000 ] && [ "$SC_READY_STATUS" != failed ]; then
            SC_FAILURES=0
            SC_LAST_FAIL=0
            SC_STALE_REPORTED=0
            rm -f "$SC_DIR/emergency_disabled" "$SC_ROOT/rearm"
        fi
    fi
    if [ "$SC_FAILURES" -ge 2 ]; then sc_disable repeated_system_server_failure; fi
    SC_STATUS=watching
    if [ -e "$SC_DIR/emergency_disabled" ]; then SC_STATUS=paused; fi
    printf '{"protocol":2,"boot":"%s","elapsed":%s,"status":"%s","failures":%s,"pid":%s}\n' "$SC_BOOT" "$((SC_NOW * 1000))" "$SC_STATUS" "$SC_FAILURES" "${SC_PID:-0}" > "$SC_DIR/recovery.json.tmp"
    chown 0:1000 "$SC_DIR/recovery.json.tmp" && chmod 0640 "$SC_DIR/recovery.json.tmp" && mv -f "$SC_DIR/recovery.json.tmp" "$SC_DIR/recovery.json"
    SC_WALL=$(date +%s)
    printf '%s %s %s %s %s %s %s\n' "$SC_BOOT" "$SC_WALL" "$SC_CLEAN" "$SC_FAILURES" "$SC_STARTED" "${SC_PID:-0}" "$SC_LAST_FAIL" > "$SC_ROOT/last-boot.tmp"
    mv -f "$SC_ROOT/last-boot.tmp" "$SC_ROOT/last-boot"
    sleep 5
done
