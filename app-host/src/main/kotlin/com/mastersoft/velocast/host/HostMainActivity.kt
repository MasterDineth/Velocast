package com.mastersoft.velocast.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mastersoft.velocast.protocol.ProtocolV2
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

    private lateinit var progressStatus: ProgressBar
    private lateinit var txtStatusPill: TextView
    private lateinit var txtEndpoint: TextView
    private lateinit var btnCopyEndpoint: TextView
    private lateinit var txtRootStatus: TextView
    private lateinit var btnGrantRoot: TextView
    private lateinit var editServerPort: EditText
    private lateinit var switchAutoStart: SwitchMaterial
    private lateinit var btnToggleServer: AppCompatButton
    private lateinit var btnCopyLogs: TextView
    private lateinit var btnClearLogs: TextView
    private lateinit var logScrollView: NestedScrollView
    private lateinit var txtDaemonLogs: TextView

    private var hasRoot = false
    private var isServerRunning = false
    private var isTransitioning = false
    private var suppressAutoStartListener = false
    private var lastLogText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_host_main)
        applyEdgeToEdgeInsets()

        val prefs = getSharedPreferences("velocast_host_prefs", MODE_PRIVATE)

        progressStatus = findViewById(R.id.progressStatus)
        txtStatusPill = findViewById(R.id.txtStatusPill)
        txtEndpoint = findViewById(R.id.txtEndpoint)
        btnCopyEndpoint = findViewById(R.id.btnCopyEndpoint)
        txtRootStatus = findViewById(R.id.txtRootStatus)
        btnGrantRoot = findViewById(R.id.btnGrantRoot)
        editServerPort = findViewById(R.id.editServerPort)
        switchAutoStart = findViewById(R.id.switchAutoStart)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        logScrollView = findViewById(R.id.logScrollView)
        txtDaemonLogs = findViewById(R.id.txtDaemonLogs)

        editServerPort.setText(prefs.getInt("port", ProtocolV2.DEFAULT_PORT).toString())

        btnCopyEndpoint.setOnClickListener {
            val ip = DaemonController.getLocalWifiIp(this)
            copyToClipboard("VeloCast Host IP", ip)
            Toast.makeText(this, "Copied $ip", Toast.LENGTH_SHORT).show()
        }

        btnGrantRoot.setOnClickListener {
            refreshState(promptRoot = true)
        }

        btnToggleServer.setOnClickListener {
            if (isTransitioning) return@setOnClickListener
            val port = editServerPort.text.toString().trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                Toast.makeText(this, "Enter a valid TCP port (1-65535)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putInt("port", port).apply()

            val stopping = isServerRunning
            setTransitionUi(
                if (stopping) "STOPPING..." else "STARTING...",
                if (stopping) "Stopping Server..." else "Starting Server..."
            )

            uiScope.launch {
                val result = withContext(Dispatchers.IO) {
                    if (!hasRoot) hasRoot = DaemonController.checkRootAccess()
                    if (!hasRoot) {
                        Result.failure(IllegalStateException("Root permission denied"))
                    } else if (stopping) {
                        DaemonController.stopDaemon(port)
                    } else {
                        DaemonController.startDaemon(this@HostMainActivity, port)
                    }
                }
                isTransitioning = false
                result.onFailure { err ->
                    Toast.makeText(
                        this@HostMainActivity,
                        err.message ?: "Action failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshState(promptRoot = false)
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressAutoStartListener) return@setOnCheckedChangeListener
            val port =
                editServerPort.text.toString().trim().toIntOrNull() ?: ProtocolV2.DEFAULT_PORT

            uiScope.launch {
                val res = withContext(Dispatchers.IO) {
                    if (!hasRoot) hasRoot = DaemonController.checkRootAccess()
                    if (!hasRoot) {
                        Result.failure(
                            IllegalStateException("Root required for Magisk boot script")
                        )
                    } else {
                        DaemonController.setAutoStartEnabled(checked, port)
                    }
                }
                res.onSuccess {
                    val msg = if (checked) {
                        "Auto-start enabled in Magisk service.d"
                    } else {
                        "Auto-start disabled"
                    }
                    Toast.makeText(this@HostMainActivity, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    suppressAutoStartListener = true
                    switchAutoStart.isChecked = !checked
                    suppressAutoStartListener = false
                    Toast.makeText(
                        this@HostMainActivity,
                        err.message ?: "Failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        btnCopyLogs.setOnClickListener {
            val content = txtDaemonLogs.text?.toString().orEmpty()
            copyToClipboard("VeloCast Daemon Logs", content)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            uiScope.launch {
                withContext(Dispatchers.IO) { DaemonController.clearLogs() }
                updateLogText("Logs cleared.")
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
                if (isTransitioning) continue
                val port =
                    editServerPort.text.toString().trim().toIntOrNull() ?: ProtocolV2.DEFAULT_PORT
                val ip =
                    withContext(Dispatchers.IO) {
                        DaemonController.getLocalWifiIp(this@HostMainActivity)
                    }
                txtEndpoint.text = "$ip:$port"
                if (hasRoot && !isTransitioning) {
                    val running = withContext(Dispatchers.IO) { DaemonController.isRunning(port) }
                    val logs = withContext(Dispatchers.IO) { DaemonController.readRecentLogs() }
                    if (!isTransitioning) {
                        updateRunningUi(running)
                        updateLogText(logs)
                    }
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

    private fun setTransitionUi(pillLabel: String, buttonLabel: String) {
        isTransitioning = true
        progressStatus.visibility = View.VISIBLE
        txtStatusPill.text = pillLabel
        txtStatusPill.setBackgroundResource(R.drawable.bg_pill_transition)
        txtStatusPill.setTextColor(0xFFFBBF24.toInt())
        btnToggleServer.isEnabled = false
        btnToggleServer.alpha = 0.7f
        btnToggleServer.text = buttonLabel
    }

    private fun refreshState(promptRoot: Boolean) {
        val port = editServerPort.text.toString().trim().toIntOrNull() ?: ProtocolV2.DEFAULT_PORT
        uiScope.launch {
            val ip =
                withContext(Dispatchers.IO) {
                    DaemonController.getLocalWifiIp(this@HostMainActivity)
                }
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

                val running = withContext(Dispatchers.IO) { DaemonController.isRunning(port) }
                val autoStart =
                    withContext(Dispatchers.IO) { DaemonController.isAutoStartEnabled() }
                val logs = withContext(Dispatchers.IO) { DaemonController.readRecentLogs() }

                updateRunningUi(running)
                suppressAutoStartListener = true
                switchAutoStart.isChecked = autoStart
                suppressAutoStartListener = false
                updateLogText(logs)
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
        progressStatus.visibility = View.GONE
        btnToggleServer.isEnabled = true
        btnToggleServer.alpha = 1.0f

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

    private fun updateLogText(logs: String) {
        if (logs != lastLogText) {
            lastLogText = logs
            txtDaemonLogs.text = logs
            logScrollView.post {
                logScrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun applyEdgeToEdgeInsets() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val rootScroll =
            (findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup) ?: return
        rootScroll.clipToPadding = false
        val initialLeft = rootScroll.paddingLeft
        val initialTop = rootScroll.paddingTop
        val initialRight = rootScroll.paddingRight
        val initialBottom = rootScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootScroll) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(
                initialLeft + insets.left,
                initialTop + insets.top,
                initialRight + insets.right,
                initialBottom + insets.bottom
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(rootScroll)
    }
}
