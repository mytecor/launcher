package su.myt.home

import java.text.Normalizer
import java.util.Locale

/** Higher scores are more relevant; null means no match. */
object FuzzySearch {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace('ё', 'е').trim()

    fun score(label: String, query: String): Int? {
        val name = normalize(label)
        val needle = normalize(query)
        if (needle.isEmpty()) return 0
        if (name == needle) return 10000
        if (name.startsWith(needle)) return 8000 - name.length
        val index = name.indexOf(needle)
        if (index >= 0) return 6000 - index * 10 - name.length
        // Ordered subsequence: "tlgrm" finds "Telegram".
        var cursor = 0
        var first = -1
        var last = -1
        for (char in needle) {
            val found = name.indexOf(char, cursor)
            if (found < 0) { last = -1; break }
            if (first < 0) first = found
            last = found
            cursor = found + 1
        }
        if (last >= 0) return 4000 - (last - first + 1 - needle.length) * 20 - first * 10 - name.length
        // Damerau-Levenshtein also allows an adjacent letter transposition.
        if (needle.length < 3) return null
        val allowance = if (needle.length >= 6) 2 else 1
        val distance = (listOf(name) + name.split(Regex("\\s+"))).minOf { distance(it, needle) }
        return if (distance <= allowance) 2000 - distance * 100 - name.length else null
    }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
