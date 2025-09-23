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
import androidx.compose.runtime.Composable
import app.whisperme.keyboard.app.LocalNavController
import app.whisperme.keyboard.app.Routes
import app.whisperme.keyboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference

@Composable
fun VoiceInputScreen() = FlorisScreen {
    title = "Voice Input"
    navigationIconVisible = true

    val navController = LocalNavController.current

    content {
        Preference(
            icon = Icons.Default.Note,
            title = "Rules",
            summary = "Manage voice input rules and instructions",
            onClick = { navController.navigate(Routes.Settings.VoiceInputNotes) },
        )

        // Future voice input categories can be added here
        // Preference(
        //     icon = Icons.Default.Settings,
        //     title = "Settings",
        //     summary = "Voice input configuration",
        //     onClick = { /* TODO */ },
        // )
    }
}
