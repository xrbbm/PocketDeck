package com.xrbbm.pideck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Cases for the PairingStore.parseBaseUrl contract. */
class PairingParserTest {
    @Test
    fun `accepts full http url`() {
        assertEquals(
            "http://192.168.50.210:8765",
            PairingParser.parseBaseUrl("http://192.168.50.210:8765"),
        )
    }

    @Test
    fun `bare host defaults to http plus 8765`() {
        assertEquals(
            "http://192.168.50.210:8765",
            PairingParser.parseBaseUrl("192.168.50.210"),
        )
    }

    @Test
    fun `bare host with port keeps port`() {
        assertEquals(
            "http://192.168.50.210:9000",
            PairingParser.parseBaseUrl("192.168.50.210:9000"),
        )
    }

    @Test
    fun `https scheme preserved`() {
        assertEquals(
            "https://example.com:8765",
            PairingParser.parseBaseUrl("https://example.com"),
        )
    }

    @Test
    fun `scheme case normalized`() {
        assertEquals(
            "http://192.168.50.210:8765",
            PairingParser.parseBaseUrl("HTTP://192.168.50.210:8765"),
        )
    }

    @Test
    fun `ipv6 host wrapped in brackets`() {
        assertEquals(
            "http://[::1]:8765",
            PairingParser.parseBaseUrl("http://[::1]:8765"),
        )
    }

    @Test
    fun `rejects empty text`() {
        assertNull(PairingParser.parseBaseUrl("   "))
    }

    @Test
    fun `rejects non-http scheme`() {
        assertNull(PairingParser.parseBaseUrl("ftp://example.com:21"))
    }

    @Test
    fun `rejects path query fragment`() {
        assertNull(PairingParser.parseBaseUrl("http://host:8765/path"))
        assertNull(PairingParser.parseBaseUrl("http://host:8765?token=x"))
        assertNull(PairingParser.parseBaseUrl("http://host:8765#frag"))
    }
}
