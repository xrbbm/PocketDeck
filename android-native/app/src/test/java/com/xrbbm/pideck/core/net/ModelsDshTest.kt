package com.xrbbm.pideck.core.net

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDshTest {
    private fun obj(json: String): JsonObject =
        LenientJson.parseToJsonElement(json) as JsonObject

    @Test
    fun `search envelope parses server hits`() {
        val json = obj(
            """
            {"result":{"sessions":[{"sessionId":"s1","matches":[
              {"messageId":"m1","role":"user","snippet":"找一下"},
              {"messageId":"m2","role":"assistant","snippet":"找到了"}
            ],"hitCount":2}]}}
            """.trimIndent(),
        )
        val hits = parseSessionSearch(json)
        assertEquals(2, hits?.size)
        assertEquals("m2", hits?.get(1)?.messageId)
        assertEquals("assistant", hits?.get(1)?.role)
    }

    @Test
    fun `search envelope null result means index unavailable`() {
        assertNull(parseSessionSearch(obj("""{"result":null}""")))
    }

    @Test
    fun `search envelope with no sessions is empty not unavailable`() {
        assertEquals(0, parseSessionSearch(obj("""{"result":{"sessions":[]}}"""))?.size)
    }

    @Test
    fun `goal parses phase and rounds`() {
        val goal = DshGoal.fromJson(
            obj("""{"phase":"active","objective":"做完","roundsStarted":2,"maxGoalRounds":5}"""),
        )
        assertEquals("active", goal.phase)
        assertEquals("做完", goal.objective)
        assertEquals(2, goal.roundsStarted)
    }

    @Test
    fun `plugins split dynamic and static entries`() {
        val json = obj(
            """
            {"dynamic":[{"pluginId":"px","packages":[{"packageId":"p1","name":"主包"}],
              "activeRun":true,"error":null}],
             "static":[{"entryId":"e1","moduleName":"core","enabled":false}]}
            """.trimIndent(),
        )
        val dynamic = json.arr("dynamic").mapNotNull {
            (it as? JsonObject)?.let(DshPlugin::fromJson)
        }
        val static = json.arr("static").mapNotNull {
            (it as? JsonObject)?.let(DshStaticPlugin::fromJson)
        }
        assertEquals(1, dynamic.size)
        assertEquals("p1", dynamic[0].packages[0].packageId)
        assertTrue(dynamic[0].activeRun)
        assertEquals("core", static[0].moduleName)
        assertEquals(false, static[0].enabled)
    }

    @Test
    fun `subagent running flag follows activity`() {
        val running = DshSubagent.fromJson(
            obj("""{"id":"c1","label":"审查","activity":"running"}"""),
        )
        val idle = DshSubagent.fromJson(obj("""{"id":"c2","activity":"done"}"""))
        assertTrue(running.isRunning)
        assertEquals(false, idle.isRunning)
    }
}
