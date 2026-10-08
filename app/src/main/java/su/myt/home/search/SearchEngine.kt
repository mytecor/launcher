package su.myt.home.search

import su.myt.home.FuzzySearch
import su.myt.home.model.App

class SearchEngine {
    fun matchScore(app: App, query: String): Int? {
        val score1 = FuzzySearch.score(app.label, query)
        if (app.badgedLabel == app.label) return score1
        val score2 = FuzzySearch.score(app.badgedLabel, query)
        return when {
            score1 == null -> score2
            score2 == null -> score1
            else -> maxOf(score1, score2)
        }
    }

    fun search(allApps: List<App>, query: String): List<App> {
        val trimmed = query.trim().take(100)
        if (trimmed.isEmpty()) return emptyList()

        return allApps
            .mapNotNull { app -> matchScore(app, trimmed)?.let { app to it } }
            .sortedWith(
                compareByDescending<Pair<App, Int>> { it.second }
                    .thenBy { it.first.label.lowercase() }
                    .thenBy { it.first.userSerial }
                    .thenBy { it.first.id }
            )
            .map { it.first }
            .reversed()
    }
}
