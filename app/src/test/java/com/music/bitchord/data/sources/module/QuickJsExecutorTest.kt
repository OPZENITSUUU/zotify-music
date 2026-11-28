package com.music.bitchord.data.sources.module

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.parseToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickJsExecutorTest {
    @Test
    fun `export arguments containing quotes and code remain single JSON string values`() {
        val hostileLookingQuery = "x\" ); globalThis.pwned = true; (\""
        val encoded = encodeJsArguments(listOf(JsonPrimitive(hostileLookingQuery), JsonPrimitive(10)))

        assertEquals(JsonPrimitive(hostileLookingQuery), encoded.substringBefore(",").parseToJsonElement())
        assertEquals("10", encoded.substringAfter(","))
    }

    @Test
    fun `context-like JSON object stays one valid argument`() {
        val encoded = encodeJsArguments(
            listOf(kotlinx.serialization.json.buildJsonObject {
                put("settings", kotlinx.serialization.json.buildJsonObject {
                    put("quality\";globalThis.pwned=true;//", JsonPrimitive("high\nquality"))
                })
            }),
        )
        val parsed = encoded.parseToJsonElement()
        assertEquals(encoded, parsed.toString())
    }
}
