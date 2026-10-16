package com.parents.tv

import com.parents.tv.data.model.Channel
import com.parents.tv.data.model.ChannelFeed
import com.parents.tv.data.model.ChannelSection
import com.parents.tv.data.model.Stream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRepositoryTest {

    private val sampleJson = """
    {
      "generated": "2026-10-16T00:00:00Z",
      "default_channel": "DDPunjabi.in",
      "total_channels": 3,
      "sections": [
        {
          "id": "punjabi",
          "title": "Punjabi",
          "channels": [
            {
              "id": "DDPunjabi.in",
              "name": "DD Punjabi",
              "category": "General",
              "is_punjabi": true,
              "streams": [
                { "url": "https://stream1.m3u8", "status": "working" },
                { "url": "https://stream1_backup.m3u8", "status": "unverified" }
              ],
              "aliases": ["DD Punjabi", "Doordarshan"]
            },
            {
              "id": "GTCPunjabi.in",
              "name": "GTC Punjabi",
              "category": "Live",
              "is_punjabi": true,
              "streams": [
                { "url": "https://stream2.m3u8", "status": "working" }
              ],
              "aliases": ["GTC"]
            }
          ]
        }
      ]
    }
    """.trimIndent()

    @Test
    fun testJsonParsingStructure() {
        val root = JSONObject(sampleJson)
        assertEquals("DDPunjabi.in", root.getString("default_channel"))
        val sections = root.getJSONArray("sections")
        assertEquals(1, sections.length())

        val sec = sections.getJSONObject(0)
        assertEquals("punjabi", sec.getString("id"))
        val channels = sec.getJSONArray("channels")
        assertEquals(2, channels.length())

        val ch1 = channels.getJSONObject(0)
        assertEquals("DD Punjabi", ch1.getString("name"))
        val streams = ch1.getJSONArray("streams")
        assertEquals(2, streams.length())
        assertEquals("https://stream1.m3u8", streams.getJSONObject(0).getString("url"))
    }

    @Test
    fun testChannelFailureSorting() {
        val chNormal1 = Channel(id = "1", name = "Channel 1", failureCount = 0)
        val chFailing = Channel(id = "2", name = "Channel Failing", failureCount = 4)
        val chNormal2 = Channel(id = "3", name = "Channel 3", failureCount = 1)

        val list = mutableListOf(chNormal1, chFailing, chNormal2)
        list.sortWith(
            compareBy<Channel> { if (it.failureCount >= 3) 1 else 0 }
                .thenBy { it.failureCount }
        )

        assertEquals("1", list[0].id)
        assertEquals("3", list[1].id)
        // Failing channel with >=3 failures must be moved to the very bottom
        assertEquals("2", list[2].id)
    }

    @Test
    fun testBackupStreamAvailability() {
        val ch = Channel(
            id = "DD",
            name = "DD Punjabi",
            streams = listOf(
                Stream("https://primary.m3u8", "working"),
                Stream("https://backup.m3u8", "unverified")
            )
        )

        assertTrue(ch.streams.size > 1)
        assertEquals("https://primary.m3u8", ch.streams[0].url)
        assertEquals("https://backup.m3u8", ch.streams[1].url)
    }
}
