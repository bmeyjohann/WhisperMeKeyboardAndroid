package app.whisperme.keyboard.app.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.whisperme.keyboard.app.LocalNavController
import app.whisperme.keyboard.app.Routes
import app.whisperme.keyboard.app.auth.Auth0Manager
import app.whisperme.keyboard.lib.compose.FlorisScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
internal fun FeedbackScreen() {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val api = remember { AccountApi(context) }
    val auth = remember { Auth0Manager.getInstance(context) }
    val signedIn by auth.isAuthenticated.collectAsState()
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf("bug") }
    var message by rememberSaveable { mutableStateOf("") }
    var submissionId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var sending by remember { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var reportId by rememberSaveable { mutableStateOf<String?>(null) }

    FlorisScreen {
        title = "Help and feedback"
        previewFieldVisible = false
        scrollable = false
        content {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Describe a bug, an idea, or a support question. Only the message you type and the selected category are submitted with a request ID.")
                Text("No transcripts, editor text, history, audio, screenshots, diagnostics or credentials are attached. Do not paste passwords, API keys or sensitive dictation.", style = MaterialTheme.typography.bodySmall)
                if (!signedIn) {
                    Text("Sign in to send feedback. Your draft stays here while this screen is open.")
                    OutlinedButton(onClick = { nav.navigate(Routes.Settings.Account) }) { Text("Open account") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bug" to "Bug", "idea" to "Idea", "support" to "Support").forEach { (value, label) ->
                        FilterChip(selected = kind == value, enabled = !sending && reportId == null, onClick = {
                            kind = value; submissionId = UUID.randomUUID().toString(); notice = null
                        }, label = { Text(label) })
                    }
                }
                OutlinedTextField(value = message, enabled = !sending && reportId == null, onValueChange = {
                    message = it; submissionId = UUID.randomUUID().toString(); notice = null
                }, modifier = Modifier.fillMaxWidth(), minLines = 5, label = { Text("Message") },
                    supportingText = { Text("${message.trim().length} / 4,000 characters") }, isError = message.trim().length > 4000)
                Button(enabled = signedIn && message.trim().length in 1..4000 && !sending && reportId == null, onClick = {
                    sending = true; notice = null
                    scope.launch {
                        try { reportId = api.feedback(submissionId, kind, message) }
                        catch (failure: Exception) {
                            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                            notice = accountError(failure)
                        } finally { sending = false }
                    }
                }) { Text(if (sending) "Sending…" else "Send feedback") }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                reportId?.let { id ->
                    Text("Feedback received. Report: $id")
                    TextButton(onClick = {
                        reportId = null; notice = null; message = ""; submissionId = UUID.randomUUID().toString()
                    }) { Text("Write another message") }
                }
                TextButton(onClick = { nav.navigate(Routes.Settings.Account) }) { Text("Account and voice setup") }
                TextButton(onClick = { nav.navigate(Routes.Settings.Home) }) { Text("Open local transcripts") }
            }
        }
    }
}
