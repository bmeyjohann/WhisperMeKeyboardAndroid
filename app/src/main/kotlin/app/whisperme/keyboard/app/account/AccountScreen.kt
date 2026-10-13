package app.whisperme.keyboard.app.account

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.whisperme.keyboard.app.LocalNavController
import app.whisperme.keyboard.app.Routes
import app.whisperme.keyboard.app.auth.Auth0Manager
import app.whisperme.keyboard.lib.compose.FlorisScreen
import app.whisperme.keyboard.lib.util.InputMethodUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

internal fun Context.accountActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.takeIf { it !== this }?.accountActivity()
    else -> null
}

internal fun accountError(failure: Exception): String = when (failure) {
    is TimeoutCancellationException -> "The request timed out. Check your connection and retry."
    is AccountServiceException -> failure.message.orEmpty() + failure.code?.let { " ($it)" }.orEmpty()
    else -> "This action could not be completed. Please try again."
}

internal fun accountDate(value: String): String = runCatching {
    DateFormat.getDateTimeInstance().format(Date.from(Instant.parse(value)))
}.getOrDefault(value)

@Composable
internal fun AccountScreen() {
    val context = LocalContext.current
    val nav = LocalNavController.current
    val auth = remember { Auth0Manager.getInstance(context) }
    val api = remember { AccountApi(context) }
    val signedIn by auth.isAuthenticated.collectAsState()
    val profile by auth.userProfile.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var account by remember { mutableStateOf<AccountDetails?>(null) }
    var plans by remember { mutableStateOf(emptyList<AccountPlan>()) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var planNotice by remember { mutableStateOf<String?>(null) }
    var checkedAt by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(signedIn, revision) {
        if (!signedIn) {
            account = null; plans = emptyList(); checkedAt = null; loading = false
            return@LaunchedEffect
        }
        loading = true; notice = null; planNotice = null
        try {
            account = api.account()
            checkedAt = DateFormat.getDateTimeInstance().format(Date())
            try { plans = api.plans() }
            catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                plans = emptyList(); planNotice = "Plan details unavailable. ${accountError(failure)}"
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            notice = accountError(failure)
        } finally { loading = false }
    }

    FlorisScreen {
        title = "Account and usage"
        previewFieldVisible = false
        scrollable = false
        content {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(if (signedIn) profile?.email ?: "Signed in to WhisperMe" else "Sign in to view your account and usage.")
                    Button(enabled = !authBusy, onClick = {
                        val activity = context.accountActivity()
                        if (activity == null) { notice = "Open WhisperMe settings to manage your sign-in." }
                        else {
                            authBusy = true
                            if (signedIn) auth.logout(activity) { success, _ ->
                                authBusy = false; account = null; plans = emptyList(); checkedAt = null
                                notice = if (success) null else "Signed out on this device. Browser sign-out could not be completed."
                            } else auth.login(activity) { success, _ ->
                                authBusy = false
                                notice = if (success) null else "Sign-in was not completed. Please retry when you are ready."
                                if (success) revision++
                            }
                        }
                    }) { Text(if (authBusy) "Please wait…" else if (signedIn) "Sign out" else "Sign in") }
                    Text("Transcripts stay on this device, including after sign-out. Manage or delete them in Transcripts.", style = MaterialTheme.typography.bodySmall)
                    if (signedIn) TextButton(enabled = !loading && !saving, onClick = { revision++ }) { Text("Refresh account") }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (account != null && notice != null) Text("Showing previously received information. Current access and balance could not be checked.")
                }
                val details = account.takeIf { signedIn }
                if (details != null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Access: ${details.access.state}", style = MaterialTheme.typography.titleMedium)
                                Text(if (details.access.allowed) "Voice access allowed" else "Voice access currently unavailable")
                                // Keep server purchase prompts and URLs out of the consumption-only app.
                                if (!details.access.allowed) Text(when (details.access.state) {
                                    "quota_exhausted" -> "Your usage allowance for this period is exhausted."
                                    "revoked" -> "Account access is paused for review. Contact support for help."
                                    else -> "This account currently has no voice access. Contact support for help."
                                })
                                if (!details.access.enforcementEnabled) Text("Usage enforcement is currently off. The server still decides voice access.")
                                details.access.planId?.let { id -> Text("Plan: ${plans.find { it.id == id }?.name ?: id}") }
                                Text("Subscription: ${details.subscription.status}")
                                details.access.expiresAt?.let { Text("Access expires: ${accountDate(it)}") }
                                Text("Audio remaining: ${String.format(Locale.getDefault(), "%.1f", details.usage.remainingAudioSeconds / 60)} minutes")
                                Text("Audio used: ${String.format(Locale.getDefault(), "%.1f / %.1f", details.usage.audioSeconds / 60, details.usage.audioSecondsLimit / 60)} minutes")
                                Text("Processing starts remaining: ${details.usage.remainingProcessingStarts}")
                                Text("Processing starts used: ${details.usage.processingStarts} / ${details.usage.processingStartsLimit}")
                                Text("Period starts: ${accountDate(details.usage.periodStart)}")
                                Text("Resets: ${accountDate(details.usage.periodEnd)}")
                                checkedAt?.let { Text("Last checked: $it", style = MaterialTheme.typography.bodySmall) }
                                Text("Purchases and payment management are unavailable in this Android app.", style = MaterialTheme.typography.bodySmall)
                                planNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    item {
                        AccountOnboardingCard(details.id, details.onboarding.currentCompleted, saving, notice == null && !loading) {
                            saving = true; notice = null
                            scope.launch {
                                try {
                                    api.finishOnboarding()
                                    // Completion is displayed only after the server accepted the explicit action.
                                    account = account?.takeIf { it.id == details.id && auth.isAuthenticated.value }
                                        ?.copy(onboarding = AccountOnboarding(1, true))
                                } catch (failure: Exception) {
                                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                                    notice = accountError(failure)
                                } finally { saving = false }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { nav.navigate(Routes.Settings.Feedback) }) { Text("Help and feedback") }
                    TextButton(onClick = { nav.navigate(Routes.Settings.Home) }) { Text("Open local transcripts") }
                }
            }
        }
    }
}

@Composable
private fun AccountOnboardingCard(accountId: String, completed: Boolean, saving: Boolean, fresh: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    val enabled by InputMethodUtils.observeIsFlorisboardEnabled(foregroundOnly = true)
    val selected by InputMethodUtils.observeIsFlorisboardSelected(foregroundOnly = true)
    var micAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var tried by remember(accountId) { mutableStateOf(false) }
    var practice by remember(accountId) { mutableStateOf("") }
    var setupNotice by remember { mutableStateOf<String?>(null) }
    var permissionRequested by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micAllowed = granted; permissionRequested = true }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            micAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun setup(action: () -> Unit) {
        try { action(); setupNotice = null } catch (_: Exception) { setupNotice = "Could not open device settings. Open Android Settings to enable the keyboard or microphone permission." }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (completed) "Setup completed for this account" else "Set up voice typing", style = MaterialTheme.typography.titleMedium)
            Text("1. Enable WhisperMe: ${if (enabled) "enabled" else "not enabled"}")
            TextButton(onClick = { setup { InputMethodUtils.showImeEnablerActivity(context) } }) { Text("Keyboard settings") }
            Text("2. Select WhisperMe: ${if (selected) "selected" else "not selected"}")
            TextButton(enabled = enabled, onClick = { setup { InputMethodUtils.showImePicker(context) } }) { Text("Select keyboard") }
            Text("3. Microphone: ${if (micAllowed) "permission granted" else "permission needed"}")
            if (!micAllowed) TextButton(onClick = { setup {
                if (permissionRequested && context.accountActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } else permission.launch(Manifest.permission.RECORD_AUDIO)
            } }) { Text("Allow microphone") }
            Text("4. Tap the practice field, then the microphone on the WhisperMe keyboard. Speak a short sentence and stop. Voice typing requires a working sign-in and connection.")
            OutlinedTextField(value = practice, onValueChange = { practice = it }, label = { Text("Try dictation here") }, modifier = Modifier.fillMaxWidth())
            if (!completed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = tried, onCheckedChange = { tried = it }, enabled = !saving)
                    Text("I tried dictation")
                }
                Button(enabled = enabled && selected && micAllowed && tried && !saving && fresh, onClick = onFinish) {
                    Text(if (saving) "Saving…" else "Finish setup")
                }
                Text("Finish saves setup completion to this signed-in account. It does not grant voice access.", style = MaterialTheme.typography.bodySmall)
            }
            setupNotice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
