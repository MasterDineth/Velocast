#!/system/bin/sh
# Magisk auto-start service script for VeloCast Daemon
# Deploy to: /data/adb/service.d/mirror.sh
# Permissions: chmod 755 /data/adb/service.d/mirror.sh

SERVER_APK="/data/local/tmp/velocast-daemon.apk"
LEGACY_APK="/data/local/tmp/mirror-server.apk"
MAIN_CLASS="com.mastersoft.velocast.daemon.VeloCastDaemonMain"
PORT="27183"
WIDTH="1080"
HEIGHT="2400"
BITRATE="8000000"
FPS="60"
LOG_FILE="/data/local/tmp/velocast-daemon.log"
PID_FILE="/data/local/tmp/velocast-daemon.pid"

log_msg() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"
}

# Rotate log if > 2 MB
if [ -f "$LOG_FILE" ] && [ "$(wc -c < "$LOG_FILE")" -gt 2097152 ]; then
    mv "$LOG_FILE" "${LOG_FILE}.old"
fi

log_msg "VeloCast service.d started; waiting for boot completion..."

while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done

sleep 5

# Support either velocast-daemon.apk or legacy mirror-server.apk path
if [ ! -f "$SERVER_APK" ] && [ -f "$LEGACY_APK" ]; then
    SERVER_APK="$LEGACY_APK"
fi

if [ ! -f "$SERVER_APK" ]; then
    log_msg "ERROR: Daemon APK not found at $SERVER_APK. Exiting."
    exit 1
fi

if [ -f "$PID_FILE" ]; then
    OLD_PID="$(cat "$PID_FILE")"
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        log_msg "Stopping previous VeloCast Daemon instance (PID $OLD_PID)"
        kill "$OLD_PID" 2>/dev/null
        sleep 1
    fi
fi

(
    while true; do
        log_msg "Launching VeloCast Daemon on port $PORT (${WIDTH}x${HEIGHT} @ ${FPS}fps, ${BITRATE}bps)"
        # Run with slightly reduced nice priority only when idle; normal priority keeps audio/UI responsive
        CLASSPATH="$SERVER_APK" /system/bin/app_process /system/bin "$MAIN_CLASS" \
            --port "$PORT" \
            --width "$WIDTH" \
            --height "$HEIGHT" \
            --bitrate "$BITRATE" \
            --fps "$FPS" >> "$LOG_FILE" 2>&1 &

        CHILD_PID=$!
        echo "$CHILD_PID" > "$PID_FILE"
        wait "$CHILD_PID"
        EXIT_CODE=$?

        log_msg "VeloCast Daemon exited with code $EXIT_CODE; restarting in 3s..."
        sleep 3
    done
) &

exit 0
