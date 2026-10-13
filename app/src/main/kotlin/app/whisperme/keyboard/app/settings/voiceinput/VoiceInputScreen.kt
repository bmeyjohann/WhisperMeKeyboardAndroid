/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.whisperme.keyboard.app.settings.voiceinput

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import app.whisperme.keyboard.app.LocalNavController
import app.whisperme.keyboard.app.Routes
import app.whisperme.keyboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries

@Composable
fun VoiceInputScreen() = FlorisScreen {
    title = "Voice settings"
    previewFieldVisible = false
    navigationIconVisible = true

    val navController = LocalNavController.current

    content {
        app.whisperme.keyboard.app.settings.VoiceAccountSettings()
        Text("Tap the WhisperMe icon to start or stop. Hold for at least one second, speak, then release to finish.", modifier = Modifier.padding(16.dp))
        Preference(title = "Saved preferences", summary = "View saved instructions, choose your writing style, and turn preferences on or off",
            onClick = { navController.navigate(Routes.Settings.VoiceInputNotes) })
        var advanced by remember { mutableStateOf(false) }
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(8.dp)) {
            Text(if (advanced) "Hide advanced settings" else "Advanced settings")
        }
        if (advanced) {
        ListPreference(
            listPref = prefs.voiceInput.transcriptionProvider,
            icon = Icons.Default.Settings,
            title = "Transcription service",
            entries = listPrefEntries {
                entry(key = VoiceTranscriptionProvider.MUSE_REALTIME, label = "Meta Muse Realtime")
                entry(
                    key = VoiceTranscriptionProvider.GROQ_BATCH,
                    label = "Groq Batch",
                    description = "Record then upload audio in one request",
                )
                entry(
                    key = VoiceTranscriptionProvider.VOXTRAL_REALTIME,
                    label = "Voxtral Realtime",
                    description = "Stream audio chunks to /realtime over websocket",
                )
            },
        )
        ListPreference(
            listPref = prefs.voiceInput.llmProvider,
            icon = Icons.Default.Settings,
            title = "LLM service",
            entries = listPrefEntries {
                entry(key = VoiceLlmProvider.SERVER_DEFAULT, label = "WhisperMe default",
                    description = "WhisperMe chooses the provider and model")
                entry(
                    key = VoiceLlmProvider.GROQ_KIMI_K2,
                    label = "Groq",
                )
                entry(
                    key = VoiceLlmProvider.CEREBRAS_GLM_4_7,
                    label = "Cerebras",
                )
            },
        )

        val llmModel by prefs.voiceInput.llmModel.observeAsState()
        val llmProvider by prefs.voiceInput.llmProvider.observeAsState()
        LaunchedEffect(llmProvider, llmModel) {
            val compatible = llmProvider.compatibleModel(llmModel)
            if (llmProvider != VoiceLlmProvider.SERVER_DEFAULT && compatible != llmModel) prefs.voiceInput.llmModel.set(compatible)
        }
        val transcriptionModel by prefs.voiceInput.transcriptionModel.observeAsState()
        val endpoint by prefs.voiceInput.realtimeEndpoint.observeAsState()
        if (llmProvider != VoiceLlmProvider.SERVER_DEFAULT) ListPreference(listPref = prefs.voiceInput.llmModel, title = "LLM model", entries = listPrefEntries {
            llmProvider.models().forEach { (id, label) -> entry(key = id, label = label) }
        })
        val auth = app.whisperme.keyboard.app.auth.Auth0Manager.getInstance(LocalContext.current)
        val serverModel by auth.backendDefaultLlmModel.collectAsState()
        val reasoningPreference = prefs.voiceInput.reasoningEffort
        val savedReasoning by reasoningPreference.observeAsState()
        val effectiveModel = if (llmProvider == VoiceLlmProvider.SERVER_DEFAULT) serverModel else llmProvider.compatibleModel(llmModel)
        val reasoningLevels = supportedVoiceReasoning(effectiveModel)
        val effectiveReasoning = compatibleVoiceReasoning(effectiveModel, savedReasoning)
        Text("Reasoning effort: ${voiceReasoningLabel(effectiveReasoning)}", modifier = Modifier.padding(horizontal = 16.dp))
        Slider(
            value = reasoningLevels.indexOf(effectiveReasoning).coerceAtLeast(0).toFloat(),
            onValueChange = { position ->
                reasoningPreference.set(reasoningLevels[position.roundToInt().coerceIn(reasoningLevels.indices)])
            },
            valueRange = 0f..reasoningLevels.lastIndex.coerceAtLeast(1).toFloat(),
            steps = (reasoningLevels.size - 2).coerceAtLeast(0),
            enabled = reasoningLevels.size > 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            reasoningLevels.forEach { level ->
                Text(voiceReasoningLabel(level), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(when {
            effectiveModel.isNullOrBlank() -> "Default follows the server. Connect once to see supported reasoning levels."
            effectiveReasoning != savedReasoning -> "The saved level is unavailable for this model; Default will be used."
            effectiveReasoning == "default" -> "Default follows the server/provider; no reasoning override is sent."
            else -> "Higher effort may take longer. Default follows the server/provider."
        }, modifier = Modifier.padding(16.dp))
        OutlinedTextField(value = transcriptionModel, onValueChange = { prefs.voiceInput.transcriptionModel.set(it) },
            label = { Text("Transcription model (blank: default)") },
            modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true)
        OutlinedTextField(value = endpoint, onValueChange = { prefs.voiceInput.realtimeEndpoint.set(it) },
            label = { Text("Trusted WhisperMe realtime endpoint") },
            supportingText = { Text("This server receives your sign-in token and audio.") },
            modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true)
        ListPreference(listPref = prefs.voiceInput.mode, title = "Voice mode", entries = listPrefEntries {
            entry(key = "auto", label = "Automatic")
            entry(key = "verbatim", label = "Verbatim")
            entry(key = "clean", label = "Clean dictation")
            entry(key = "command", label = "Editing command")
        })
        SwitchPreference(prefs.voiceInput.diagnosticContext, title = "Save context diagnostics",
            summary = "Include editor context in local session details and exports. No audio or authentication data. Off by default.")
        SwitchPreference(prefs.voiceInput.liveHints, title = "Live clarification questions (experimental)",
            summary = "Show optional assistant questions while you speak. Adds LLM requests.")
        }
    }
}
