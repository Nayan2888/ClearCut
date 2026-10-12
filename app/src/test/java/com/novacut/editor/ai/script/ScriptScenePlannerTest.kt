package com.novacut.editor.ai.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptScenePlannerTest {
    private val planner = OfflineScriptScenePlanner()

    @Test
    fun blankLineSeparatedHindiParagraphsBecomeDistinctScenes() {
        val result = planner.plan(
            "जरा सोचिए, ब्रह्मांड कितना विशाल है। सितारों की संख्या हमारी कल्पना से परे है।\n\n" +
                "अब खेल समझिए। हर रोशनी हमें अतीत की खबर देती है।"
        )

        assertEquals(2, result.size)
        assertTrue(result[0].narration.startsWith("जरा सोचिए"))
        assertTrue(result[1].narration.startsWith("अब खेल समझिए"))
        assertTrue(result.all { it.visualPrompt.isNotBlank() })
        assertEquals(5_000L, result.first().durationMs)
    }

    @Test
    fun singleParagraphSplitsOnHindiAndLatinSentencePunctuation() {
        val result = planner.plan("यह पहला दृश्य है। This is scene two. क्या अगला दृश्य तैयार है?")

        assertEquals(3, result.size)
        assertTrue(result[0].narration.endsWith("।"))
        assertTrue(result[1].narration.endsWith("."))
        assertTrue(result[2].narration.endsWith("?"))
    }

    @Test
    fun generatedIdsAreStableAndDuplicateSceneTextStillHasDistinctIds() {
        val once = planner.plan("एक ही लाइन।\n\nएक ही लाइन।")
        val twice = planner.plan("एक ही लाइन।\n\nएक ही लाइन।")

        assertEquals(once.map { it.id }, twice.map { it.id })
        assertNotEquals(once[0].id, once[1].id)
    }

    @Test
    fun emptyScriptsAreRejected() {
        val thrown = runCatching { planner.plan(" \n  ") }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }
}
