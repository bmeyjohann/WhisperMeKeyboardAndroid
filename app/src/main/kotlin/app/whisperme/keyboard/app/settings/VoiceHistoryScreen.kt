package app.whisperme.keyboard.app.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.whisperme.keyboard.ime.voice.exportVoiceHistory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.whisperme.keyboard.keyboardManager
import app.whisperme.keyboard.ime.voice.canContinueFinalProcessing
import app.whisperme.keyboard.ime.voice.summary
import app.whisperme.keyboard.ime.voice.VoiceHistoryStore
import app.whisperme.keyboard.lib.compose.FlorisScreen
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen() {
    val nav = app.whisperme.keyboard.app.LocalNavController.current
    val context = LocalContext.current
    val store = remember { VoiceHistoryStore.get(context) }
    val manager by context.keyboardManager()
    val continuingId by manager.historyContinuationId.collectAsState()
    val retryStatus by manager.voiceRetryStatus.collectAsState()
    val budgetStatus by manager.voiceBudgetStatus.collectAsState()
    val connectionStatus by manager.voiceConnectionStatus.collectAsState()
    val processingStatus by manager.voiceProcessingStatus.collectAsState()
    val entries by store.entries.collectAsState()
    val lastBudget by remember { app.whisperme.keyboard.ime.voice.VoiceBudgetStore.get(context) }.status.collectAsState()
    val error by store.error.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var showHistoryInfo by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var selectingExport by rememberSaveable { mutableStateOf(false) }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var exportNotice by rememberSaveable { mutableStateOf<String?>(null) }
    val exporting = pendingIds.isNotEmpty()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) pendingIds = emptyList() else pendingUri = uri.toString()
    }
    LaunchedEffect(pendingUri) {
        val uri = pendingUri ?: return@LaunchedEffect
        try {
            store.awaitLoaded()
            val snapshot = store.entries.value
            val ids = pendingIds.toList()
            withContext(Dispatchers.IO) {
                val json = exportVoiceHistory(snapshot, ids, System.currentTimeMillis())
                val stream = context.contentResolver.openOutputStream(Uri.parse(uri), "wt")
                    ?: error("Cannot open the selected file")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            exportNotice = "Saved ${ids.distinct().size} sessions as JSON."
            selectingExport = false
            selectedIds = emptyList()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            exportNotice = "Export failed. Please retry; the destination may contain an incomplete file."
        }
        pendingUri = null
        pendingIds = emptyList()
    }
    BackHandler(enabled = selectingExport && !exporting) { selectingExport = false }

    FlorisScreen {
        title = "Transcripts"
        navigationIconVisible = false
        previewFieldVisible = false
        scrollable = false
        content {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate(app.whisperme.keyboard.app.Routes.Settings.VoiceInput) }, modifier = Modifier.weight(1f)) { Text("Voice settings") }
                    OutlinedButton(onClick = { nav.navigate(app.whisperme.keyboard.app.Routes.Settings.KeyboardSettings) }, modifier = Modifier.weight(1f)) { Text("Keyboard settings") }
                }
                KeyboardSetupStatus()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { nav.navigate(app.whisperme.keyboard.app.Routes.Settings.Account) }) { Text("Account and usage") }
                    TextButton(onClick = { nav.navigate(app.whisperme.keyboard.app.Routes.Settings.Feedback) }) { Text("Help and feedback") }
                }
                Text("Your words and finished text, saved on this device.")
                lastBudget?.warningText()?.let { Text("Last reported: $it") }
                TextButton(onClick = { showHistoryInfo = !showHistoryInfo }) { Text(if (showHistoryInfo) "Hide history information" else "About history") }
                if (showHistoryInfo) Text("Sessions from before this feature was enabled cannot be recovered. Sessions are kept until you delete them or uninstall the app. Full transcript and result are kept. Debug events keep the latest 200 samples, up to 4,000 characters each. No audio or authentication tokens are stored. Completed transcripts include a continuation payload; history continuation does not restore editor contents or saved instructions. Context diagnostics are saved only when you enable them in Voice settings; they are included only when you copy full technical details, not in JSON exports.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (entries.isEmpty()) Text("Your voice sessions will appear here. Use the keyboard microphone to begin.")
                else {
                    Row {
                        TextButton(onClick = { selectingExport = !selectingExport }, enabled = !exporting) {
                            Text(if (selectingExport) "Cancel selection" else "Export JSON")
                        }
                        TextButton(onClick = { confirmClear = true }, enabled = !exporting) { Text("Delete all history") }
                    }
                    if (selectingExport) {
                        Text("Choose sessions to save. The file contains your dictation text and results, plus timing, model and status details. No diagnostics, editor context, prompts or audio. Review or redact the text before sharing.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !exporting, onClick = { selectedIds = entries.map { it.id } }) { Text("Select all") }
                            TextButton(enabled = !exporting, onClick = { selectedIds = emptyList() }) { Text("Clear selection") }
                        }
                        val availableIds = entries.map { it.id }.filter { it in selectedIds }
                        Button(enabled = availableIds.isNotEmpty() && !exporting, onClick = {
                            pendingIds = availableIds
                            exportNotice = null
                            try {
                                exportLauncher.launch("whisperme-history-${System.currentTimeMillis()}.json")
                            } catch (failure: Exception) {
                                pendingIds = emptyList()
                                exportNotice = "Could not open the file picker. Please try again."
                            }
                        }) { Text(if (exporting) "Saving…" else "Save JSON (${availableIds.size})") }
                    }
                }
                exportNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                items(entries, key = { it.id }) { entry ->
                    run {
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        var technical by rememberSaveable { mutableStateOf(false) }
                        var confirmResume by remember { mutableStateOf(false) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (selectingExport) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Checkbox(checked = entry.id in selectedIds, enabled = !exporting,
                                            onCheckedChange = { checked ->
                                                selectedIds = if (checked) (selectedIds + entry.id).distinct() else selectedIds - entry.id
                                            })
                                        Text("Include this session")
                                    }
                                }
                                Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                                    Text(DateFormat.getDateTimeInstance().format(Date(entry.startedAt)), style = MaterialTheme.typography.titleMedium)
                                    Text(entry.summary())
                                    Text((entry.result ?: entry.finalAsr ?: entry.transcript).ifEmpty { entry.error ?: "No transcript received" }, maxLines = 3)
                                    Text(if (expanded) "Hide details" else "Show details", color = MaterialTheme.colorScheme.primary)

                                }
                                Row {
                                    entry.result?.let { result -> TextButton(onClick = { clipboard.setText(AnnotatedString(result)) }) { Text("Copy result") } }
                                    val transcript = entry.finalAsr ?: entry.transcript
                                    if (transcript.isNotEmpty()) TextButton(onClick = { clipboard.setText(AnnotatedString(transcript)) }) { Text("Copy transcript") }
                                }
                                if (entry.canContinueFinalProcessing()) {
                                    TextButton(enabled = continuingId == null, onClick = { confirmResume = true }) {
                                        Text("Continue final processing")
                                    }
                                }
                                if (continuingId == entry.id) {
                                    Text(processingStatus)
                                    if (retryStatus.isNotBlank()) Text(retryStatus)
                                    if (budgetStatus.isNotBlank()) Text(budgetStatus)
                                    if (connectionStatus.isNotBlank()) Text(connectionStatus, style = MaterialTheme.typography.bodySmall)
                                }
                                if (confirmResume) AlertDialog(onDismissRequest = { confirmResume = false },
                                    title = { Text("Continue saved transcript?") },
                                    text = { Text("This sends the completed transcript to WhisperMe again. The original field context and instructions are not restored from history. The result stays here for copying; nothing is inserted into an editor.") },
                                    confirmButton = { TextButton(onClick = { confirmResume = false; manager.resumeVoiceHistory(entry) }) { Text("Continue") } },
                                    dismissButton = { TextButton(onClick = { confirmResume = false }) { Text("Cancel") } })
                                entry.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                entry.budget?.warningText()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (expanded) {
                                    entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    Text(if (entry.finalAsr != null) "Complete transcript" else "Partial transcript · not safe for automatic continuation", style = MaterialTheme.typography.titleSmall)
                                    Text(entry.finalAsr ?: entry.transcript)
                                    entry.result?.let { Text("Finished text", style = MaterialTheme.typography.titleSmall); Text(it) }
                                    TextButton(onClick = { technical = !technical }) { Text(if (technical) "Hide technical details" else "Technical details") }
                                    TextButton(onClick = { store.delete(entry.id) }) { Text("Delete session") }
                                    if (technical) {
                                    Text("${entry.status} · ${entry.transport} · ${entry.elapsedMs / 1000.0}s")
                                    entry.failure?.let { Text("${it.stage} / ${it.provider ?: "unknown provider"}: ${it.code} · ${if (it.retryable) "retry possible" else "requires attention"}${it.retryAfterMs?.let { delay -> " · retry after ${delay}ms" }.orEmpty()}") }
                                    Text(entry.usageJson?.let { runCatching { org.json.JSONObject(it).optString("summary") }.getOrNull() }
                                        ?: "Token usage / cost unavailable (not necessarily free)", style = MaterialTheme.typography.bodySmall)
                                    entry.diagnostics?.let { diagnostics ->
                                        var showDiagnostics by rememberSaveable { mutableStateOf(false) }
                                        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                                            Text(if (showDiagnostics) "Hide context diagnostics" else "Show context diagnostics")
                                        }
                                        if (showDiagnostics) {
                                            if (diagnostics.truncated) Text("Diagnostics were truncated.")
                                            diagnostics.contextSources?.let { Text("Context sources", style = MaterialTheme.typography.titleSmall); Text(it.toString()) }
                                            diagnostics.entries.forEach { diagnostic ->
                                                Text("${diagnostic.kind} / ${diagnostic.stage}", style = MaterialTheme.typography.titleSmall)
                                                Text(diagnostic.data.toString())
                                            }
                                        }
                                    }
                                    entry.usageJson?.let { usage ->
                                        Text("Per-call usage and cost", style = MaterialTheme.typography.titleSmall)
                                        val asr = runCatching { org.json.JSONObject(usage).optJSONObject("asr") }.getOrNull()
                                        if (asr != null) Text("ASR used: ${asr.optString("provider")} / ${asr.optString("model")} · ${asr.optDouble("audioSeconds")}s audio")
                                        val calls = runCatching { org.json.JSONObject(usage).optJSONArray("calls") }.getOrNull()
                                        if (calls != null) for (index in 0 until calls.length()) {
                                            val call = calls.optJSONObject(index) ?: continue
                                            Text("${call.optString("stage")} · ${call.optString("provider")} / ${call.optString("model")}")
                                            Text("${call.optString("inputTokens")} input / ${call.optString("outputTokens")} output tokens · " +
                                                if (call.isNull("estimatedUsd")) "Estimate unavailable" else "$${call.optString("estimatedUsd")} estimated")
                                            Text(call.optString("modelSource", "Requested model (older record)"), style = MaterialTheme.typography.bodySmall)
                                            if (call.has("estimateBasis")) Text(call.optString("estimateBasis"), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    TextButton(onClick = {
                                        clipboard.setText(AnnotatedString(kotlinx.serialization.json.Json.encodeToString(
                                            app.whisperme.keyboard.ime.voice.VoiceHistoryEntry.serializer(), entry)))
                                    }) { Text("Copy full technical details") }
                                    Text("ASR: ${entry.transcriptionProvider} / ${entry.transcriptionModel}\nLLM: ${entry.llmProvider.replace("groq_kimi_k2", "groq").replace("cerebras_glm_4_7", "cerebras")} / ${entry.llmModel.ifBlank { "server default" }} (${if (entry.resolvedLlm) "resolved by server" else "requested"})\nMode: ${entry.mode}")
                                    Text("Reasoning effort: ${app.whisperme.keyboard.app.settings.voiceinput.voiceReasoningLabel(entry.reasoningEffort)}")
                                    Text("Timings from start (ms): recording ${entry.recordingMs ?: "—"}, first transcript ${entry.firstTranscriptMs ?: "—"}, final ASR ${entry.finalAsrMs ?: "—"}, LLM result ${entry.resultMs ?: "—"}")
                                    entry.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                                    Text(if (entry.finalAsr != null) "Final ASR" else "Latest ASR (no final received)", style = MaterialTheme.typography.titleSmall)
                                    Text(entry.finalAsr ?: entry.transcript)
                                    entry.result?.let { Text("Final LLM text", style = MaterialTheme.typography.titleSmall); Text(it) }
                                    Text("Debug events (${entry.omittedSamples} older samples omitted)", style = MaterialTheme.typography.titleSmall)
                                    entry.samples.filterNot { it.kind == "assistant.message" && it.text.isBlank() }.forEach { sample ->
                                        Text("${sample.kind} · revision ${sample.revision} · ${sample.elapsedMs}ms\n${sample.text}${if (sample.truncated) "\n[Truncated to 4,000 characters]" else ""}")
                                    }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Delete all voice history?") },
        text = { Text("This permanently removes saved sessions from this device.") },
        confirmButton = { TextButton(onClick = { store.clear(); confirmClear = false }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}
