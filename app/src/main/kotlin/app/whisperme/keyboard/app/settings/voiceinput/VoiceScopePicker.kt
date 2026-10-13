package app.whisperme.keyboard.app.settings.voiceinput

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.whisperme.keyboard.ime.voice.*

@Composable
internal fun VoiceCategoryPicker(category: String?, onChange: (String?) -> Unit, allowUnassigned: Boolean = false, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }) { Text(category?.let(::voiceCategoryLabel) ?: "Choose category") }
        DropdownMenu(expanded, { expanded = false }) {
            if (allowUnassigned) DropdownMenuItem(text = { Text("Unassigned") }, onClick = { onChange(null); expanded = false })
            voiceAppCategories.forEach { item ->
                DropdownMenuItem(text = { Text(item.label) }, onClick = { onChange(item.id); expanded = false })
            }
        }
    }
}

@Composable
internal fun VoiceAppPicker(app: String?, appNames: Map<String, String>, onChange: (String) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    Column {
        OutlinedButton(enabled = enabled, onClick = { expanded = !expanded }) { Text(appNames[app] ?: app ?: "Choose app") }
        if (expanded) {
            OutlinedTextField(search, { search = it }, label = { Text("Find app") }, singleLine = true)
            Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                appNames.entries.sortedBy { it.value.lowercase() }.filter { it.value.contains(search, true) || it.key.contains(search, true) }.forEach { item ->
                    TextButton(onClick = { onChange(item.key); expanded = false }) { Text(item.value) }
                }
            }
        }
    }
}

@Composable
internal fun VoiceScopePicker(value: VoicePreferenceScope, onChange: (VoicePreferenceScope) -> Unit,
    appNames: Map<String, String>, originalApp: String? = null, locked: Boolean = false, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Applies to", style = MaterialTheme.typography.labelMedium)
        Row {
            listOf("app" to "App", "category" to "Category", "all" to "All apps").forEach { (id, label) ->
                TextButton(enabled = enabled && !locked, onClick = {
                    onChange(when (id) {
                        "app" -> VoicePreferenceScope("app", originalApp ?: value.appIdentifier)
                        "category" -> VoicePreferenceScope("category", categoryId = value.categoryId)
                        else -> VoicePreferenceScope()
                    })
                }) { Text(if (value.scope == id) "✓ $label" else label) }
            }
        }
        if (value.scope == "category") VoiceCategoryPicker(value.categoryId, {
            onChange(VoicePreferenceScope("category", categoryId = it))
        }, enabled = enabled && !locked)
        if (value.scope == "app") {
            if (originalApp != null) Text(appNames[originalApp] ?: originalApp)
            else VoiceAppPicker(value.appIdentifier, appNames, { onChange(VoicePreferenceScope("app", it)) }, enabled && !locked)
        }
    }
}
