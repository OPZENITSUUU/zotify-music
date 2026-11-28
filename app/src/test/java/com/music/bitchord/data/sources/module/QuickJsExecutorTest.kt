package com.music.bitchord.data.sources.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickJsExecutorTest {
    @Test
    fun `export arguments containing quotes and code remain single JSON string values`() {
        val hostileLookingQuery = "x\" ); globalThis.pwned = true; (\""
        val encoded = encodeJsArguments(listOf(JsonPrimitive(hostileLookingQuery), JsonPrimitive(10)))

        assertEquals(JsonPrimitive(hostileLookingQuery), Json.parseToJsonElement(encoded.substringBefore(",")))
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
        val parsed = Json.parseToJsonElement(encoded)
        assertEquals(encoded, parsed.toString())
    }
}
