package com.parents.tv

import com.parents.tv.player.datasource.PngTsUnmasker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PngTsUnmaskerTest {

    @Test
    fun testNonPngReturnsFalse() {
        val nonPng = byteArrayOf(0x47, 0x40, 0x11, 0x10, 0x00)
        assertFalse(PngTsUnmasker.isPng(nonPng))
        val result = PngTsUnmasker.unmask(nonPng)
        assertTrue(result == null)
    }

    @Test
    fun testPngSignatureDetection() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00, 0x00, 0x00, 0x00
        )
        assertTrue(PngTsUnmasker.isPng(pngHeader))
    }
}
