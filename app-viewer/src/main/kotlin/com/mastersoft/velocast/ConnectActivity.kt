package com.mastersoft.velocast

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mastersoft.velocast.launcher.adb.LocalNetworkPermissionGate

class ConnectActivity : AppCompatActivity() {

    private val bitrateOptions = listOf(
        "4 Mbps" to 4_000_000,
        "8 Mbps" to 8_000_000,
        "12 Mbps" to 12_000_000,
        "16 Mbps" to 16_000_000,
        "20 Mbps" to 20_000_000
    )

    private val fpsOptions = listOf(
        "30 FPS" to 30,
        "60 FPS" to 60,
        "90 FPS" to 90,
        "120 FPS" to 120
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        applyEdgeToEdgeInsets()
        requestLocalNetworkPermissionIfNeeded()

        val prefs = MirrorPreferences(this)
        val editHostIp = findViewById<EditText>(R.id.editHostIp)
        val editPort = findViewById<EditText>(R.id.editPort)
        val spinnerBitrate = findViewById<Spinner>(R.id.spinnerBitrate)
        val spinnerFps = findViewById<Spinner>(R.id.spinnerFps)
        val btnConnect = findViewById<AppCompatButton>(R.id.btnConnect)
        val btnOpenSettings = findViewById<ImageButton>(R.id.btnOpenSettings)

        editHostIp.setText(prefs.hostIp)
        editPort.setText(prefs.port.toString())

        val bitrateAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item_dark,
            bitrateOptions.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_dark)
        }
        spinnerBitrate.adapter = bitrateAdapter
        spinnerBitrate.setSelection(prefs.bitrateIdx.coerceIn(bitrateOptions.indices))

        val fpsAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item_dark,
            fpsOptions.map { it.first }
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_dark)
        }
        spinnerFps.adapter = fpsAdapter
        spinnerFps.setSelection(prefs.fpsIdx.coerceIn(fpsOptions.indices))

        btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnConnect.setOnClickListener {
            val host = editHostIp.text.toString().trim()
            val port = editPort.text.toString().trim().toIntOrNull()
            if (host.isEmpty() || port == null || port !in 1..65535) {
                Toast.makeText(
                    this,
                    "Enter a valid host IP and TCP port",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val bitrateIdx = spinnerBitrate.selectedItemPosition.coerceIn(bitrateOptions.indices)
            val fpsIdx = spinnerFps.selectedItemPosition.coerceIn(fpsOptions.indices)

            prefs.hostIp = host
            prefs.port = port
            prefs.bitrateIdx = bitrateIdx
            prefs.fpsIdx = fpsIdx

            val intent = Intent(this, MirrorActivity::class.java).apply {
                putExtra(MirrorActivity.EXTRA_HOST, host)
                putExtra(MirrorActivity.EXTRA_PORT, port)
                putExtra(MirrorActivity.EXTRA_BITRATE, bitrateOptions[bitrateIdx].second)
                putExtra(MirrorActivity.EXTRA_FPS, fpsOptions[fpsIdx].second)
                putExtra(MirrorActivity.EXTRA_SCALE, prefs.resolutionScalePercent / 100f)
            }
            startActivity(intent)
        }
    }

    private fun requestLocalNetworkPermissionIfNeeded() {
        // FUN-13: Do not request ACCESS_LOCAL_NETWORK before targeting API 37.
        // Centralized in LocalNetworkPermissionGate for future API 37 runtime request wiring.
        LocalNetworkPermissionGate.status(this)
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
