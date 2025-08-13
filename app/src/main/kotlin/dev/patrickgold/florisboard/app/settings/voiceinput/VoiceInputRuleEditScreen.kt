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

package dev.patrickgold.florisboard.app.settings.voiceinput

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisIconButton
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import dev.patrickgold.florisboard.app.settings.voiceinput.AppListCache
import dev.patrickgold.florisboard.app.settings.voiceinput.AppAssociation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.width

private const val MAX_CHARACTERS = 500
private const val WARNING_THRESHOLD = 450

@Composable
fun VoiceInputRuleEditScreen(ruleId: String?) = FlorisScreen {
    title = if (ruleId == null) "Add Rule" else "Edit Rule"
    navigationIconVisible = true
    
    val navController = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var tagName by remember { mutableStateOf("") }
    var ruleText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSuccess by remember { mutableStateOf(false) }
    var showAppDialog by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var selectedApps by remember { mutableStateOf<List<AppAssociation>>(emptyList()) }

    val apiService = remember { TagRulesApiService(context) }
    val isEditing = ruleId != null
    
    // Calculate character limits
    val characterCount = ruleText.length
    val isNearLimit = characterCount >= WARNING_THRESHOLD
    val isOverLimit = characterCount > MAX_CHARACTERS
    
    // Load existing rule if editing
    LaunchedEffect(ruleId) {
        if (isEditing && ruleId != null) {
            isLoading = true
            // Fetch rule from API
            apiService.getTagRules().collect { result ->
                result.fold(
                    onSuccess = { rules ->
                        val rule = rules.find { it.id.toString() == ruleId }
                        if (rule != null) {
                            tagName = rule.name
                            ruleText = rule.rule_text
                            selectedApps = rule.apps ?: emptyList()
                        }
                        isLoading = false
                    },
                    onFailure = { exception ->
                        error = exception.message
                        isLoading = false
                    }
                )
            }
        }
    }

    navigationIcon {
        FlorisIconButton(
            onClick = { navController.popBackStack() },
            icon = Icons.Default.ArrowBack
        )
    }
    
    actions {
        FlorisIconButton(
            onClick = {
                if (tagName.isNotBlank() && ruleText.isNotBlank() && !isOverLimit) {
                    // Save the rule
                    scope.launch {
                        isLoading = true
                        error = null
                        
                        try {
                            println("DEBUG: Saving rule - Name: '$tagName', Content: '${ruleText.take(50)}...'")
                            
                            val request = TagRuleRequest(
                                name = tagName.trim(),
                                rule_text = ruleText.trim(),
                                apps = selectedApps
                            )
                            
                            if (isEditing && ruleId != null) {
                                // Update existing rule
                                println("DEBUG: Updating existing rule with ID: $ruleId")
                                apiService.updateTagRule(ruleId.toInt(), request).collect { result ->
                                    result.fold(
                                        onSuccess = { rule ->
                                            println("DEBUG: Rule updated successfully: ${rule.name}")
                                            showSuccess = true
                                            // Navigate back after a short delay to show success
                                            delay(1000)
                                            navController.popBackStack()
                                        },
                                        onFailure = { exception ->
                                            println("DEBUG: Failed to update rule: ${exception.message}")
                                            error = "Failed to update rule: ${exception.message}"
                                            isLoading = false
                                        }
                                    )
                                }
                            } else {
                                // Create new rule
                                println("DEBUG: Creating new rule")
                                apiService.createTagRule(request).collect { result ->
                                    result.fold(
                                        onSuccess = { rule ->
                                            println("DEBUG: Rule created successfully: ${rule.name}")
                                            showSuccess = true
                                            // Navigate back after a short delay to show success
                                            delay(1000)
                                            navController.popBackStack()
                                        },
                                        onFailure = { exception ->
                                            println("DEBUG: Failed to create rule: ${exception.message}")
                                            error = "Failed to create rule: ${exception.message}"
                                            isLoading = false
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            println("DEBUG: Exception during save: ${e.message}")
                            e.printStackTrace()
                            error = "Unexpected error: ${e.message}"
                            isLoading = false
                        }
                    }
                } else {
                    // Show validation error
                    error = when {
                        tagName.isBlank() -> "Tag name is required"
                        ruleText.isBlank() -> "Rule content is required"
                        isOverLimit -> "Rule content is too long (max $MAX_CHARACTERS characters)"
                        else -> "Please fill in all required fields"
                    }
                }
            },
            icon = Icons.Default.Check
        )
    }

    content {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (isLoading) {
                Text("Loading...")
                return@content
            }
            
            if (error != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = error!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            if (showSuccess) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = if (isEditing) "Rule updated successfully!" else "Rule created successfully!",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Tag Name Input
            OutlinedTextField(
                value = tagName,
                onValueChange = { 
                    if (it.length <= 50) { // Reasonable limit for tag names
                        tagName = it 
                    }
                },
                label = { Text("Tag Name") },
                placeholder = { Text("e.g., fast messaging, email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = tagName.isBlank()
            )
            
            if (tagName.isBlank()) {
                Text(
                    text = "Tag name is required",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Rule Text Input
            Column {
                Text(
                    text = "Rule Content",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = ruleText,
                    onValueChange = { 
                        if (it.length <= MAX_CHARACTERS) {
                            ruleText = it 
                        }
                    },
                    label = { Text("Rule content") },
                    placeholder = { Text("Enter your rule content here...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    isError = isOverLimit
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "$characterCount/$MAX_CHARACTERS",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            isOverLimit -> MaterialTheme.colorScheme.error
                            isNearLimit -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                
                if (isOverLimit) {
                    Text(
                        text = "Rule content is too long",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                } else if (isNearLimit) {
                    Text(
                        text = "Approaching character limit",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Save Button
            Button(
                onClick = {
                    if (tagName.isNotBlank() && ruleText.isNotBlank() && !isOverLimit) {
                        // Save the rule
                        scope.launch {
                            isLoading = true
                            error = null
                            
                            try {
                                println("DEBUG: Saving rule - Name: '$tagName', Content: '${ruleText.take(50)}...'")
                                
                                val request = TagRuleRequest(
                                    name = tagName.trim(),
                                    rule_text = ruleText.trim(),
                                    apps = selectedApps
                                )
                                
                                if (isEditing && ruleId != null) {
                                    // Update existing rule
                                    println("DEBUG: Updating existing rule with ID: $ruleId")
                                    apiService.updateTagRule(ruleId.toInt(), request).collect { result ->
                                        result.fold(
                                            onSuccess = { rule ->
                                                println("DEBUG: Rule updated successfully: ${rule.name}")
                                                showSuccess = true
                                                // Navigate back after a short delay to show success
                                                delay(1000)
                                                navController.popBackStack()
                                            },
                                            onFailure = { exception ->
                                                println("DEBUG: Failed to update rule: ${exception.message}")
                                                error = "Failed to update rule: ${exception.message}"
                                                isLoading = false
                                            }
                                        )
                                    }
                                } else {
                                    // Create new rule
                                    println("DEBUG: Creating new rule")
                                    apiService.createTagRule(request).collect { result ->
                                        result.fold(
                                            onSuccess = { rule ->
                                                println("DEBUG: Rule created successfully: ${rule.name}")
                                                showSuccess = true
                                                // Navigate back after a short delay to show success
                                                delay(1000)
                                                navController.popBackStack()
                                            },
                                            onFailure = { exception ->
                                                println("DEBUG: Failed to create rule: ${exception.message}")
                                                error = "Failed to create rule: ${exception.message}"
                                                isLoading = false
                                            }
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                println("DEBUG: Exception during save: ${e.message}")
                                e.printStackTrace()
                                error = "Unexpected error: ${e.message}"
                                isLoading = false
                            }
                        }
                    } else {
                        // Show validation error
                        error = when {
                            tagName.isBlank() -> "Tag name is required"
                            ruleText.isBlank() -> "Rule content is required"
                            isOverLimit -> "Rule content is too long (max $MAX_CHARACTERS characters)"
                            else -> "Please fill in all required fields"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = tagName.isNotBlank() && ruleText.isNotBlank() && !isOverLimit && !isLoading
            ) {
                if (isLoading) {
                    Text("Saving...")
                } else {
                    Text(if (isEditing) "Update Rule" else "Create Rule")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Character limit info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Character Limits",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Maximum 500 characters per rule\n" +
                               "• Warning shown at 450 characters\n" +
                               "• Tag names limited to 50 characters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    val allApps = remember { AppListCache.loadAppList(context) }

    // App selection UI
    if (showAppDialog) {
        AlertDialog(
            onDismissRequest = { showAppDialog = false },
            title = { Text("Select Apps") },
            text = {
                Column {
                    TextField(
                        value = appSearch,
                        onValueChange = { appSearch = it },
                        label = { Text("Search apps") },
                        singleLine = true
                    )
                    val filtered = allApps.filter { it.appName.contains(appSearch, ignoreCase = true) }
                    filtered.forEach { app ->
                        val checked = selectedApps.any { it.app_name == app.appName }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedApps = if (isChecked) {
                                        selectedApps + AppAssociation(app.appName, "android")
                                    } else {
                                        selectedApps.filter { it.app_name != app.appName }
                                    }
                                }
                            )
                            Text(app.appName)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showAppDialog = false }) { Text("OK") }
            }
        )
    }

    // Button to open app selection dialog
    Button(onClick = { showAppDialog = true }) { Text("Select Apps") }

    // Show selected apps as chips
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        selectedApps.forEach { app ->
            AssistChip(onClick = {}, label = { Text(app.app_name) })
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
} 