package com.mastersoft.velocast.daemon

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Manages the root `app_process` lifecycle of VeloCastDaemonMain directly from the Host UI app,
 * eliminating manual ADB pushes and Magisk script copying.
 */
object DaemonController {

    private const val MAIN_CLASS = "com.mastersoft.velocast.daemon.VeloCastDaemonMain"
    private const val PKG_NAME = "com.mastersoft.velocast.daemon"
    private const val LOG_FILE = "/data/local/tmp/velocast-daemon.log"
    private const val PID_FILE = "/data/local/tmp/velocast-daemon.pid"
    private const val MAGISK_SCRIPT = "/data/adb/service.d/velocast.sh"
    private const val LEGACY_MAGISK_SCRIPT = "/data/adb/service.d/mirror.sh"

    data class ShellResult(val exitCode: Int, val output: String)

    fun runSu(command: String, timeoutMs: Long = 5000L): ShellResult {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val errReader = BufferedReader(InputStreamReader(proc.errorStream))
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            while (reader.ready()) {
                out.append(reader.readLine()).append('\n')
            }
            while (errReader.ready()) {
                out.append(errReader.readLine()).append('\n')
            }
            if (!finished) {
                proc.destroyForcibly()
                ShellResult(-1, "Command timed out")
            } else {
                ShellResult(proc.exitValue(), out.toString().trim())
            }
        } catch (t: Throwable) {
            ShellResult(-1, t.message ?: "su execution failed")
        }
    }

    /**
     * Triggers Magisk Superuser prompt if not yet granted and verifies uid=0.
     */
    fun checkRootAccess(): Boolean {
        val res = runSu("id", 8000L)
        return res.exitCode == 0 && res.output.contains("uid=0")
    }

    fun isRunning(): Boolean {
        val cmd = """
            if [ -f "$PID_FILE" ]; then
                PID=$(cat "$PID_FILE")
                if [ -n "${'$'}PID" ] && kill -0 "${'$'}PID" 2>/dev/null; then
                    echo "RUNNING"
                    exit 0
                fi
            fi
            if pgrep -f "$MAIN_CLASS" >/dev/null 2>&1; then
                echo "RUNNING"
                exit 0
            fi
            echo "STOPPED"
        """.trimIndent()
        val res = runSu(cmd)
        return res.output.contains("RUNNING")
    }

    /**
     * Launches VeloCastDaemonMain as root directly from this installed APK's path (no /data/local/tmp copy needed).
     */
    fun startDaemon(
        context: Context,
        port: Int,
        bitrateBps: Int,
        fps: Int
    ): Result<Unit> {
        val apkPath = context.applicationInfo.sourceDir
            ?: return Result.failure(IllegalStateException("Could not resolve installed APK path"))

        // Cleanly stop any previous daemon first
        stopDaemon()

        val launchCmd = """
            nohup env CLASSPATH="$apkPath" /system/bin/app_process /system/bin $MAIN_CLASS \
                --port $port --bitrate $bitrateBps --fps $fps \
                >> "$LOG_FILE" 2>&1 </dev/null &
            echo ${'$'}! > "$PID_FILE"
        """.trimIndent()

        val res = runSu(launchCmd)
        if (res.exitCode != 0) {
            return Result.failure(IllegalStateException("Failed to start daemon: ${res.output}"))
        }

        Thread.sleep(450L)
        return if (isRunning()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Daemon exited immediately. Check logs below."))
        }
    }

    fun stopDaemon(): Result<Unit> {
        val stopCmd = """
            if [ -f "$PID_FILE" ]; then
                PID=$(cat "$PID_FILE")
                if [ -n "${'$'}PID" ]; then kill "${'$'}PID" 2>/dev/null; fi
                rm -f "$PID_FILE"
            fi
            pkill -f "$MAIN_CLASS" 2>/dev/null || true
            pkill -f "MirrorServerMain" 2>/dev/null || true
        """.trimIndent()
        runSu(stopCmd)
        return Result.success(Unit)
    }

    fun isAutoStartEnabled(): Boolean {
        val res = runSu("[ -f \"$MAGISK_SCRIPT\" ] && echo YES || echo NO")
        return res.output.contains("YES")
    }

    /**
     * Installs or removes /data/adb/service.d/velocast.sh using root.
     * The generated script dynamically queries `pm path` on boot so app updates never break auto-start.
     */
    fun setAutoStartEnabled(
        enabled: Boolean,
        port: Int,
        bitrateBps: Int,
        fps: Int
    ): Result<Unit> {
        if (!enabled) {
            val res = runSu("rm -f \"$MAGISK_SCRIPT\" \"$LEGACY_MAGISK_SCRIPT\"")
            return if (res.exitCode == 0) Result.success(Unit)
            else Result.failure(IllegalStateException(res.output))
        }

        val scriptContent = """
#!/system/bin/sh
LOG_FILE="$LOG_FILE"
PID_FILE="$PID_FILE"

while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
sleep 5

APK_PATH="${'$'}(pm path $PKG_NAME | head -n 1 | cut -d: -f2 | tr -d '\r')"
if [ -z "${'$'}APK_PATH" ] || [ ! -f "${'$'}APK_PATH" ]; then
    echo "[${'$'}(date)] VeloCast Host APK not installed; exiting." >> "${'$'}LOG_FILE"
    exit 1
fi

(
    while true; do
        CLASSPATH="${'$'}APK_PATH" /system/bin/app_process /system/bin $MAIN_CLASS \
            --port $port --bitrate $bitrateBps --fps $fps >> "${'$'}LOG_FILE" 2>&1 &
        CHILD_PID=${'$'}!
        echo "${'$'}CHILD_PID" > "${'$'}PID_FILE"
        wait "${'$'}CHILD_PID"
        sleep 3
    done
) &
exit 0
        """.trimIndent()

        val installCmd = """
            mkdir -p /data/adb/service.d
            rm -f "$LEGACY_MAGISK_SCRIPT"
            cat <<'EOF' > "$MAGISK_SCRIPT"
$scriptContent
EOF
            chmod 755 "$MAGISK_SCRIPT"
        """.trimIndent()

        val res = runSu(installCmd)
        return if (res.exitCode == 0) Result.success(Unit)
        else Result.failure(IllegalStateException("Failed to write Magisk script: ${res.output}"))
    }

    fun readRecentLogs(maxLines: Int = 50): String {
        val res = runSu("if [ -f \"$LOG_FILE\" ]; then tail -n $maxLines \"$LOG_FILE\"; fi")
        return res.output.ifBlank { "No logs yet. Tap Start Server to begin." }
    }

    fun clearLogs() {
        runSu(": > \"$LOG_FILE\"")
    }

    fun getLocalWifiIp(): String {
        return try {
            var fallbackIp: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (nif.name.startsWith("wlan")) return ip
                        if (fallbackIp == null) fallbackIp = ip
                    }
                }
            }
            fallbackIp ?: "0.0.0.0"
        } catch (_: Throwable) {
            "0.0.0.0"
        }
    }
}
