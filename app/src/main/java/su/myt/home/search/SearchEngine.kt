package su.myt.home.search

import su.myt.home.FuzzySearch
import su.myt.home.model.App
import java.util.Locale

class SearchEngine {
    companion object {
        const val MAX_QUERY_LENGTH = 100
    }

    fun matchScore(app: App, query: String): Int? = matchNormalized(app, FuzzySearch.normalize(query))

    private fun matchNormalized(app: App, query: String): Int? {
        val score = FuzzySearch.scoreNormalized(FuzzySearch.normalize(app.label), query)
        if (app.badgedLabel == app.label) return score
        val badgedScore = FuzzySearch.scoreNormalized(FuzzySearch.normalize(app.badgedLabel), query)
        return when {
            score == null -> badgedScore
            badgedScore == null -> score
            else -> maxOf(score, badgedScore)
        }
    }

    /** Best matches come last because the home screen displays this list from the bottom. */
    fun search(allApps: List<App>, query: String): List<App> {
        val normalized = FuzzySearch.normalize(query.trim().take(MAX_QUERY_LENGTH))
        if (normalized.isEmpty()) return emptyList()

        return allApps
            .mapNotNull { app -> matchNormalized(app, normalized)?.let { app to it } }
            .sortedWith(
                compareBy<Pair<App, Int>> { it.second }
                    .thenByDescending { it.first.label.lowercase(Locale.ROOT) }
                    .thenByDescending { it.first.userSerial }
                    .thenByDescending { it.first.id }
            )
            .map { it.first }
    }
}
