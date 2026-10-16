package com.parents.tv

import com.parents.tv.data.model.Channel
import com.parents.tv.data.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSwitchTest {

    private val channels = listOf(
        Channel(id = "1", name = "DD Punjabi", streams = listOf(Stream("https://stream1.m3u8"))),
        Channel(id = "2", name = "GTC Punjabi", streams = listOf(Stream("https://stream2.m3u8"))),
        Channel(id = "3", name = "Akaal Channel", streams = listOf(Stream("https://stream3.m3u8"))),
        Channel(id = "4", name = "9X Tashan", streams = listOf(Stream("https://stream4.m3u8")))
    )

    @Test
    fun testNextChannelWraparound() {
        val currentIndex = 3
        val nextIndex = (currentIndex + 1) % channels.size
        assertEquals(0, nextIndex)
        assertEquals("DD Punjabi", channels[nextIndex].name)
    }

    @Test
    fun testPreviousChannelWraparound() {
        val currentIndex = 0
        val prevIndex = (currentIndex - 1 + channels.size) % channels.size
        assertEquals(3, prevIndex)
        assertEquals("9X Tashan", channels[prevIndex].name)
    }

    @Test
    fun testFindChannelById() {
        val savedId = "AkaalChannel"
        val found = channels.firstOrNull { it.id == "3" }
        assertEquals("Akaal Channel", found?.name)
    }
}
