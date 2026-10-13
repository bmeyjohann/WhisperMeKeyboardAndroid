package app.whisperme.keyboard.ime.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.whisperme.keyboard.app.settings.voiceinput.AppListCache
import app.whisperme.keyboard.app.settings.voiceinput.VoiceScopePicker
import app.whisperme.keyboard.app.florisPreferenceModel
import dev.patrickgold.jetpref.datastore.model.observeAsState
import app.whisperme.keyboard.keyboardManager

@Composable
fun PreferenceSuggestionDialog() {
    val context = LocalContext.current
    val manager by context.keyboardManager()
    val prefs by florisPreferenceModel()
    val dictionaryEnabled by prefs.voiceInput.useDictionary.observeAsState()
    val suggestion by manager.preferenceSuggestion.collectAsState()
    val editorOpen by manager.preferenceEditorOpen.collectAsState()
    val busy by manager.preferenceSaveBusy.collectAsState()
    val error by manager.preferenceSaveError.collectAsState()
    val recording by manager.isVoiceRecording.collectAsState()
    val processing by manager.isVoiceProcessing.collectAsState()
    val draft = suggestion ?: return
    if (recording || processing) return
    var text by remember(draft) { mutableStateOf(draft.text) }
    var name by remember(draft) { mutableStateOf(draft.name.orEmpty()) }
    var selectedScope by remember(draft) { mutableStateOf(draft.suggestedScope) }
    val word = draft.kind == "dictionary"
    val appNames = remember { AppListCache.loadAppList(context).associate { it.packageName to it.appName } }
    // Render inside the IME window: an Activity Dialog has no valid window token here.
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        if (!editorOpen) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AssistChip(modifier = Modifier.weight(1f).padding(horizontal = 8.dp), onClick = manager::openPreferenceEditor,
                    label = { Text(if (word) "Remember spelling: ${draft.text}" else "Review writing rule", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                TextButton(onClick = manager::dismissPreferenceSuggestion) { Text("Dismiss") }
            }
        } else {
        Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (word) "Remember this spelling?" else if (draft.requiresConfirmation) "Save this rule?" else "Remember this correction?",
                style = MaterialTheme.typography.titleMedium)
            Text(if (word) "Add this word to your dictionary for future dictation."
                else if (draft.requiresConfirmation) "Nothing has been saved. Review this rule for future dictation."
                else "Review this instruction before saving it for future dictation.")
            if (!word) OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, enabled = !busy)
            OutlinedTextField(text, { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (word) "Word or name" else "Instruction to remember") }, singleLine = word, enabled = !busy)
            Text("${utf8Bytes(text)} UTF-8 bytes${if (word) " · All apps on this device. Affects the next recording; dictionary use stays as configured." else " · Up to 2,000 characters / 8,000 bytes. Preference use stays as configured."}")
            if (word && !dictionaryEnabled) Text("Dictionary use is off. Saving keeps it off. Enable it in Voice settings when you want to use these words.")
            if (!word) VoiceScopePicker(selectedScope, { selectedScope = it }, appNames, draft.packageName.takeIf { it.isNotBlank() }, draft.effectiveScopeLocked, !busy)
            draft.scopeReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!draft.requiresConfirmation) TextButton(enabled = !busy, onClick = manager::closePreferenceEditor) { Text("Back") }
                TextButton(enabled = !busy, onClick = { manager.dismissPreferenceSuggestion() }) {
                    Text(if (draft.requiresConfirmation) "Don't save" else "Just this time")
                }
                TextButton(enabled = !busy && (word || selectedScope.valid()) && name.length <= 180 &&
                    (if (word) validDictionaryWord(text) else VoicePreferenceLimits.validRule(text)),
                    onClick = { manager.savePreferenceSuggestion(text, selectedScope, name) }) {
                    Text(if (busy) "Saving…" else "Save")
                }
            }
        }
        }
    }
}
