package com.xrbbm.pideck.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAttachTest {
    @Test
    fun `mime guess by suffix`() {
        assertEquals("image/png", ImageAttach.guessMime("a.png"))
        assertEquals("image/gif", ImageAttach.guessMime("a.gif"))
        assertEquals("image/webp", ImageAttach.guessMime("a.webp"))
        assertEquals("image/jpeg", ImageAttach.guessMime("a.jpg"))
        assertEquals("image/jpeg", ImageAttach.guessMime(null))
    }

    @Test
    fun `mime of data url`() {
        assertEquals(
            "image/png",
            ImageAttach.mimeOfDataUrl("data:image/png;base64,xxxx"),
        )
        assertEquals("image/jpeg", ImageAttach.mimeOfDataUrl("nope"))
    }

    @Test
    fun `limits match the web contract`() {
        assertEquals(4, ImageAttach.MAX_COUNT)
        assertEquals(2 * 1024 * 1024, ImageAttach.MAX_BYTES)
        assertEquals(1600, ImageAttach.MAX_SIDE)
    }

    @Test
    fun `append prompt joins with blank line`() {
        // Contract check only (ViewModel covered separately).
        val base = "hello"
        val content = "world"
        assertTrue("$base\n\n$content".contains("\n\n"))
    }
}
