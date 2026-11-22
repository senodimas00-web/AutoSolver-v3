package com.solver.auto

import kotlin.math.abs

class Matcher {

    var onGestureResult: ((Boolean) -> Unit)? = null
    var lastViaGesture = false
        private set
    var lastGestureOk: Boolean? = null
        private set

    fun lastGestureOkSet(v: Boolean) { lastGestureOk = v }
    fun markGestureStart() { lastViaGesture = true; lastGestureOk = null }
    fun markActionClick() { lastViaGesture = false; lastGestureOk = null }

    fun pick(shots: List<Shot>, answer: String, questionIdx: Int): Int {
        val letter = answer.trim().uppercase().firstOrNull()
        val isLetter = letter != null && letter in 'A'..'E' && answer.trim().length <= 2
        val pool = shots.indices.filter { it != questionIdx && shots[it].text.isNotEmpty() }

        if (isLetter) {
            val l = letter.lowercase()
            pool.firstOrNull { i ->
                val t = shots[i].text.lowercase()
                t.startsWith("$l.") || t.startsWith("$l)") || t.startsWith("$l:") ||
                t.startsWith("$l ") || t == l
            }?.let { return it }
        }

        val nb = norm(answer)
        pool.firstOrNull { norm(shots[it].text) == nb }?.let { return it }

        if (nb.length <= 3) {
            pool.firstOrNull { i -> tokens(shots[i].text).contains(nb) }?.let { return it }
        } else {
            pool.filter { i ->
                val nt = norm(shots[i].text)
                nt.contains(nb) || nb.contains(nt)
            }.maxByOrNull { i -> -abs(norm(shots[i].text).length - nb.length) }?.let { return it }
        }

        return pool.map { it to score(shots[it], answer) }
            .filter { it.second >= 0.6 }
            .maxByOrNull { it.second }?.first ?: -1
    }

    fun pickNext(shots: List<Shot>, screenH: Int): Int {
        val exact = listOf("next", "selanjutnya", "berikutnya")
        val keys = exact + listOf("lanjut", "->", "→", "»")
        val cand = shots.indices.filter { i ->
            val t = shots[i].text.lowercase().trim()
            shots[i].clickable && t.length < 25 && keys.any { t.contains(it) }
        }
        if (cand.isEmpty()) return -1
        return cand.maxByOrNull { i ->
            var s = shots[i].cy.toDouble() / screenH
            if (exact.any { shots[i].text.lowercase().trim() == it }) s += 2.0
            s
        }!!
    }

    fun pickEditable(shots: List<Shot>): Int = shots.indexOfFirst { it.editable }

    private fun score(s: Shot, answer: String): Double {
        val a = norm(s.text); val b = norm(answer)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var v = when {
            a == b -> 1.0
            a.contains(b) || b.contains(a) -> 0.9
            a.length <= 80 && b.length <= 80 -> {
                val levSim = 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
                0.55 * levSim + 0.45 * jaccard(a, b)
            }
            else -> 0.0
        }
        if (s.clickable) v += 0.05
        return v.coerceIn(0.0, 1.0)
    }

    private fun tokens(s: String) = norm(s).split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }.toSet()

    private fun jaccard(a: String, b: String): Double {
        val ta = a.split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
        val tb = b.split(Regex("[^a-z0-9]+")).filter { it.length > 1 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size
        return inter.toDouble() / (ta.size + tb.size - inter)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun lev(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + c)
        }
        return dp[a.length][b.length]
    }
}
