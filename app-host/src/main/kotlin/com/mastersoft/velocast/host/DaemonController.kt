package com.mastersoft.velocast.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.mastersoft.velocast.protocol.ProtocolV2
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Manages the root `app_process` lifecycle of `com.mastersoft.velocast.server.Main` from the Host UI app.
 *
 * Uses `setsid` + `trap '' HUP` when launching `app_process` so the server is detached from the
 * transient `su -c` subshell and survives after the Host UI exits or backgrounds.
 */
object DaemonController {

    private const val MAIN_CLASS = "com.mastersoft.velocast.server.Main"
    private const val PKG_NAME = "com.mastersoft.velocast.host"
    private const val STATE_DIR = "/data/adb/velocast"
    private const val LOG_FILE = "$STATE_DIR/server.log"
    private const val PID_FILE = "$STATE_DIR/server.pid"
    private const val SECRET_FILE = "$STATE_DIR/secret.hex"
    private const val MAGISK_SCRIPT = "/data/adb/service.d/velocast.sh"
    private const val SECRET_BYTES = 32

    data class ShellResult(val exitCode: Int, val output: String)

    fun runSu(command: String, timeoutMs: Long = 5000L): ShellResult = try {
        val proc = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        val out = StringBuilder()
        val readerThread = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        out.append(line).append('\n')
                    }
                }
            } catch (e: IOException) {
                Log.w("DaemonController", "Error reading su stdout", e)
            }
        }
        readerThread.start()

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            readerThread.join(300L)
            ShellResult(-1, out.toString().trim().ifEmpty { "Command timed out" })
        } else {
            readerThread.join(500L)
            ShellResult(proc.exitValue(), out.toString().trim())
        }
    } catch (t: Throwable) {
        ShellResult(-1, t.message ?: "su execution failed")
    }

    fun checkRootAccess(): Boolean {
        val res = runSu("id", 8000L)
        return res.exitCode == 0 && res.output.contains("uid=0")
    }

    fun isRunning(port: Int = ProtocolV2.DEFAULT_PORT): Boolean {
        val portHex = "%04X".format(port)
        val cmd = """
            if [ -f "$PID_FILE" ]; then
                PID=$(cat "$PID_FILE" 2>/dev/null)
                if [ -n "${'$'}PID" ] && [ -d "/proc/${'$'}PID" ]; then
                    if [ -r "/proc/${'$'}PID/cmdline" ]; then
                        read -r cmd < "/proc/${'$'}PID/cmdline" 2>/dev/null
                        case "${'$'}cmd" in
                            *$MAIN_CLASS*)
                                echo "RUNNING"
                                exit 0
                                ;;
                        esac
                    fi
                fi
            fi
            for p in /proc/[0-9]*; do
                pid=${'$'}{p#/proc/}
                if [ "${'$'}pid" = "${'$'}${'$'}" ]; then continue; fi
                if [ -r "${'$'}p/cmdline" ]; then
                    read -r cmd < "${'$'}p/cmdline" 2>/dev/null
                    case "${'$'}cmd" in
                        *$MAIN_CLASS*)
                            echo "RUNNING"
                            exit 0
                            ;;
                    esac
                fi
            done
            if grep -q -i ":$portHex .* 0A" /proc/net/tcp /proc/net/tcp6 2>/dev/null; then
                echo "RUNNING"
                exit 0
            fi
            echo "STOPPED"
        """.trimIndent()
        val res = runSu(cmd, 3000L)
        return res.output.contains("RUNNING")
    }

    fun startDaemon(context: Context, port: Int): Result<Unit> {
        val apkPath = context.applicationInfo.sourceDir
            ?: return Result.failure(IllegalStateException("Could not resolve installed APK path"))

        stopDaemon(port)

        val secretInitRes = DaemonScripts.ensureSecretFile()
        if (secretInitRes.isFailure) return secretInitRes

        val launchCmd = """
            mkdir -p "$STATE_DIR"
            chmod 700 "$STATE_DIR"
            : > "$LOG_FILE"
            chmod 600 "$LOG_FILE"
            trap '' HUP
            export CLASSPATH="$apkPath"
            if command -v setsid >/dev/null 2>&1; then
                setsid /system/bin/app_process /system/bin $MAIN_CLASS --port $port --bind 0.0.0.0 --state-dir "$STATE_DIR" --secret-from-stdin < "$SECRET_FILE" >> "$LOG_FILE" 2>&1 &
            else
                /system/bin/app_process /system/bin $MAIN_CLASS --port $port --bind 0.0.0.0 --state-dir "$STATE_DIR" --secret-from-stdin < "$SECRET_FILE" >> "$LOG_FILE" 2>&1 &
            fi
            DAEMON_PID=${'$'}!
            echo "${'$'}DAEMON_PID" > "$PID_FILE"
            chmod 600 "$PID_FILE"
            echo "STARTED:${'$'}DAEMON_PID"
        """.trimIndent()

        val res = runSu(launchCmd, 5000L)
        if (res.exitCode != 0 && !res.output.contains("STARTED:")) {
            return Result.failure(IllegalStateException("Failed to launch daemon: ${res.output}"))
        }

        // Wait up to 3 seconds for daemon to initialize and bind the port
        for (i in 1..15) {
            Thread.sleep(200L)
            if (isRunning(port)) {
                return Result.success(Unit)
            }
        }

        val errLog = readRecentLogs(30)
        return Result.failure(
            IllegalStateException("Daemon failed to start or port not bound:\n$errLog")
        )
    }

    fun stopDaemon(port: Int = ProtocolV2.DEFAULT_PORT): Result<Unit> {
        val stopCmd = """
            if [ -f "$PID_FILE" ]; then
                PID=$(cat "$PID_FILE" 2>/dev/null)
                if [ -n "${'$'}PID" ] && [ -d "/proc/${'$'}PID" ]; then
                    kill -9 "${'$'}PID" 2>/dev/null || true
                fi
                rm -f "$PID_FILE"
            fi

            for p in /proc/[0-9]*; do
                pid=${'$'}{p#/proc/}
                if [ "${'$'}pid" = "${'$'}${'$'}" ]; then continue; fi
                if [ -r "${'$'}p/cmdline" ]; then
                    read -r cmd < "${'$'}p/cmdline" 2>/dev/null
                    case "${'$'}cmd" in
                        *$MAIN_CLASS*|*velocast.sh*)
                            kill -9 "${'$'}pid" 2>/dev/null || true
                            ;;
                    esac
                fi
            done

            PORT_PID=$(ss -tlnp 2>/dev/null | grep ":$port " | grep -o 'pid=[0-9]*' | cut -d= -f2)
            if [ -n "${'$'}PORT_PID" ]; then
                kill -9 "${'$'}PORT_PID" 2>/dev/null || true
            fi

            rm -f "$PID_FILE"
        """.trimIndent()
        runSu(stopCmd, 4000L)
        return Result.success(Unit)
    }

    fun isAutoStartEnabled(): Boolean {
        val res = runSu("[ -f \"$MAGISK_SCRIPT\" ] && echo YES || echo NO")
        return res.output.contains("YES")
    }

    fun setAutoStartEnabled(enabled: Boolean, port: Int): Result<Unit> {
        val result: Result<Unit>
        if (!enabled) {
            val res = runSu("rm -f \"$MAGISK_SCRIPT\"")
            result = if (res.exitCode == 0) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(res.output))
            }
        } else {
            val secretInitRes = DaemonScripts.ensureSecretFile()
            if (secretInitRes.isFailure) {
                result = secretInitRes
            } else {
                val scriptContent = DaemonScripts.buildMagiskScript(port)
                val installCmd = """
                    mkdir -p /data/adb/service.d
                    cat <<'EOF' > "$MAGISK_SCRIPT"
$scriptContent
EOF
                    chmod 755 "$MAGISK_SCRIPT"
                """.trimIndent()

                val res = runSu(installCmd)
                result = if (res.exitCode == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        IllegalStateException("Failed to write Magisk script: ${res.output}")
                    )
                }
            }
        }
        return result
    }

    fun readRecentLogs(maxLines: Int = 80): String {
        val res = runSu("if [ -f \"$LOG_FILE\" ]; then tail -n $maxLines \"$LOG_FILE\"; fi")
        return res.output.ifBlank { "No logs yet. Tap Start Server to begin." }
    }

    fun clearLogs() {
        runSu(": > \"$LOG_FILE\"")
    }

    fun getLocalWifiIp(context: Context): String {
        try {
            val cm = context.applicationContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as? ConnectivityManager
            if (cm != null) {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val lp = cm.getLinkProperties(network) ?: continue
                        for (la in lp.linkAddresses) {
                            val addr = la.address
                            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                                return addr.hostAddress ?: continue
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w("DaemonController", "ConnectivityManager query denied", e)
        } catch (e: IllegalStateException) {
            Log.w("DaemonController", "ConnectivityManager query failed", e)
        }

        try {
            var lanFallback: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("tun") || name.startsWith("tap") ||
                    name.startsWith("dummy") || name.startsWith("p2p")
                ) {
                    continue
                }

                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("wifi")) return ip
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            lanFallback = ip
                        }
                    }
                }
            }
            if (lanFallback != null) return lanFallback
        } catch (e: IOException) {
            Log.w("DaemonController", "NetworkInterface enumeration failed", e)
        } catch (e: SecurityException) {
            Log.w("DaemonController", "NetworkInterface enumeration denied", e)
        }

        return "No Wi-Fi IP"
    }
}

