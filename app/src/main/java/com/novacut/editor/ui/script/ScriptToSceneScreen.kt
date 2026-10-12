package com.novacut.editor.ui.script

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PasswordVisualTransformation
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.novacut.editor.R
import com.novacut.editor.ai.script.ScriptScene
import com.novacut.editor.ai.script.ScriptSceneExportFormat
import com.novacut.editor.ai.script.ScriptSceneGenerationMode
import com.novacut.editor.ai.script.ScriptToSceneViewModel
import com.novacut.editor.ui.projects.ProjectListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ScriptToSceneScreen(
    onBack: () -> Unit,
    onProjectCreated: (String) -> Unit,
    viewModel: ScriptToSceneViewModel = hiltViewModel(),
    projectListViewModel: ProjectListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSceneId by remember { mutableStateOf<String?>(null) }
    var exportFailed by remember { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val sceneId = pendingSceneId
        if (uri != null && sceneId != null) viewModel.attachMedia(sceneId, uri)
        pendingSceneId = null
    }

    fun saveExport(uri: Uri?, format: ScriptSceneExportFormat) {
        if (uri == null) return
        scope.launch {
            try {
                val contents = withContext(Dispatchers.Default) { viewModel.export(format) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(contents.toByteArray(Charsets.UTF_8))
                    } ?: error("Output stream is unavailable.")
                }
                exportFailed = false
            } catch (_: Exception) {
                exportFailed = true
            }
        }
    }

    val otioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        saveExport(it, ScriptSceneExportFormat.OTIO)
    }
    val fcpxmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) {
        saveExport(it, ScriptSceneExportFormat.FCPXML)
    }
    val edlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        saveExport(it, ScriptSceneExportFormat.EDL)
    }
    val storyboardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        saveExport(it, ScriptSceneExportFormat.STORYBOARD_JSON)
    }

    Scaffold { insets ->
        Column(
            modifier = Modifier
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                Text(
                    text = stringResource(R.string.s2s_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            OutlinedTextField(
                value = state.projectName,
                onValueChange = viewModel::setProjectName,
                label = { Text(stringResource(R.string.s2s_project_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.script,
                onValueChange = viewModel::setScript,
                label = { Text(stringResource(R.string.s2s_script_label)) },
                placeholder = { Text(stringResource(R.string.s2s_script_hint)) },
                minLines = 5,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.s2s_mode_label), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.generationMode == ScriptSceneGenerationMode.OFFLINE,
                    onClick = { viewModel.setGenerationMode(ScriptSceneGenerationMode.OFFLINE) },
                    label = { Text(stringResource(R.string.s2s_mode_offline)) },
                )
                FilterChip(
                    selected = state.generationMode == ScriptSceneGenerationMode.OPENAI_COMPATIBLE,
                    onClick = { viewModel.setGenerationMode(ScriptSceneGenerationMode.OPENAI_COMPATIBLE) },
                    label = { Text(stringResource(R.string.s2s_mode_cloud)) },
                )
            }
            if (state.generationMode == ScriptSceneGenerationMode.OFFLINE) {
                Text(
                    text = stringResource(R.string.s2s_local_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.s2s_cloud_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = state.endpoint,
                    onValueChange = viewModel::setEndpoint,
                    label = { Text(stringResource(R.string.s2s_endpoint_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::setModel,
                    label = { Text(stringResource(R.string.s2s_model_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.apiKey,
                    onValueChange = viewModel::setApiKey,
                    label = { Text(stringResource(R.string.s2s_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = viewModel::generate,
                enabled = state.script.isNotBlank() && !state.isWorking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.isWorking) R.string.s2s_generating else R.string.s2s_generate
                    )
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            if (exportFailed) {
                Text(
                    text = stringResource(R.string.s2s_generate_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.scenes.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.s2s_scene_count, state.scenes.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.s2s_media_relink_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.scenes.forEachIndexed { index, scene ->
                    ScriptSceneCard(
                        index = index,
                        scene = scene,
                        onNarrationChange = { viewModel.updateScene(scene.id, narration = it) },
                        onVisualPromptChange = { viewModel.updateScene(scene.id, visualPrompt = it) },
                        onNegativePromptChange = { viewModel.updateScene(scene.id, negativePrompt = it) },
                        onDurationChange = { viewModel.updateScene(scene.id, durationMs = it) },
                        onAttachMedia = {
                            pendingSceneId = scene.id
                            mediaPicker.launch(arrayOf("image/*", "video/*"))
                        },
                        onClearMedia = { viewModel.clearMedia(scene.id) },
                        onMoveUp = { viewModel.moveScene(scene.id, -1) },
                        onMoveDown = { viewModel.moveScene(scene.id, 1) },
                        onRemove = { viewModel.removeScene(scene.id) },
                    )
                }

                Button(
                    onClick = {
                        projectListViewModel.createProjectFromScriptScenes(
                            projectName = state.projectName,
                            scenes = state.scenes,
                            onCreated = onProjectCreated,
                        )
                    },
                    enabled = state.scenes.any { !it.mediaUri.isNullOrBlank() } && !state.isWorking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.s2s_create_timeline))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { otioLauncher.launch(fileName(state.projectName, "otio")) },
                        enabled = state.scenes.any { !it.mediaUri.isNullOrBlank() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.s2s_export_otio)) }
                    OutlinedButton(
                        onClick = { fcpxmlLauncher.launch(fileName(state.projectName, "fcpxml")) },
                        enabled = state.scenes.any { !it.mediaUri.isNullOrBlank() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.s2s_export_fcpxml)) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = { edlLauncher.launch(fileName(state.projectName, "edl")) },
                        enabled = state.scenes.any { !it.mediaUri.isNullOrBlank() },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.s2s_export_edl)) }
                    OutlinedButton(
                        onClick = {
                            storyboardLauncher.launch(fileName(state.projectName, "clearcut-storyboard.json"))
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.s2s_export_storyboard)) }
                }
            } else {
                Text(
                    text = stringResource(R.string.s2s_no_scenes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScriptSceneCard(
    index: Int,
    scene: ScriptScene,
    onNarrationChange: (String) -> Unit,
    onVisualPromptChange: (String) -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onDurationChange: (Long) -> Unit,
    onAttachMedia: () -> Unit,
    onClearMedia: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.s2s_scene_index, index + 1),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = scene.narration,
                onValueChange = onNarrationChange,
                label = { Text(stringResource(R.string.s2s_scene_narration)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = scene.visualPrompt,
                onValueChange = onVisualPromptChange,
                label = { Text(stringResource(R.string.s2s_visual_prompt)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = scene.negativePrompt,
                onValueChange = onNegativePromptChange,
                label = { Text(stringResource(R.string.s2s_negative_prompt)) },
                minLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = (scene.durationMs / 1_000L).toString(),
                onValueChange = { raw ->
                    raw.toLongOrNull()?.takeIf { it > 0L }?.let { onDurationChange(it * 1_000L) }
                },
                label = { Text(stringResource(R.string.s2s_duration_seconds)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (scene.mediaUri != null) {
                Text(
                    text = stringResource(R.string.s2s_media_attached),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onAttachMedia, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.s2s_attach_media))
                }
                if (scene.mediaUri != null) {
                    TextButton(onClick = onClearMedia) { Text(stringResource(R.string.s2s_clear_media)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onMoveUp) { Text(stringResource(R.string.s2s_move_up)) }
                TextButton(onClick = onMoveDown) { Text(stringResource(R.string.s2s_move_down)) }
                Spacer(Modifier.width(1.dp))
                TextButton(onClick = onRemove) { Text(stringResource(R.string.s2s_remove_scene)) }
            }
        }
    }
}

private fun fileName(projectName: String, extension: String): String {
    val safeBase = projectName
        .replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(70)
        .ifBlank { "clearcut-storyboard" }
    return "$safeBase.$extension"
}
