package app.whisperme.keyboard.ime.voice

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.whisperme.keyboard.ime.keyboard.FlorisImeSizing
import app.whisperme.keyboard.ime.smartbar.Smartbar
import app.whisperme.keyboard.ime.text.keyboard.TextKeyData
import app.whisperme.keyboard.ime.theme.FlorisImeUi
import app.whisperme.keyboard.keyboardManager
import org.florisboard.lib.snygg.ui.SnyggBox

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun VoiceRecordingLayout(modifier: Modifier = Modifier, processingStatus: String? = null) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val manager by context.keyboardManager()
    val recording by manager.isVoiceRecording.collectAsState()
    val processing by manager.isVoiceProcessing.collectAsState()
    val retryable by manager.isVoiceRetryable.collectAsState()
    val error by manager.voiceErrorMessage.collectAsState()
    val authError by manager.isVoiceAuthError.collectAsState()
    val transcript by manager.voiceLiveTranscript.collectAsState()
    val result by manager.voiceFinalText.collectAsState()
    val assistant by manager.voiceAssistantMessage.collectAsState()
    val connectionStatus by manager.voiceConnectionStatus.collectAsState()
    val retryStatus by manager.voiceRetryStatus.collectAsState()
    val budgetStatus by manager.voiceBudgetStatus.collectAsState()
    val liveProcessingStatus by manager.voiceProcessingStatus.collectAsState()
    val subtitle = remember(transcript) { voiceSubtitle(transcript) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(recording, processing) { if (recording || processing) expanded = false }

    Column(modifier.fillMaxWidth().height(FlorisImeSizing.imeUiHeight())) {
        Smartbar()
        SnyggBox(elementName = FlorisImeUi.VoiceInputLayout.elementName,
            modifier = Modifier.fillMaxWidth().weight(1f).clickable(
                enabled = recording && !processing,
                onClickLabel = "Finish recording and insert",
            ) {
                manager.inputEventDispatcher.sendDownUp(TextKeyData.VOICE_STOP_RECORDING)
            }) {
            run {
                val foreground = LocalContentColor.current
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedVisibility(visible = assistant.isNotBlank() && assistant != error,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)) {
                            Text(assistant.trim().replace(Regex("\\s+"), " "),
                                color = Color(0xFF60A5FA), fontSize = 16.sp, lineHeight = 24.sp,
                                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        }
                        if (expanded && !recording && !processing) {
                            Text(result ?: transcript, color = foreground, fontSize = 24.sp,
                                lineHeight = 36.sp, modifier = Modifier.fillMaxWidth()
                                    .weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp))
                        } else {
                            val scroll = rememberScrollState()
                            val status = (processingStatus ?: liveProcessingStatus).takeIf { processing && it.isNotBlank() && transcript.isBlank() }
                            val shown = status?.let(::voiceSubtitle)
                                ?: if (!recording && !processing) voiceSubtitle(result ?: transcript) else subtitle
                            LaunchedEffect(shown, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                            Box(Modifier.fillMaxWidth().horizontalScroll(scroll), contentAlignment = Alignment.Center) {
                                Text(shown.ifBlank { if (recording) "Listening…" else if (processing) "Processing…" else "" },
                                    color = if (status != null) foreground.copy(alpha = 0.75f) else foreground,
                                    fontStyle = if (status != null) FontStyle.Italic else FontStyle.Normal,
                                    fontSize = if (status != null) 18.sp else 24.sp, lineHeight = 36.sp,
                                    maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                    if (budgetStatus.isNotBlank()) Text(budgetStatus, color = foreground, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (retryStatus.isNotBlank()) Text(retryStatus, color = foreground, fontSize = 14.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (connectionStatus.isNotBlank() && (recording || processing)) Text(connectionStatus,
                        color = foreground.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 2)
                    if (!error.isNullOrBlank()) {
                        Text(error!!, color = foreground, fontSize = 14.sp,
                            modifier = Modifier.heightIn(max = 64.dp).verticalScroll(rememberScrollState()),
                            textAlign = TextAlign.Center)
                    }
                    if (!recording && !processing && (transcript.isNotBlank() || result != null)) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Collapse" else "Show full result", color = foreground)
                        }
                    }
                    FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        if (recording) {
                            Text("Tap to finish and insert", color = foreground, fontSize = 14.sp)
                        } else if (processing) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = foreground, strokeWidth = 2.dp)
                            Text(liveProcessingStatus.ifBlank { "Finishing…" }, color = foreground, fontSize = 14.sp, maxLines = 2)
                        } else {
                            if (retryable) {
                                TextButton(onClick = {
                                    if (authError) manager.openLoginScreen() else manager.retryVoiceProcessing()
                                }) { Text(if (authError) "Sign in" else "Retry processing", color = foreground) }
                                if (!authError && manager.canContinueVoiceRecording()) {
                                    TextButton(onClick = { manager.continueVoiceRecording() }) {
                                        Text("Keep dictating", color = foreground)
                                    }
                                }
                            }
                            if (transcript.isNotBlank() || result != null) {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(result ?: transcript)) }) {
                                    Text(if (result != null) "Copy result" else "Copy transcript", color = foreground)
                                }
                            }
                            TextButton(onClick = { manager.cancelVoiceProcessing() }) {
                                Text("Dismiss", color = foreground)
                            }
                        }
                    }
                    if (recording && !processing) {
                        // A child button consumes its own tap instead of finishing the canvas.
                        TextButton(onClick = { manager.cancelVoiceProcessing() },
                            modifier = Modifier.semantics {
                                contentDescription = "Cancel recording without inserting"
                            }) {
                            Text("Cancel", color = foreground.copy(alpha = 0.75f), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
