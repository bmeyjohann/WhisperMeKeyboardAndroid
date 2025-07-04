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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patrickgold.compose.tooltip.PlainTooltip
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.ime.keyboard.ComputingEvaluator
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import org.florisboard.lib.snygg.SnyggSelector
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon

enum class VoiceButtonState {
    IDLE,
    RECORDING,
    PROCESSING
}

@Composable
fun VoiceActionButton(
    action: QuickAction,
    evaluator: ComputingEvaluator,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val inputFeedbackController = FlorisImeService.inputFeedbackController()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isEnabled = evaluator.evaluateEnabled(action.keyData())
    
    // Voice states
    val isRecording by keyboardManager.isVoiceRecording.collectAsState()
    val isProcessing by keyboardManager.isVoiceProcessing.collectAsState()
    
    val voiceState = when {
        isProcessing -> VoiceButtonState.PROCESSING
        isRecording -> VoiceButtonState.RECORDING
        else -> VoiceButtonState.IDLE
    }
    
    val elementName = FlorisImeUi.SmartbarActionKey.elementName
    val attributes = mapOf(FlorisImeUi.Attr.Code to action.keyData().code)
    
    // Determine selector based on state
    val selector = when {
        isPressed -> SnyggSelector.PRESSED
        !isEnabled -> SnyggSelector.DISABLED
        voiceState == VoiceButtonState.RECORDING -> SnyggSelector.FOCUS
        else -> null
    }

    // Animation for recording pulsing effect
    val infiniteTransition = rememberInfiniteTransition(label = "voice_recording_pulse")
    val recordingPulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording_pulse"
    )

    // Need to manually cancel an action if this composable suddenly leaves the composition to prevent the key from
    // being stuck in the pressed state
    DisposableEffect(action, isEnabled) {
        onDispose {
            if (action is QuickAction.InsertKey) {
                action.onPointerCancel(context)
            }
        }
    }

    PlainTooltip(action.computeTooltip(evaluator)) {
        SnyggBox(
            elementName = elementName,
            attributes = attributes,
            selector = selector,
            modifier = modifier,
            clickAndSemanticsModifier = Modifier
                .aspectRatio(1f)
                .indication(interactionSource, LocalIndication.current)
                .pointerInput(action, isEnabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (isEnabled) {
                            val press = PressInteraction.Press(down.position)
                            inputFeedbackController?.keyPress(TextKeyData.UNSPECIFIED)
                            interactionSource.tryEmit(press)
                            android.util.Log.d("VoiceActionButton", "Calling action.onPointerDown")
                            action.onPointerDown(context)
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                interactionSource.tryEmit(PressInteraction.Release(press))
                                android.util.Log.d("VoiceActionButton", "Calling action.onPointerUp")
                                action.onPointerUp(context)
                            } else {
                                interactionSource.tryEmit(PressInteraction.Cancel(press))
                                android.util.Log.d("VoiceActionButton", "Calling action.onPointerCancel")
                                action.onPointerCancel(context)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (voiceState) {
                    VoiceButtonState.IDLE -> {
                        SnyggBox(
                            elementName = "$elementName-icon",
                            attributes = attributes,
                            selector = selector,
                        ) {
                            SnyggIcon(
                                imageVector = Icons.Default.Mic,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    
                    VoiceButtonState.RECORDING -> {
                        SnyggBox(
                            elementName = "$elementName-icon",
                            attributes = attributes,
                            selector = selector,
                        ) {
                            SnyggIcon(
                                imageVector = Icons.Default.Stop,
                                modifier = Modifier
                                    .size(24.dp)
                                    .scale(recordingPulse)
                            )
                        }
                    }
                    
                    VoiceButtonState.PROCESSING -> {
                        SnyggBox(
                            elementName = "$elementName-icon",
                            attributes = attributes,
                            selector = selector,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
    }
} 