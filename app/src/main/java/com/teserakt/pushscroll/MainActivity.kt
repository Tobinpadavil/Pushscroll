package com.teserakt.pushscroll

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity(), PushupBus.Listener {

    private lateinit var prefs: Prefs
    private lateinit var txtCount: TextView
    private lateinit var txtTotal: TextView
    private lateinit var txtStatus: TextView
    private lateinit var levelView: LevelView
    private lateinit var btnStart: Button
    private lateinit var txtCam: TextView
    private lateinit var btnCam: Button
    private lateinit var txtAcc: TextView
    private lateinit var btnAcc: Button
    private lateinit var txtAccHint: TextView
    private lateinit var txtSens: TextView

    @Suppress("UseSwitchCompatOrMaterialCode")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        txtCount = findViewById(R.id.txtCount)
        txtTotal = findViewById(R.id.txtTotal)
        txtStatus = findViewById(R.id.txtStatus)
        levelView = findViewById(R.id.levelView)
        btnStart = findViewById(R.id.btnStart)
        txtCam = findViewById(R.id.txtCam)
        btnCam = findViewById(R.id.btnCam)
        txtAcc = findViewById(R.id.txtAcc)
        btnAcc = findViewById(R.id.btnAcc)
        txtAccHint = findViewById(R.id.txtAccHint)
        txtSens = findViewById(R.id.txtSens)

        btnStart.setOnClickListener {
            if (PushupBus.running) stopTracking() else startTracking()
        }
        btnCam.setOnClickListener { requestCamera() }
        btnAcc.setOnClickListener {
            Toast.makeText(this, "Find PushScroll in the list and turn it on", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnShorts).setOnClickListener { openShorts() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { PushupBus.resetSession() }

        val swBlock = findViewById<Switch>(R.id.swBlock)
        swBlock.isChecked = prefs.blockManual
        swBlock.setOnCheckedChangeListener { _, c -> prefs.blockManual = c }

        val swAll = findViewById<Switch>(R.id.swAll)
        swAll.isChecked = prefs.lockAllYoutube
        swAll.setOnCheckedChangeListener { _, c -> prefs.lockAllYoutube = c }

        val seek = findViewById<SeekBar>(R.id.seekSens)
        seek.progress = prefs.sensitivity
        txtSens.text = "Sensitivity: ${prefs.sensitivity}"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                prefs.sensitivity = p
                txtSens.text = "Sensitivity: $p"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        PushupBus.add(this)
        refreshSetup()
        refreshLive()
    }

    override fun onPause() {
        PushupBus.remove(this)
        super.onPause()
    }

    override fun onPushupEvent(event: Int) = refreshLive()

    // ---------- actions ----------

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCamera() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun startTracking() {
        if (!hasCamera()) {
            requestCamera()
            return
        }
        if (!isAccessibilityOn()) {
            Toast.makeText(this, "Counting only — enable Shorts control to auto-scroll", Toast.LENGTH_LONG).show()
        }
        ContextCompat.startForegroundService(this, Intent(this, PushupService::class.java))
        PushupBus.status = "Starting…"
        refreshLive()
    }

    private fun stopTracking() {
        stopService(Intent(this, PushupService::class.java))
    }

    private fun openShorts() {
        val uri = Uri.parse("https://www.youtube.com/shorts")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(ShortsAccessibilityService.YT))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                Toast.makeText(this, "YouTube isn't installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshSetup()
        if (!hasCamera()) {
            Toast.makeText(this, "Camera is needed to count pushups", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- UI ----------

    private fun isAccessibilityOn(): Boolean {
        if (ShortsAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, ShortsAccessibilityService::class.java)
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == me
        }
    }

    private fun refreshSetup() {
        val ok = ContextCompat.getColor(this, R.color.ok)
        val bad = ContextCompat.getColor(this, R.color.bad)

        val cam = hasCamera()
        txtCam.text = if (cam) "✓ Camera" else "✗ Camera"
        txtCam.setTextColor(if (cam) ok else bad)
        btnCam.visibility = if (cam) android.view.View.GONE else android.view.View.VISIBLE

        val acc = isAccessibilityOn()
        txtAcc.text = if (acc) "✓ Shorts control" else "✗ Shorts control (Accessibility)"
        txtAcc.setTextColor(if (acc) ok else bad)
        btnAcc.visibility = if (acc) android.view.View.GONE else android.view.View.VISIBLE
        txtAccHint.visibility = if (acc) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun refreshLive() {
        txtCount.text = PushupBus.sessionCount.toString()
        txtTotal.text = "All time: ${PushupBus.totalOf(this)}"
        txtStatus.text = PushupBus.status
        levelView.level = PushupBus.level
        btnStart.text = if (PushupBus.running) "Stop tracking" else "Start tracking"
    }
}
