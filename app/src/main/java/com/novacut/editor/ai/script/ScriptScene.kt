package com.novacut.editor.ai.script

import java.nio.charset.StandardCharsets
import java.util.UUID

enum class ScriptSceneMediaKind { IMAGE, VIDEO }

data class ScriptScene(
    val id: String,
    val narration: String,
    val visualPrompt: String,
    val negativePrompt: String = "",
    val durationMs: Long = DEFAULT_SCENE_DURATION_MS,
    val mediaUri: String? = null,
    val mediaKind: ScriptSceneMediaKind? = null,
    val sourceDurationMs: Long? = null,
) {
    init {
        require(id.isNotBlank())
        require(durationMs in MIN_SCENE_DURATION_MS..MAX_SCENE_DURATION_MS)
    }

    companion object {
        const val DEFAULT_SCENE_DURATION_MS = 5_000L
        const val MIN_SCENE_DURATION_MS = 1_000L
        const val MAX_SCENE_DURATION_MS = 1_800_000L

        fun create(narration: String, index: Int): ScriptScene {
            val clean = narration.trim()
            val stableId = UUID.nameUUIDFromBytes(
                "$index:$clean".toByteArray(StandardCharsets.UTF_8)
            ).toString()
            return ScriptScene(
                id = stableId,
                narration = clean,
                visualPrompt = "Cinematic documentary visual illustrating: $clean",
                negativePrompt = "Avoid unrelated subjects, distorted details, unwanted text, and watermarks.",
            )
        }
    }
}

class OfflineScriptScenePlanner {
    /** Local fallback: deterministic scene splitting, not a downloaded language model. */
    fun plan(script: String): List<ScriptScene> {
        val normalized = script.replace("\r\n", "\n").replace('\r', '\n').trim()
        require(normalized.isNotBlank()) { "Script must not be blank." }
        require(normalized.length <= MAX_SCRIPT_CHARACTERS) {
            "Script is too long; limit it to $MAX_SCRIPT_CHARACTERS characters."
        }

        val paragraphs = normalized
            .split(Regex("\n\\s*\n+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val segments = (if (paragraphs.size > 1) paragraphs else splitSentences(normalized))
            .filter(String::isNotBlank)
        require(segments.size <= MAX_SCENES) {
            "Script creates too many scenes; limit it to $MAX_SCENES scenes."
        }
        return segments.mapIndexed { index, segment -> ScriptScene.create(segment, index) }
    }

    private fun splitSentences(script: String): List<String> {
        val sentences = script
            .split(Regex("(?<=[.!?।؟])\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
        return if (sentences.isEmpty()) listOf(script.trim()) else sentences
    }

    companion object {
        const val MAX_SCRIPT_CHARACTERS = 20_000
        const val MAX_SCENES = 100
    }
}
