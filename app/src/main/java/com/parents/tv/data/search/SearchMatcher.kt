package com.parents.tv.data.search

import com.parents.tv.data.model.Channel
import java.util.Locale

object SearchMatcher {

    /**
     * Common phonetic and transliteration equivalents for Indian TV channels.
     * Maps common user query terms to channel keywords or alias terms.
     */
    private val PHONETIC_MAP = mapOf(
        "gurbani" to listOf("simran", "akaal", "fateh", "chardikla", "religious", "devotional", "sgpc"),
        "kirtan" to listOf("simran", "akaal", "fateh", "chardikla", "religious"),
        "cricket" to listOf("star sports", "sony sports", "sports", "dd sports"),
        "khabar" to listOf("news"),
        "samachar" to listOf("news"),
        "movies" to listOf("cinema", "gold", "max", "movies")
    )

    /**
     * Matches channels against query with tiered ranking:
     * 1. Exact or prefix match on name or aliases
     * 2. Contains match on name or aliases
     * 3. Transliteration / keyword expansion match
     * 4. Category or language match
     * 5. Fuzzy edit distance (Levenshtein) tolerance for minor typos
     */
    fun search(query: String, channels: List<Channel>): List<Channel> {
        val q = cleanString(query)
        if (q.isEmpty()) return channels

        val rankedList = mutableListOf<Pair<Channel, Int>>()

        // Expanded query keywords
        val expandedKeywords = mutableListOf(q)
        for ((trigger, targets) in PHONETIC_MAP) {
            if (q.contains(trigger)) {
                expandedKeywords.addAll(targets)
            }
        }

        for (channel in channels) {
            val score = scoreChannel(channel, q, expandedKeywords)
            if (score > 0) {
                rankedList.add(channel to score)
            }
        }

        // Sort by score descending (higher score = better match)
        return rankedList.sortedByDescending { it.second }.map { it.first }
    }

    private fun scoreChannel(
        channel: Channel,
        cleanQuery: String,
        expandedKeywords: List<String>
    ): Int {
        val cleanName = cleanString(channel.name)
        val cleanAliases = channel.aliases.map { cleanString(it) }
        val cleanCategory = cleanString(channel.category ?: "")

        // 1. Exact name match = 100 points
        if (cleanName == cleanQuery) return 100

        // 2. Exact alias match = 95 points
        if (cleanAliases.any { it == cleanQuery }) return 95

        // 3. Name starts with query = 85 points
        if (cleanName.startsWith(cleanQuery)) return 85

        // 4. Any alias starts with query = 80 points
        if (cleanAliases.any { it.startsWith(cleanQuery) }) return 80

        // 5. Name contains query as a distinct word = 70 points
        if (cleanName.split(" ").any { it == cleanQuery }) return 70

        // 6. Name contains substring query = 60 points
        if (cleanName.contains(cleanQuery)) return 60

        // 7. Any alias contains query = 55 points
        if (cleanAliases.any { it.contains(cleanQuery) }) return 55

        // 8. Expanded semantic keywords (e.g. "cricket" -> "Star Sports", "gurbani" -> "PTC Simran")
        for (kw in expandedKeywords) {
            if (cleanName.contains(kw) || cleanCategory.contains(kw)) {
                return 50
            }
        }

        // 9. Category or Language match = 40 points
        if (cleanCategory.contains(cleanQuery) || channel.languages.any { it.equals(cleanQuery, ignoreCase = true) }) {
            return 40
        }

        // 10. Fuzzy match (Levenshtein distance <= 2 for words >= 4 chars)
        if (cleanQuery.length >= 4) {
            val nameWords = cleanName.split(" ")
            for (word in nameWords) {
                if (word.length >= 3) {
                    val dist = levenshteinDistance(cleanQuery, word)
                    if (dist <= 1) return 35
                    if (dist <= 2 && cleanQuery.length >= 5) return 25
                }
            }
        }

        return 0
    }

    private fun cleanString(str: String): String {
        return str.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0A00-\\u0A7F\\u0900-\\u097F]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
