package com.parents.tv

import com.parents.tv.data.model.Channel
import com.parents.tv.data.search.SearchMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchMatcherTest {

    private lateinit var testChannels: List<Channel>

    @Before
    fun setup() {
        testChannels = listOf(
            Channel(
                id = "PTCSimran.in",
                name = "PTC Simran",
                category = "Religious",
                aliases = listOf("PTC Simran", "Simran", "ਪੀਟੀਸੀ ਸਿਮਰਨ", "ਸਿਮਰਨ", "Gurbani", "ਗੁਰਬਾਣੀ"),
                isPunjabi = true
            ),
            Channel(
                id = "PTCPunjabi.in",
                name = "PTC Punjabi",
                category = "Entertainment",
                aliases = listOf("PTC Punjabi", "PTC", "ਪੀਟੀਸੀ ਪੰਜਾਬੀ", "पीटीसी"),
                isPunjabi = true
            ),
            Channel(
                id = "PTCNews.in",
                name = "PTC News",
                category = "News",
                aliases = listOf("PTC News", "ਪੀਟੀਸੀ ਨਿਊਜ਼"),
                isPunjabi = true
            ),
            Channel(
                id = "DDPunjabi.in",
                name = "DD Punjabi",
                category = "General",
                aliases = listOf("DD Punjabi", "Doordarshan Punjabi", "ਡੀਡੀ ਪੰਜਾਬੀ", "डीडी पंजाबी"),
                isPunjabi = true
            ),
            Channel(
                id = "StarSports1.in",
                name = "Star Sports 1",
                category = "Sports",
                aliases = listOf("Star Sports 1", "Star Sports", "Cricket"),
                languages = listOf("eng")
            ),
            Channel(
                id = "SonySportsTen1.in",
                name = "Sony Sports Ten 1",
                category = "Sports",
                aliases = listOf("Sony Sports Ten 1", "Sony Ten 1", "Sony Sports"),
                languages = listOf("eng")
            ),
            Channel(
                id = "AajTak.in",
                name = "Aaj Tak",
                category = "News",
                aliases = listOf("Aaj Tak", "Aajtak", "आज तक"),
                languages = listOf("hin")
            ),
            Channel(
                id = "ZeePunjabi.in",
                name = "Zee Punjabi",
                category = "Entertainment",
                aliases = listOf("Zee Punjabi", "ਜ਼ੀ ਪੰਜਾਬੀ"),
                isPunjabi = true
            )
        )
    }

    @Test
    fun testTenCommonChannelSearches() {
        val testCases = listOf(
            "ptc" to "PTCSimran.in",
            "simran" to "PTCSimran.in",
            "gurbani" to "PTCSimran.in",
            "dd punjabi" to "DDPunjabi.in",
            "star sports" to "StarSports1.in",
            "cricket" to "StarSports1.in",
            "aaj tak" to "AajTak.in",
            "aajtak" to "AajTak.in",
            "sony" to "SonySportsTen1.in",
            "zee" to "ZeePunjabi.in",
            "ਪੀਟੀਸੀ" to "PTCSimran.in",
            "आज तक" to "AajTak.in"
        )

        var passedCount = 0
        for ((query, expectedTopChannelId) in testCases) {
            val results = SearchMatcher.search(query, testChannels)
            assertTrue("Results should not be empty for '$query'", results.isNotEmpty())
            val topId = results.first().id
            val matched = (topId == expectedTopChannelId) || results.take(2).any { it.id == expectedTopChannelId }
            if (matched) {
                passedCount++
            }
        }

        // PRD requirement: At least 8 out of 10 test cases must pass
        assertTrue("Must pass at least 8/10 searches (Passed: $passedCount / ${testCases.size})", passedCount >= 8)
        assertEquals("All test cases should pass", testCases.size, passedCount)
    }
}
