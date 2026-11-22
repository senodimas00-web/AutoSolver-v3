package com.solver.auto

class Blacklist(private val ttlMs: Long = 60_000L, private val maxEntries: Int = 64) {
    private data class Entry(val ts: Long, var count: Int)
    private val map = HashMap<Int, Entry>()

    fun isBlocked(hash: Int): Boolean {
        val entry = map[hash] ?: return false
        if (System.currentTimeMillis() - entry.ts > ttlMs) {
            map.remove(hash); return false
        }
        return true
    }

    fun record(hash: Int) {
        val now = System.currentTimeMillis()
        val existing = map[hash]
        if (existing != null) map[hash] = Entry(now, existing.count + 1)
        else {
            if (map.size >= maxEntries) {
                val oldest = map.entries.minByOrNull { it.value.ts }?.key
                if (oldest != null) map.remove(oldest)
            }
            map[hash] = Entry(now, 1)
        }
    }

    fun clear() = map.clear()
}
