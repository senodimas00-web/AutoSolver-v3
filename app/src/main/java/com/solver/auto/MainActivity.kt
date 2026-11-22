package com.solver.auto

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefs = getSharedPreferences("solver", MODE_PRIVATE)
        val etKey = findViewById<EditText>(R.id.etApiKey)
        val etBase = findViewById<EditText>(R.id.etBaseUrl)
        val etModel = findViewById<EditText>(R.id.etModel)
        etKey.setText(Secrets.getKey(this))
        etBase.setText(prefs.getString("base_url", "https://api.openai.com/"))
        etModel.setText(prefs.getString("model", "gpt-4o-mini"))

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            Secrets.putKey(this, etKey.text.toString().trim())
            prefs.edit()
                .putString("base_url", etBase.text.toString().trim().ifBlank { "https://api.openai.com/" })
                .putString("model", etModel.text.toString().trim().ifBlank { "gpt-4o-mini" })
                .apply()

            if (etKey.text.isNullOrBlank()) { toast("API key wajib diisi"); return@setOnClickListener }
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                toast("Grant izin overlay, lalu buka lagi"); return@setOnClickListener
            }
            if (!a11yOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                toast("Aktifkan AI Robot Ujian di Accessibility, lalu buka lagi"); return@setOnClickListener
            }
            val i = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            toast("Panel aktif. Centang AUTO MODE.")
            finish()
        }
    }

    private fun a11yOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(":").any {
            it.contains(packageName, true) && it.contains("SolverAccessibilityService")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
