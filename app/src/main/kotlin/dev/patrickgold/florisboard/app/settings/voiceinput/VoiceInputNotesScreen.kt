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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.compose.FlorisIconButton

@Composable
fun VoiceInputNotesScreen() = FlorisScreen {
    title = "Rules"
    navigationIconVisible = true

    val navController = LocalNavController.current
    val context = LocalContext.current
    
    var tagRules by remember { mutableStateOf<List<TagRule>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    val apiService = remember { TagRulesApiService(context) }
    
    // Refresh key that changes when we need to reload data
    var refreshKey by remember { mutableStateOf(0) }
    
    // Function to refresh the rules list
    fun refreshRules() {
        println("DEBUG: Refreshing tag rules")
        refreshKey++ // This will trigger LaunchedEffect to run again
    }
    
    actions {
        FlorisIconButton(
            onClick = { refreshRules() },
            icon = Icons.Default.Refresh
        )
    }
    
    LaunchedEffect(refreshKey) {
        println("DEBUG: Starting to fetch tag rules (refresh key: $refreshKey)")
        isLoading = true
        error = null
        
        // First test the API connection
        apiService.testApiConnection().collect { testResult ->
            testResult.fold(
                onSuccess = { message ->
                    println("DEBUG: API test successful: $message")
                    // Now try to fetch the actual rules
                    apiService.getTagRules().collect { result ->
                        isLoading = false
                        result.fold(
                            onSuccess = { rules ->
                                println("DEBUG: Successfully fetched ${rules.size} rules")
                                tagRules = rules
                                error = null
                            },
                            onFailure = { exception ->
                                println("DEBUG: Failed to fetch rules: ${exception.message}")
                                exception.printStackTrace()
                                error = exception.message ?: "Unknown error"
                            }
                        )
                    }
                },
                onFailure = { exception ->
                    println("DEBUG: API test failed: ${exception.message}")
                    isLoading = false
                    error = "API connection failed: ${exception.message}"
                }
            )
        }
    }

    content {
        if (isLoading) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Loading rules...")
            }
        } else if (error != null) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Error: $error",
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { refreshRules() }
                ) {
                    Text("Retry")
                }
            }
        } else if (tagRules.isEmpty()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No rules yet. Tap the + button to add your first rule.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                tagRules.forEach { rule ->
                    TagRuleCard(
                        rule = rule,
                        onEdit = { navController.navigate(Routes.Settings.VoiceInputRuleEdit(rule.id.toString())) },
                        onDelete = {
                            // TODO: Implement delete functionality
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        
        // Add button
        if (!isLoading && error == null) {
            FloatingActionButton(
                onClick = { navController.navigate(Routes.Settings.VoiceInputRuleEdit(null)) },
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add rule")
            }
        }
    }
}

@Composable
private fun TagRuleCard(
    rule: TagRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = rule.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = truncateText(rule.rule_text, 3),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit rule",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun truncateText(text: String, maxLines: Int): String {
    val lines = text.split("\n")
    return if (lines.size > maxLines) {
        lines.take(maxLines).joinToString("\n") + "..."
    } else {
        text
    }
} 