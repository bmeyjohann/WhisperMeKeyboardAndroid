package app.whisperme.keyboard.app.settings.voiceinput

import androidx.compose.runtime.Composable
import app.whisperme.keyboard.lib.compose.FlorisScreen

/** Keep the existing route so saved links open the unified preferences screen. */
@Composable
fun VoiceInputNotesScreen() = FlorisScreen {
    title = "Saved preferences"
    navigationIconVisible = true
    previewFieldVisible = false
    content { VoicePreferenceSettings(prefs) }
}