private object DaemonScripts {
    private const val MAIN_CLASS = "com.mastersoft.velocast.server.Main"
    private const val PKG_NAME = "com.mastersoft.velocast.host"
    private const val STATE_DIR = "/data/adb/velocast"
    private const val LOG_FILE = "$STATE_DIR/server.log"
    private const val PID_FILE = "$STATE_DIR/server.pid"
    private const val SECRET_FILE = "$STATE_DIR/secret.hex"
    private const val SECRET_BYTES = 32

    fun ensureSecretFile(): Result<Unit> {
        val check = DaemonController.runSu("[ -s \"$SECRET_FILE\" ] && echo OK || echo MISSING")
        if (check.output.contains("OK")) {
            return Result.success(Unit)
        }
        val bytes = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        bytes.fill(0)
        val cmd = """
            mkdir -p "$STATE_DIR"
            chmod 700 "$STATE_DIR"
            umask 077
            printf '%s\n' "$hex" > "$SECRET_FILE"
            chmod 600 "$SECRET_FILE"
        """.trimIndent()
        val res = DaemonController.runSu(cmd)
        return if (res.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("Failed to initialize server secret: ${res.output}")
            )
        }
    }

    fun buildMagiskScript(port: Int): String = """
#!/system/bin/sh
STATE_DIR="$STATE_DIR"
LOG_FILE="$LOG_FILE"
PID_FILE="$PID_FILE"
SECRET_FILE="$SECRET_FILE"

mkdir -p "${'$'}STATE_DIR"
chmod 700 "${'$'}STATE_DIR"

while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
sleep 5

APK_PATH="${'$'}(pm path $PKG_NAME | head -n 1 | cut -d: -f2 | tr -d '\r')"
if [ -z "${'$'}APK_PATH" ] || [ ! -f "${'$'}APK_PATH" ]; then
    echo "[${'$'}(date)] VeloCast Host APK not installed; exiting." >> "${'$'}LOG_FILE"
    exit 1
fi

trap '' HUP
export CLASSPATH="${'$'}APK_PATH"
if command -v setsid >/dev/null 2>&1; then
    setsid /system/bin/app_process /system/bin $MAIN_CLASS --port $port --bind 0.0.0.0 --state-dir "${'$'}STATE_DIR" --secret-from-stdin < "${'$'}SECRET_FILE" >> "${'$'}LOG_FILE" 2>&1 &
else
    /system/bin/app_process /system/bin $MAIN_CLASS --port $port --bind 0.0.0.0 --state-dir "${'$'}STATE_DIR" --secret-from-stdin < "${'$'}SECRET_FILE" >> "${'$'}LOG_FILE" 2>&1 &
fi
echo "${'$'}!" > "${'$'}PID_FILE"
chmod 600 "${'$'}PID_FILE"
exit 0
    """.trimIndent()
}
