package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderUsageTest {

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `disabled provider renders nothing`() {
        val usage = ProviderUsage.fromJson(
            obj("""{"success":false,"disabled":true,"error":"用量查询未开启。"}"""),
        )
        assertFalse(usage.success)
        assertTrue(usage.periods.isEmpty())
        assertNull(usage.balance)
    }

    @Test
    fun `periods kind keeps server order and clamps`() {
        val usage = ProviderUsage.fromJson(
            obj(
                """{"success":true,"kind":"periods","provider":"claude",
                    "periods":{"weekly":{"percent":12.5},"rolling":{"percent":140}}}""",
            ),
        )
        assertEquals(listOf("rolling" to 100.0, "weekly" to 12.5), usage.periods)
    }

    @Test
    fun `credits kind derives percent and remaining from used and total`() {
        val usage = ProviderUsage.fromJson(
            obj(
                """{"success":true,"kind":"credits",
                    "credits":{"windows":[{"key":"5h","used":25,"total":100}]}}""",
            ),
        )
        assertEquals(1, usage.windows.size)
        assertEquals(25.0, usage.windows[0].percent!!, 0.001)
        assertEquals(75.0, usage.windows[0].remaining!!, 0.001)
    }

    @Test
    fun `balance kind exposes prepaid value`() {
        val usage = ProviderUsage.fromJson(
            obj("""{"success":true,"kind":"balance","balance":{"value":3.25,"currency":"USD"}}"""),
        )
        assertEquals(3.25, usage.balance!!, 0.001)
    }
}
