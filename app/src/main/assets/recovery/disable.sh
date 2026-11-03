#!/system/bin/sh
set -eu
SC_DIR=/data/system/stop_card
mkdir -p "$SC_DIR"
chown 0:1000 "$SC_DIR"
chmod 0770 "$SC_DIR"
printf '%s\n' manual_emergency_disable > "$SC_DIR/emergency_disabled.tmp"
chown 0:1000 "$SC_DIR/emergency_disabled.tmp"
chmod 0640 "$SC_DIR/emergency_disabled.tmp"
mv -f "$SC_DIR/emergency_disabled.tmp" "$SC_DIR/emergency_disabled"
printf '%s\n' 'Stop Card emergency marker written. The agent reports pause when responsive; the next system_server start skips enforcement hooks.'
