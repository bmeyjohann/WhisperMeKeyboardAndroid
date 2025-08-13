/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.smartbar.Smartbar
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggText

@Composable
fun VoiceRecordingLayout(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    
    // Voice states
    val isRecording by keyboardManager.isVoiceRecording.collectAsState()
    val isProcessing by keyboardManager.isVoiceProcessing.collectAsState()
    val isRetryable by keyboardManager.isVoiceRetryable.collectAsState()
    val errorMessage by keyboardManager.voiceErrorMessage.collectAsState()
    val isAuthError by keyboardManager.isVoiceAuthError.collectAsState()
    
    // Animation for recording pulsing effect
    val infiniteTransition = rememberInfiniteTransition(label = "voice_recording_pulse")
    val recordingPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording_pulse"
    )
    
    // Clean up voice state when composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            // This will be called when the composable is removed from composition
            // but we rely on the keyboard lifecycle events for cleanup instead
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.imeUiHeight()),
    ) {
        // Keep the smartbar at the top
        Smartbar()
        
        // Main voice recording/processing area
        SnyggBox(
            elementName = FlorisImeUi.VoiceInputLayout.elementName,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isRecording) {
                            Modifier.clickable {
                                // Stop recording when clicking anywhere in the content area
                                keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.VOICE_STOP_RECORDING)
                            }
                        } else if (isRetryable) {
                            Modifier.clickable {
                                // Cancel retry when clicking anywhere in the content area
                                keyboardManager.cancelVoiceProcessing()
                            }
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when {
                        isRetryable -> {
                            // Retry state - show error message and retry/cancel options
                            SnyggText(
                                text = errorMessage ?: "Voice processing failed",
                                modifier = Modifier.padding(bottom = 32.dp),
                            )
                            
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(32.dp),
                                modifier = Modifier.padding(vertical = 16.dp)
                            ) {
                                if (isAuthError) {
                                    // Login button for authentication errors
                                    SnyggIconButton(
                                        elementName = FlorisImeUi.VoiceInputStopButton.elementName,
                                        onClick = {
                                            keyboardManager.openLoginScreen()
                                        },
                                        modifier = Modifier.size(100.dp),
                                    ) {
                                        SnyggIcon(
                                            imageVector = Icons.Default.Login,
                                            modifier = Modifier.size(50.dp),
                                        )
                                    }
                                } else {
                                    // Retry button for other errors
                                    SnyggIconButton(
                                        elementName = FlorisImeUi.VoiceInputStopButton.elementName,
                                        onClick = {
                                            keyboardManager.retryVoiceProcessing()
                                        },
                                        modifier = Modifier.size(100.dp),
                                    ) {
                                        SnyggIcon(
                                            imageVector = Icons.Default.Refresh,
                                            modifier = Modifier.size(50.dp),
                                        )
                                    }
                                }
                                
                                // Cancel button (always shown)
                                SnyggIconButton(
                                    elementName = FlorisImeUi.VoiceInputStopButton.elementName,
                                    onClick = {
                                        keyboardManager.cancelVoiceProcessing()
                                    },
                                    modifier = Modifier.size(100.dp),
                                ) {
                                    SnyggIcon(
                                        imageVector = Icons.Default.Cancel,
                                        modifier = Modifier.size(50.dp),
                                    )
                                }
                            }
                            
                            SnyggText(
                                text = if (isAuthError) {
                                    "Tap login to authenticate or cancel to return"
                                } else {
                                    "Tap retry to try again or cancel to return"
                                },
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                        
                        isProcessing -> {
                            // Processing state - show loading indicator
                            SnyggText(
                                text = "Processing voice...",
                                modifier = Modifier.padding(bottom = 32.dp),
                            )
                            
                            CircularProgressIndicator(
                                modifier = Modifier.size(120.dp),
                                strokeWidth = 8.dp
                            )
                            
                            SnyggText(
                                text = "Please wait while we process your audio",
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                        
                        isRecording -> {
                            // Recording state - show big stop button
                            SnyggText(
                                text = "Recording...",
                                modifier = Modifier.padding(bottom = 32.dp),
                            )
                            
                            SnyggIconButton(
                                elementName = FlorisImeUi.VoiceInputStopButton.elementName,
                                onClick = {
                                    // Stop recording
                                    keyboardManager.inputEventDispatcher.sendDownUp(TextKeyData.VOICE_STOP_RECORDING)
                                },
                                modifier = Modifier
                                    .size(120.dp)
                                    .scale(recordingPulse),
                            ) {
                                SnyggIcon(
                                    imageVector = Icons.Default.Stop,
                                    modifier = Modifier.size(60.dp),
                                )
                            }
                            
                            SnyggText(
                                text = "Tap the stop button to finish recording",
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
} 