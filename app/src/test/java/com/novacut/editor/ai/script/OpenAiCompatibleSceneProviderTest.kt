package com.novacut.editor.ai.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleSceneProviderTest {
    private val provider = OpenAiCompatibleSceneProvider()

    @Test
    fun responseJsonMapsHindiNarrationAndClampsDuration() {
        val result = provider.parseScenes(
            """{"scenes":[{"narration":"भाई साहब, जरा सोचिए।","visualPrompt":"गहरे अंतरिक्ष का दृश्य","negativePrompt":"वॉटरमार्क नहीं","durationMs":999999999}]}"""
        )

        assertEquals(1, result.size)
        assertEquals("भाई साहब, जरा सोचिए।", result.single().narration)
        assertEquals("गहरे अंतरिक्ष का दृश्य", result.single().visualPrompt)
        assertEquals(ScriptScene.MAX_SCENE_DURATION_MS, result.single().durationMs)
    }

    @Test
    fun markdownFencedResponseIsAccepted() {
        val result = provider.parseScenes(
            """```json
{"scenes":[{"narration":"A scene","visualPrompt":"A night sky","durationMs":2500}]}
```"""
        )

        assertEquals(1, result.size)
        assertEquals(2_500L, result.single().durationMs)
    }

    @Test
    fun emptySceneArraysAreRejected() {
        val thrown = runCatching { provider.parseScenes("""{"scenes":[]}""") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }
}
