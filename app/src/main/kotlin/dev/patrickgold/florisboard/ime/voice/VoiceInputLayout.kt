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

package dev.patrickgold.florisboard.ime.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.smartbar.Smartbar
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggText

@Composable
fun VoiceInputLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val state by keyboardManager.activeState.collectAsState()

    var isRecording by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        Smartbar()
        SnyggBox(
            elementName = FlorisImeUi.VoiceInputLayout.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Header with back button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SnyggIconButton(
                        elementName = FlorisImeUi.VoiceInputBackButton.elementName,
                        onClick = {
                            keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.IME_UI_MODE_TEXT)
                        },
                    ) {
                        SnyggIcon(
                            imageVector = Icons.Default.ArrowBack,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    SnyggText(
                        text = "Voice Input",
                    )

                    // Spacer to balance the layout
                    Spacer(modifier = Modifier.width(48.dp))
                }

                // Main recording area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Recording status
                        SnyggText(
                            text = if (isRecording) "Recording..." else "Tap to start recording",
                            modifier = Modifier.padding(bottom = 32.dp),
                        )

                        // Record/Stop button
                        SnyggIconButton(
                            elementName = if (isRecording)
                                FlorisImeUi.VoiceInputStopButton.elementName
                            else
                                FlorisImeUi.VoiceInputRecordButton.elementName,
                            onClick = {
                                if (isRecording) {
                                    // Stop recording
                                    keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.VOICE_STOP_RECORDING)
                                    isRecording = false
                                } else {
                                    // Start recording
                                    keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.VOICE_START_RECORDING)
                                    isRecording = true
                                }
                            },
                            modifier = Modifier.size(72.dp),
                        ) {
                            SnyggIcon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                modifier = Modifier.size(48.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
