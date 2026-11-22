package com.solver.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    companion object {
        private var instance: OverlayService? = null
        fun setOffscreen(off: Boolean) {
            instance?.let { it.offscreen = off; it.applyPosition() }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var homeX = 40
    private var homeY = 160
    private var offscreen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("solver", "Solver", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        startForeground(1, NotificationCompat.Builder(this, "solver")
            .setContentTitle("AI Robot Ujian aktif")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        show()
        return START_STICKY
    }

    private fun applyPosition() {
        val v = overlayView ?: return
        params.x = if (offscreen) -10000 else homeX
        params.y = if (offscreen) -10000 else homeY
        wm.updateViewLayout(v, params)
    }

    private fun show() {
        if (overlayView != null) return
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_solver, null)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, flag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = homeX; y = homeY }
        wm.addView(overlayView, params)
        bind()
    }

    private fun bind() {
        val v = overlayView ?: return
        val body = v.findViewById<View>(R.id.panelBody)
        val handle = v.findViewById<View>(R.id.dragHandle)
        val phase = v.findViewById<TextView>(R.id.phaseText)
        val status = v.findViewById<TextView>(R.id.statusText)
        val solved = v.findViewById<TextView>(R.id.solvedText)
        val errors = v.findViewById<TextView>(R.id.errorsText)
        val chk = v.findViewById<CheckBox>(R.id.chkAuto)
        val seek = v.findViewById<SeekBar>(R.id.seekAlpha)
        val stop = v.findViewById<Button>(R.id.btnStop)

        scope.launch { SolverState.phase.collect { phase.text = it.name } }
        scope.launch { SolverState.status.collect { status.text = it } }
        scope.launch { SolverState.solved.collect { solved.text = "✓ $it" } }
        scope.launch { SolverState.errors.collect { errors.text = "✗ $it" } }
        scope.launch {
            SolverState.auto.collect { on ->
                if (chk.isChecked != on) chk.isChecked = on
                body.visibility = if (on) View.GONE else View.VISIBLE
            }
        }

        handle.setOnLongClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        chk.setOnCheckedChangeListener { _, c ->
            if (SolverState.auto.value != c) {
                SolverState.setAuto(c)
                SolverState.setStatus(if (c) "🟢 AUTO ON" else "⚪ AUTO OFF")
            }
            body.visibility = if (c) View.GONE else View.VISIBLE
        }
        stop.setOnClickListener { stopSelf() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                overlayView?.alpha = 0.2f + (p * 0.8f) / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    homeX = ix + (e.rawX - tx).toInt()
                    homeY = iy + (e.rawY - ty).toInt()
                    if (!offscreen) {
                        params.x = homeX; params.y = homeY
                        wm.updateViewLayout(overlayView, params)
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        scope.cancel()
        overlayView?.let { wm.removeView(it) }
        overlayView = null
    }
}
