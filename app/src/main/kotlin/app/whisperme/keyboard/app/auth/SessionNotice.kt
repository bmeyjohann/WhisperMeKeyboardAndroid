package app.whisperme.keyboard.app.auth

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth

/** Visible before dictation; does not open a browser from the keyboard without a tap. */
@Composable
fun SessionNotice(manager: Auth0Manager, modifier: Modifier = Modifier, onSignIn: () -> Unit) {
    val status by manager.sessionStatus.collectAsState()
    if (status == Auth0Manager.SessionStatus.READY) return
    TextButton(modifier = modifier.fillMaxWidth(),
        enabled = status != Auth0Manager.SessionStatus.CHECKING,
        onClick = {
            if (status == Auth0Manager.SessionStatus.SIGN_IN_REQUIRED) onSignIn()
            else manager.checkSessionBeforeUse()
        }) {
        Text(when (status) {
            Auth0Manager.SessionStatus.CHECKING -> "Checking voice sign-in…"
            Auth0Manager.SessionStatus.SIGN_IN_REQUIRED -> "Sign in before voice recording"
            else -> "Could not check sign-in. Tap to retry."
        })
    }
}
