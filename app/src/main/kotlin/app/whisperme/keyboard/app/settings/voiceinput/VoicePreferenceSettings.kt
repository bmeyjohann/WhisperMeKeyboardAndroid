package app.whisperme.keyboard.app.settings.voiceinput

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.whisperme.keyboard.app.AppPrefs
import app.whisperme.keyboard.ime.voice.*
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun VoicePreferenceSettings(prefs: AppPrefs, fetchRules: (suspend () -> List<JSONObject>)? = null) {
    val context = LocalContext.current
    val api = remember { VoicePreferenceApi(context) }
    val budget by remember { app.whisperme.keyboard.ime.voice.VoiceBudgetStore.get(context) }.status.collectAsState()
    val scope = rememberCoroutineScope()
    val stored by prefs.voiceInput.preferenceProfiles.observeAsState()
    val profiles = remember(stored) { readVoiceProfiles(stored) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VoicePreferenceProfile?>(null) }
    var rules by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editingRule by remember { mutableStateOf<JSONObject?>(null) }
    var deletingRule by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val dictionaryRaw by prefs.voiceInput.dictionary.observeAsState()
    val dictionary = remember(dictionaryRaw) { app.whisperme.keyboard.ime.voice.readVoiceDictionary(dictionaryRaw) }
    val categoriesRaw by prefs.voiceInput.appCategories.observeAsState()
    val categories = remember(categoriesRaw) { readVoiceAppCategories(categoriesRaw) }
    var mappingExpanded by rememberSaveable { mutableStateOf(false) }
    var mappingApp by remember { mutableStateOf<String?>(null) }
    var dictionaryExpanded by rememberSaveable { mutableStateOf(false) }
    var dictionaryDraft by remember { mutableStateOf<String?>(null) }
    var dictionaryOriginal by remember { mutableStateOf<String?>(null) }
    var dictionaryError by remember { mutableStateOf<String?>(null) }
    val appNames = remember { AppListCache.loadAppList(context).associate { it.packageName to it.appName } }
    fun appLabel(identifier: String): String = appNames[identifier] ?: when (identifier) {
        "*" -> "All apps"
        "com.openai.chatgpt" -> "ChatGPT"
        else -> identifier
    }
    fun appliesTo(rule: JSONObject): String {
        if (rule.optString("scope") == "category" && validVoiceCategory(rule.optString("categoryId")))
            return "Category: ${voiceCategoryLabel(rule.getString("categoryId"))}"
        val apps = rule.optJSONArray("apps") ?: return "Not assigned to an app"
        return readVoiceRuleMappings(apps.toString()).map { it.label(::appLabel) }
            .distinct().joinToString().ifBlank { "Not assigned to an app" }
    }
    suspend fun loadRules() {
        val entries = fetchRules?.invoke() ?: api.request("preferences", "GET").getJSONArray("rules").let { values ->
            (0 until values.length()).map { values.getJSONObject(it) }
        }
        rules = entries.distinctBy { it.getInt("id") }
            .sortedBy { it.getString("name").lowercase() }
        loaded = true
    }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try { block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = e.message ?: "Request failed. Please retry."
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { action { loadRules() } }
    SwitchPreference(prefs.voiceInput.usePreferences, title = "Use saved preferences",
        summary = "Apply your saved instructions and writing styles.")
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        budget?.warningText()?.let { Text("Last reported: $it") }
        Text("Writing rules control tone, style and punctuation. Say: Remember for this app: keep my wording.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (loaded) "Saved instructions (${rules.size})" else "Saved instructions", style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !busy, onClick = { action { loadRules() } }) { Text("Refresh") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loaded && rules.isEmpty()) Text("No saved instructions yet. Instructions you save by voice will appear here.")
        Text("${rules.size}/100 rules · ${rules.sumOf { utf8Bytes(it.getString("rule_text")) }}/64,000 UTF-8 bytes saved", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy, onClick = {
            message = null
            editingRule = JSONObject().put("id", -1).put("name", "").put("rule_text", "")
        }) { Text("Add writing rule / Extract from examples") }
        if (rules.isNotEmpty()) {
            OutlinedTextField(search, { search = it }, label = { Text("Find an instruction or app") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            val visibleRules = rules.filter {
                listOf(it.getString("name"), it.getString("rule_text"), appliesTo(it)).any { value -> value.contains(search.trim(), ignoreCase = true) }
            }
            if (visibleRules.isEmpty()) Text("No matching instructions.")
            visibleRules.forEach { rule ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(rule.getString("name").replace(Regex(" · [a-f0-9]{16}$"), ""), style = MaterialTheme.typography.titleSmall)
                        Text(rule.getString("rule_text"))
                        Text("Applies to: ${appliesTo(rule)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !busy, onClick = { editingRule = rule }) { Text("Edit") }
                            TextButton(enabled = !busy, onClick = { deletingRule = rule }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        TextButton(onClick = { dictionaryExpanded = !dictionaryExpanded }) {
            Text(if (dictionaryExpanded) "Hide dictionary" else "Dictionary on this device (${dictionary.size})")
        }
        if (dictionaryExpanded) {
            SwitchPreference(prefs.voiceInput.useDictionary, title = "Use my dictionary",
                summary = "Names and specialist words for voice recognition and AI spelling, in all apps on this device.")
            Text("New words affect the next recording. Saving keeps dictionary use as configured.")
            Text("${dictionary.size}/100 entries · ${dictionary.sumOf(::utf8Bytes)}/4,096 UTF-8 bytes (about 1,024 tokens max)", style = MaterialTheme.typography.bodySmall)
            if (dictionary.isEmpty()) Text("Add names, specialist terms, or words you often correct.")
            dictionary.forEach { word ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        dictionaryOriginal = word; dictionaryDraft = word; dictionaryError = null
                    }) { Text(word) }
                    TextButton(onClick = {
                        prefs.voiceInput.dictionary.set(Json.encodeToString(dictionary.filterNot { it == word }))
                    }) { Text("Delete") }
                }
            }
            TextButton(onClick = { dictionaryOriginal = null; dictionaryDraft = ""; dictionaryError = null }) { Text("Add word") }
        }
        HorizontalDivider()
        TextButton(onClick = { mappingExpanded = !mappingExpanded }) { Text(if (mappingExpanded) "Hide app categories" else "App categories (${categories.size} assigned)") }
        if (mappingExpanded) {
            Text("You choose which category each app belongs to. Category rules and dictionary words apply to every app you assign there.")
            VoiceAppPicker(mappingApp, appNames, { mappingApp = it })
            mappingApp?.let { app ->
                VoiceCategoryPicker(categories[app], { category ->
                    prefs.voiceInput.appCategories.set(Json.encodeToString(if (category == null) categories - app else categories + (app to category)))
                }, allowUnassigned = true)
            }
            categories.forEach { (app, category) ->
                TextButton(onClick = { mappingApp = app }) { Text("${appLabel(app)} · ${voiceCategoryLabel(category)}") }
            }
        }
        HorizontalDivider()
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide writing styles" else "Writing styles on this device (${profiles.size})") }
        if (expanded) {
            Text("Optional tone and formatting settings. A style for a specific app takes priority over a category or general style.")
            profiles.forEach { profile ->
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { editing = profile }, modifier = Modifier.weight(1f)) {
                        Text("${profile.name} · ${when (profile.kind) {
                            "app" -> appLabel(profile.packages.singleOrNull().orEmpty())
                            "category" -> if (validVoiceCategory(profile.categoryId)) "Category: ${voiceCategoryLabel(profile.categoryId!!)}"
                                else "Legacy category ${profile.categoryId ?: profile.name}: ${profile.packages.joinToString { appLabel(it) }}"
                            else -> "All apps"
                        }}")
                    }
                    TextButton(onClick = { prefs.voiceInput.preferenceProfiles.set(removeVoiceProfile(stored, profile.id)) }) { Text("Delete") }
                }
            }
            TextButton(onClick = { editing = VoicePreferenceProfile(UUID.randomUUID().toString(), "", "") }) { Text("Add writing style") }
        }
        SwitchPreference(prefs.voiceInput.suggestPreferences, title = "Offer to remember corrections",
            summary = "Review each suggestion before anything is saved.")
    }
    dictionaryDraft?.let { draft ->
        AlertDialog(onDismissRequest = { dictionaryDraft = null }, title = { Text("Dictionary word") },
            text = { Column {
                OutlinedTextField(draft, { dictionaryDraft = it }, label = { Text("Exact spelling (${draft.length}/80)") }, singleLine = true)
                Text("All apps on this device. Affects your next recording.")
                dictionaryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = validDictionaryWord(draft.trim()), onClick = {
                try {
                    val kept = dictionary.filterNot { it == dictionaryOriginal }
                    val updated = app.whisperme.keyboard.ime.voice.addVoiceDictionaryWord(Json.encodeToString(kept), draft)
                    prefs.voiceInput.dictionary.set(updated)
                    dictionaryDraft = null
                } catch (e: IllegalArgumentException) { dictionaryError = e.message }
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dictionaryDraft = null }) { Text("Cancel") } })
    }
    editing?.let { original ->
        key(original.id) {
            var name by rememberSaveable { mutableStateOf(original.name) }
            var instructions by rememberSaveable { mutableStateOf(original.instructions) }
            var kind by rememberSaveable { mutableStateOf(original.kind) }
            var packages by rememberSaveable { mutableStateOf(original.packages.joinToString(", ")) }
            var categoryId by rememberSaveable { mutableStateOf(original.categoryId) }
            var samples by remember { mutableStateOf(listOf("")) }
            var extraction by remember { mutableStateOf(false) }
            var extractionMessage by remember { mutableStateOf<String?>(null) }
            val draft = original.copy(name = name, instructions = instructions, kind = kind,
                packages = packages.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                categoryId = if (kind == "category") categoryId else null)
            AlertDialog(onDismissRequest = { if (!extraction) editing = null }, title = { Text("Writing style on this device") },
                text = {
                    androidx.compose.foundation.rememberScrollState().let { scroll ->
                        Column(Modifier.heightIn(max = 500.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(name, { name = it }, label = { Text("Name") })
                            Row { listOf("all", "app", "category").forEach { value ->
                                TextButton(enabled = !extraction, onClick = { kind = value }) { Text(if (kind == value) "✓ $value" else when (value) { "all" -> "All apps"; "app" -> "App"; else -> "Category" }) }
                            } }
                            if (kind == "app") VoiceAppPicker(draft.packages.singleOrNull(), appNames, { packages = it }, !extraction)
                            if (kind == "category") {
                                if (!validVoiceCategory(categoryId) && original.kind == "category") {
                                    Text("Legacy category: ${original.categoryId ?: original.name}. Your existing app assignments remain in use. Choose a category to migrate.")
                                    OutlinedTextField(packages, { packages = it }, label = { Text("Existing app assignments") }, enabled = !extraction)
                                }
                                VoiceCategoryPicker(categoryId.takeIf(::validVoiceCategory), { categoryId = it }, enabled = !extraction)
                            }
                            OutlinedTextField(instructions, { instructions = it }, label = { Text("Instructions (${instructions.length}/2000)") }, enabled = !extraction)
                            Text("${utf8Bytes(instructions)}/8,000 UTF-8 bytes. Saving keeps preference use as configured.")
                            samples.forEachIndexed { index, sample ->
                                OutlinedTextField(sample, { updated -> samples = samples.toMutableList().also { it[index] = updated } },
                                    label = { Text("Writing example ${index + 1}") }, enabled = !extraction)
                                if (samples.size > 1) TextButton(enabled = !extraction, onClick = { samples = samples.filterIndexed { i, _ -> i != index } }) { Text("Remove example ${index + 1}") }
                            }
                            Text("${samples.sumOf { it.length }}/12,000 characters total · Up to 5 examples")
                            TextButton(enabled = !extraction && samples.size < 5, onClick = { samples = samples + "" }) { Text("Add example") }
                            Text("Extract style sends these examples to WhisperMe. Review and edit the returned draft before saving.")
                            TextButton(enabled = !extraction && samples.all { it.isNotBlank() } && samples.sumOf { it.length } <= 12000, onClick = {
                                extraction = true
                                scope.launch {
                                    try {
                                        VoicePreferenceLimits.requireSamples(samples)
                                        val app = draft.packages.singleOrNull()
                                        val result = api.request("style-profile", "POST", JSONObject()
                                            .put("samples", org.json.JSONArray(samples)).put("appIdentifier", app)
                                            .put("appCategory", categories[app]).put("categoryId", categoryId.takeIf(::validVoiceCategory))
                                            .put("llmProvider", prefs.voiceInput.llmProvider.get().wireValue)
                                            .put("llmModel", prefs.voiceInput.llmProvider.get().compatibleModel(prefs.voiceInput.llmModel.get())))
                                        check(result.optBoolean("success", true)) { "Style extraction failed" }
                                        val extracted = readVoiceStyleDraft(result.toString())
                                        val fallback = when (kind) {
                                            "app" -> VoicePreferenceScope("app", app)
                                            "category" -> VoicePreferenceScope("category", categoryId = categoryId)
                                            else -> VoicePreferenceScope()
                                        }
                                        val suggested = extracted.resolvedScope(app, fallback)
                                        instructions = extracted.instructions
                                        extracted.name?.let { name = it }
                                        if (extracted.scope != null) {
                                            kind = suggested.scope
                                            categoryId = suggested.categoryId
                                            if (kind == "app") packages = suggested.appIdentifier.orEmpty()
                                        }
                                        extractionMessage = listOfNotNull("Review this draft before saving.", extracted.scopeReason).joinToString(" ")
                                    } catch (e: Exception) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        extractionMessage = e.message
                                    } finally { extraction = false }
                                }
                            }) { Text(if (extraction) "Extracting…" else "Extract style") }
                            extractionMessage?.let { Text(it) }
                            budget?.warningText()?.let { Text(it) }
                        }
                    }
                },
                confirmButton = { TextButton(enabled = draft.valid() && name.length <= 180 && !extraction, onClick = {
                    try {
                        // Updating one style never deletes another profile, including legacy categories.
                        val updated = profiles.filterNot { it.id == draft.id } + draft
                        VoicePreferenceLimits.requireSaved(updated.map { it.instructions } + rules.map { it.getString("rule_text") })
                        validateVoiceRuleBudgets(rules, updated, categories)
                        prefs.voiceInput.preferenceProfiles.set(updateVoiceProfile(stored, draft))
                        editing = null
                    } catch (e: IllegalArgumentException) { extractionMessage = e.message }
                }) { Text("Save profile") } },
                dismissButton = { TextButton(enabled = !extraction, onClick = { editing = null }) { Text("Cancel") } })
        }
    }
    editingRule?.let { rule ->
        val newRule = rule.optInt("id", -1) < 0
        var name by remember(rule) { mutableStateOf(rule.getString("name")) }
        var text by remember(rule) { mutableStateOf(rule.getString("rule_text")) }
        var selectedScope by remember(rule) { mutableStateOf(VoicePreferenceScope()) }
        var sampleApp by remember(rule) { mutableStateOf<String?>(null) }
        var samples by remember(rule) { mutableStateOf(listOf("")) }
        var reason by remember(rule) { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) editingRule = null }, title = { Text(if (newRule) "New writing rule" else "Edit writing rule") },
            text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, enabled = !busy)
                OutlinedTextField(text, { text = it }, label = { Text("Writing / punctuation rule (${text.length}/2000)") }, enabled = !busy)
                Text("${utf8Bytes(text)}/8,000 UTF-8 bytes · Active rules up to 16,000 bytes (about 4,000 tokens)", style = MaterialTheme.typography.bodySmall)
                if (newRule) {
                    VoiceScopePicker(selectedScope, { selectedScope = it; if (it.scope == "app") sampleApp = it.appIdentifier }, appNames, enabled = !busy)
                    reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                    Text("Learn style from examples", style = MaterialTheme.typography.titleSmall)
                    Text("Optional: choose the app these examples came from.")
                    VoiceAppPicker(sampleApp, appNames, { sampleApp = it }, !busy)
                    sampleApp?.let { app -> Text("${appLabel(app)} · ${categories[app]?.let(::voiceCategoryLabel) ?: "Unassigned category"}") }
                    samples.forEachIndexed { index, sample ->
                        OutlinedTextField(sample, { updated -> samples = samples.toMutableList().also { it[index] = updated } },
                            label = { Text("Example ${index + 1}") }, enabled = !busy)
                        if (samples.size > 1) TextButton(enabled = !busy, onClick = { samples = samples.filterIndexed { i, _ -> i != index } }) { Text("Remove example ${index + 1}") }
                    }
                    Text("${samples.sumOf { it.length }}/12,000 characters total · Up to 5 examples")
                    TextButton(enabled = !busy && samples.size < 5, onClick = { samples = samples + "" }) { Text("Add example") }
                    Text("Extraction sends your examples to WhisperMe. Review the editable draft and scope, then Save.")
                    TextButton(enabled = !busy && samples.all { it.isNotBlank() } && samples.sumOf { it.length } <= 12000, onClick = { action {
                        VoicePreferenceLimits.requireSamples(samples)
                        val requestedApp = sampleApp ?: selectedScope.appIdentifier
                        val result = api.request("style-profile", "POST", JSONObject()
                            .put("samples", org.json.JSONArray(samples)).put("appIdentifier", requestedApp)
                            .put("appCategory", categories[requestedApp]).put("categoryId", selectedScope.categoryId)
                            .put("llmProvider", prefs.voiceInput.llmProvider.get().wireValue)
                            .put("llmModel", prefs.voiceInput.llmProvider.get().compatibleModel(prefs.voiceInput.llmModel.get())))
                        check(result.optBoolean("success", true)) { result.optString("error", "Style extraction failed") }
                        val extracted = readVoiceStyleDraft(result.toString())
                        val returnedScope = extracted.resolvedScope(requestedApp, selectedScope)
                        text = extracted.instructions
                        extracted.name?.let { name = it }
                        selectedScope = returnedScope
                        reason = extracted.scopeReason
                        message = "Draft ready to review. Nothing has been saved."
                    } }) { Text(if (busy) "Extracting…" else "Extract style and punctuation") }
                } else Text("Applies to: ${appliesTo(rule)}")
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy && name.isNotBlank() && name.length <= 180 && VoicePreferenceLimits.validRule(text) && (!newRule || selectedScope.valid()), onClick = { action {
                if (newRule) api.request("preferences", "POST", voiceRuleBody(text, selectedScope, name))
                else api.request("preferences", "PUT", JSONObject().put("id", rule.getInt("id")).put("name", name).put("rule_text", text))
                editingRule = null
                loadRules()
            } }) { Text("Save") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { editingRule = null }) { Text("Cancel") } })
    }
    deletingRule?.let { rule ->
        AlertDialog(onDismissRequest = { if (!busy) deletingRule = null }, title = { Text("Delete saved instruction?") },
            text = { Text(rule.getString("name")) },
            confirmButton = { TextButton(enabled = !busy, onClick = { action {
                api.request("preferences", "DELETE", JSONObject().put("id", rule.getInt("id")))
                deletingRule = null
                loadRules()
            } }) { Text("Delete") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletingRule = null }) { Text("Cancel") } })
    }
}
