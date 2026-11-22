package com.solver.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SolverAccessibilityService : AccessibilityService() {

    private lateinit var serviceScope: CoroutineScope
    private val matcher = Matcher()
    private val shots by lazy { ScreenshotHelper(this) }
    private val blacklist = Blacklist(ttlMs = 60_000L)

    private var lastScreenHash = 0
    private var lastClickTime = 0L
    private var busy = false
    private var consecutiveFails = 0
    private var pausedUntil = 0L

    private val DIALOG_WORDS = setOf(
        "ok", "okay", "cancel", "batal", "izinkan", "allow", "deny", "tolak",
        "i understand", "mengerti", "lanjutkan", "continue", "close", "tutup"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        matcher.onGestureResult = { ok ->
            matcher.lastGestureOkSet(ok)
            SolverState.setStatus(if (ok) "✅ gesture ok" else "⚠️ gesture dibatalkan")
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (::serviceScope.isInitialized) serviceScope.cancel()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !SolverState.auto.value || busy) return
        if (System.currentTimeMillis() < pausedUntil) return
        if (SolverState.phase.value != SolverState.Phase.IDLE) return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        serviceScope.launch {
            delay(600)
            if (System.currentTimeMillis() - lastClickTime in 1 until 1500) return@launch
            if (SolverState.auto.value && !busy &&
                SolverState.phase.value == SolverState.Phase.IDLE) run()
        }
    }

    private fun run() {
        if (busy) return
        busy = true
        SolverState.setPhase(SolverState.Phase.BUSY)
        serviceScope.launch {
            try {
                withTimeout(60_000) { pipeline() }
            } catch (e: TimeoutCancellationException) {
                SolverState.incErrors(); SolverState.setStatus("❌ Timeout")
            } catch (e: CancellationException) {
                Log.d("Solver", "pipeline cancelled")
            } catch (e: Exception) {
                Log.e("Solver", "pipeline", e)
                SolverState.incErrors(); SolverState.setStatus("❌ ${e.message}")
            } finally {
                busy = false
                SolverState.setPhase(SolverState.Phase.IDLE)
            }
        }
    }

    private suspend fun pipeline() {
        if (!SolverState.auto.value) return
        var root: AccessibilityNodeInfo? = null
        val owned = mutableListOf<ScreenSnapshot>()
        try {
            root = withContext(Dispatchers.Main) { rootInActiveWindow } ?: return
            val rootRef = root
            if (withContext(Dispatchers.Main) { looksLikeDialog(rootRef) }) return

            val rot0 = currentRotation()
            val screenH = withContext(Dispatchers.Main) {
                Rect().also { rootRef.getBoundsInScreen(it) }.height().coerceAtLeast(1)
            }
            var snap = withContext(Dispatchers.Main) { ScreenReader.collect(rootRef) }
            owned += snap
            val hash = ScreenReader.hashAsync(snap.shots)

            if (hash == lastScreenHash || blacklist.isBlocked(hash)) return

            var text = snap.shots.joinToString("\n") { it.text }
            var scrolls = 0
            while (text.trim().length < 25 && scrolls < 2) {
                val idx = ScreenReader.firstScrollableIndex(snap.shots)
                if (idx < 0) break
                withContext(Dispatchers.Main) {
                    snap.nodes[idx].performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                delay(700)
                snap = withContext(Dispatchers.Main) { ScreenReader.collect(rootRef) }
                owned += snap
                text = snap.shots.joinToString("\n") { it.text }
                scrolls++
            }

            if (text.trim().length < 25 && !snap.hasBigImage) return

            val sourceText = if (text.trim().length < 25 && snap.hasBigImage) {
                SolverState.setStatus("🖼️ OCR...")
                withContext(Dispatchers.Main) { OverlayService.setOffscreen(true) }
                awaitFrame(); awaitFrame()
                val ocr = shots.captureOCR()
                withContext(Dispatchers.Main) { OverlayService.setOffscreen(false) }
                awaitFrame(); awaitFrame()
                ocr.ifBlank { text }
            } else text

            val truncated = sourceText.take(12_000)
            if (currentRotation() != rot0) return

            SolverState.setStatus("🤖 AI...")
            val answer = AiRepository.solve(applicationContext, truncated)
            if (answer == null) { fail(hash); return }
            if (!SolverState.auto.value) return
            if (currentRotation() != rot0) return

            SolverState.setStatus("🎯 $answer")
            val qIdx = ScreenReader.questionIndex(snap.shots, screenH)
            val pick = withContext(Dispatchers.Default) { matcher.pick(snap.shots, answer, qIdx) }

            val clicked: Boolean
            if (pick >= 0) {
                val res = withContext(Dispatchers.Main) { clickNodeSafe(snap.nodes[pick]) }
                clicked = res.first
                if (clicked && res.second) {
                    delay(400)
                    if (matcher.lastGestureOk == false) { fail(hash); return }
                }
            } else {
                val ed = matcher.pickEditable(snap.shots)
                clicked = if (ed >= 0) withContext(Dispatchers.Main) {
                    snap.nodes[ed].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, answer)
                        })
                } else false
            }
            lastClickTime = System.currentTimeMillis()

            if (!clicked) { fail(hash); return }
            consecutiveFails = 0
            SolverState.incSolved(); SolverState.setStatus("✅ $answer")
            lastScreenHash = hash

            delay(900)
            val after = withContext(Dispatchers.Main) { rootInActiveWindow }
            if (after != null) {
                val asnap = withContext(Dispatchers.Main) { ScreenReader.collect(after) }
                owned += asnap
                if (ScreenReader.hashAsync(asnap.shots) == hash) {
                    SolverState.setStatus("➡️ Next...")
                    delay(800)
                    val nIdx = withContext(Dispatchers.Default) { matcher.pickNext(asnap.shots, screenH) }
                    if (nIdx >= 0) withContext(Dispatchers.Main) { clickNodeSafe(asnap.nodes[nIdx]) }
                }
            }
        } finally {
            owned.forEach { s -> s.nodes.forEach { runCatching { it.recycle() } } }
            root?.let { runCatching { it.recycle() } }
        }
    }

    private fun looksLikeDialog(root: AccessibilityNodeInfo): Boolean {
        if ((root.className?.toString() ?: "").contains("Dialog")) return true
        var seen = 0; var hits = 0
        fun rec(n: AccessibilityNodeInfo?) {
            if (n == null || seen > 12) return
            seen++
            val t = n.text?.toString()?.trim()?.lowercase() ?: ""
            if (t.isNotEmpty() && t.length < 15 && DIALOG_WORDS.contains(t)) hits++
            for (i in 0 until n.childCount) rec(n.getChild(i))
        }
        rec(root)
        return hits >= 2
    }

    private fun clickNodeSafe(node: AccessibilityNodeInfo): Pair<Boolean, Boolean> {
        val orig = Rect().also { node.getBoundsInScreen(it) }
        var c = node
        while (c.parent != null && !c.isClickable) c = c.parent
        if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            matcher.markActionClick()
            return true to false
        }
        matcher.markGestureStart()
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(
                android.graphics.Path().apply { moveTo(orig.exactCenterX(), orig.exactCenterY()) },
                0, 90))
            .build()
        val dispatched = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: android.accessibilityservice.GestureDescription?) {
                matcher.onGestureResult?.invoke(true)
            }
            override fun onCancelled(d: android.accessibilityservice.GestureDescription?) {
                matcher.onGestureResult?.invoke(false)
            }
        }, null)
        return dispatched to true
    }

    private fun fail(hash: Int) {
        blacklist.record(hash)
        consecutiveFails++
        SolverState.incErrors()
        if (consecutiveFails >= 5) {
            pausedUntil = System.currentTimeMillis() + 60_000
            consecutiveFails = 0
            SolverState.setStatus("⛔ Breaker: pause 60s")
        } else {
            SolverState.setStatus("❌ Gagal (TTL 60s)")
        }
    }

    private fun currentRotation(): Int =
        getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0

    private suspend fun awaitFrame() = withContext(Dispatchers.Main) {
        suspendCoroutine<Unit> { cont ->
            Choreographer.getInstance().postFrameCallback { cont.resume(Unit) }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        if (::serviceScope.isInitialized) serviceScope.cancel()
        shots.release()
    }
}
