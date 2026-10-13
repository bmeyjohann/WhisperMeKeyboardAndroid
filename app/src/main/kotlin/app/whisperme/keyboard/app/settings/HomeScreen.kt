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

package app.whisperme.keyboard.app.settings

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.whisperme.keyboard.app.LocalNavController
import app.whisperme.keyboard.app.Routes
import app.whisperme.keyboard.lib.compose.FlorisScreen
import app.whisperme.keyboard.lib.util.InputMethodUtils
import dev.patrickgold.jetpref.datastore.ui.Preference

@Composable
fun KeyboardSettingsScreen() = FlorisScreen {
    title = "Keyboard settings"
    previewFieldVisible = false
    val nav = LocalNavController.current
    content {
        KeyboardSetupStatus()
        Preference(title = "Languages and layouts", onClick = { nav.navigate(Routes.Settings.Localization) })
        Preference(title = "Keyboard appearance", onClick = { nav.navigate(Routes.Settings.Theme) })
        Preference(title = "Size and key feedback", onClick = { nav.navigate(Routes.Settings.Keyboard) })
        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.padding(8.dp)) {
            Text(if (advanced) "Hide advanced settings" else "Advanced settings")
        }
        if (advanced) {
            listOf("Toolbar" to Routes.Settings.Smartbar, "Typing" to Routes.Settings.Typing,
                "Gestures" to Routes.Settings.Gestures, "Clipboard" to Routes.Settings.Clipboard,
                "Emoji and media" to Routes.Settings.Media, "Extensions" to Routes.Ext.Home,
                "Backup and other settings" to Routes.Settings.Other, "About and licenses" to Routes.Settings.About
            ).forEach { (label, route) -> Preference(title = label, onClick = { nav.navigate(route) }) }
        }
    }
}

@Composable
internal fun KeyboardSetupStatus() {
    val context = LocalContext.current
    val enabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val selected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
    if (!enabled) Preference(title = "Enable WhisperMe keyboard", summary = "One-time setup to start dictating.",
        onClick = { InputMethodUtils.showImeEnablerActivity(context) })
    else if (!selected) Preference(title = "Select WhisperMe keyboard", summary = "Choose WhisperMe to use the microphone in any app.",
        onClick = { InputMethodUtils.showImePicker(context) })
}

@Composable
internal fun VoiceAccountSettings() {
    val nav = LocalNavController.current
    Preference(title = "Account and usage", summary = "Sign-in, balance, reset date and voice setup.",
        onClick = { nav.navigate(Routes.Settings.Account) })
    Preference(title = "Help and feedback", summary = "Report a bug or share an idea without attaching your transcripts.",
        onClick = { nav.navigate(Routes.Settings.Feedback) })
}
