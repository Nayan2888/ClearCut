package com.novacut.editor.ai.script

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novacut.editor.engine.TimelineExchangeEngine
import com.novacut.editor.model.Clip
import com.novacut.editor.model.TimelineTimebase
import com.novacut.editor.model.Track
import com.novacut.editor.model.TrackType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

enum class ScriptSceneGenerationMode { OFFLINE, OPENAI_COMPATIBLE }
enum class ScriptSceneExportFormat(val extension: String, val mimeType: String) {
    OTIO("otio", "application/json"),
    FCPXML("fcpxml", "application/xml"),
    EDL("edl", "text/plain"),
    STORYBOARD_JSON("clearcut-storyboard.json", "application/json"),
}

data class ScriptToSceneUiState(
    val projectName: String = "Script storyboard",
    val script: String = "",
    val generationMode: ScriptSceneGenerationMode = ScriptSceneGenerationMode.OFFLINE,
    val endpoint: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val scenes: List<ScriptScene> = emptyList(),
    val isWorking: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ScriptToSceneViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val timelineExchangeEngine: TimelineExchangeEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(ScriptToSceneUiState())
    val state = _state.asStateFlow()
    private val offlinePlanner = OfflineScriptScenePlanner()
    private val remoteProvider = OpenAiCompatibleSceneProvider()

    fun setProjectName(value: String) = _state.update { it.copy(projectName = value.take(80)) }
    fun setScript(value: String) = _state.update { it.copy(script = value.take(OfflineScriptScenePlanner.MAX_SCRIPT_CHARACTERS), error = null) }
    fun setGenerationMode(value: ScriptSceneGenerationMode) = _state.update { it.copy(generationMode = value, error = null) }
    fun setEndpoint(value: String) = _state.update { it.copy(endpoint = value.take(2_000), error = null) }
    fun setModel(value: String) = _state.update { it.copy(model = value.take(200), error = null) }
    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value.take(4_000), error = null) }

    fun generate() {
        val snapshot = _state.value
        if (snapshot.isWorking) return
        _state.update { it.copy(isWorking = true, error = null) }
        viewModelScope.launch {
            try {
                val generated = when (snapshot.generationMode) {
                    ScriptSceneGenerationMode.OFFLINE -> withContext(Dispatchers.Default) {
                        offlinePlanner.plan(snapshot.script)
                    }
                    ScriptSceneGenerationMode.OPENAI_COMPATIBLE -> remoteProvider.generate(
                        script = snapshot.script,
                        endpoint = snapshot.endpoint,
                        model = snapshot.model,
                        apiKey = snapshot.apiKey,
                    )
                }
                _state.update { current ->
                    current.copy(scenes = generated, isWorking = false, error = null)
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isWorking = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        isWorking = false,
                        error = failure.message?.take(240) ?: "Scene generation failed.",
                    )
                }
            }
        }
    }

    fun updateScene(
        sceneId: String,
        narration: String? = null,
        visualPrompt: String? = null,
        negativePrompt: String? = null,
        durationMs: Long? = null,
    ) {
        _state.update { current ->
            current.copy(
                scenes = current.scenes.map { scene ->
                    if (scene.id != sceneId) scene else scene.copy(
                        narration = narration?.take(4_000) ?: scene.narration,
                        visualPrompt = visualPrompt?.take(4_000) ?: scene.visualPrompt,
                        negativePrompt = negativePrompt?.take(4_000) ?: scene.negativePrompt,
                        durationMs = durationMs?.coerceIn(
                            ScriptScene.MIN_SCENE_DURATION_MS,
                            ScriptScene.MAX_SCENE_DURATION_MS,
                        ) ?: scene.durationMs,
                    )
                }
            )
        }
    }

    fun removeScene(sceneId: String) {
        _state.update { it.copy(scenes = it.scenes.filterNot { scene -> scene.id == sceneId }) }
    }

    fun moveScene(sceneId: String, offset: Int) {
        _state.update { current ->
            val sourceIndex = current.scenes.indexOfFirst { it.id == sceneId }
            if (sourceIndex < 0 || current.scenes.isEmpty()) {
                current
            } else {
                val targetIndex = (sourceIndex + offset).coerceIn(0, current.scenes.lastIndex)
                if (sourceIndex == targetIndex) {
                    current
                } else {
                    val mutable = current.scenes.toMutableList()
                    val scene = mutable.removeAt(sourceIndex)
                    mutable.add(targetIndex, scene)
                    current.copy(scenes = mutable)
                }
            }
        }
    }

    fun attachMedia(sceneId: String, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mimeType = runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("")
            val kind = when {
                mimeType.startsWith("image/") -> ScriptSceneMediaKind.IMAGE
                mimeType.startsWith("video/") -> ScriptSceneMediaKind.VIDEO
                else -> null
            }
            if (kind == null) {
                _state.update { it.copy(error = "Choose an image or video file.") }
                return@launch
            }
            val sourceDurationMs = if (kind == ScriptSceneMediaKind.IMAGE) {
                null
            } else {
                withContext(Dispatchers.IO) { readVideoDuration(uri) }
            }
            _state.update { current ->
                current.copy(
                    error = null,
                    scenes = current.scenes.map { scene ->
                        if (scene.id == sceneId) scene.copy(
                            mediaUri = uri.toString(),
                            mediaKind = kind,
                            sourceDurationMs = sourceDurationMs,
                        ) else scene
                    },
                )
            }
        }
    }

    fun clearMedia(sceneId: String) {
        _state.update { current ->
            current.copy(
                scenes = current.scenes.map { scene ->
                    if (scene.id == sceneId) scene.copy(
                        mediaUri = null,
                        mediaKind = null,
                        sourceDurationMs = null,
                    ) else scene
                },
            )
        }
    }

    fun export(format: ScriptSceneExportFormat): String {
        val snapshot = _state.value
        val projectName = snapshot.projectName.trim().ifBlank { "ClearCut Storyboard" }
        if (format == ScriptSceneExportFormat.STORYBOARD_JSON) {
            return JSONObject().apply {
                put("schema", "com.novacut.clearcut.storyboard")
                put("schemaVersion", 1)
                put("projectName", projectName)
                put("exportedAtEpochMs", System.currentTimeMillis())
                put("scenes", JSONArray().apply {
                    snapshot.scenes.forEach { scene ->
                        put(JSONObject().apply {
                            put("id", scene.id)
                            put("narration", scene.narration)
                            put("visualPrompt", scene.visualPrompt)
                            put("negativePrompt", scene.negativePrompt)
                            put("durationMs", scene.durationMs)
                            scene.mediaUri?.let { put("mediaUri", it) }
                            scene.mediaKind?.let { put("mediaKind", it.name) }
                        })
                    }
                })
                put("mediaRelinkingMayBeRequired", true)
            }.toString(2)
        }

        val tracks = buildExportTracks(snapshot.scenes)
        require(tracks.firstOrNull { it.type == TrackType.VIDEO }?.clips?.isNotEmpty() == true) {
            "Attach at least one image or video before exporting a timeline."
        }
        val timebase = TimelineTimebase(30)
        return when (format) {
            ScriptSceneExportFormat.OTIO -> timelineExchangeEngine.exportToOtio(
                tracks = tracks,
                textOverlays = emptyList(),
                projectName = projectName,
                timebase = timebase,
            )
            ScriptSceneExportFormat.FCPXML -> timelineExchangeEngine.exportToFcpxml(
                tracks = tracks,
                projectName = projectName,
                timebase = timebase,
            )
            ScriptSceneExportFormat.EDL -> timelineExchangeEngine.exportToEdl(
                tracks = tracks,
                projectName = projectName,
                timebase = timebase,
            )
            ScriptSceneExportFormat.STORYBOARD_JSON -> error("Handled above.")
        }
    }

    private fun buildExportTracks(scenes: List<ScriptScene>): List<Track> {
        var cursorMs = 0L
        val clips = buildList {
            scenes.forEach { scene ->
                val rawUri = scene.mediaUri ?: return@forEach
                val androidUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return@forEach
                if (androidUri.scheme.isNullOrBlank()) return@forEach
                val sourceDuration = when (scene.mediaKind) {
                    ScriptSceneMediaKind.IMAGE -> scene.durationMs
                    ScriptSceneMediaKind.VIDEO -> scene.sourceDurationMs ?: scene.durationMs
                    null -> return@forEach
                }.coerceAtLeast(1L)
                val trimEnd = if (scene.mediaKind == ScriptSceneMediaKind.IMAGE) {
                    scene.durationMs
                } else {
                    scene.durationMs.coerceAtMost(sourceDuration)
                }.coerceIn(1L, sourceDuration)
                val clip = Clip(
                    sourceUri = androidUri,
                    sourceDurationMs = sourceDuration,
                    timelineStartMs = cursorMs,
                    trimStartMs = 0L,
                    trimEndMs = trimEnd,
                    name = scene.narration.take(80),
                )
                add(clip)
                cursorMs += clip.durationMs
            }
        }
        return listOf(
            Track(type = TrackType.VIDEO, index = 0, clips = clips),
            Track(type = TrackType.AUDIO, index = 1),
        )
    }

    private fun readVideoDuration(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}
