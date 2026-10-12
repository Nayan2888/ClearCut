package com.novacut.editor.ai.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * OpenAI chat-completions compatible provider. HTTPS is required so a bearer
 * token cannot silently be sent over cleartext HTTP. API keys are per-call only.
 */
class OpenAiCompatibleSceneProvider {
    suspend fun generate(
        script: String,
        endpoint: String,
        model: String,
        apiKey: String,
    ): List<ScriptScene> = withContext(Dispatchers.IO) {
        require(script.isNotBlank()) { "Script must not be blank." }
        require(script.length <= OfflineScriptScenePlanner.MAX_SCRIPT_CHARACTERS) {
            "Script is too long."
        }
        require(model.isNotBlank()) { "Model name is required." }
        val requestUrl = resolveEndpoint(endpoint)
        val payload = JSONObject().apply {
            put("model", model.trim())
            put("temperature", 0.3)
            put("max_tokens", 4_000)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "Convert the provided script into a storyboard and return only valid JSON. " +
                            "The root object must contain a scenes array. Every scene must contain " +
                            "narration, visualPrompt, negativePrompt, and durationMs. " +
                            "Keep narration faithful to its original language, create a useful visual prompt, " +
                            "and use durationMs between 1000 and 1800000. Do not return markdown fences."
                    )
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", script)
                })
            })
        }

        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            apiKey.trim().takeIf(String::isNotEmpty)?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val response = StringBuilder()
                val chunk = CharArray(8_192)
                while (true) {
                    val count = reader.read(chunk)
                    if (count < 0) break
                    require(response.length + count <= MAX_RESPONSE_CHARACTERS) {
                        "Provider response exceeded the allowed size."
                    }
                    response.append(chunk, 0, count)
                }
                response.toString()
            }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("Provider returned HTTP $status.")
            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .optJSONObject(0)
                ?.getJSONObject("message")
                ?.optString("content")
                .orEmpty()
            parseScenes(content)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseScenes(content: String): List<ScriptScene> {
        val clean = content.trim()
            .replace(Regex("^\\s*```(?:json)?\\s*"), "")
            .replace(Regex("\\s*```\\s*$"), "")
            .trim()
        val root = JSONObject(clean)
        val scenes = root.optJSONArray("scenes")
            ?: if (root.has("narration")) JSONArray().put(root) else JSONArray()
        require(scenes.length() in 1..OfflineScriptScenePlanner.MAX_SCENES) {
            "Provider returned an invalid scene count."
        }

        val result = buildList {
            for (index in 0 until scenes.length()) {
                val item = scenes.optJSONObject(index) ?: continue
                val narration = item.optString("narration").trim().take(MAX_FIELD_CHARACTERS)
                if (narration.isBlank()) continue
                val prompt = item.optString("visualPrompt").trim()
                    .ifBlank { "Cinematic documentary visual illustrating: $narration" }
                    .take(MAX_FIELD_CHARACTERS)
                val negativePrompt = item.optString("negativePrompt").trim().take(MAX_FIELD_CHARACTERS)
                val duration = item.optLong("durationMs", ScriptScene.DEFAULT_SCENE_DURATION_MS)
                    .coerceIn(ScriptScene.MIN_SCENE_DURATION_MS, ScriptScene.MAX_SCENE_DURATION_MS)
                add(
                    ScriptScene(
                        id = ScriptScene.create(narration, index).id,
                        narration = narration,
                        visualPrompt = prompt,
                        negativePrompt = negativePrompt,
                        durationMs = duration,
                    )
                )
            }
        }
        require(result.isNotEmpty()) { "Provider response contains no usable scenes." }
        return result
    }

    private fun resolveEndpoint(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "Endpoint URL is required." }
        val uri = URI(trimmed)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "Use an HTTPS OpenAI-compatible endpoint."
        }
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_RESPONSE_CHARACTERS = 1_000_000
        private const val MAX_FIELD_CHARACTERS = 4_000
    }
}
