package com.mastersoft.velocast.daemon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HostMainActivity : AppCompatActivity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private val bitrateOptions = listOf(
        "4 Mbps (Cool)" to 4_000_000,
        "8 Mbps (Balanced)" to 8_000_000,
        "12 Mbps (High)" to 12_000_000,
        "16 Mbps" to 16_000_000
    )

    private val fpsOptions = listOf(
        "30 FPS" to 30,
        "60 FPS" to 60,
        "90 FPS" to 90,
        "120 FPS" to 120
    )

    private lateinit var txtStatusPill: TextView
    private lateinit var txtEndpoint: TextView
    private lateinit var btnCopyEndpoint: TextView
    private lateinit var txtRootStatus: TextView
    private lateinit var btnGrantRoot: TextView
    private lateinit var editServerPort: EditText
    private lateinit var spinnerHostBitrate: Spinner
    private lateinit var spinnerHostFps: Spinner
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var btnToggleServer: Button
    private lateinit var btnClearLogs: TextView
    private lateinit var txtDaemonLogs: TextView

    private var hasRoot = false
    private var isServerRunning = false
    private var suppressAutoStartListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_main)

        val prefs = getSharedPreferences("velocast_host_prefs", MODE_PRIVATE)

        txtStatusPill = findViewById(R.id.txtStatusPill)
        txtEndpoint = findViewById(R.id.txtEndpoint)
        btnCopyEndpoint = findViewById(R.id.btnCopyEndpoint)
        txtRootStatus = findViewById(R.id.txtRootStatus)
        btnGrantRoot = findViewById(R.id.btnGrantRoot)
        editServerPort = findViewById(R.id.editServerPort)
        spinnerHostBitrate = findViewById(R.id.spinnerHostBitrate)
        spinnerHostFps = findViewById(R.id.spinnerHostFps)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        txtDaemonLogs = findViewById(R.id.txtDaemonLogs)

        editServerPort.setText(prefs.getInt("port", Protocol.DEFAULT_PORT).toString())

        spinnerHostBitrate.adapter = ArrayAdapter(
            this, R.layout.spinner_item_host, bitrateOptions.map { it.first }
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_host) }
        spinnerHostBitrate.setSelection(prefs.getInt("bitrate_idx", 1).coerceIn(bitrateOptions.indices))

        spinnerHostFps.adapter = ArrayAdapter(
            this, R.layout.spinner_item_host, fpsOptions.map { it.first }
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_host) }
        spinnerHostFps.setSelection(prefs.getInt("fps_idx", 1).coerceIn(fpsOptions.indices))

        btnCopyEndpoint.setOnClickListener {
            val ip = DaemonController.getLocalWifiIp()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("VeloCast Host IP", ip))
            Toast.makeText(this, "Copied $ip", Toast.LENGTH_SHORT).show()
        }

        btnGrantRoot.setOnClickListener {
            refreshState(promptRoot = true)
        }

        btnToggleServer.setOnClickListener {
            val port = editServerPort.text.toString().trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                Toast.makeText(this, "Enter a valid TCP port (1-65535)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val bitrateIdx = spinnerHostBitrate.selectedItemPosition.coerceIn(bitrateOptions.indices)
            val fpsIdx = spinnerHostFps.selectedItemPosition.coerceIn(fpsOptions.indices)
            prefs.edit()
                .putInt("port", port)
                .putInt("bitrate_idx", bitrateIdx)
                .putInt("fps_idx", fpsIdx)
                .apply()

            btnToggleServer.isEnabled = false
            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (!hasRoot) hasRoot = DaemonController.checkRootAccess()
                    if (!hasRoot) {
                        Result.failure(IllegalStateException("Root permission denied"))
                    } else if (isServerRunning) {
                        DaemonController.stopDaemon()
                    } else {
                        DaemonController.startDaemon(
                            context = this@HostMainActivity,
                            port = port,
                            bitrateBps = bitrateOptions[bitrateIdx].second,
                            fps = fpsOptions[fpsIdx].second
                        )
                    }
                }
                btnToggleServer.isEnabled = true
                result.onFailure { err ->
                    Toast.makeText(this@HostMainActivity, err.message ?: "Action failed", Toast.LENGTH_LONG).show()
                }
                refreshState(promptRoot = false)
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoStartListener) return@setOnCheckedChangeListener
            val port = editServerPort.text.toString().trim().toIntOrNull() ?: Protocol.DEFAULT_PORT
            val bitrateBps = bitrateOptions[spinnerHostBitrate.selectedItemPosition.coerceIn(bitrateOptions.indices)].second
            val fps = fpsOptions[spinnerHostFps.selectedItemPosition.coerceIn(fpsOptions.indices)].second

            uiScope.launch {
                val res = withContext(Dispatchers.IO) {
                    if (!hasRoot) hasRoot = DaemonController.checkRootAccess()
                    if (!hasRoot) Result.failure(IllegalStateException("Root required for Magisk boot script"))
                    else DaemonController.setAutoStartEnabled(checked, port, bitrateBps, fps)
                }
                res.onSuccess {
                    val msg = if (checked) "Auto-start enabled in Magisk service.d" else "Auto-start disabled"
                    Toast.makeText(this@HostMainActivity, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    suppressAutoStartListener = true
                    switchAutoStart.isChecked = !checked
                    suppressAutoStartListener = false
                    Toast.makeText(this@HostMainActivity, err.message ?: "Failed", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnClearLogs.setOnClickListener {
            uiScope.launch {
                withContext(Dispatchers.IO) { DaemonController.clearLogs() }
                txtDaemonLogs.text = "Logs cleared."
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState(promptRoot = true)
        pollJob?.cancel()
        pollJob = uiScope.launch {
            while (isActive) {
                delay(2_000L)
                if (hasRoot) {
                    val running = withContext(Dispatchers.IO) { DaemonController.isRunning() }
                    val logs = withContext(Dispatchers.IO) { DaemonController.readRecentLogs() }
                    updateRunningUi(running)
                    txtDaemonLogs.text = logs
                }
            }
        }
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun refreshState(promptRoot: Boolean) {
        val port = editServerPort.text.toString().trim().toIntOrNull() ?: Protocol.DEFAULT_PORT
        uiScope.launch {
            val ip = withContext(Dispatchers.IO) { DaemonController.getLocalWifiIp() }
            txtEndpoint.text = "$ip:$port"

            val rootOk = if (promptRoot || !hasRoot) {
                withContext(Dispatchers.IO) { DaemonController.checkRootAccess() }
            } else {
                hasRoot
            }
            hasRoot = rootOk

            if (rootOk) {
                txtRootStatus.text = "Root Access: Granted (uid=0)"
                txtRootStatus.setTextColor(0xFF34D399.toInt())
                btnGrantRoot.text = "Verified"

                val running = withContext(Dispatchers.IO) { DaemonController.isRunning() }
                val autoStart = withContext(Dispatchers.IO) { DaemonController.isAutoStartEnabled() }
                val logs = withContext(Dispatchers.IO) { DaemonController.readRecentLogs() }

                updateRunningUi(running)
                suppressAutoStartListener = true
                switchAutoStart.isChecked = autoStart
                suppressAutoStartListener = false
                txtDaemonLogs.text = logs
            } else {
                txtRootStatus.text = "Root Access: Not Granted (Magisk required)"
                txtRootStatus.setTextColor(0xFFF87171.toInt())
                btnGrantRoot.text = "Grant Root"
                updateRunningUi(false)
            }
        }
    }

    private fun updateRunningUi(running: Boolean) {
        isServerRunning = running
        if (running) {
            txtStatusPill.text = "RUNNING"
            txtStatusPill.setBackgroundResource(R.drawable.bg_pill_running)
            txtStatusPill.setTextColor(0xFF34D399.toInt())
            btnToggleServer.text = "Stop Server"
            btnToggleServer.setBackgroundResource(R.drawable.bg_btn_stop)
        } else {
            txtStatusPill.text = "STOPPED"
            txtStatusPill.setBackgroundResource(R.drawable.bg_pill_stopped)
            txtStatusPill.setTextColor(0xFF94A3B8.toInt())
            btnToggleServer.text = "Start Server"
            btnToggleServer.setBackgroundResource(R.drawable.bg_btn_start)
        }
    }
}
