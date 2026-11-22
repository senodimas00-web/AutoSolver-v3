package com.solver.auto

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Shot(
    val text: String,
    val cx: Int, val cy: Int, val top: Int, val bottom: Int,
    val clickable: Boolean, val scrollable: Boolean, val editable: Boolean
)

data class ScreenSnapshot(
    val shots: List<Shot>,
    val nodes: List<AccessibilityNodeInfo>,
    val hasBigImage: Boolean
)

object ScreenReader {

    private val OPT_RE = Regex("^([a-e]|[1-9])[.): ]", RegexOption.IGNORE_CASE)

    fun collect(root: AccessibilityNodeInfo, maxNodes: Int = 400): ScreenSnapshot {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val top = screen.top + 100
        val bottom = screen.bottom - 150
        val shots = ArrayList<Shot>(64)
        val nodes = ArrayList<AccessibilityNodeInfo>(64)
        var bigImage = false

        fun rec(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || shots.size >= maxNodes) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            val cls = n.className?.toString() ?: ""
            if (cls.contains("Image") && r.width() > 120 && r.height() > 120) bigImage = true
            if (r.centerY() in top..bottom && r.width() > 0) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty() || n.isScrollable) {
                    shots.add(Shot(t, r.centerX(), r.centerY(), r.top, r.bottom,
                        n.isClickable, n.isScrollable, n.isEditable))
                    nodes.add(n)
                }
            }
            for (i in 0 until n.childCount) rec(n.getChild(i), depth + 1)
        }
        rec(root, 0)
        return ScreenSnapshot(shots, nodes, bigImage)
    }

    suspend fun hashAsync(shots: List<Shot>): Int = withContext(Dispatchers.Default) {
        shots.joinToString("|") { it.text }.hashCode()
    }

    fun firstOptionIndex(shots: List<Shot>): Int {
        val alpha = mutableListOf<Int>(); val num = mutableListOf<Int>()
        for (i in shots.indices) {
            val m = OPT_RE.find(shots[i].text) ?: continue
            if (m.groupValues[1][0] in 'a'..'e') alpha.add(i) else num.add(i)
        }
        return when {
            alpha.size >= 2 -> alpha[0]
            num.size >= 2 -> num[0]
            else -> -1
        }
    }

    fun questionIndex(shots: List<Shot>, screenH: Int): Int {
        val opt = firstOptionIndex(shots)
        if (opt > 0) {
            val above = shots.indices.filter { i ->
                i < opt && shots[i].text.length > 30 && shots[i].bottom <= shots[opt].top + 8
            }
            if (above.isNotEmpty()) return above.maxByOrNull { shots[it].text.length }!!
        }
        return shots.indices.filter { shots[it].text.length > 30 }.maxByOrNull { i ->
            (minOf(shots[i].text.length, 300) / 300.0) * 0.5 +
            (1.0 - shots[i].cy.toDouble() / screenH) * 0.5
        } ?: -1
    }

    fun firstScrollableIndex(shots: List<Shot>): Int = shots.indexOfFirst { it.scrollable }
}
