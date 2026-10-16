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

package app.whisperme.keyboard.ime.keyboard

import android.content.Context
import android.content.Intent
import android.icu.lang.UCharacter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.media.MediaRecorder
import android.util.Base64
import android.view.KeyEvent
import org.florisboard.lib.android.ToastHandle
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
import app.whisperme.keyboard.FlorisImeService
import app.whisperme.keyboard.R
import app.whisperme.keyboard.app.auth.LocalVoiceSession
import app.whisperme.keyboard.app.auth.Auth0Manager
import app.whisperme.keyboard.app.florisPreferenceModel
import app.whisperme.keyboard.appContext
import app.whisperme.keyboard.clipboardManager
import app.whisperme.keyboard.editorInstance
import app.whisperme.keyboard.extensionManager
import app.whisperme.keyboard.ime.ImeUiMode
import app.whisperme.keyboard.ime.core.DisplayLanguageNamesIn
import app.whisperme.keyboard.ime.core.Subtype
import app.whisperme.keyboard.ime.core.SubtypePreset
import app.whisperme.keyboard.ime.editor.EditorContent
import app.whisperme.keyboard.ime.editor.FlorisEditorInfo
import app.whisperme.keyboard.ime.editor.ImeOptions
import app.whisperme.keyboard.ime.editor.InputAttributes
import app.whisperme.keyboard.ime.input.CapitalizationBehavior
import app.whisperme.keyboard.ime.input.InputEventDispatcher
import app.whisperme.keyboard.ime.input.InputKeyEventReceiver
import app.whisperme.keyboard.ime.input.InputShiftState
import app.whisperme.keyboard.ime.nlp.ClipboardSuggestionCandidate
import app.whisperme.keyboard.ime.nlp.PunctuationRule
import app.whisperme.keyboard.ime.nlp.SuggestionCandidate
import app.whisperme.keyboard.ime.onehanded.OneHandedMode
import app.whisperme.keyboard.ime.popup.PopupMappingComponent
import app.whisperme.keyboard.ime.text.composing.Composer
import app.whisperme.keyboard.ime.text.gestures.SwipeAction
import app.whisperme.keyboard.ime.text.key.KeyCode
import app.whisperme.keyboard.ime.text.key.KeyType
import app.whisperme.keyboard.ime.text.key.UtilityKeyAction
import app.whisperme.keyboard.ime.text.keyboard.TextKeyData
import app.whisperme.keyboard.ime.text.keyboard.TextKeyboardCache
import app.whisperme.keyboard.lib.devtools.LogTopic
import app.whisperme.keyboard.lib.devtools.flogError
import app.whisperme.keyboard.lib.devtools.flogInfo
import app.whisperme.keyboard.lib.ext.ExtensionComponentName
import app.whisperme.keyboard.lib.titlecase
import app.whisperme.keyboard.lib.uppercase
import app.whisperme.keyboard.lib.util.InputMethodUtils
import app.whisperme.keyboard.nlpManager
import app.whisperme.keyboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.AndroidKeyguardManager
import org.florisboard.lib.android.showLongToast
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.android.systemService
import org.florisboard.lib.kotlin.collectIn
import org.florisboard.lib.kotlin.collectLatestIn
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.whisperme.keyboard.app.FlorisAppActivity
import app.whisperme.keyboard.app.settings.voiceinput.VoiceTranscriptionProvider
import app.whisperme.keyboard.app.settings.voiceinput.compatibleModel
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import app.whisperme.keyboard.BuildConfig
import app.whisperme.keyboard.ime.voice.voiceContextWindow
import app.whisperme.keyboard.ime.voice.voiceFieldRole
import app.whisperme.keyboard.app.settings.voiceinput.VoiceLlmProvider
import app.whisperme.keyboard.app.settings.voiceinput.compatibleVoiceReasoning
import app.whisperme.keyboard.ime.voice.canContinueFinalProcessing
import app.whisperme.keyboard.ime.voice.VoiceButtonGesture
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private val DoubleSpacePeriodMatcher = """([^.!?‽\s]\s)""".toRegex()

class KeyboardManager(context: Context) : InputKeyEventReceiver {
    private val prefs by florisPreferenceModel()
    private val appContext by context.appContext()
    private val clipboardManager by context.clipboardManager()
    private val editorInstance by context.editorInstance()
    private val extensionManager by context.extensionManager()
    private val nlpManager by context.nlpManager()
    private val subtypeManager by context.subtypeManager()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val layoutManager = LayoutManager(context)
    private val keyboardCache = TextKeyboardCache()

    val resources = KeyboardManagerResources()
    val activeState = ObservableKeyboardState.new()
    var smartbarVisibleDynamicActionsCount by mutableIntStateOf(0)
    private var lastToast: ToastHandle? = null

    private val historyStore by lazy { app.whisperme.keyboard.ime.voice.VoiceHistoryStore.get(appContext) }
    @Volatile private var historyEntry: app.whisperme.keyboard.ime.voice.VoiceHistoryEntry? = null
    private var historyStartNanos = 0L
    private var historyCheckpointMs = 0L
    private var recoverableHistoryEntry: app.whisperme.keyboard.ime.voice.VoiceHistoryEntry? = null

    @Synchronized private fun beginVoiceHistory() {
        finishVoiceHistory("canceled", "Superseded by a new recording")
        recoverableHistoryEntry = null
        historyStartNanos = System.nanoTime()
        historyCheckpointMs = 0
        historyEntry = app.whisperme.keyboard.ime.voice.VoiceHistoryEntry(
            transport = if (prefs.voiceInput.transcriptionProvider.get() == VoiceTranscriptionProvider.GROQ_BATCH) "batch" else "realtime",
            transcriptionProvider = currentTranscriptionProvider(),
            transcriptionModel = if (prefs.voiceInput.transcriptionProvider.get() == VoiceTranscriptionProvider.GROQ_BATCH) "server default" else prefs.voiceInput.transcriptionModel.get(),
            llmProvider = currentLlmProvider(),
            llmModel = prefs.voiceInput.llmProvider.get().compatibleModel(prefs.voiceInput.llmModel.get()),
            mode = prefs.voiceInput.mode.get(),
            reasoningEffort = currentVoiceReasoning(),
        )
        historyEntry?.let(historyStore::save)
    }
    private fun historyElapsed() = (System.nanoTime() - historyStartNanos) / 1_000_000
    @Synchronized private fun sampleVoiceHistory(kind: String, revision: Long, text: String) {
        historyEntry = historyEntry?.sample(kind, revision, text, historyElapsed())
        if (kind == "transcript.final" && text.isNotBlank()) {
            val provider = prefs.voiceInput.llmProvider.get()
            historyEntry = historyEntry?.copy(recovery = app.whisperme.keyboard.ime.voice.VoiceRecoveryPayload(
                text, if (provider == VoiceLlmProvider.SERVER_DEFAULT) "server" else "explicit",
                provider.wireValue, provider.compatibleModel(prefs.voiceInput.llmModel.get()).takeIf { provider != VoiceLlmProvider.SERVER_DEFAULT },
                prefs.voiceInput.mode.get(), historyEntry?.reasoningEffort ?: currentVoiceReasoning()))
        }
        // Text checkpoints only; PCM frames never cause disk writes.
        if (historyElapsed() - historyCheckpointMs >= 2000 || kind == "transcript.final") {
            historyCheckpointMs = historyElapsed()
            historyEntry?.let { historyStore.save(it.copy(elapsedMs = historyElapsed())) }
        }
    }
    @Synchronized private fun finishVoiceHistory(status: String, error: String? = null) {
        historyEntry?.let {
            val kind = when (status) { "successful" -> "insertion.applied"; "unverified" -> "insertion.unverified"; "handled" -> "notice"; "canceled" -> "cancelled"; else -> "error" }
            val saved = it.sample(kind, realtimeRevision, error ?: status, historyElapsed())
                .copy(status = status, error = error, elapsedMs = historyElapsed())
            recoverableHistoryEntry = saved
            historyStore.save(saved)
        }
        historyEntry = null
    }
    @Synchronized private fun historyResponse(transcript: String?, result: String?) {
        if (transcript != null && historyEntry?.finalAsr != transcript) sampleVoiceHistory("transcript.final", realtimeRevision, transcript)
        if (result != null && historyEntry?.result != result) sampleVoiceHistory("llm.result", realtimeRevision, result)
        historyEntry = historyEntry?.let { it.copy(
            transcript = transcript?.takeIf { text -> text.isNotEmpty() } ?: it.transcript,
            finalAsr = transcript?.takeIf { text -> text.isNotEmpty() } ?: it.finalAsr,
            finalAsrMs = it.finalAsrMs ?: historyElapsed().takeIf { transcript != null },
            result = result, resultMs = historyElapsed().takeIf { result != null },
        ) }
    }
    @Synchronized private fun historyStopped() {
        historyEntry = historyEntry?.copy(recordingMs = historyElapsed())
    }

    // Voice recording state
    private val _isVoiceRecording = MutableStateFlow(false)
    val isVoiceRecording = _isVoiceRecording.asStateFlow()
    private val _isVoiceProcessing = MutableStateFlow(false)
    val isVoiceProcessing = _isVoiceProcessing.asStateFlow()
    private val _isVoiceRetryable = MutableStateFlow(false)
    val isVoiceRetryable = _isVoiceRetryable.asStateFlow()
    private val _voiceErrorMessage = MutableStateFlow<String?>(null)
    val voiceErrorMessage = _voiceErrorMessage.asStateFlow()
    private val _isVoiceAuthError = MutableStateFlow(false)
    val isVoiceAuthError = _isVoiceAuthError.asStateFlow()
    private val _voiceLiveTranscript = MutableStateFlow("")
    val voiceLiveTranscript = _voiceLiveTranscript.asStateFlow()
    private val _voiceFinalText = MutableStateFlow<String?>(null)
    val voiceFinalText = _voiceFinalText.asStateFlow()
    private var voiceRecordingFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var voiceAudioPreparationEnabled = false
    private val preparedVoiceRecorder = app.whisperme.keyboard.ime.voice.PreparedVoiceResource(
        create = {
            val started = android.os.SystemClock.elapsedRealtime()
            val size = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(size > 0) { "Failed to determine realtime audio buffer size" }
            val record = AudioRecord(AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, size * 2)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("Failed to initialize realtime audio recorder")
            }
            if (BuildConfig.DEBUG) android.util.Log.i("VoiceAudioAllocation", "allocatedMs=${android.os.SystemClock.elapsedRealtime() - started}")
            record
        },
        dispose = { synchronized(it) { it.release() } },
    )

    /** Allocation only: no startRecording(), read(), audio buffering or network activity. */
    fun prepareVoiceAudio() {
        voiceAudioPreparationEnabled = true
        preparedVoiceRecorder.enable()
        prepareVoiceAudioIfVisible()
    }

    private fun prepareVoiceAudioIfVisible() {
        if (voiceAudioPreparationEnabled && !_isVoiceRecording.value && realtimeStartJob?.isActive != true &&
            hasRecordAudioPermission() && prefs.voiceInput.transcriptionProvider.get() != VoiceTranscriptionProvider.GROQ_BATCH) {
            preparedVoiceRecorder.prepare(scope)
        }
    }
    private var realtimeWebSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var connectionHealth = app.whisperme.keyboard.ime.voice.VoiceConnectionHealth()
    private val _voiceConnectionStatus = MutableStateFlow("")
    val voiceConnectionStatus = _voiceConnectionStatus.asStateFlow()
    private val _voiceRetryStatus = MutableStateFlow("")
    val voiceRetryStatus = _voiceRetryStatus.asStateFlow()
    private val _voiceBudgetStatus = MutableStateFlow("")
    val voiceBudgetStatus = _voiceBudgetStatus.asStateFlow()
    private var continuationJob: Job? = null
    @Volatile private var realtimeStreamingJob: Job? = null
    private var realtimeSenderJob: Job? = null
    private var realtimeAudioQueue: Channel<ByteArray>? = null
    private val retainedVoiceAudio = app.whisperme.keyboard.ime.voice.RetainedVoiceAudio()
    private var realtimeStartJob: Job? = null
    @Volatile private var realtimeGeneration = 0L
    private val _voiceAssistantMessage = MutableStateFlow("")
    private val _preferenceSuggestion = MutableStateFlow<app.whisperme.keyboard.ime.voice.VoicePreferenceSuggestion?>(null)
    val preferenceSuggestion = _preferenceSuggestion.asStateFlow()
    private val _preferenceSaveBusy = MutableStateFlow(false)
    val preferenceSaveBusy = _preferenceSaveBusy.asStateFlow()
    private val _preferenceSaveError = MutableStateFlow<String?>(null)
    val preferenceSaveError = _preferenceSaveError.asStateFlow()
    private val _preferenceEditorOpen = MutableStateFlow(false)
    val preferenceEditorOpen = _preferenceEditorOpen.asStateFlow()
    private var preferenceFieldIdentity: android.view.inputmethod.EditorInfo? = null
    private var preferenceFieldState: Triple<String, Int, Int>? = null

    fun openPreferenceEditor() { _preferenceEditorOpen.value = true }
    fun closePreferenceEditor() { if (!_preferenceSaveBusy.value) _preferenceEditorOpen.value = false }

    fun dismissPreferenceSuggestion() {
        if (!_preferenceSaveBusy.value) {
            _preferenceSuggestion.value = null; _preferenceSaveError.value = null; _preferenceEditorOpen.value = false
            preferenceFieldIdentity = null; preferenceFieldState = null
        }
    }

    fun onPreferenceFieldUpdated() {
        if (_preferenceSuggestion.value == null || _isVoiceProcessing.value || _isVoiceRecording.value) return
        val current = FlorisImeService.currentInputConnection()?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val state = current?.let { Triple(it.text?.toString().orEmpty(), it.selectionStart, it.selectionEnd) }
        if (editorInstance.activeInfo.base !== preferenceFieldIdentity || state != preferenceFieldState) dismissPreferenceSuggestion()
    }

    fun savePreferenceSuggestion(text: String, allApps: Boolean) {
        val suggestion = _preferenceSuggestion.value ?: return
        savePreferenceSuggestion(text, app.whisperme.keyboard.ime.voice.VoicePreferenceScope(
            if (allApps) "all" else "app", if (allApps) null else suggestion.packageName))
    }

    fun savePreferenceSuggestion(text: String, selectedScope: app.whisperme.keyboard.ime.voice.VoicePreferenceScope, name: String? = null) {
        val suggestion = _preferenceSuggestion.value ?: return
        if (_preferenceSaveBusy.value) return
        _preferenceSaveBusy.value = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                _preferenceSaveError.value = null
                if (suggestion.kind == "dictionary") {
                    val updated = app.whisperme.keyboard.ime.voice.addVoiceDictionaryWord(prefs.voiceInput.dictionary.get(), text)
                    prefs.voiceInput.dictionary.set(updated)
                } else {
                    val saveScope = suggestion.saveScope(selectedScope)
                    app.whisperme.keyboard.ime.voice.VoicePreferenceLimits.requireRule(text)
                    app.whisperme.keyboard.app.settings.voiceinput.VoicePreferenceApi(appContext).request("preferences", "POST",
                        app.whisperme.keyboard.app.settings.voiceinput.voiceRuleBody(text, saveScope, name ?: suggestion.name))
                }
                if (_preferenceSuggestion.value === suggestion) { _preferenceSuggestion.value = null; _preferenceEditorOpen.value = false }
                appContext.showShortToast(if (suggestion.kind == "dictionary") "Dictionary word saved" else "Preference saved")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _preferenceSaveError.value = e.message ?: "Could not save. Please retry."
            } finally { _preferenceSaveBusy.value = false }
        }
    }

    private fun offerVoicePreference(response: VoiceResponse, context: VoiceContextData): Boolean {
        if (_preferenceSaveBusy.value) return false
        val suggestion = app.whisperme.keyboard.ime.voice.readVoicePreferenceSuggestion(
            response.preferenceSuggestionJson, context.packageName) ?: return false
        if (suggestion.requiresConfirmation) {
            if (suggestion.kind == "dictionary") {
                if (!context.suggestPreferences || !prefs.voiceInput.suggestPreferences.get()) return false
            } else if (!context.usePreferences || !prefs.voiceInput.usePreferences.get()) return false
        } else if (!context.suggestPreferences || !prefs.voiceInput.suggestPreferences.get()) return false
        _preferenceSaveError.value = null
        preferenceFieldIdentity = editorInstance.activeInfo.base
        preferenceFieldState = FlorisImeService.currentInputConnection()?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            ?.let { Triple(it.text?.toString().orEmpty(), it.selectionStart, it.selectionEnd) }
        _preferenceSuggestion.value = suggestion
        _preferenceEditorOpen.value = false
        return true
    }
    val voiceAssistantMessage = _voiceAssistantMessage.asStateFlow()
    private val _voiceProcessingStatus = MutableStateFlow("")
    val voiceProcessingStatus = _voiceProcessingStatus.asStateFlow()
    @Volatile private var voiceWindowBefore = 2_000
    @Volatile private var voiceWindowAfter = 2_000
    private var voiceContextExpansions = 0
    private var realtimeReady: CompletableDeferred<Unit>? = null
    private var voiceInputConnection: InputConnection? = null
    private var realtimeRevision = 0L
    private var realtimeResponseDeferred: CompletableDeferred<VoiceResponse>? = null
    private var realtimeLastFinalText: String? = null
    private var wasPendingVoiceInput = false
    private var previousUiModeBeforeVoice: ImeUiMode? = null

    private fun rememberUiModeBeforeVoice() {
        if (activeState.imeUiMode != ImeUiMode.VOICE_RECORDING && activeState.imeUiMode != ImeUiMode.VOICE) {
            previousUiModeBeforeVoice = activeState.imeUiMode
        }
    }

    @androidx.annotation.MainThread
    private fun restoreUiModeAfterVoice() {
        activeState.imeUiMode = previousUiModeBeforeVoice
            ?.takeUnless { it == ImeUiMode.VOICE_RECORDING || it == ImeUiMode.VOICE }
            ?: ImeUiMode.TEXT
        previousUiModeBeforeVoice = null
    }

    private var lastVoiceContext: VoiceContextData? = null
    private var lastBase64Audio: String? = null

    private val activeEvaluatorGuard = Mutex(locked = false)
    private var activeEvaluatorVersion = AtomicInteger(0)
    private val _activeEvaluator = MutableStateFlow<ComputingEvaluator>(DefaultComputingEvaluator)
    val activeEvaluator get() = _activeEvaluator.asStateFlow()
    private val _activeSmartbarEvaluator = MutableStateFlow<ComputingEvaluator>(
        DefaultComputingEvaluator
    )
    val activeSmartbarEvaluator get() = _activeSmartbarEvaluator.asStateFlow()
    private val _lastCharactersEvaluator = MutableStateFlow<ComputingEvaluator>(
        DefaultComputingEvaluator
    )
    val lastCharactersEvaluator get() = _lastCharactersEvaluator.asStateFlow()
    private val realtimeWsClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val inputEventDispatcher = InputEventDispatcher.new(
        repeatableKeyCodes = intArrayOf(
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.DELETE,
            KeyCode.FORWARD_DELETE,
            KeyCode.UNDO,
            KeyCode.REDO,
        )
    ).also { it.keyEventReceiver = this }

    init {
        // Set up authentication callback to clear auth error state after login
        val authManager = Auth0Manager.getInstance(appContext)
        authManager.setAuthStateChangeCallback { isAuthenticated ->
            if (isAuthenticated && _isVoiceAuthError.value && _isVoiceRetryable.value) {
                flogInfo { "User authenticated after auth error - clearing auth error state" }
                // Clear auth error state so user sees normal retry UI (not login UI)
                _isVoiceAuthError.value = false
                // Keep _isVoiceRetryable.value = true so user can manually retry
                // Update error message to show normal retry message
                _voiceErrorMessage.value = "🔄 Ready to retry voice processing"
            }
        }

        scope.launch(Dispatchers.Main.immediate) {
            resources.anyChanged.observeForever {
                updateActiveEvaluators {
                    keyboardCache.clear()
                }
            }
            prefs.keyboard.numberRow.observeForever {
                updateActiveEvaluators {
                    keyboardCache.clear(KeyboardMode.CHARACTERS)
                }
            }
            prefs.keyboard.hintedNumberRowEnabled.observeForever {
                updateActiveEvaluators()
            }
            prefs.keyboard.hintedSymbolsEnabled.observeForever {
                updateActiveEvaluators()
            }
            prefs.keyboard.utilityKeyEnabled.observeForever {
                updateActiveEvaluators()
            }
            prefs.keyboard.utilityKeyAction.observeForever {
                updateActiveEvaluators()
            }
            activeState.collectLatestIn(scope) {
                updateActiveEvaluators()
            }
            subtypeManager.subtypesFlow.collectLatestIn(scope) {
                updateActiveEvaluators()
            }
            subtypeManager.activeSubtypeFlow.collectLatestIn(scope) {
                reevaluateInputShiftState()
                updateActiveEvaluators()
                editorInstance.refreshComposing()
                resetSuggestions(editorInstance.activeContent)
            }
            clipboardManager.primaryClipFlow.collectLatestIn(scope) {
                updateActiveEvaluators()
            }
            editorInstance.activeContentFlow.collectIn(scope) { content ->
                resetSuggestions(content)
            }
            prefs.devtools.enabled.observeForever {
                reevaluateDebugFlags()
            }
            prefs.devtools.showDragAndDropHelpers.observeForever {
                reevaluateDebugFlags()
            }
        }
    }

    private fun updateActiveEvaluators(action: () -> Unit = { }) = scope.launch {
        activeEvaluatorGuard.withLock {
            action()
            val editorInfo = editorInstance.activeInfo
            val state = activeState.snapshot()
            val subtype = subtypeManager.activeSubtype
            val mode = state.keyboardMode
            // We need to reset the snapshot input shift state for non-character layouts, because the shift mechanic
            // only makes sense for the character layouts.
            if (mode != KeyboardMode.CHARACTERS) {
                state.inputShiftState = InputShiftState.UNSHIFTED
            }
            val computedKeyboard = keyboardCache.getOrElseAsync(mode, subtype) {
                layoutManager.computeKeyboardAsync(
                    keyboardMode = mode,
                    subtype = subtype,
                ).await()
            }
            val computingEvaluator = ComputingEvaluatorImpl(
                version = activeEvaluatorVersion.getAndAdd(1),
                keyboard = computedKeyboard,
                editorInfo = editorInfo,
                state = state,
                subtype = subtype,
            )
            for (key in computedKeyboard.keys()) {
                key.compute(computingEvaluator)
                key.computeLabelsAndDrawables(computingEvaluator)
            }
            _activeEvaluator.value = computingEvaluator
            _activeSmartbarEvaluator.value = computingEvaluator.asSmartbarQuickActionsEvaluator()
            if (computedKeyboard.mode == KeyboardMode.CHARACTERS) {
                _lastCharactersEvaluator.value = computingEvaluator
            }
        }
    }

    fun reevaluateInputShiftState() {
        if (activeState.inputShiftState != InputShiftState.CAPS_LOCK && !inputEventDispatcher.isPressed(
                KeyCode.SHIFT)) {
            val shift = prefs.correction.autoCapitalization.get()
                && subtypeManager.activeSubtype.primaryLocale.supportsCapitalization
                && editorInstance.activeCursorCapsMode != InputAttributes.CapsMode.NONE
            activeState.inputShiftState = when {
                shift -> InputShiftState.SHIFTED_AUTOMATIC
                else -> InputShiftState.UNSHIFTED
            }
        }
    }

    fun resetSuggestions(content: EditorContent) {
        if (!(activeState.isComposingEnabled || nlpManager.isSuggestionOn())) {
            nlpManager.clearSuggestions()
            return
        }
        nlpManager.suggest(subtypeManager.activeSubtype, content)
    }

    /**
     * @return If the language switch should be shown.
     */
    fun shouldShowLanguageSwitch(): Boolean {
        return subtypeManager.subtypes.size > 1
    }

    fun toggleOneHandedMode() {
        prefs.keyboard.oneHandedModeEnabled.set(!prefs.keyboard.oneHandedModeEnabled.get())
    }

    fun executeSwipeAction(swipeAction: SwipeAction) {
        val keyData = when (swipeAction) {
            SwipeAction.CYCLE_TO_PREVIOUS_KEYBOARD_MODE -> when (activeState.keyboardMode) {
                KeyboardMode.CHARACTERS -> TextKeyData.VIEW_NUMERIC_ADVANCED
                KeyboardMode.NUMERIC_ADVANCED -> TextKeyData.VIEW_SYMBOLS2
                KeyboardMode.SYMBOLS2 -> TextKeyData.VIEW_SYMBOLS
                else -> TextKeyData.VIEW_CHARACTERS
            }
            SwipeAction.CYCLE_TO_NEXT_KEYBOARD_MODE -> when (activeState.keyboardMode) {
                KeyboardMode.CHARACTERS -> TextKeyData.VIEW_SYMBOLS
                KeyboardMode.SYMBOLS -> TextKeyData.VIEW_SYMBOLS2
                KeyboardMode.SYMBOLS2 -> TextKeyData.VIEW_NUMERIC_ADVANCED
                else -> TextKeyData.VIEW_CHARACTERS
            }
            SwipeAction.DELETE_WORD -> TextKeyData.DELETE_WORD
            SwipeAction.HIDE_KEYBOARD -> TextKeyData.IME_HIDE_UI
            SwipeAction.INSERT_SPACE -> TextKeyData.SPACE
            SwipeAction.MOVE_CURSOR_DOWN -> TextKeyData.ARROW_DOWN
            SwipeAction.MOVE_CURSOR_UP -> TextKeyData.ARROW_UP
            SwipeAction.MOVE_CURSOR_LEFT -> TextKeyData.ARROW_LEFT
            SwipeAction.MOVE_CURSOR_RIGHT -> TextKeyData.ARROW_RIGHT
            SwipeAction.MOVE_CURSOR_START_OF_LINE -> TextKeyData.MOVE_START_OF_LINE
            SwipeAction.MOVE_CURSOR_END_OF_LINE -> TextKeyData.MOVE_END_OF_LINE
            SwipeAction.MOVE_CURSOR_START_OF_PAGE -> TextKeyData.MOVE_START_OF_PAGE
            SwipeAction.MOVE_CURSOR_END_OF_PAGE -> TextKeyData.MOVE_END_OF_PAGE
            SwipeAction.SHIFT -> TextKeyData.SHIFT
            SwipeAction.REDO -> TextKeyData.REDO
            SwipeAction.UNDO -> TextKeyData.UNDO
            SwipeAction.SHOW_INPUT_METHOD_PICKER -> TextKeyData.SYSTEM_INPUT_METHOD_PICKER
            SwipeAction.SHOW_SUBTYPE_PICKER -> TextKeyData.SHOW_SUBTYPE_PICKER
            SwipeAction.SWITCH_TO_CLIPBOARD_CONTEXT -> TextKeyData.IME_UI_MODE_CLIPBOARD
            SwipeAction.SWITCH_TO_PREV_SUBTYPE -> TextKeyData.IME_PREV_SUBTYPE
            SwipeAction.SWITCH_TO_NEXT_SUBTYPE -> TextKeyData.IME_NEXT_SUBTYPE
            SwipeAction.SWITCH_TO_PREV_KEYBOARD -> TextKeyData.SYSTEM_PREV_INPUT_METHOD
            SwipeAction.TOGGLE_SMARTBAR_VISIBILITY -> TextKeyData.TOGGLE_SMARTBAR_VISIBILITY
            else -> null
        }
        if (keyData != null) {
            inputEventDispatcher.sendDownUp(keyData)
        }
    }

    fun commitCandidate(candidate: SuggestionCandidate) {
        dismissPreferenceSuggestion()
        scope.launch {
            candidate.sourceProvider?.notifySuggestionAccepted(subtypeManager.activeSubtype, candidate)
        }
        when (candidate) {
            is ClipboardSuggestionCandidate -> editorInstance.commitClipboardItem(candidate.clipboardItem)
            else -> editorInstance.commitCompletion(candidate)
        }
    }

    fun commitGesture(word: String) {
        dismissPreferenceSuggestion()
        editorInstance.commitGesture(fixCase(word))
    }

    /**
     * Changes a word to the current case.
     * eg if [KeyboardState.isUppercase] is true, abc -> ABC
     *    if [caps]     is true, abc -> Abc
     *    otherwise            , abc -> abc
     */
    fun fixCase(word: String): String {
        return when(activeState.inputShiftState) {
            InputShiftState.CAPS_LOCK -> {
                word.uppercase(subtypeManager.activeSubtype.primaryLocale)
            }
            InputShiftState.SHIFTED_MANUAL, InputShiftState.SHIFTED_AUTOMATIC -> {
                word.titlecase(subtypeManager.activeSubtype.primaryLocale)
            }
            else -> word
        }
    }

    /**
     * Handles [KeyCode] arrow and move events, behaves differently depending on text selection.
     */
    fun handleArrow(code: Int, count: Int = 1) = editorInstance.apply {
        val isShiftPressed = activeState.isManualSelectionMode || inputEventDispatcher.isPressed(
            KeyCode.SHIFT)
        val content = activeContent
        val selection = content.selection
        when (code) {
            KeyCode.ARROW_LEFT -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta(shift = isShiftPressed), count)
            }
            KeyCode.ARROW_RIGHT -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta(shift = isShiftPressed), count)
            }
            KeyCode.ARROW_UP -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP, meta(shift = isShiftPressed), count)
            }
            KeyCode.ARROW_DOWN -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, meta(shift = isShiftPressed), count)
            }
            KeyCode.MOVE_START_OF_PAGE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_UP, meta(alt = true, shift = isShiftPressed), count)
            }
            KeyCode.MOVE_END_OF_PAGE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, meta(alt = true, shift = isShiftPressed), count)
            }
            KeyCode.MOVE_START_OF_LINE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = true
                    activeState.isManualSelectionModeEnd = false
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, meta(alt = true, shift = isShiftPressed), count)
            }
            KeyCode.MOVE_END_OF_LINE -> {
                if (!selection.isSelectionMode && activeState.isManualSelectionMode) {
                    activeState.isManualSelectionModeStart = false
                    activeState.isManualSelectionModeEnd = true
                }
                sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, meta(alt = true, shift = isShiftPressed), count)
            }
        }
    }

    /**
     * Handles a [KeyCode.CLIPBOARD_SELECT] event.
     */
    private fun handleClipboardSelect() {
        val activeSelection = editorInstance.activeContent.selection
        activeState.isManualSelectionMode = if (activeSelection.isSelectionMode) {
            if (activeState.isManualSelectionMode && activeState.isManualSelectionModeStart) {
                editorInstance.setSelection(activeSelection.start, activeSelection.start)
            } else {
                editorInstance.setSelection(activeSelection.end, activeSelection.end)
            }
            false
        } else {
            !activeState.isManualSelectionMode
        }
    }

    private fun revertPreviouslyAcceptedCandidate() {
        editorInstance.phantomSpace.candidateForRevert?.let { candidateForRevert ->
            candidateForRevert.sourceProvider?.let { sourceProvider ->
                scope.launch {
                    sourceProvider.notifySuggestionReverted(
                        subtype = subtypeManager.activeSubtype,
                        candidate = candidateForRevert,
                    )
                }
            }
        }
    }

    /**
     * Handles a [KeyCode.DELETE] event.
     */
    private fun handleDelete() {
        activeState.batchEdit {
            it.isManualSelectionMode = false
            it.isManualSelectionModeStart = false
            it.isManualSelectionModeEnd = false
        }
        revertPreviouslyAcceptedCandidate()
        editorInstance.deleteBackwards()
    }

    /**
     * Handles a [KeyCode.DELETE_WORD] event.
     */
    private fun handleDeleteWord() {
        activeState.batchEdit {
            it.isManualSelectionMode = false
            it.isManualSelectionModeStart = false
            it.isManualSelectionModeEnd = false
        }
        revertPreviouslyAcceptedCandidate()
        editorInstance.deleteWordBackwards()
    }

    /**
     * Handles a [KeyCode.ENTER] event.
     */
    private fun handleEnter() {
        val info = editorInstance.activeInfo
        val isShiftPressed = inputEventDispatcher.isPressed(KeyCode.SHIFT)
        if (editorInstance.tryPerformEnterCommitRaw()) {
            return
        }
        if (info.imeOptions.flagNoEnterAction || info.inputAttributes.flagTextMultiLine && isShiftPressed) {
            editorInstance.performEnter()
        } else {
            when (val action = info.imeOptions.action) {
                ImeOptions.Action.DONE,
                ImeOptions.Action.GO,
                ImeOptions.Action.NEXT,
                ImeOptions.Action.PREVIOUS,
                ImeOptions.Action.SEARCH,
                ImeOptions.Action.SEND -> {
                    editorInstance.performEnterAction(action)
                }
                else -> editorInstance.performEnter()
            }
        }
    }

    /**
     * Handles a [KeyCode.LANGUAGE_SWITCH] event. Also handles if the language switch should cycle
     * FlorisBoard internal or system-wide.
     */
    private fun handleLanguageSwitch() {
        when (prefs.keyboard.utilityKeyAction.get()) {
            UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS,
            UtilityKeyAction.SWITCH_LANGUAGE -> subtypeManager.switchToNextSubtype()
            else -> FlorisImeService.switchToNextInputMethod()
        }
    }

    /**
     * Handles a [KeyCode.SHIFT] down event.
     */
    private fun handleShiftDown(data: KeyData) {
        val prefs = prefs.keyboard.capitalizationBehavior
        when (prefs.get()) {
            CapitalizationBehavior.CAPSLOCK_BY_DOUBLE_TAP -> {
                if (inputEventDispatcher.isConsecutiveDown(data)) {
                    activeState.inputShiftState = InputShiftState.CAPS_LOCK
                } else {
                    if (activeState.inputShiftState == InputShiftState.UNSHIFTED) {
                        activeState.inputShiftState = InputShiftState.SHIFTED_MANUAL
                    } else {
                        activeState.inputShiftState = InputShiftState.UNSHIFTED
                    }
                }
            }
            CapitalizationBehavior.CAPSLOCK_BY_CYCLE -> {
                activeState.inputShiftState = when (activeState.inputShiftState) {
                    InputShiftState.UNSHIFTED -> InputShiftState.SHIFTED_MANUAL
                    InputShiftState.SHIFTED_MANUAL -> InputShiftState.CAPS_LOCK
                    InputShiftState.SHIFTED_AUTOMATIC -> InputShiftState.UNSHIFTED
                    InputShiftState.CAPS_LOCK -> InputShiftState.UNSHIFTED
                }
            }
        }
    }

    /**
     * Handles a [KeyCode.SHIFT] up event.
     */
    private fun handleShiftUp(data: KeyData) {
        if (activeState.inputShiftState != InputShiftState.CAPS_LOCK && !inputEventDispatcher.isAnyPressed() &&
            !inputEventDispatcher.isUninterruptedEventSequence(data)) {
            activeState.inputShiftState = InputShiftState.UNSHIFTED
        }
    }

    /**
     * Handles a [KeyCode.CAPS_LOCK] event.
     */
    private fun handleCapsLock() {
        activeState.inputShiftState = InputShiftState.CAPS_LOCK
    }

    /**
     * Handles a [KeyCode.SHIFT] cancel event.
     */
    private fun handleShiftCancel() {
        activeState.inputShiftState = InputShiftState.UNSHIFTED
    }

    /**
     * Handles a hardware [KeyEvent.KEYCODE_SPACE] event. Same as [handleSpace],
     * but skips handling changing to characters keyboard and double space periods.
     */
    fun handleHardwareKeyboardSpace() {
        val candidate = nlpManager.getAutoCommitCandidate()
        candidate?.let { commitCandidate(it) }
        // Skip handling changing to characters keyboard and double space periods
        // TODO: this is whether we commit space after selecting candidate. Should be determined by SuggestionProvider
        if (!subtypeManager.activeSubtype.primaryLocale.supportsAutoSpace &&
                candidate != null) { /* Do nothing */ } else {
            editorInstance.commitText(KeyCode.SPACE.toChar().toString())
        }
    }

    /**
     * Handles a [KeyCode.SPACE] event. Also handles the auto-correction of two space taps if
     * enabled by the user.
     */
    private fun handleSpace(data: KeyData) {
        val candidate = nlpManager.getAutoCommitCandidate()
        candidate?.let { commitCandidate(it) }
        if (prefs.keyboard.spaceBarSwitchesToCharacters.get()) {
            when (activeState.keyboardMode) {
                KeyboardMode.NUMERIC_ADVANCED,
                KeyboardMode.SYMBOLS,
                KeyboardMode.SYMBOLS2 -> {
                    activeState.keyboardMode = KeyboardMode.CHARACTERS
                }
                else -> { /* Do nothing */ }
            }
        }
        if (prefs.correction.doubleSpacePeriod.get()) {
            if (inputEventDispatcher.isConsecutiveUp(data)) {
                val text = editorInstance.run { activeContent.getTextBeforeCursor(2) }
                if (text.length == 2 && DoubleSpacePeriodMatcher.matches(text)) {
                    editorInstance.deleteBackwards()
                    editorInstance.commitText(". ")
                    return
                }
            }
        }
        // TODO: this is whether we commit space after selecting candidate. Should be determined by SuggestionProvider
        if (!subtypeManager.activeSubtype.primaryLocale.supportsAutoSpace &&
                candidate != null) { /* Do nothing */ } else {
            editorInstance.commitText(KeyCode.SPACE.toChar().toString())
        }
    }

    /**
     * Handles a [KeyCode.TOGGLE_INCOGNITO_MODE] event.
     */
    private fun handleToggleIncognitoMode() {
        prefs.suggestion.forceIncognitoModeFromDynamic.set(!prefs.suggestion.forceIncognitoModeFromDynamic.get())
        val newState = !activeState.isIncognitoMode
        activeState.isIncognitoMode = newState
        lastToast?.cancel()
        lastToast =
            if (newState) {
                appContext.showLongToast(
                    R.string.incognito_mode__toast_after_enabled,
                    "app_name" to appContext.getString(R.string.floris_app_name),
                )
            } else {
                appContext.showLongToast(
                    R.string.incognito_mode__toast_after_disabled,
                    "app_name" to appContext.getString(R.string.floris_app_name),
                )
            }
    }

    /**
     * Handles a [KeyCode.TOGGLE_AUTOCORRECT] event.
     */
    private fun handleToggleAutocorrect() {
        lastToast?.cancel()
        lastToast = appContext.showLongToast("Autocorrect toggle is a placeholder and not yet implemented")
    }

    /**
     * Handles a [KeyCode.KANA_SWITCHER] event
     */
    private fun handleKanaSwitch() {
        activeState.batchEdit {
            it.isKanaKata = !it.isKanaKata
            it.isCharHalfWidth = false
        }
    }

    /**
     * Handles a [KeyCode.KANA_HIRA] event
     */
    private fun handleKanaHira() {
        activeState.batchEdit {
            it.isKanaKata = false
            it.isCharHalfWidth = false
        }
    }

    /**
     * Handles a [KeyCode.KANA_KATA] event
     */
    private fun handleKanaKata() {
        activeState.batchEdit {
            it.isKanaKata = true
            it.isCharHalfWidth = false
        }
    }

    /**
     * Handles a [KeyCode.KANA_HALF_KATA] event
     */
    private fun handleKanaHalfKata() {
        activeState.batchEdit {
            it.isKanaKata = true
            it.isCharHalfWidth = true
        }
    }

    /**
     * Handles a [KeyCode.CHAR_WIDTH_SWITCHER] event
     */
    private fun handleCharWidthSwitch() {
        activeState.isCharHalfWidth = !activeState.isCharHalfWidth
    }

    /**
     * Handles a [KeyCode.CHAR_WIDTH_SWITCHER] event
     */
    private fun handleCharWidthFull() {
        activeState.isCharHalfWidth = false
    }

    /**
     * Handles a [KeyCode.CHAR_WIDTH_SWITCHER] event
     */
    private fun handleCharWidthHalf() {
        activeState.isCharHalfWidth = true
    }

    override fun onInputKeyDown(data: KeyData) {
        dismissPreferenceSuggestion()
        when (data.code) {
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.MOVE_START_OF_PAGE,
            KeyCode.MOVE_END_OF_PAGE,
            KeyCode.MOVE_START_OF_LINE,
            KeyCode.MOVE_END_OF_LINE -> {
                editorInstance.massSelection.begin()
            }
            KeyCode.SHIFT -> handleShiftDown(data)
        }
    }

    override fun onInputKeyUp(data: KeyData) = activeState.batchEdit {
        when (data.code) {
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.MOVE_START_OF_PAGE,
            KeyCode.MOVE_END_OF_PAGE,
            KeyCode.MOVE_START_OF_LINE,
            KeyCode.MOVE_END_OF_LINE -> {
                editorInstance.massSelection.end()
                handleArrow(data.code)
            }
            KeyCode.CAPS_LOCK -> handleCapsLock()
            KeyCode.CHAR_WIDTH_SWITCHER -> handleCharWidthSwitch()
            KeyCode.CHAR_WIDTH_FULL -> handleCharWidthFull()
            KeyCode.CHAR_WIDTH_HALF -> handleCharWidthHalf()
            KeyCode.CLIPBOARD_CUT -> editorInstance.performClipboardCut()
            KeyCode.CLIPBOARD_COPY -> editorInstance.performClipboardCopy()
            KeyCode.CLIPBOARD_PASTE -> editorInstance.performClipboardPaste()
            KeyCode.CLIPBOARD_SELECT -> handleClipboardSelect()
            KeyCode.CLIPBOARD_SELECT_ALL -> editorInstance.performClipboardSelectAll()
            KeyCode.CLIPBOARD_CLEAR_HISTORY -> clipboardManager.clearHistory()
            KeyCode.CLIPBOARD_CLEAR_FULL_HISTORY -> clipboardManager.clearFullHistory()
            KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP -> {
                if (prefs.clipboard.clearPrimaryClipDeletesLastItem.get()) {
                    clipboardManager.primaryClip?.let { clipboardManager.deleteClip(it) }
                }
                clipboardManager.updatePrimaryClip(null)
                appContext.showShortToast(R.string.clipboard__cleared_primary_clip)
            }
            KeyCode.TOGGLE_COMPACT_LAYOUT -> toggleOneHandedMode()
            KeyCode.COMPACT_LAYOUT_TO_LEFT -> {
                prefs.keyboard.oneHandedMode.set(OneHandedMode.START)
                toggleOneHandedMode()
            }
            KeyCode.COMPACT_LAYOUT_TO_RIGHT -> {
                prefs.keyboard.oneHandedMode.set(OneHandedMode.END)
                toggleOneHandedMode()
            }
            KeyCode.DELETE -> handleDelete()
            KeyCode.DELETE_WORD -> handleDeleteWord()
            KeyCode.ENTER -> handleEnter()
            KeyCode.IME_SHOW_UI -> FlorisImeService.showUi()
            KeyCode.IME_HIDE_UI -> FlorisImeService.hideUi()
            KeyCode.IME_PREV_SUBTYPE -> subtypeManager.switchToPrevSubtype()
            KeyCode.IME_NEXT_SUBTYPE -> subtypeManager.switchToNextSubtype()
            KeyCode.IME_UI_MODE_TEXT -> activeState.imeUiMode = ImeUiMode.TEXT
            KeyCode.IME_UI_MODE_MEDIA -> activeState.imeUiMode = ImeUiMode.MEDIA
            KeyCode.IME_UI_MODE_CLIPBOARD -> activeState.imeUiMode = ImeUiMode.CLIPBOARD
            KeyCode.IME_UI_MODE_VOICE -> { /* Disabled - voice input now works directly from smartbar */ }
            KeyCode.VOICE_INPUT -> {
                android.util.Log.d("KeyboardManager", "VOICE_INPUT key event received (should be handled in QuickAction)")
            }
            KeyCode.VOICE_START_RECORDING -> {
                android.util.Log.d("KeyboardManager", "VOICE_START_RECORDING key event received")
                startVoiceRecording()
            }
            KeyCode.VOICE_STOP_RECORDING -> {
                android.util.Log.d("KeyboardManager", "VOICE_STOP_RECORDING key event received")
                stopVoiceRecording()
            }
            KeyCode.KANA_SWITCHER -> handleKanaSwitch()
            KeyCode.KANA_HIRA -> handleKanaHira()
            KeyCode.KANA_KATA -> handleKanaKata()
            KeyCode.KANA_HALF_KATA -> handleKanaHalfKata()
            KeyCode.LANGUAGE_SWITCH -> handleLanguageSwitch()
            KeyCode.REDO -> editorInstance.performRedo()
            KeyCode.SETTINGS -> FlorisImeService.launchSettings()
            KeyCode.SHIFT -> handleShiftUp(data)
            KeyCode.SPACE -> handleSpace(data)
            KeyCode.SYSTEM_INPUT_METHOD_PICKER -> InputMethodUtils.showImePicker(appContext)
            KeyCode.SHOW_SUBTYPE_PICKER -> {
                appContext.keyboardManager.value.activeState.isSubtypeSelectionVisible = true
            }
            KeyCode.SYSTEM_PREV_INPUT_METHOD -> FlorisImeService.switchToPrevInputMethod()
            KeyCode.SYSTEM_NEXT_INPUT_METHOD -> FlorisImeService.switchToNextInputMethod()
            KeyCode.TOGGLE_SMARTBAR_VISIBILITY -> {
                prefs.smartbar.enabled.let { it.set(!it.get()) }
            }
            KeyCode.TOGGLE_ACTIONS_OVERFLOW -> {
                activeState.isActionsOverflowVisible = !activeState.isActionsOverflowVisible
            }
            KeyCode.TOGGLE_ACTIONS_EDITOR -> {
                activeState.isActionsEditorVisible = !activeState.isActionsEditorVisible
            }
            KeyCode.TOGGLE_INCOGNITO_MODE -> handleToggleIncognitoMode()
            KeyCode.TOGGLE_AUTOCORRECT -> handleToggleAutocorrect()
            KeyCode.UNDO -> editorInstance.performUndo()
            KeyCode.VIEW_CHARACTERS -> activeState.keyboardMode = KeyboardMode.CHARACTERS
            KeyCode.VIEW_NUMERIC -> activeState.keyboardMode = KeyboardMode.NUMERIC
            KeyCode.VIEW_NUMERIC_ADVANCED -> activeState.keyboardMode =
                KeyboardMode.NUMERIC_ADVANCED
            KeyCode.VIEW_PHONE -> activeState.keyboardMode = KeyboardMode.PHONE
            KeyCode.VIEW_PHONE2 -> activeState.keyboardMode = KeyboardMode.PHONE2
            KeyCode.VIEW_SYMBOLS -> activeState.keyboardMode = KeyboardMode.SYMBOLS
            KeyCode.VIEW_SYMBOLS2 -> activeState.keyboardMode = KeyboardMode.SYMBOLS2
            else -> {
                if (activeState.imeUiMode == ImeUiMode.MEDIA) {
                    nlpManager.getAutoCommitCandidate()?.let { commitCandidate(it) }
                    editorInstance.commitText(data.asString(isForDisplay = false))
                    return@batchEdit
                }
                when (activeState.keyboardMode) {
                    KeyboardMode.NUMERIC,
                    KeyboardMode.NUMERIC_ADVANCED,
                    KeyboardMode.PHONE,
                    KeyboardMode.PHONE2 -> when (data.type) {
                        KeyType.CHARACTER,
                        KeyType.NUMERIC -> {
                            val text = data.asString(isForDisplay = false)
                            editorInstance.commitText(text)
                        }
                        else -> when (data.code) {
                            KeyCode.PHONE_PAUSE,
                            KeyCode.PHONE_WAIT -> {
                                val text = data.asString(isForDisplay = false)
                                editorInstance.commitText(text)
                            }
                        }
                    }
                    else -> when (data.type) {
                        KeyType.CHARACTER, KeyType.NUMERIC ->{
                            val text = data.asString(isForDisplay = false)
                            if (!UCharacter.isUAlphabetic(UCharacter.codePointAt(text, 0))) {
                                nlpManager.getAutoCommitCandidate()?.let { commitCandidate(it) }
                            }
                            editorInstance.commitChar(text)
                        }
                        else -> {
                            flogError(LogTopic.KEY_EVENTS) { "Received unknown key: $data" }
                        }
                    }
                }
                if (activeState.inputShiftState != InputShiftState.CAPS_LOCK && !inputEventDispatcher.isPressed(
                        KeyCode.SHIFT)) {
                    activeState.inputShiftState = InputShiftState.UNSHIFTED
                }
            }
        }
    }

    override fun onInputKeyCancel(data: KeyData) {
        when (data.code) {
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.MOVE_START_OF_PAGE,
            KeyCode.MOVE_END_OF_PAGE,
            KeyCode.MOVE_START_OF_LINE,
            KeyCode.MOVE_END_OF_LINE -> {
                editorInstance.massSelection.end()
            }
            KeyCode.SHIFT -> handleShiftCancel()
        }
    }

    override fun onInputKeyRepeat(data: KeyData) {
        FlorisImeService.inputFeedbackController()?.keyRepeatedAction(data)
        when (data.code) {
            KeyCode.ARROW_DOWN,
            KeyCode.ARROW_LEFT,
            KeyCode.ARROW_RIGHT,
            KeyCode.ARROW_UP,
            KeyCode.MOVE_START_OF_PAGE,
            KeyCode.MOVE_END_OF_PAGE,
            KeyCode.MOVE_START_OF_LINE,
            KeyCode.MOVE_END_OF_LINE -> handleArrow(data.code)
            else -> onInputKeyUp(data)
        }
    }

    private fun reevaluateDebugFlags() {
        val devtoolsEnabled = prefs.devtools.enabled.get()
        activeState.batchEdit {
            activeState.debugShowDragAndDropHelpers = devtoolsEnabled && prefs.devtools.showDragAndDropHelpers.get()
        }
    }

    fun onHardwareKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                handleHardwareKeyboardSpace()
                return true
            }
            KeyEvent.KEYCODE_ENTER -> {
                handleEnter()
                return true
            }
            else -> return false
        }
    }

    inner class KeyboardManagerResources {
        val composers = MutableLiveData<Map<ExtensionComponentName, Composer>>(emptyMap())
        val currencySets = MutableLiveData<Map<ExtensionComponentName, CurrencySet>>(emptyMap())
        val layouts = MutableLiveData<Map<LayoutType, Map<ExtensionComponentName, LayoutArrangementComponent>>>(emptyMap())
        val popupMappings = MutableLiveData<Map<ExtensionComponentName, PopupMappingComponent>>(emptyMap())
        val punctuationRules = MutableLiveData<Map<ExtensionComponentName, PunctuationRule>>(emptyMap())
        val subtypePresets = MutableLiveData<List<SubtypePreset>>(emptyList())

        private val anyChangedGuard = Mutex(locked = false)
        val anyChanged = MutableLiveData(Unit)

        init {
            scope.launch(Dispatchers.Main.immediate) {
                extensionManager.keyboardExtensions.observeForever { keyboardExtensions ->
                    scope.launch {
                        anyChangedGuard.withLock {
                            parseKeyboardExtensions(keyboardExtensions)
                        }
                    }
                }
            }
        }

        private fun parseKeyboardExtensions(keyboardExtensions: List<KeyboardExtension>) {
            val localComposers = mutableMapOf<ExtensionComponentName, Composer>()
            val localCurrencySets = mutableMapOf<ExtensionComponentName, CurrencySet>()
            val localLayouts = mutableMapOf<LayoutType, MutableMap<ExtensionComponentName, LayoutArrangementComponent>>()
            val localPopupMappings = mutableMapOf<ExtensionComponentName, PopupMappingComponent>()
            val localPunctuationRules = mutableMapOf<ExtensionComponentName, PunctuationRule>()
            val localSubtypePresets = mutableListOf<SubtypePreset>()
            for (layoutType in LayoutType.entries) {
                localLayouts[layoutType] = mutableMapOf()
            }
            for (keyboardExtension in keyboardExtensions) {
                keyboardExtension.composers.forEach { composer ->
                    localComposers[ExtensionComponentName(keyboardExtension.meta.id, composer.id)] = composer
                }
                keyboardExtension.currencySets.forEach { currencySet ->
                    localCurrencySets[ExtensionComponentName(keyboardExtension.meta.id, currencySet.id)] = currencySet
                }
                keyboardExtension.layouts.forEach { (type, layoutComponents) ->
                    for (layoutComponent in layoutComponents) {
                        localLayouts[LayoutType.entries.first { it.id == type }]!![ExtensionComponentName(keyboardExtension.meta.id, layoutComponent.id)] = layoutComponent
                    }
                }
                keyboardExtension.popupMappings.forEach { popupMapping ->
                    localPopupMappings[ExtensionComponentName(keyboardExtension.meta.id, popupMapping.id)] = popupMapping
                }
                keyboardExtension.punctuationRules.forEach { punctuationRule ->
                    localPunctuationRules[ExtensionComponentName(keyboardExtension.meta.id, punctuationRule.id)] = punctuationRule
                }
                localSubtypePresets.addAll(keyboardExtension.subtypePresets)
            }
            localSubtypePresets.sortBy { it.locale.displayName() }
            for (languageCode in listOf("en-CA", "en-AU", "en-UK", "en-US")) {
                val index: Int = localSubtypePresets.indexOfFirst { it.locale.languageTag() == languageCode }
                if (index > 0) {
                    localSubtypePresets.add(0, localSubtypePresets.removeAt(index))
                }
            }
            subtypePresets.postValue(localSubtypePresets)
            composers.postValue(localComposers)
            currencySets.postValue(localCurrencySets)
            layouts.postValue(localLayouts)
            popupMappings.postValue(localPopupMappings)
            punctuationRules.postValue(localPunctuationRules)
            anyChanged.postValue(Unit)
        }
    }

    private inner class ComputingEvaluatorImpl(
        override val version: Int,
        override val keyboard: Keyboard,
        override val editorInfo: FlorisEditorInfo,
        override val state: KeyboardState,
        override val subtype: Subtype,
    ) : ComputingEvaluator {

        override fun context(): Context = appContext

        val androidKeyguardManager = context().systemService(AndroidKeyguardManager::class)

        override fun displayLanguageNamesIn(): DisplayLanguageNamesIn {
            return prefs.localization.displayLanguageNamesIn.get()
        }

        override fun evaluateEnabled(data: KeyData): Boolean {
            return when (data.code) {
                KeyCode.CLIPBOARD_COPY,
                KeyCode.CLIPBOARD_CUT -> {
                    state.isSelectionMode && editorInfo.isRichInputEditor
                }
                KeyCode.CLIPBOARD_PASTE -> {
                    !androidKeyguardManager.let { it.isDeviceLocked || it.isKeyguardLocked }
                        && clipboardManager.canBePasted(clipboardManager.primaryClip)
                }
                KeyCode.CLIPBOARD_CLEAR_PRIMARY_CLIP -> {
                    clipboardManager.canBePasted(clipboardManager.primaryClip)
                }
                KeyCode.CLIPBOARD_SELECT_ALL -> {
                    editorInfo.isRichInputEditor
                }
                KeyCode.TOGGLE_INCOGNITO_MODE -> when (prefs.suggestion.incognitoMode.get()) {
                    IncognitoMode.FORCE_OFF, IncognitoMode.FORCE_ON -> false
                    IncognitoMode.DYNAMIC_ON_OFF -> !editorInfo.imeOptions.flagNoPersonalizedLearning
                }
                KeyCode.LANGUAGE_SWITCH -> {
                    subtypeManager.subtypes.size > 1
                }
                else -> true
            }
        }

        override fun evaluateVisible(data: KeyData): Boolean {
            return when (data.code) {
                KeyCode.IME_UI_MODE_TEXT,
                KeyCode.IME_UI_MODE_MEDIA -> {
                    val tempUtilityKeyAction = when {
                        prefs.keyboard.utilityKeyEnabled.get() -> prefs.keyboard.utilityKeyAction.get()
                        else -> UtilityKeyAction.DISABLED
                    }
                    when (tempUtilityKeyAction) {
                        UtilityKeyAction.DISABLED,
                        UtilityKeyAction.SWITCH_LANGUAGE,
                        UtilityKeyAction.SWITCH_KEYBOARD_APP -> false
                        UtilityKeyAction.SWITCH_TO_EMOJIS -> true
                        UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS -> !shouldShowLanguageSwitch()
                    }
                }
                KeyCode.LANGUAGE_SWITCH -> {
                    val tempUtilityKeyAction = when {
                        prefs.keyboard.utilityKeyEnabled.get() -> prefs.keyboard.utilityKeyAction.get()
                        else -> UtilityKeyAction.DISABLED
                    }
                    when (tempUtilityKeyAction) {
                        UtilityKeyAction.DISABLED,
                        UtilityKeyAction.SWITCH_TO_EMOJIS -> false
                        UtilityKeyAction.SWITCH_LANGUAGE,
                        UtilityKeyAction.SWITCH_KEYBOARD_APP -> true
                        UtilityKeyAction.DYNAMIC_SWITCH_LANGUAGE_EMOJIS -> shouldShowLanguageSwitch()
                    }
                }
                else -> true
            }
        }

        override fun isSlot(data: KeyData): Boolean {
            return CurrencySet.isCurrencySlot(data.code)
        }

        override fun slotData(data: KeyData): KeyData? {
            return subtypeManager.getCurrencySet(subtype).getSlot(data.code)
        }

        fun asSmartbarQuickActionsEvaluator(): ComputingEvaluatorImpl {
            return ComputingEvaluatorImpl(
                version = version,
                keyboard = SmartbarQuickActionsKeyboard,
                editorInfo = editorInfo,
                state = state,
                subtype = Subtype.DEFAULT,
            )
        }
    }

    /**
     * Starts voice recording and gathers context information.
     */
    private var voiceAuthJob: Job? = null
    private var voiceFieldGeneration = 0L
    @Volatile private var voiceInputVisible = true
    @Volatile private var voiceOutputDetached = false
    @Volatile private var voiceBackgroundProtected = false
    private var realtimeTranscriptPrefix: String? = null

    fun shouldShowVoiceUi(): Boolean = !voiceOutputDetached &&
        (_isVoiceRecording.value || _isVoiceProcessing.value || _isVoiceRetryable.value || _voiceFinalText.value != null)

    fun onVoiceInputViewShown() { voiceInputVisible = true }

    /** Hiding a view does not cancel a dictation or an in-flight final result. */
    fun onVoiceInputViewHidden() {
        voiceInputVisible = false
        voiceAudioPreparationEnabled = false
        preparedVoiceRecorder.clear()
        voiceButtonGesture.reset()
        if (_isVoiceRecording.value) {
            if (!voiceBackgroundProtected) {
                _voiceAssistantMessage.value = "Finishing recording; Android could not keep the microphone active in the background."
                stopVoiceRecording()
            }
        } else if (!_isVoiceProcessing.value) {
            voiceAuthJob?.cancel()
            if (realtimeStartJob?.isActive == true) {
                realtimeGeneration++
                realtimeStartJob?.cancel()
                cleanupRealtimeCapture()
            }
        }
    }

    /** A notification stop cannot insert into an unrelated or hidden field. */
    fun finishVoiceRecordingFromNotification() {
        if (!voiceInputVisible) voiceOutputDetached = true
        stopVoiceRecording()
    }

    private fun stopVoiceRecordingProtection() {
        val generation = realtimeGeneration
        scope.launch(Dispatchers.Main.immediate) {
            if (generation == realtimeGeneration) {
                voiceBackgroundProtected = false
                FlorisImeService.endVoiceRecordingProtection()
            }
        }
    }

    fun onVoiceFieldChanged() {
        dismissPreferenceSuggestion()
        voiceFieldGeneration++
        voiceAuthJob?.cancel()
        if (_isVoiceRecording.value || _isVoiceProcessing.value) voiceOutputDetached = true
    }

    /** Preserve recovery through view recreation only for a verified input session. Metadata alone
     * is insufficient for anonymous web fields (many share fieldId=0 and the same InputConnection). */
    fun onVoiceInputStarted(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        if (_preferenceSuggestion.value != null && preferenceFieldIdentity !== info) dismissPreferenceSuggestion()
        val snapshot = lastVoiceContext ?: run {
            if (!_isVoiceRecording.value && !_isVoiceProcessing.value) {
                _isVoiceRetryable.value = false
                _voiceFinalText.value = null
                _voiceLiveTranscript.value = ""
                _voiceErrorMessage.value = null
                _voiceAssistantMessage.value = ""
                restoreUiModeAfterVoice()
            }
            return
        }
        val same = app.whisperme.keyboard.ime.voice.sameVoiceField(
            snapshot.editorIdentity === info, snapshot.fieldGeneration == voiceFieldGeneration,
            voiceInputConnection === FlorisImeService.currentInputConnection(),
            snapshot.packageName == info.packageName && snapshot.fieldId == info.fieldId && snapshot.fieldName == info.fieldName)
        if (!same) {
            if (_isVoiceRecording.value || _isVoiceProcessing.value) {
                // Preserve capture/processing, but never move its result to another editor.
                voiceOutputDetached = true
                restoreUiModeAfterVoice()
                return
            }
            cleanupVoiceState()
            _isVoiceRetryable.value = false
            _voiceFinalText.value = null
            _voiceLiveTranscript.value = ""
            _voiceErrorMessage.value = null
            _voiceAssistantMessage.value = ""
            lastVoiceContext = null
            voiceInputConnection = null
            restoreUiModeAfterVoice()
        }
        // restartInput is not proof of identity: WebViews may reuse connection/field metadata.
        // Keep the old immutable identity instead of rebinding recovery to a replacement editor.
    }

    private val voiceButtonGesture = VoiceButtonGesture()

    fun voiceButtonDown() {
        if (voiceButtonGesture.down(_isVoiceRecording.value,
                _isVoiceProcessing.value || _isVoiceRetryable.value ||
                    (voiceAuthJob?.isActive == true || realtimeStartJob?.isActive == true) && !_isVoiceRecording.value)) startVoiceRecording()
    }

    fun voiceButtonUp(heldMs: Long, canceled: Boolean = false) {
        when (voiceButtonGesture.up(heldMs, canceled)) {
            VoiceButtonGesture.Release.CANCEL -> { cancelVoiceProcessing(); return }
            VoiceButtonGesture.Release.NONE -> return
            VoiceButtonGesture.Release.FINISH -> if (_isVoiceRecording.value) {
                voiceButtonGesture.consumeFinish()
                stopVoiceRecording()
            }
            VoiceButtonGesture.Release.LATCH -> Unit
        }
        if (_isVoiceRecording.value || _isVoiceProcessing.value || _isVoiceRetryable.value) {
            rememberUiModeBeforeVoice()
            activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
        }
    }

    private fun voiceCaptureStarted() {
        // Keep the original pointer node mounted until release, including asynchronous startup.
        if (!voiceButtonGesture.held) {
            rememberUiModeBeforeVoice()
            activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
        }
        if (voiceButtonGesture.consumeFinish()) stopVoiceRecording()
    }
    private fun startVoiceRecording() {
        dismissPreferenceSuggestion()
        if (voiceAuthJob?.isActive == true || _isVoiceRecording.value || _isVoiceProcessing.value) return
        val originalConnection = FlorisImeService.currentInputConnection()
        val originalEditor = editorInstance.activeInfo.base
        val originalGeneration = voiceFieldGeneration
        voiceAuthJob = scope.launch(Dispatchers.Main.immediate) {
            val manager = Auth0Manager.getInstance(appContext)
            val ready = kotlinx.coroutines.withTimeoutOrNull(15_000) { manager.getBackendAccessToken(voiceCapabilitiesUrl()) } != null
            if (!ready) {
                handleVoiceError(if (manager.sessionStatus.value == Auth0Manager.SessionStatus.SIGN_IN_REQUIRED)
                    "Please sign in before recording. No audio was recorded."
                    else "Could not check your session. Check your connection and retry; no audio was recorded.",
                    manager.sessionStatus.value == Auth0Manager.SessionStatus.SIGN_IN_REQUIRED)
                return@launch
            }
            if (FlorisImeService.currentInputConnection() !== originalConnection ||
                editorInstance.activeInfo.base !== originalEditor || voiceFieldGeneration != originalGeneration) return@launch
            startVoiceRecordingWithSession()
        }
    }

    private fun startVoiceRecordingWithSession() {
        android.util.Log.d("KeyboardManager", "startVoiceRecording() called")
        if (_isVoiceRecording.value || _isVoiceProcessing.value || realtimeStartJob?.isActive == true) {
            flogInfo { "Voice recording already in progress" }
            android.util.Log.d("KeyboardManager", "Voice recording already in progress")
            return
        }

        beginVoiceHistory()
        voiceOutputDetached = false
        realtimeGeneration++
        realtimeTranscriptPrefix = null
        _voiceRetryStatus.value = ""
        _voiceBudgetStatus.value = ""
        _voiceConnectionStatus.value = ""
        _voiceErrorMessage.value = null
        _isVoiceAuthError.value = false
        _isVoiceRetryable.value = false
        voiceWindowBefore = 2_000
        voiceWindowAfter = 2_000
        _voiceProcessingStatus.value = ""
        _voiceAssistantMessage.value = ""
        _voiceLiveTranscript.value = ""
        _voiceFinalText.value = null
        if (prefs.voiceInput.transcriptionProvider.get() != VoiceTranscriptionProvider.GROQ_BATCH) {
            startRealtimeVoiceRecording()
            return
        }

        val editorInfo = editorInstance.activeInfo
        val editorContent = editorInstance.activeContent
        val inputConnection = FlorisImeService.currentInputConnection()

        flogInfo { "=== VOICE RECORDING START ===" }
        flogInfo { "Package: ${editorInfo.packageName}" }

        // Check microphone permission first
        if (!hasRecordAudioPermission()) {
            finishVoiceHistory("failed", "Microphone permission required")
            flogError { "RECORD_AUDIO permission not granted" }
            wasPendingVoiceInput = true // Remember we wanted to start voice input
            requestMicrophonePermission()
            return
        }

        // Check if microphone is available
        if (isMicrophoneBusy()) {
            finishVoiceHistory("failed", "Microphone is in use by another app")
            flogError { "Microphone is currently in use by another app" }
            appContext.showShortToast("🎤 Microphone in use by another app")
            return
        }

        // Start the actual recording
        try {
            val voiceContext = buildVoiceContext(editorInfo, editorContent, inputConnection)
            requireSupportedVoiceContext(voiceContext)
            voiceContextWindow(voiceContext.fullText, voiceContext.cursorPosition,
                voiceContext.cursorPosition + voiceContext.selectedText.length)
            lastVoiceContext = voiceContext
            if (voiceContext.insertOnly) appContext.showShortToast("Insertion-only dictation: editing existing text is unavailable in this field.")
            voiceInputConnection = inputConnection
            // Create output file (using .wav for optimal Groq performance)
            voiceRecordingFile = File(appContext.cacheDir, "voice_recording_${System.currentTimeMillis()}.wav")
            flogInfo { "Created audio file: ${voiceRecordingFile?.absolutePath}" }

            // Initialize MediaRecorder with better configuration
            voiceBackgroundProtected = FlorisImeService.beginVoiceRecordingProtection()
            mediaRecorder = MediaRecorder().apply {
                try {
                    flogInfo { "Setting audio source..." }
                    // Try different audio sources as fallbacks
                    val audioSources = listOf(
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.DEFAULT,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    )

                    var audioSourceSet = false
                    for (source in audioSources) {
                        try {
                            flogInfo { "Trying audio source: $source" }
                            setAudioSource(source)
                            flogInfo { "Audio source $source set successfully" }
                            audioSourceSet = true
                            break
                        } catch (e: Exception) {
                            flogInfo { "Audio source $source failed: ${e.message}" }
                        }
                    }

                    if (!audioSourceSet) {
                        throw RuntimeException("No audio source available")
                    }

                    flogInfo { "Setting output format..." }
                    setOutputFormat(MediaRecorder.OutputFormat.DEFAULT) // Use default for WAV compatibility
                    flogInfo { "Output format set successfully" }

                    flogInfo { "Setting audio encoder..." }
                    setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT) // Use default encoder for WAV
                    flogInfo { "Audio encoder set successfully" }

                    flogInfo { "Setting audio encoding bitrate..." }
                    setAudioEncodingBitRate(16000) // 16 Kbps as per working config
                    flogInfo { "Audio encoding bitrate set successfully" }

                    flogInfo { "Setting audio sampling rate..." }
                    setAudioSamplingRate(16000) // 16kHz as per working config
                    flogInfo { "Audio sampling rate set successfully" }

                    flogInfo { "Setting audio channels..." }
                    setAudioChannels(1) // Mono as per working config
                    flogInfo { "Audio channels set successfully" }

                    flogInfo { "Setting output file: ${voiceRecordingFile?.absolutePath}" }
                    setOutputFile(voiceRecordingFile?.absolutePath)
                    flogInfo { "Output file set successfully" }

                    flogInfo { "Preparing MediaRecorder..." }
                    prepare()
                    flogInfo { "MediaRecorder prepared successfully" }

                    flogInfo { "Starting recording..." }
                    start()
                    flogInfo { "MediaRecorder started successfully!" }
                } catch (e: Exception) {
                    flogError { "MediaRecorder setup failed at step: ${e.message}" }
                    flogError { "Exception details: ${e.javaClass.simpleName}" }
                    throw e
                }
            }

            _isVoiceRecording.value = true

            // Switch to voice recording layout
            voiceCaptureStarted()

            flogInfo { "Voice recording started successfully" }
            flogInfo { "Output file: ${voiceRecordingFile?.absolutePath}" }
            flogInfo { "Switched from ${previousUiModeBeforeVoice} to VOICE_RECORDING mode" }

            flogInfo { "Context gathered: ${voiceContext.packageName}, field: ${voiceContext.fieldType}" }

            // Note: Removed toast to avoid occluding text input - recording state is shown in button

        } catch (e: SecurityException) {
            finishVoiceHistory("failed", "Permission denied for voice recording: ${e.message}")
            stopVoiceRecordingProtection()
            flogError { "Permission denied for voice recording: ${e.message}" }
            _isVoiceRecording.value = false
            voiceRecordingFile = null
            mediaRecorder = null

            // Restore previous UI mode on error
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                previousUiModeBeforeVoice = null
            }

            appContext.showShortToast("❌ Microphone permission required")
        } catch (e: Exception) {
            finishVoiceHistory("failed", "Failed to start voice recording: ${e.message}")
            stopVoiceRecordingProtection()
            flogError { "Failed to start voice recording: ${e.message}" }
            flogError { "Exception type: ${e.javaClass.simpleName}" }
            e.printStackTrace()
            _isVoiceRecording.value = false
            voiceRecordingFile = null
            mediaRecorder = null

            // Restore previous UI mode on error
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                previousUiModeBeforeVoice = null
            }

            appContext.showShortToast("❌ Recording failed: ${e.message}")
        }
    }

    /**
     * Stops voice recording, processes audio, and sends to backend for processing.
     */
    private fun stopVoiceRecording() {
        if (!_isVoiceRecording.value) {
            flogInfo { "No voice recording in progress" }
            return
        }

        historyStopped()
        if (prefs.voiceInput.transcriptionProvider.get() != VoiceTranscriptionProvider.GROQ_BATCH) {
            scope.launch {
                stopRealtimeVoiceRecording()
            }
            return
        }

        val editorInfo = editorInstance.activeInfo
        val editorContent = editorInstance.activeContent
        val inputConnection = FlorisImeService.currentInputConnection()

        flogInfo { "=== VOICE RECORDING STOP ===" }

        try {
            // Stop MediaRecorder
            flogInfo { "Stopping MediaRecorder..." }
            mediaRecorder?.apply {
                stop()
                release()
                flogInfo { "MediaRecorder stopped and released" }
            }
            mediaRecorder = null
            _isVoiceRecording.value = false
            stopVoiceRecordingProtection()

            // Note: Removed toast to avoid occluding text input - processing state is shown in button

            val voiceContext = checkNotNull(lastVoiceContext) { "Original editor snapshot is unavailable" }

            flogInfo { "Recording stopped, processing audio..." }
            flogInfo { "File: ${voiceRecordingFile?.absolutePath}" }
            flogInfo { "File exists: ${voiceRecordingFile?.exists()}" }
            flogInfo { "File size: ${voiceRecordingFile?.length()} bytes" }
            flogInfo { "Context: ${voiceContext.packageName} - ${voiceContext.fieldType}" }

            // Process the audio file
            voiceRecordingFile?.let { audioFile ->
                if (audioFile.exists() && audioFile.length() > 0) {
                    flogInfo { "Audio file is valid, starting processing..." }
                    _isVoiceProcessing.value = true
                    scope.launch {
                        processVoiceRecording(audioFile, voiceContext)
                    }
                } else {
                    finishVoiceHistory("failed", "No audio recorded")
                    flogError { "Audio file is invalid: exists=${audioFile.exists()}, size=${audioFile.length()}" }
                    appContext.showShortToast("❌ No audio recorded")
                    _isVoiceProcessing.value = false
                }
            } ?: run {
                finishVoiceHistory("failed", "No audio file found")
                flogError { "No audio file to process" }
                appContext.showShortToast("❌ No audio file found")
                _isVoiceProcessing.value = false
            }

        } catch (e: RuntimeException) {
            finishVoiceHistory("failed", "Failed to stop MediaRecorder: ${e.message}")
            flogError { "Failed to stop MediaRecorder: ${e.message}" }
            flogError { "Exception type: ${e.javaClass.simpleName}" }
            e.printStackTrace()
            _isVoiceRecording.value = false
            mediaRecorder?.release()
            mediaRecorder = null

            // Restore previous UI mode on error
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                previousUiModeBeforeVoice = null
            }

            appContext.showShortToast("❌ Failed to stop recording")
        } catch (e: Exception) {
            finishVoiceHistory("failed", "Failed to stop voice recording: ${e.message}")
            flogError { "Failed to stop voice recording: ${e.message}" }
            flogError { "Exception type: ${e.javaClass.simpleName}" }
            e.printStackTrace()
            _isVoiceRecording.value = false

            // Restore previous UI mode on error
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                previousUiModeBeforeVoice = null
            }

            appContext.showShortToast("❌ Recording error: ${e.message}")
        } finally {
            // Clean up will happen in processVoiceRecording or here if failed
            if (!_isVoiceProcessing.value) {
                voiceRecordingFile?.delete()
                voiceRecordingFile = null
            }
        }
    }

    private fun startRealtimeVoiceRecording(replay: Boolean = false, authRetried: Boolean = false) {
        if (replay && historyEntry == null) beginVoiceHistory()
        val generation = ++realtimeGeneration
        _voiceErrorMessage.value = null
        _isVoiceAuthError.value = false
        _isVoiceRetryable.value = false
        val startup = app.whisperme.keyboard.ime.voice.VoiceStartupTrace(android.os.SystemClock::elapsedRealtime) { stage, elapsed ->
            if (generation == realtimeGeneration) {
                sampleVoiceHistory("startup.$stage", 0, "${elapsed}ms")
                if (BuildConfig.DEBUG) android.util.Log.i("VoiceStartup", "attempt=$generation stage=$stage elapsedMs=$elapsed")
            }
        }
        voiceWindowBefore = 2_000
        voiceWindowAfter = 2_000
        voiceContextExpansions = 0
        _voiceProcessingStatus.value = ""
        _voiceAssistantMessage.value = ""
        realtimeRevision = 0L
        val editorInfo = editorInstance.activeInfo
        val editorContent = editorInstance.activeContent
        val inputConnection = FlorisImeService.currentInputConnection()
        if (isPasswordField(editorInfo)) { handleVoiceError("Voice input is unavailable in password fields"); return }

        if (!hasRecordAudioPermission()) {
            finishVoiceHistory("failed", "Microphone permission required")
            wasPendingVoiceInput = true
            requestMicrophonePermission()
            return
        }
        if (!hasNetworkConnection()) {
            handleVoiceError("No internet connection available")
            return
        }

        realtimeStartJob = scope.launch(Dispatchers.IO) {
            // Token refresh overlaps the fresh editor snapshot and AudioRecord initialization.
            // This child is cancelled with startup; no background authentication job leaks across attempts.
            val localSession = LocalVoiceSession.configured(appContext)
            val tokenReady = async {
                try {
                    val token = Auth0Manager.getInstance(appContext).getBackendAccessToken(voiceCapabilitiesUrl())
                    startup.mark("token_ready")
                    Result.success(token)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Observe failures after capture setup so the ordinary retry/cleanup path owns them.
                    Result.failure<String?>(e)
                }
            }
            try {
                val authToken = tokenReady.await().getOrThrow()
                check(generation == realtimeGeneration) { "Session cancelled" }
                if (authToken == null) {
                    if (Auth0Manager.getInstance(appContext).sessionStatus.value == Auth0Manager.SessionStatus.SIGN_IN_REQUIRED)
                        throw VoiceSignInRequired(refreshAllowed = false)
                    error("Could not check your session; check your connection and retry")
                }
                if (realtimeTranscriptPrefix != null) check(Auth0Manager.getInstance(appContext).backendSupportsTranscriptPrefix) {
                    "This server cannot continue a saved transcript with new audio yet. Retry final processing or update the server."
                }
                val voiceContext = if (replay || realtimeTranscriptPrefix != null) checkNotNull(lastVoiceContext)
                    else buildVoiceContext(editorInfo, editorContent, inputConnection)
                requireSupportedVoiceContext(voiceContext)
                voiceContextWindow(voiceContext.fullText, voiceContext.cursorPosition,
                    voiceContext.cursorPosition + voiceContext.selectedText.length)
                if (voiceContext.insertOnly && !replay) app.whisperme.keyboard.ime.voice.showInsertionOnlyNotice(appContext)
                startup.mark("context_ready")
                val record = preparedVoiceRecorder.take()
                if (generation != realtimeGeneration) {
                    record.release()
                    return@launch
                }
                startup.mark("recorder_ready")

                val responseDeferred = CompletableDeferred<VoiceResponse>()
                val ready = CompletableDeferred<Unit>()
                realtimeReady = ready
                realtimeResponseDeferred = responseDeferred
                realtimeLastFinalText = null

                // Capture before token refresh and either websocket handshake. Never drop queued audio.
                val audioQueue = Channel<ByteArray>(1000) // 100 seconds: bounded queue covers 90-second provider startup retries.
                realtimeAudioQueue = audioQueue
                audioRecord = record
                lastVoiceContext = voiceContext
                if (!replay) voiceInputConnection = inputConnection
                lastBase64Audio = null
                if (!replay) {
                    retainedVoiceAudio.clear()
                    withContext(Dispatchers.Main) {
                        check(generation == realtimeGeneration) { "Session cancelled" }
                        voiceBackgroundProtected = FlorisImeService.beginVoiceRecordingProtection()
                    }
                    synchronized(record) {
                        check(generation == realtimeGeneration) { "Session cancelled" }
                        record.startRecording()
                    }
                }
                startup.mark(if (replay) "replay_ready" else "microphone_started")
                withContext(Dispatchers.Main) {
                    check(generation == realtimeGeneration) { "Session cancelled" }
                    if (!replay) _voiceLiveTranscript.value = realtimeTranscriptPrefix.orEmpty()
                    _isVoiceRetryable.value = false
                    _voiceErrorMessage.value = null
                    _isVoiceAuthError.value = false
                    _isVoiceRecording.value = true
                    _isVoiceProcessing.value = false
                    if (!voiceButtonGesture.held) {
                        rememberUiModeBeforeVoice()
                        activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
                    }
                }
                startup.mark("recording_ui_ready")
                // Publish ownership before the producer can read; cleanup always joins it before release.
                val producer = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        if (replay) {
                            for (frame in retainedVoiceAudio.snapshot()) audioQueue.send(frame)
                            return@launch
                        }
                        val buffer = ByteArray(3200)
                        while (_isVoiceRecording.value && generation == realtimeGeneration) {
                            val count = record.read(buffer, 0, buffer.size)
                            if (count < 0 && _isVoiceRecording.value) error("Microphone stopped providing audio")
                            if (count <= 0) break
                            startup.mark("first_pcm")
                            val retained = synchronized(retainedVoiceAudio) {
                                if (generation != realtimeGeneration) false
                                else { retainedVoiceAudio.append(buffer.copyOf(count)); true }
                            }
                            if (!retained) break
                            check(audioQueue.trySend(buffer.copyOf(count)).isSuccess) {
                                "Connection took too long; audio buffer is full. Please retry."
                            }
                        }
                    } catch (e: Exception) {
                        if (generation == realtimeGeneration && _isVoiceRecording.value) {
                            withContext(Dispatchers.Main) {
                                _isVoiceRecording.value = false
                                handleVoiceError(e.message ?: "Audio capture failed")
                                cleanupRealtimeCapture()
                            }
                        }
                    } finally {
                        audioQueue.close()
                    }
                }
                realtimeStreamingJob = producer
                producer.start()
                withContext(Dispatchers.Main) { if (generation == realtimeGeneration) voiceCaptureStarted() }

                val wsUrl = if (localSession) LocalVoiceSession.ENDPOINT else prefs.voiceInput.realtimeEndpoint.get().trim()
                val endpoint = java.net.URI(wsUrl)
                require(endpoint.scheme == "wss" || (endpoint.scheme == "ws" && endpoint.host in listOf("localhost", "127.0.0.1", "10.0.2.2"))) {
                    "Use wss:// for the WhisperMe server"
                }
                val request = Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer $authToken")
                    .header("User-Agent", "WhisperMe-Android/1.0")
                    .build()

                val socket = realtimeWsClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (generation != realtimeGeneration) { webSocket.cancel(); return }
                        startup.mark("backend_open")
                        startVoiceHeartbeat(webSocket, generation)
                        webSocket.send(buildRealtimeStartRequest(voiceContext))
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (generation == realtimeGeneration && realtimeResponseDeferred === responseDeferred) {
                            val event = runCatching { org.json.JSONObject(text) }.getOrNull()
                            if (event?.optString("type") == "session.started") startup.mark("provider_ready")
                            if (event?.optString("type") in listOf("transcript.partial", "transcript.final") &&
                                !event?.optString("text").isNullOrBlank()) startup.mark("first_text")
                            handleRealtimeWebSocketMessage(text, responseDeferred)
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!responseDeferred.isCompleted) {
                            ready.completeExceptionally(IllegalStateException("Realtime connection closed"))
                            responseDeferred.complete(VoiceResponse(success = false, transcription = null, finalText = null,
                                error = "Connection closed before final processing completed"))
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (realtimeResponseDeferred !== responseDeferred) return
                        ready.completeExceptionally(if (response?.code == 401) VoiceSignInRequired() else t)
                        if (!responseDeferred.isCompleted) {
                            responseDeferred.complete(
                                VoiceResponse(
                                    success = false,
                                    transcription = null,
                                    finalText = null,
                                    error = "Realtime connection failed: ${t.message}",
                                )
                            )
                        }
                        scope.launch(Dispatchers.Main) {
                            if (generation != realtimeGeneration) return@launch
                            if (_isVoiceRecording.value) {
                                _isVoiceRecording.value = false
                                if (response?.code != 401) cleanupRealtimeCapture()
                                if (response?.code != 401) handleVoiceError("Realtime connection failed: ${t.message}")
                            }
                        }
                    }
                })

                realtimeWebSocket = socket
                withTimeout(90_000L) { ready.await() }
                check(generation == realtimeGeneration) { "Session cancelled" }
                realtimeSenderJob = scope.launch(Dispatchers.IO) {
                    try {
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        var sentBytes = 0L
                        for (frame in audioQueue) {
                            // At most four seconds ahead of realtime: Meta rejects larger audio bursts.
                            val waitMs = (sentBytes + frame.size) / 32 - 4000 - (android.os.SystemClock.elapsedRealtime() - startedAt)
                            if (waitMs > 0) delay(waitMs)
                            check(generation == realtimeGeneration) { "Session cancelled" }
                            val chunk = Base64.encodeToString(frame, Base64.NO_WRAP)
                            check(socket.queueSize() <= 256000 && socket.send("""{"type":"audio.chunk","payload":{"audio":"$chunk"}}""")) {
                                "Voice connection cannot keep up"
                            }
                            sentBytes += frame.size
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        responseDeferred.complete(VoiceResponse(success = false, transcription = null,
                            finalText = null, error = e.message ?: "Audio transmission failed"))
                        withContext(Dispatchers.Main) {
                            if (generation == realtimeGeneration) {
                                _isVoiceRecording.value = false
                                handleVoiceError(e.message ?: "Audio transmission failed")
                                cleanupRealtimeCapture()
                            }
                        }
                    }
                }
                if (replay) scope.launch(Dispatchers.Main) { stopRealtimeVoiceRecording() }
            } catch (e: Exception) {
                if (generation != realtimeGeneration) return@launch
                if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) return@launch
                _isVoiceRecording.value = false
                cleanupRealtimeAudioRecordOnly(abort = true)
                if (e is VoiceSignInRequired && e.refreshAllowed && !authRetried &&
                    Auth0Manager.getInstance(appContext).getValidAccessToken(forceRefresh = true) != null) {
                    cleanupRealtimeCapture()
                    withContext(Dispatchers.Main) {
                        if (generation == realtimeGeneration) startRealtimeVoiceRecording(replay = true, authRetried = true)
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    _isVoiceRecording.value = false
                    cleanupRealtimeCapture()
                    if (e is VoiceSignInRequired) Auth0Manager.getInstance(appContext).requireSignIn()
                    handleVoiceError("Failed to start realtime recording: ${e.message}", e is VoiceSignInRequired)
                }
            } finally {
                tokenReady.cancel()
            }
        }
    }

    private class VoiceSignInRequired(val refreshAllowed: Boolean = true) : IllegalStateException("Sign in, then retry; your audio is retained")

    private suspend fun stopRealtimeVoiceRecording() {
        if (!_isVoiceRecording.value) return
        val generation = realtimeGeneration
        historyStopped()

        _isVoiceRecording.value = false
        _isVoiceProcessing.value = true

        try {
            cleanupRealtimeAudioRecordOnly()
            stopVoiceRecordingProtection()
            withTimeout(95_000L + retainedVoiceAudio.durationMillis()) {
                realtimeStartJob?.join()
                realtimeSenderJob?.join()
            }
            check(generation == realtimeGeneration && realtimeWebSocket?.send("""{"type":"session.stop"}""") == true) {
                "Voice connection ended before audio could be processed"
            }

            val deferred = realtimeResponseDeferred
            val response = try {
                if (deferred != null) {
                    withTimeout(160_000L) { deferred.await() }
                } else {
                    null
                }
            } catch (_: TimeoutCancellationException) {
                null
            }

            val finalResponse = response ?: VoiceResponse(success = false, transcription = null,
                finalText = null, error = "Final processing timed out; transcript has been preserved")

            withContext(Dispatchers.Main) {
                if (generation == realtimeGeneration) processVoiceResponse(finalResponse)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                if (generation == realtimeGeneration) handleVoiceError("Failed to stop realtime recording: ${e.message}")
            }
        } finally {
            if (generation == realtimeGeneration) cleanupRealtimeCapture()
        }
    }

    private fun handleRealtimeWebSocketMessage(
        message: String,
        responseDeferred: CompletableDeferred<VoiceResponse>,
    ) {
        val event = runCatching { org.json.JSONObject(message) }.getOrNull() ?: return
        recordVoiceMetadata(event.optJSONObject("diagnostics")?.toString(),
            event.optString("notice").takeIf { it.isNotBlank() })
        when (event.optString("type")) {
            "budget.warning" -> showVoiceBudgetWarning(event)
            "session.started" -> {
                historyEntry = historyEntry?.let { it.copy(
                    llmProvider = event.optString("llmProvider").takeIf { value -> value.isNotBlank() } ?: it.llmProvider,
                    llmModel = event.optString("llmModel").takeIf { value -> value.isNotBlank() } ?: it.llmModel,
                    resolvedLlm = event.optString("llmProvider").isNotBlank()) }
                historyEntry?.let(historyStore::save)
                realtimeReady?.complete(Unit)
            }
            "connection.pong" -> {
                if (event.has("nonce") && connectionHealth.pong(event.optLong("nonce"), android.os.SystemClock.elapsedRealtime()))
                    _voiceConnectionStatus.value = connectionHealth.description(!hasNetworkConnection())
            }
            "processing.retry" -> {
                _voiceRetryStatus.value = event.optString("text").takeIf { it.isNotBlank() }
                    ?: "${event.optString("stage")} provider retry ${event.optInt("attempt")}/${event.optInt("maxAttempts", 3)}"
                sampleVoiceHistory("processing.retry", realtimeRevision, _voiceRetryStatus.value)
            }
            "session.stopping" -> {
                _voiceAssistantMessage.value = event.optString("text", "Recording limit reached. Finishing your text…")
                sampleVoiceHistory("recording.limit", realtimeRevision, _voiceAssistantMessage.value)
                scope.launch(Dispatchers.Main) {
                    if (realtimeResponseDeferred === responseDeferred) stopRealtimeVoiceRecording()
                }
            }
            "transcript.partial", "transcript.final" -> {
                realtimeRevision = event.optLong("revision", realtimeRevision + 1)
                sampleVoiceHistory(event.optString("type"), realtimeRevision, event.optString("text"))
                _voiceLiveTranscript.value = event.optString("text")
                if (event.optString("type") == "transcript.final") realtimeLastFinalText = event.optString("text")
            }
            "usage.updated" -> {
                historyEntry = historyEntry?.copy(usageJson = event.optJSONObject("usage")?.toString())
                historyEntry?.let(historyStore::save)
            }
            "assistant.message" -> {
                if (event.optString("text").isNotBlank()) sampleVoiceHistory(
                    if (event.optString("kind") == "recording_limit") "recording.limit_warning" else "assistant.message",
                    event.optLong("revision"), event.optString("text"))
                if (event.optLong("revision") == realtimeRevision) _voiceAssistantMessage.value = event.optString("text")
            }
            "assistant.review" -> {
                sampleVoiceHistory("clarification.${event.optString("kind")}", event.optLong("revision"),
                    event.optString("text") + if (event.optBoolean("stale")) " [Earlier transcript revision; not shown live]" else "")
            }
            "processing.final_text" -> if (event.has("finalText") && !responseDeferred.isCompleted) {
                historyResponse(realtimeLastFinalText, event.getString("finalText"))
                historyEntry?.let(historyStore::save)
                responseDeferred.complete(VoiceResponse(success = true, transcription = realtimeLastFinalText,
                    finalText = event.getString("finalText"), error = null,
                    contextWindowJson = event.optJSONObject("contextWindow")?.toString(),
                    cursorPosition = if (event.has("cursorPosition") && !event.isNull("cursorPosition")) event.getInt("cursorPosition") else null).apply {
                        notice = event.optString("notice").takeIf { it.isNotBlank() }
                        preferenceSuggestionJson = event.optJSONObject("preferenceSuggestion")?.toString()
                    })
            }
            "processing.status" -> {
                _voiceProcessingStatus.value = when (event.optString("stage")) {
                    "gathering_context" -> "Gathering context…"
                    "formatting" -> "Formatting…"
                    else -> "Processing…"
                }
            }
            "context.request" -> {
                try {
                    check(voiceContextExpansions < 2) { "Too many context expansion requests" }
                    val snapshot = checkNotNull(lastVoiceContext) { "Original editor snapshot is unavailable" }
                    check(!snapshot.insertOnly) { "Context is unavailable in this insertion-only field" }
                    val before = event.getInt("beforeChars")
                    val after = event.getInt("afterChars")
                    val previousWindow = voiceContextWindow(snapshot.fullText, snapshot.cursorPosition,
                        snapshot.cursorPosition + snapshot.selectedText.length, voiceWindowBefore, voiceWindowAfter)
                    val expandedWindow = voiceContextWindow(snapshot.fullText, snapshot.cursorPosition,
                        snapshot.cursorPosition + snapshot.selectedText.length, before, after)
                    require(expandedWindow.contains(previousWindow)) { "Requested context would discard previously supplied text" }
                    val requestId = event.getString("requestId")
                    require(requestId.isNotBlank() && requestId.length <= 128) { "Invalid context request identifier" }
                    val contextJson = buildVoiceContextJson(snapshot, before, after)
                    check(realtimeWebSocket?.send(org.json.JSONObject().put("type", "context.provide")
                        .put("requestId", requestId).put("context", org.json.JSONObject(contextJson)).toString()) == true) {
                        "Could not provide the requested editor context"
                    }
                    voiceWindowBefore = before
                    voiceWindowAfter = after
                    voiceContextExpansions++
                } catch (e: Exception) {
                    realtimeWebSocket?.send("""{"type":"session.cancel"}""")
                    responseDeferred.complete(VoiceResponse(false, realtimeLastFinalText, null,
                        e.message ?: "Could not provide editor context"))
                }
            }
            "error", "session.closed" -> if (!responseDeferred.isCompleted) {
                val error = event.optString("error", "Session closed before final processing")
                historyEntry = historyEntry?.copy(failure = parseVoiceFailure(event.optJSONObject("failure")?.toString()))
                realtimeReady?.completeExceptionally(IllegalStateException(error))
                responseDeferred.complete(VoiceResponse(success = false, transcription = realtimeLastFinalText, finalText = null, error = error))
                scope.launch(Dispatchers.Main) {
                    if (realtimeResponseDeferred !== responseDeferred) return@launch
                    _isVoiceRecording.value = false
                    cleanupRealtimeCapture()
                    handleVoiceError(error)
                }
            }
        }
    }

    private fun extractJsonStringField(json: String, fieldName: String): String? {
        val pattern = """"$fieldName"\s*:\s*"((?:\\.|[^"\\])*)"""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.let { value ->
            value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }

    private fun extractNestedTranscriptText(json: String): String? {
        val pattern = """"transcript"\s*:\s*\{[^}]*"text"\s*:\s*"((?:\\.|[^"\\])*)"""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)?.let { value ->
            value.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
    }

    private fun String.escapeJson(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    private fun voiceCapabilitiesUrl(): String {
        if (prefs.voiceInput.transcriptionProvider.get() == VoiceTranscriptionProvider.GROQ_BATCH)
            return "https://process-voice.whisperme.app/capabilities"
        return runCatching {
            val endpoint = java.net.URI(prefs.voiceInput.realtimeEndpoint.get())
            java.net.URI(if (endpoint.scheme == "wss") "https" else "http", null,
                endpoint.host, endpoint.port, "/capabilities", null, null).toString()
        }.getOrDefault("") // Invalid configuration fails the preflight before recording.
    }

    private fun requireSupportedVoiceContext(context: VoiceContextData) {
        check(!context.insertOnly || Auth0Manager.getInstance(appContext).backendSupportsInsertOnly) {
            "This server has not confirmed insertion-only support. No audio will be recorded in this unreadable field; update the server and retry."
        }
    }

    private fun buildRealtimeStartRequest(context: VoiceContextData): String {
        requireSupportedVoiceContext(context)
        return org.json.JSONObject().put("type", "session.start").put("payload", org.json.JSONObject()
            .put("transcriptionProvider", currentTranscriptionProvider())
            .put("transcriptionModel", prefs.voiceInput.transcriptionModel.get())
            .apply { putVoiceRouting(this) }
            .put("mode", prefs.voiceInput.mode.get()).put("liveHints", prefs.voiceInput.liveHints.get())
            .apply { realtimeTranscriptPrefix?.let { put("transcriptPrefix", it) } }
            .put("appCategory", context.appCategory)
            .put("preferenceProfile", context.preferenceProfile?.let {
                org.json.JSONObject().put("instructions", it.instructions).put("scope", it.scope)
            })
            .put("usePreferences", context.usePreferences).put("diagnosticContext", context.diagnosticContext)
            .put("useDictionary", context.useDictionary)
            .put("dictionary", org.json.JSONArray(context.dictionary)).put("suggestPreferences", context.suggestPreferences)
            .put("context", org.json.JSONObject(buildVoiceContextJson(context)))).toString()
    }

    private suspend fun cleanupRealtimeAudioRecordOnly(abort: Boolean = false) {
        val record = audioRecord
        val job = realtimeStreamingJob
        audioRecord = null
        realtimeStreamingJob = null
        runCatching { record?.let { synchronized(it) { it.stop() } } }
        if (abort) {
            realtimeAudioQueue?.cancel()
            job?.cancelAndJoin()
        } else {
            job?.join()
        }
        record?.let { synchronized(it) { it.release() } }
        prepareVoiceAudioIfVisible()
    }

    @Synchronized private fun showVoiceBudgetWarning(event: org.json.JSONObject) {
        recordVoiceBudget(app.whisperme.keyboard.ime.voice.parseVoiceBudgetStatus(event.toString()))
        val message = event.optString("text").takeIf { it.isNotBlank() }
            ?: "WhisperMe has used 80% of its monthly processing budget."
        _voiceBudgetStatus.value = message
        sampleVoiceHistory("budget.warning", realtimeRevision, message)
        historyEntry = historyEntry?.copy(notice = message)
        historyEntry?.let(historyStore::save)
    }

    private fun startVoiceHeartbeat(socket: WebSocket, generation: Long) {
        heartbeatJob?.cancel()
        connectionHealth = app.whisperme.keyboard.ime.voice.VoiceConnectionHealth()
        if (!Auth0Manager.getInstance(appContext).backendSupportsHeartbeat) {
            _voiceConnectionStatus.value = "Connection timing unavailable on this server"
            return
        }
        heartbeatJob = scope.launch {
            while (generation == realtimeGeneration) {
                val nonce = connectionHealth.ping(android.os.SystemClock.elapsedRealtime())
                if (!socket.send(org.json.JSONObject().put("type", "connection.ping").put("nonce", nonce).toString())) {
                    _voiceConnectionStatus.value = "Connection to WhisperMe interrupted; cause unknown"
                    return@launch
                }
                _voiceConnectionStatus.value = connectionHealth.description(!hasNetworkConnection())
                delay(5000)
            }
        }
    }

    private fun cleanupRealtimeCapture() {
        val generation = realtimeGeneration
        heartbeatJob?.cancel()
        heartbeatJob = null
        realtimeSenderJob?.cancel()
        realtimeSenderJob = null
        realtimeAudioQueue?.cancel()
        realtimeAudioQueue = null
        val record = audioRecord
        val job = realtimeStreamingJob
        audioRecord = null
        realtimeStreamingJob = null
        scope.launch(Dispatchers.IO) {
            runCatching { record?.let { synchronized(it) { it.stop() } } }
            job?.cancelAndJoin()
            record?.let { synchronized(it) { it.release() } }
            withContext(Dispatchers.Main) {
                if (generation == realtimeGeneration) stopVoiceRecordingProtection()
            }
            prepareVoiceAudioIfVisible()
        }
        realtimeWebSocket?.close(1000, "finished")
        realtimeWebSocket = null
        realtimeReady?.cancel()
        realtimeReady = null
        realtimeResponseDeferred = null
    }

    /**
     * Builds voice context from current editor state for backend processing.
     */
    private fun buildVoiceContext(
        editorInfo: FlorisEditorInfo,
        editorContent: EditorContent,
        inputConnection: InputConnection?
    ): VoiceContextData {
        require(inputConnection != null) { "The text field is no longer connected" }
        require(!isPasswordField(editorInfo)) { "Voice input is unavailable in password fields" }
        val full = runCatching {
            inputConnection.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
                hintMaxChars = 1_000_001
            }, 0)
        }.getOrNull()
        val complete = full != null && full.startOffset == 0 && full.partialStartOffset == -1 &&
            full.partialEndOffset == -1 && full.text != null && full.text.length <= 1_000_000
        val extracted = if (complete) full else {
            val nearby = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 31) inputConnection.getSurroundingText(4096, 4096, 0) else null
            }.getOrNull()
            nearby?.takeIf { it.offset >= 0 }?.let {
                android.view.inputmethod.ExtractedText().apply {
                    text = it.text
                    startOffset = it.offset
                    selectionStart = it.selectionStart
                    selectionEnd = it.selectionEnd
                }
            }
        }
        val captured = extracted?.text?.toString().orEmpty()
        val capturedStart = minOf(extracted?.selectionStart ?: -1, extracted?.selectionEnd ?: -1)
        val capturedEnd = maxOf(extracted?.selectionStart ?: -1, extracted?.selectionEnd ?: -1)
        val insertOnly = !(capturedStart in 0..captured.length && capturedEnd in capturedStart..captured.length &&
            !app.whisperme.keyboard.ime.voice.splitsSurrogate(captured, capturedStart) &&
            !app.whisperme.keyboard.ime.voice.splitsSurrogate(captured, capturedEnd))
        // Empty strings here represent unavailable context, never an empty document to rewrite.
        val fullText = if (insertOnly) "" else captured
        val start = if (insertOnly) 0 else capturedStart
        val end = if (insertOnly) 0 else capturedEnd
        val textBeforeCursor = fullText.substring(0, start)
        val selectedText = fullText.substring(start, end)
        val textAfterCursor = fullText.substring(end)

        val appCategory = app.whisperme.keyboard.ime.voice.readVoiceAppCategories(prefs.voiceInput.appCategories.get())[editorInfo.packageName.orEmpty()]
        val context = VoiceContextData(
            textBeforeCursor = textBeforeCursor,
            textAfterCursor = textAfterCursor,
            selectedText = selectedText,
            cursorPosition = textBeforeCursor.length,
            fullText = fullText,
            packageName = editorInfo.packageName ?: "unknown",
            appCategory = appCategory,
            fieldType = determineFieldType(editorInfo),
            isMultiline = editorInfo.inputAttributes.flagTextMultiLine,
            fieldHint = editorInfo.base.hintText?.toString(),
            fieldLabel = editorInfo.base.label?.toString(),
            fieldName = editorInfo.base.fieldName,
            fieldId = editorInfo.base.fieldId,
            insertOnly = insertOnly,
            editorIdentity = editorInfo.base,
            fieldGeneration = voiceFieldGeneration,
            preferenceProfile = app.whisperme.keyboard.ime.voice.selectVoiceProfile(
                prefs.voiceInput.preferenceProfiles.get(), prefs.voiceInput.usePreferences.get(), editorInfo.packageName.orEmpty(), appCategory),
            usePreferences = prefs.voiceInput.usePreferences.get(),
            useDictionary = prefs.voiceInput.useDictionary.get(),
            dictionary = if (prefs.voiceInput.useDictionary.get()) app.whisperme.keyboard.ime.voice.readVoiceDictionary(prefs.voiceInput.dictionary.get()).also {
                app.whisperme.keyboard.ime.voice.requireVoiceDictionaryBudget(it)
            } else emptyList(),
            suggestPreferences = prefs.voiceInput.suggestPreferences.get(),
            diagnosticContext = prefs.voiceInput.diagnosticContext.get(),
            completeField = complete && !insertOnly,
            contextOffset = if (insertOnly) -1 else extracted!!.startOffset,
            nativeSelectionStart = if (insertOnly) -1 else extracted!!.startOffset + extracted.selectionStart,
            nativeSelectionEnd = if (insertOnly) -1 else extracted!!.startOffset + extracted.selectionEnd,
            isPasswordField = isPasswordField(editorInfo),
            isRichEditor = editorInfo.isRichInputEditor,
            keyboardMode = activeState.keyboardMode.toString(),
            inputShiftState = activeState.inputShiftState.toString(),
            locale = subtypeManager.activeSubtype.primaryLocale.toString(),
            contentMimeTypes = editorInfo.contentMimeTypes?.toList(),
            imeAction = editorInfo.imeOptions.action.toString(),
            flagNoEnterAction = editorInfo.imeOptions.flagNoEnterAction,
            flagNoPersonalizedLearning = editorInfo.imeOptions.flagNoPersonalizedLearning
        )

        return context
    }

    /**
     * Determines if the field is a password field.
     */
    private fun isPasswordField(editorInfo: FlorisEditorInfo): Boolean {
        return when (editorInfo.inputAttributes.variation) {
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD -> true
            else -> false
        }
    }

    /**
     * Determines the field type based on editor info.
     */
    private fun determineFieldType(editorInfo: FlorisEditorInfo): String {
        if (editorInfo.base.inputType and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_NUMBER) return "number"
        return when (editorInfo.inputAttributes.variation) {
            InputAttributes.Variation.EMAIL_ADDRESS,
            InputAttributes.Variation.WEB_EMAIL_ADDRESS -> "email"
            InputAttributes.Variation.PASSWORD,
            InputAttributes.Variation.WEB_PASSWORD,
            InputAttributes.Variation.VISIBLE_PASSWORD -> "password"
            InputAttributes.Variation.URI -> "url"
            InputAttributes.Variation.PERSON_NAME -> "name"
            else -> when {
                editorInfo.inputAttributes.flagTextMultiLine -> "multiline_text"
                editorInfo.packageName?.contains("sms", ignoreCase = true) == true -> "sms"
                editorInfo.packageName?.contains("mail", ignoreCase = true) == true -> "email_app"
                editorInfo.packageName?.contains("search", ignoreCase = true) == true -> "search"
                else -> "text"
            }
        }
    }

    /**
     * Processes the recorded audio file and sends it to the backend.
     */
    private suspend fun processVoiceRecording(audioFile: File, context: VoiceContextData) {
        val attemptId = historyEntry?.id
        try {
            flogInfo { "=== PROCESSING VOICE RECORDING ===" }
            flogInfo { "Audio file: ${audioFile.absolutePath}" }
            flogInfo { "File size: ${audioFile.length()} bytes" }
            flogInfo { "File exists: ${audioFile.exists()}" }

            if (!audioFile.exists() || audioFile.length() == 0L) {
                finishVoiceHistory("failed", "Invalid audio file")
                flogError { "Audio file is invalid: exists=${audioFile.exists()}, size=${audioFile.length()}" }
                withContext(Dispatchers.Main) {
                    appContext.showShortToast("❌ Invalid audio file")
                }
                return
            }

            // Convert audio to base64
            flogInfo { "Reading audio file..." }
            val audioBytes = audioFile.readBytes()
            flogInfo { "Audio data read: ${audioBytes.size} bytes" }

            flogInfo { "Converting to base64..." }
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            flogInfo { "Audio converted to base64: ${base64Audio.length} characters" }

            // Cache data for retry purposes BEFORE network check
            lastVoiceContext = context
            lastBase64Audio = base64Audio

            // Check network connectivity after caching data
            if (!hasNetworkConnection()) {
                flogError { "No network connection available" }
                withContext(Dispatchers.Main) {
                    handleVoiceError("No internet connection available")
                }
                return
            }

            // Send to backend
            flogInfo { "Sending to backend..." }
            val response = sendVoiceToBackend(base64Audio, context)
            flogInfo { "Backend response received: success=${response.success}" }

            // Process the response
            withContext(Dispatchers.Main) {
                if (historyEntry?.id == attemptId) processVoiceResponse(response)
            }

        } catch (e: OutOfMemoryError) {
            finishVoiceHistory("failed", "Audio file too large")
            flogError { "Out of memory processing audio: ${e.message}" }
            withContext(Dispatchers.Main) {
                appContext.showShortToast("❌ Audio file too large")
            }
        } catch (e: Exception) {
            flogError { "Failed to process voice recording: ${e.message}" }
            flogError { "Exception type: ${e.javaClass.simpleName}" }
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                if (historyEntry?.id == attemptId) handleVoiceError("Failed to process audio: ${e.message}")
            }
        } finally {
            // Clean up the audio file
            try {
                if (audioFile.exists()) {
                    val deleted = audioFile.delete()
                    flogInfo { "Temporary audio file deleted: $deleted" }
                }
            } catch (e: Exception) {
                flogError { "Failed to delete temporary file: ${e.message}" }
            }

            finishBatchRecordingUi(audioFile)
        }
    }

    internal suspend fun finishBatchRecordingUi(audioFile: File) {
        // Cleanup must still run after cancellation, but UI state belongs to Main.
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
            // Check ownership after dispatch: a newer recording may have started meanwhile.
            if (voiceRecordingFile !== audioFile) return@withContext
            voiceRecordingFile = null
            if (!_isVoiceRetryable.value && _voiceFinalText.value == null && _voiceLiveTranscript.value.isBlank()) {
                _isVoiceProcessing.value = false
                restoreUiModeAfterVoice()
                lastVoiceContext = null
                lastBase64Audio = null
            }
        }
    }

    /**
     * Sends voice data to the backend for processing.
     */
    private suspend fun sendVoiceToBackend(base64Audio: String, context: VoiceContextData, authRetried: Boolean = false): VoiceResponse {
        val serverUrl = "https://process-voice.whisperme.app"

        return withContext(Dispatchers.IO) {
            try {
                flogInfo { "=== SENDING TO BACKEND ===" }
                flogInfo { "URL: $serverUrl" }
                flogInfo { "Audio length: ${base64Audio.length} characters" }
                flogInfo { "Context: ${context.packageName} - ${context.fieldType}" }
                flogInfo { "Editor snapshot length: ${context.fullText.length}; selection: ${context.nativeSelectionStart}..${context.nativeSelectionEnd}" }

                // Get fresh authentication credentials from Auth0Manager
                val authManager = Auth0Manager.getInstance(appContext)

                // Get fresh credentials (automatically handles token refresh if needed)
                val authToken: String? = authManager.getValidAccessToken()
                flogInfo { "Access token available: ${authToken != null}" }

                if (authToken == null) {
                    flogError { "No valid Auth0 credentials available" }
                    return@withContext VoiceResponse(
                        success = false,
                        transcription = null,
                        finalText = null,
                        error = "Authentication failed - please log in again",
                        isAuthenticationError = true
                    )
                }

                // Create JSON request body
                _voiceProcessingStatus.value = "Processing recording; providers may retry automatically up to twice…"
                val requestBody = buildJsonRequest(base64Audio, context)
                flogInfo { "Request body length: ${requestBody.length} characters" }

                // Create HTTP connection
                val url = URL(serverUrl)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "WhisperMe-Android/1.0")

                // Add authentication header if available
                authToken?.let { token ->
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    flogInfo { "Added authentication header" }
                } ?: run {
                    flogInfo { "No authentication token available - request may fail" }
                }

                connection.doOutput = true
                connection.connectTimeout = 30000 // 30 seconds
                connection.readTimeout = 300_000 // ASR and LLM each allow at most two server retries.

                flogInfo { "Sending HTTP request..." }

                // Send request
                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                flogInfo { "Request sent, waiting for response..." }

                // Read response
                val responseCode = connection.responseCode
                flogInfo { "Response code: $responseCode" }

                val responseBody = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().readText()
                } else {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                }

                flogInfo { "Response received (${responseBody.length} chars): ${responseBody.take(500)}..." }

                if (responseCode in 200..299) {
                    parseVoiceResponse(responseBody)
                } else if (responseCode == 401) {
                    flogError { "Authentication error (401): $responseBody" }
                    connection.disconnect()
                    if (!authRetried && authManager.getValidAccessToken(forceRefresh = true) != null) {
                        return@withContext sendVoiceToBackend(base64Audio, context, authRetried = true)
                    }
                    authManager.requireSignIn()
                    VoiceResponse(
                        success = false,
                        transcription = null,
                        finalText = null,
                        error = "Authentication failed - please log in again",
                        isAuthenticationError = true
                    )
                } else {
                    flogError { "HTTP error $responseCode: $responseBody" }
                    parseVoiceResponse(responseBody, responseCode)
                }

            } catch (e: java.net.SocketTimeoutException) {
                flogError { "Request timeout: ${e.message}" }
                VoiceResponse(success = false, transcription = null, finalText = null, error = "Connection to WhisperMe timed out; cause unknown")
            } catch (e: java.net.UnknownHostException) {
                flogError { "Network error: ${e.message}" }
                VoiceResponse(success = false, transcription = null, finalText = null, error = "Could not reach WhisperMe; cause unknown")
            } catch (e: Exception) {
                flogError { "HTTP request failed: ${e.message}" }
                flogError { "Exception type: ${e.javaClass.simpleName}" }
                e.printStackTrace()
                VoiceResponse(success = false, transcription = null, finalText = null, error = "Network request failed: ${e.message}")
            }
        }
    }

    /**
     * Builds the JSON request body for the backend.
     */
    private fun buildJsonRequest(base64Audio: String, context: VoiceContextData): String {
        requireSupportedVoiceContext(context)
        return """
            {
                ${context.preferenceProfile?.let { "\"preferenceProfile\":" + org.json.JSONObject().put("instructions", it.instructions).put("scope", it.scope).toString() + "," } ?: ""}
                "usePreferences": ${context.usePreferences},
                "useDictionary": ${context.useDictionary},
                ${context.appCategory?.let { "\"appCategory\":\"$it\"," } ?: ""}
                "dictionary": ${org.json.JSONArray(context.dictionary)},
                "suggestPreferences": ${context.suggestPreferences},
                "diagnosticContext": ${context.diagnosticContext},
                "audio": "$base64Audio",
                "format": "wav",
                "transcriptionProvider": "${currentTranscriptionProvider()}",
                ${voiceRoutingJson()},
                "mode": "${prefs.voiceInput.mode.get().escapeJson()}",
                "context": ${buildVoiceContextJson(context)}
            }
        """.trimIndent()
    }

    private fun buildVoiceContextJson(context: VoiceContextData, beforeChars: Int = 2_000, afterChars: Int = 2_000): String {
        val window = voiceContextWindow(context.fullText, context.cursorPosition,
            context.cursorPosition + context.selectedText.length, beforeChars, afterChars)
        val json = """
            {
                "supportsPreferenceConfirmation": true,
                "supportsScopedPreferenceConfirmation": true,
                "supportsDictionaryConfirmation": true,
                "insertOnly": ${context.insertOnly},
                "textBeforeCursor": "${context.fullText.substring(window.start, context.cursorPosition).escapeJson()}",
                "textAfterCursor": "${context.fullText.substring(context.cursorPosition + context.selectedText.length, window.end).escapeJson()}",
                "selectedText": "${context.selectedText.escapeJson()}",
                "cursorPosition": ${context.cursorPosition - window.start},
                "fullText": "${context.fullText.substring(window.start, window.end).escapeJson()}",
                "contextWindow": {"start": ${window.start}, "totalLength": ${window.totalLength}, "hasBefore": ${window.hasBefore}, "hasAfter": ${window.hasAfter}},
                "packageName": "${context.packageName.escapeJson()}",
                "fieldType": "${context.fieldType?.escapeJson() ?: ""}",
                "fieldRole": "${voiceFieldRole(context.fieldType, context.fieldHint, context.fieldLabel)}",
                "fieldHint": "${context.fieldHint?.escapeJson() ?: ""}",
                "fieldLabel": "${context.fieldLabel?.escapeJson() ?: ""}",
                "fieldName": "${context.fieldName?.escapeJson() ?: ""}",
                "isPasswordField": ${context.isPasswordField},
                "isRichEditor": ${context.isRichEditor},
                "isMultiline": ${context.isMultiline},
                "keyboardMode": "${context.keyboardMode.escapeJson()}",
                "inputShiftState": "${context.inputShiftState.escapeJson()}",
                "locale": "${context.locale.escapeJson()}",
                "contentMimeTypes": ${context.contentMimeTypes?.takeIf { it.isNotEmpty() }?.let { types -> "[" + types.map { "\"${it.escapeJson()}\"" }.joinToString(",") + "]" } ?: "null"},
                "imeOptions": {
                    "action": "${context.imeAction.escapeJson()}",
                    "flagNoEnterAction": ${context.flagNoEnterAction},
                    "flagNoPersonalizedLearning": ${context.flagNoPersonalizedLearning}
                }
            }
        """.trimIndent()
        return org.json.JSONObject(json).apply { if (context.insertOnly) remove("contextWindow") }.toString()
    }

    private fun currentTranscriptionProvider(): String {
        return prefs.voiceInput.transcriptionProvider.get().wireValue
    }

    private fun currentVoiceReasoning(): String {
        val provider = prefs.voiceInput.llmProvider.get()
        val model = if (provider == VoiceLlmProvider.SERVER_DEFAULT) Auth0Manager.getInstance(appContext).backendDefaultLlmModel.value
            else provider.compatibleModel(prefs.voiceInput.llmModel.get())
        return compatibleVoiceReasoning(model, prefs.voiceInput.reasoningEffort.get())
    }

    private fun putVoiceRouting(json: org.json.JSONObject) {
        val provider = prefs.voiceInput.llmProvider.get()
        json.put("llmRouting", if (provider == VoiceLlmProvider.SERVER_DEFAULT) "server" else "explicit")
            .put("llmProvider", provider.wireValue)
        if (provider != VoiceLlmProvider.SERVER_DEFAULT) json.put("llmModel", provider.compatibleModel(prefs.voiceInput.llmModel.get()))
        currentVoiceReasoning().takeUnless { it == "default" }?.let { json.put("reasoningEffort", it) }
    }
    private fun voiceRoutingJson(): String = org.json.JSONObject().apply { putVoiceRouting(this) }.toString().removePrefix("{").removeSuffix("}")

    private fun currentLlmProvider(): String {
        return prefs.voiceInput.llmProvider.get().wireValue
    }

    /**
     * Parses the JSON response from the backend.
     */
    private fun parseVoiceFailure(json: String?): app.whisperme.keyboard.ime.voice.VoiceFailure? =
        json?.let { runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<app.whisperme.keyboard.ime.voice.VoiceFailure>(it) }.getOrNull() }

    @Synchronized private fun recordVoiceBudget(budget: app.whisperme.keyboard.ime.voice.VoiceBudgetStatus?, notify: Boolean = false) {
        if (budget == null) return
        app.whisperme.keyboard.ime.voice.VoiceBudgetStore.get(appContext).record(budget)
        historyEntry = historyEntry?.copy(budget = budget)
        historyEntry?.let(historyStore::save)
        _voiceBudgetStatus.value = budget.warningText().orEmpty()
        if (notify) budget.warningText()?.let { appContext.showLongToast(it) }
    }

    private fun parseVoiceResponse(responseBody: String, httpStatus: Int = 200): VoiceResponse {
        val parsed = app.whisperme.keyboard.ime.voice.parseVoiceBatchResult(responseBody, httpStatus)
        return VoiceResponse(success = parsed.success, transcription = parsed.transcription,
            finalText = parsed.finalText, error = parsed.error,
            cursorPosition = runCatching { org.json.JSONObject(responseBody).let {
                if (it.has("cursorPosition") && !it.isNull("cursorPosition")) it.getInt("cursorPosition") else null
            } }.getOrNull(),
            contextWindowJson = runCatching { org.json.JSONObject(responseBody).optJSONObject("contextWindow")?.toString() }.getOrNull(),
            usageJson = runCatching { org.json.JSONObject(responseBody).optJSONObject("usage")?.toString() }.getOrNull()).apply {
                budget = parsed.budget
                failure = parseVoiceFailure(runCatching { org.json.JSONObject(responseBody).optJSONObject("failure")?.toString() }.getOrNull())
                diagnosticsJson = runCatching { org.json.JSONObject(responseBody).optJSONObject("diagnostics")?.toString() }.getOrNull()
                notice = runCatching { org.json.JSONObject(responseBody).optString("notice").takeIf { it.isNotBlank() } }.getOrNull()
                preferenceSuggestionJson = runCatching { org.json.JSONObject(responseBody).optJSONObject("preferenceSuggestion")?.toString() }.getOrNull()
            }
    }


    /**
     * Processes the response from the backend.
     */
    @Synchronized private fun recordVoiceMetadata(diagnostics: String?, notice: String?) {
        val parsed = app.whisperme.keyboard.ime.voice.parseVoiceDiagnostics(diagnostics,
            lastVoiceContext?.diagnosticContext == true && prefs.voiceInput.diagnosticContext.get())
        historyEntry = historyEntry?.let { it.copy(diagnostics = parsed ?: it.diagnostics, notice = notice ?: it.notice) }
        if (parsed != null || notice != null) historyEntry?.let(historyStore::save)
    }

    @androidx.annotation.MainThread
    private fun processVoiceResponse(response: VoiceResponse) {
        stopVoiceRecordingProtection()
        recordVoiceBudget(response.budget, notify = true)
        historyEntry = historyEntry?.copy(failure = response.failure ?: historyEntry?.failure)
        recordVoiceMetadata(response.diagnosticsJson, response.notice)
        // Result delivery runs on Main; metadata also arrives on the WebSocket thread.
        response.notice?.let { appContext.showShortToast(it) }
        response.usageJson?.let { historyEntry = historyEntry?.copy(usageJson = it) }
        historyResponse(response.transcription, response.finalText)
        response.transcription?.takeIf { it.isNotEmpty() }?.let { _voiceLiveTranscript.value = it }
        _voiceFinalText.value = response.finalText
        flogInfo { "=== PROCESSING VOICE RESPONSE ===" }
        flogInfo { "Success: ${response.success}" }
        flogInfo { "Error: ${response.error}" }

        if (!response.success) {
            val errorMessage = response.error ?: "Unknown error"
            flogError { "Voice processing failed: $errorMessage" }
            handleVoiceError(errorMessage, response.isAuthenticationError)
            return
        }

        if (!voiceInputVisible || voiceOutputDetached) {
            finishVoiceHistory("recovered")
            _isVoiceRecording.value = false
            _isVoiceProcessing.value = false
            _isVoiceRetryable.value = false
            _isVoiceAuthError.value = false
            _voiceProcessingStatus.value = ""
            _voiceErrorMessage.value = null
            _voiceAssistantMessage.value = "Result saved in Transcripts. Nothing was inserted while the keyboard was hidden or the field changed."
            retainedVoiceAudio.clear()
            lastBase64Audio = null
            return
        }

        if (app.whisperme.keyboard.ime.voice.voicePreferenceNoticeOnly(response.preferenceSuggestionJson,
                lastVoiceContext?.insertOnly == true, response.finalText, response.notice)) {
            // Memory confirmation is independent of editor readability/connection.
            // Bind it to the request snapshot before clearing that snapshot, and
            // return before any selection, commitText or caret operation.
            val offered = lastVoiceContext?.let { offerVoicePreference(response, it) } == true
            val notice = if (offered) "Review the rule before saving it for this app."
                else response.notice ?: "The rule could not be offered. Nothing was saved or inserted."
            finishVoiceHistory("handled")
            _voiceFinalText.value = null
            _voiceErrorMessage.value = notice
            _voiceAssistantMessage.value = notice
            _isVoiceRecording.value = false
            _isVoiceProcessing.value = false
            _isVoiceRetryable.value = false
            _isVoiceAuthError.value = false
            _voiceProcessingStatus.value = ""
            retainedVoiceAudio.clear()
            lastBase64Audio = null
            lastVoiceContext = null
            voiceInputConnection = null
            if (offered) restoreUiModeAfterVoice()
            return
        }

        _voiceFinalText.value = response.finalText
        val snapshot = lastVoiceContext
        val connection = FlorisImeService.currentInputConnection()
        if (snapshot == null || connection == null || connection !== voiceInputConnection) {
            handleVoiceError("The text field changed; result was not inserted")
            return
        }
        val info = editorInstance.activeInfo
        if (snapshot.fieldGeneration != voiceFieldGeneration || snapshot.editorIdentity !== info.base || snapshot.packageName != info.packageName ||
            snapshot.fieldId != info.base.fieldId || snapshot.fieldName != info.base.fieldName || isPasswordField(info)) {
            handleVoiceError("The text field changed; result was not inserted. Copy the result to recover it.")
            return
        }
        if (snapshot.insertOnly) {
            var attempted = false
            val notice = app.whisperme.keyboard.ime.voice.commitUnverifiableVoiceInsertion(
                response.finalText, response.contextWindowJson != null,
                commit = { attempted = true; connection.commitText(it, 1) })
            if (attempted) {
                offerVoicePreference(response, snapshot)
                historyEntry = historyEntry?.let { it.copy(notice = listOfNotNull(it.notice, notice).joinToString("\n")) }
                finishVoiceHistory("unverified")
                _voiceErrorMessage.value = notice // Neutral recovery text in the recording UI.
                _voiceAssistantMessage.value = notice
            } else handleVoiceError(notice)
            _isVoiceRecording.value = false
            _isVoiceProcessing.value = false
            _isVoiceAuthError.value = false
            _voiceProcessingStatus.value = ""
            _isVoiceRetryable.value = false
            retainedVoiceAudio.clear()
            lastBase64Audio = null
            // Retain identity for the copy-only panel; a completed insertion cannot be retried.
            return
        }
        val current = runCatching { buildVoiceContext(editorInstance.activeInfo, editorInstance.activeContent, connection) }.getOrElse {
            handleVoiceError("This field no longer exposes its complete text; result was not inserted: ${it.message ?: it.javaClass.simpleName}")
            return
        }
        if (current.insertOnly || snapshot.completeField != current.completeField || snapshot.contextOffset != current.contextOffset ||
            snapshot.fullText != current.fullText || snapshot.textBeforeCursor != current.textBeforeCursor ||
            snapshot.selectedText != current.selectedText || snapshot.packageName != current.packageName ||
            snapshot.fieldId != current.fieldId || snapshot.fieldName != current.fieldName ||
            snapshot.nativeSelectionStart != current.nativeSelectionStart || snapshot.nativeSelectionEnd != current.nativeSelectionEnd) {
            handleVoiceError("The text or selection changed; result was not inserted")
            return
        }
        val windowText = response.finalText ?: run {
            handleVoiceError("No final editing result received")
            return
        }
        val (finalText, finalCursor) = runCatching {
            val window = voiceContextWindow(snapshot.fullText, snapshot.cursorPosition,
                snapshot.cursorPosition + snapshot.selectedText.length, voiceWindowBefore, voiceWindowAfter)
            response.contextWindowJson?.let { json ->
                val returned = org.json.JSONObject(json)
                require(returned.getInt("start") == window.start && returned.getInt("totalLength") == window.totalLength &&
                    returned.getBoolean("hasBefore") == window.hasBefore && returned.getBoolean("hasAfter") == window.hasAfter) {
                    "The returned editor window does not match the original snapshot"
                }
            }
            window.apply(snapshot.fullText, windowText, response.cursorPosition)
        }.getOrElse {
            handleVoiceError(it.message ?: "The windowed editing result is invalid")
            return
        }
        val change = app.whisperme.keyboard.ime.voice.voiceTextDelta(current.fullText, finalText)
        // An editor may accept ordinary typing but refuse arbitrary selection or full-text reads.
        // Derive insertion at the original caret explicitly (a minimal diff can shift across repeats).
        val inserted = app.whisperme.keyboard.ime.voice.voiceInsertionAtCaret(
            current.fullText, finalText, current.cursorPosition, current.selectedText.length)
        if (!current.completeField && inserted == null) {
            handleVoiceError("This app exposes only nearby text. Adding text at the cursor is supported, but this result changes existing text; nothing was inserted. Copy the result or use an editor that supports full text access.")
            return
        }
        val insertionError = app.whisperme.keyboard.ime.voice.applyVoiceHistoryResult(
            unchanged = change == null,
            selectAll = {
                inserted != null || (connection.setSelection(checkNotNull(change).start, change.oldEnd) &&
                    connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.let {
                        it.startOffset == 0 && it.text?.toString() == current.fullText &&
                            it.selectionStart == change.start && it.selectionEnd == change.oldEnd
                    } == true)
            },
            commit = { connection.commitText(inserted ?: checkNotNull(change).replacement, 1) },
            verify = {
                if (current.completeField) {
                    val extracted = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                    extracted != null && extracted.startOffset == 0 && extracted.text?.toString() == finalText
                } else {
                    val after = buildVoiceContext(editorInstance.activeInfo, editorInstance.activeContent, connection)
                    after.nativeSelectionStart == current.nativeSelectionStart + checkNotNull(inserted).length &&
                        after.nativeSelectionStart == after.nativeSelectionEnd &&
                        after.textBeforeCursor == (current.textBeforeCursor + inserted).takeLast(4096) &&
                        after.textAfterCursor == current.textAfterCursor
                }
            },
        )
        if (insertionError != null) {
            handleVoiceError(insertionError)
            return
        }
        val cursorVerified = runCatching {
            if (inserted != null && finalCursor == current.cursorPosition + inserted.length) {
                // commitText(..., 1) already placed the caret; do not require setSelection for typing.
                val after = buildVoiceContext(editorInstance.activeInfo, editorInstance.activeContent, connection)
                after.nativeSelectionStart == current.nativeSelectionStart + inserted.length &&
                    after.nativeSelectionEnd == after.nativeSelectionStart
            } else if (!current.completeField) false else connection.setSelection(finalCursor, finalCursor) &&
                connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.let {
                    it.startOffset == 0 && it.text?.toString() == finalText &&
                        it.selectionStart == finalCursor && it.selectionEnd == finalCursor
                } == true
        }.getOrDefault(false)
        if (!cursorVerified) {
            handleVoiceError("Text was applied but the editor did not confirm the cursor position; check the field before continuing")
            return
        }
        finishVoiceHistory("successful")
        offerVoicePreference(response, snapshot)
        _isVoiceRetryable.value = false
        _voiceErrorMessage.value = null
        _isVoiceAuthError.value = false
        _isVoiceProcessing.value = false
        _voiceLiveTranscript.value = ""
        _voiceAssistantMessage.value = ""
        _voiceFinalText.value = null
        retainedVoiceAudio.clear()
        lastVoiceContext = null
        lastBase64Audio = null
        voiceInputConnection = null
        restoreUiModeAfterVoice()
    }



    /**
     * Handles voice processing errors.
     */
    @androidx.annotation.MainThread
    private fun handleVoiceError(error: String, isAuthenticationError: Boolean = false) {
        if (audioRecord == null && mediaRecorder == null) stopVoiceRecordingProtection()
        if (_voiceFinalText.value != null) sampleVoiceHistory("insertion.failed", realtimeRevision, error)
        finishVoiceHistory("failed", error)
        _voiceRetryStatus.value = ""
        if (recoverableHistoryEntry?.recovery?.valid() == true && _voiceFinalText.value == null)
            _isVoiceRetryable.value = recoverableHistoryEntry?.failure?.retryable != false
        flogError { "Voice processing error: $error (auth error: $isAuthenticationError)" }

        // Reset processing state
        _isVoiceProcessing.value = false
        _voiceErrorMessage.value = error

        // Set authentication error flag
        _isVoiceAuthError.value = isAuthenticationError

        if (_voiceFinalText.value != null || (_voiceLiveTranscript.value.isNotBlank() && lastBase64Audio == null && !retainedVoiceAudio.isNotEmpty())) {
            if (_voiceFinalText.value != null) _isVoiceRetryable.value = false
            _isVoiceRecording.value = false
            _voiceErrorMessage.value = error
            _voiceAssistantMessage.value = error
            // Keep the transcript visible and copyable until the user dismisses it.
            return
        }

        // Authentication errors are always retryable (but with login option)
        val isRetryable = if (recoverableHistoryEntry?.failure?.retryable == false) false else if (isAuthenticationError) {
            true
        } else {
            when {
                error.contains("timeout", ignoreCase = true) -> true
                error.contains("network", ignoreCase = true) -> true
                error.contains("server", ignoreCase = true) -> true
                error.contains("connection", ignoreCase = true) -> true
                error.contains("internet", ignoreCase = true) -> true
                error.contains("available", ignoreCase = true) -> true
                error.contains("permission", ignoreCase = true) -> false
                error.contains("audio", ignoreCase = true) -> false
                error.contains("invalid", ignoreCase = true) -> false
                else -> true // Default to retryable for unknown errors
            }
        }

        if (recoverableHistoryEntry?.failure?.retryable != false && (isAuthenticationError || recoverableHistoryEntry?.recovery?.valid() == true || (isRetryable && lastVoiceContext != null && (lastBase64Audio != null || retainedVoiceAudio.isNotEmpty())))) {
            // Show retry options
            _isVoiceRetryable.value = true
            _voiceErrorMessage.value = when {
                isAuthenticationError -> error
                error.contains("(402)") -> "$error. Check provider billing or choose Groq in Voice Input settings, then retry."
                else -> error
            }
            flogInfo { "Error is retryable - showing retry options" }
        } else if (lastVoiceContext != null && (_voiceLiveTranscript.value.isNotBlank() || retainedVoiceAudio.isNotEmpty())) {
            _isVoiceRetryable.value = false
            _isVoiceRecording.value = false
        } else {
            // Non-retryable error - return to previous UI mode
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                flogInfo { "Restored UI mode to $previousMode due to non-retryable error" }
                previousUiModeBeforeVoice = null
            }

            // Show user-friendly error message
            val userMessage = when {
                error.contains("permission", ignoreCase = true) -> "🎤 Microphone permission required"
                error.contains("audio", ignoreCase = true) -> "🔊 Audio recording error"
                error.contains("invalid", ignoreCase = true) -> "❌ Invalid audio file"
                else -> "❌ Voice input failed"
            }

            appContext.showShortToast(userMessage)
            flogInfo { "Displayed user message: $userMessage" }
        }
    }

    /**
     * Returns whether voice recording is currently active.
     * This can be used by UI components to show recording state.
     */
    fun isVoiceRecordingActive(): Boolean = _isVoiceRecording.value

    /**
     * Called when permissions might have changed - checks if we can now start pending voice input.
     */
    fun checkPendingVoiceInput() {
        if (wasPendingVoiceInput && hasRecordAudioPermission()) {
            flogInfo { "Permission granted - resuming voice input" }
            wasPendingVoiceInput = false
            // Try to start voice recording again
            scope.launch(Dispatchers.Main) {
                // Give a small delay for UI to settle
                delay(500)
                startVoiceRecording()
            }
        }
    }

    /**
     * Checks if device has internet connectivity.
     */
    private fun hasNetworkConnection(): Boolean {
        val connectivityManager = appContext.systemService(ConnectivityManager::class)
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val _historyContinuationId = MutableStateFlow<String?>(null)
    val historyContinuationId = _historyContinuationId.asStateFlow()

    /** Explicit continuation. History requests are always copy-only; only live recovery can use
     * the original in-memory editor snapshot, and insertion still re-verifies text/selection. */
    fun resumeVoiceHistory(entry: app.whisperme.keyboard.ime.voice.VoiceHistoryEntry, useOriginalField: Boolean = false) {
        val recovery = entry.recovery?.takeIf { it.valid() } ?: return
        if (!entry.canContinueFinalProcessing()) return
        if (_isVoiceRecording.value || _isVoiceProcessing.value || continuationJob?.isActive == true) return
        val snapshot = lastVoiceContext.takeIf { useOriginalField }
        val originalField = snapshot != null && snapshot.fieldGeneration == voiceFieldGeneration &&
            voiceInputConnection === FlorisImeService.currentInputConnection() &&
            snapshot.editorIdentity === editorInstance.activeInfo.base
        val generation = ++realtimeGeneration
        voiceWindowBefore = 2_000
        voiceWindowAfter = 2_000
        voiceContextExpansions = 0
        _voiceFinalText.value = null
        _voiceAssistantMessage.value = ""
        historyEntry = entry.copy(status = "processing", error = null, failure = null)
        historyStartNanos = System.nanoTime() - entry.elapsedMs * 1_000_000
        _historyContinuationId.value = entry.id
        _voiceBudgetStatus.value = ""
        _isVoiceProcessing.value = true
        _isVoiceRetryable.value = false
        _voiceRetryStatus.value = ""
        _voiceErrorMessage.value = null
        _voiceProcessingStatus.value = "Continuing from saved final transcript…"
        if (useOriginalField) {
            rememberUiModeBeforeVoice()
            activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
        }
        continuationJob = scope.launch(Dispatchers.Main) {
            var socket: WebSocket? = null
            try {
                val auth = Auth0Manager.getInstance(appContext)
                val token = auth.getBackendAccessToken(voiceCapabilitiesUrl()) ?: error("Sign in or reconnect to continue the saved transcript")
                val request = if (originalField) org.json.JSONObject(buildRealtimeStartRequest(checkNotNull(snapshot))).getJSONObject("payload")
                    else org.json.JSONObject().put("context", org.json.JSONObject()
                        .put("insertOnly", true).put("fullText", "").put("textBeforeCursor", "")
                        .put("textAfterCursor", "").put("selectedText", "").put("cursorPosition", 0)
                        .put("packageName", "").put("fieldType", "text").put("isMultiline", true))
                        .put("usePreferences", false).put("suggestPreferences", false)
                        .put("diagnosticContext", false).put("dictionary", org.json.JSONArray())
                request.put("transcript", recovery.transcript).put("mode", recovery.mode)
                    .put("llmRouting", recovery.llmRouting).put("llmProvider", recovery.llmProvider)
                    .put("liveHints", false)
                request.remove("llmModel")
                recovery.llmModel?.let { request.put("llmModel", it) }
                val reasoningModel = if (recovery.llmRouting == "server") auth.backendDefaultLlmModel.value else recovery.llmModel
                val reasoning = compatibleVoiceReasoning(reasoningModel, recovery.reasoningEffort)
                request.remove("reasoningEffort")
                if (reasoning != "default") request.put("reasoningEffort", reasoning)
                historyEntry = historyEntry?.copy(reasoningEffort = reasoning)
                val response = if (auth.backendSupportsResumeTranscript) {
                    val complete = CompletableDeferred<VoiceResponse>()
                    val endpoint = if (LocalVoiceSession.configured(appContext)) LocalVoiceSession.ENDPOINT
                        else prefs.voiceInput.realtimeEndpoint.get().trim()
                    socket = realtimeWsClient.newWebSocket(Request.Builder().url(endpoint)
                        .header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (generation != realtimeGeneration) { webSocket.cancel(); return }
                            startVoiceHeartbeat(webSocket, generation)
                            webSocket.send(org.json.JSONObject().put("type", "session.start").put("payload", request).toString())
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (generation != realtimeGeneration) return
                            val event = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return
                            when (event.optString("type")) {
                                "budget.warning" -> showVoiceBudgetWarning(event)
                                "session.started" -> {
                                    historyEntry = historyEntry?.copy(llmProvider = event.optString("llmProvider", entry.llmProvider),
                                        llmModel = event.optString("llmModel", entry.llmModel), resolvedLlm = event.has("llmProvider"))
                                }
                                "connection.pong" -> if (connectionHealth.pong(event.optLong("nonce", -1), android.os.SystemClock.elapsedRealtime()))
                                    _voiceConnectionStatus.value = connectionHealth.description(!hasNetworkConnection())
                                "processing.retry" -> {
                                    _voiceRetryStatus.value = event.optString("text", "Provider is retrying…")
                                    sampleVoiceHistory("processing.retry", 0, _voiceRetryStatus.value)
                                }
                                "processing.status" -> _voiceProcessingStatus.value = event.optString("text", "Finishing saved transcript…")
                                "usage.updated" -> historyEntry = historyEntry?.copy(usageJson = event.optJSONObject("usage")?.toString())
                                "context.request" -> try {
                                    check(originalField && snapshot != null && !snapshot.insertOnly) { "Original editor context unavailable; copy the transcript instead" }
                                    check(snapshot.fieldGeneration == voiceFieldGeneration && voiceInputConnection === FlorisImeService.currentInputConnection()) { "Original field changed" }
                                    check(voiceContextExpansions < 2) { "Too many context expansion requests" }
                                    val before = event.getInt("beforeChars")
                                    val after = event.getInt("afterChars")
                                    val previous = voiceContextWindow(snapshot.fullText, snapshot.cursorPosition,
                                        snapshot.cursorPosition + snapshot.selectedText.length, voiceWindowBefore, voiceWindowAfter)
                                    val expanded = voiceContextWindow(snapshot.fullText, snapshot.cursorPosition,
                                        snapshot.cursorPosition + snapshot.selectedText.length, before, after)
                                    require(expanded.contains(previous)) { "Context expansion discarded supplied text" }
                                    check(webSocket.send(org.json.JSONObject().put("type", "context.provide")
                                        .put("requestId", event.getString("requestId"))
                                        .put("context", org.json.JSONObject(buildVoiceContextJson(snapshot, before, after))).toString()))
                                    voiceWindowBefore = before
                                    voiceWindowAfter = after
                                    voiceContextExpansions++
                                } catch (e: Exception) {
                                    complete.complete(VoiceResponse(false, recovery.transcript, null, e.message ?: "Context unavailable"))
                                }
                                "processing.final_text" -> if (event.has("finalText")) complete.complete(VoiceResponse(true,
                                    recovery.transcript, event.getString("finalText"), null,
                                    cursorPosition = if (event.has("cursorPosition") && !event.isNull("cursorPosition")) event.getInt("cursorPosition") else null,
                                    contextWindowJson = event.optJSONObject("contextWindow")?.toString()).apply {
                                        notice = event.optString("notice").takeIf { it.isNotBlank() }
                                        diagnosticsJson = event.optJSONObject("diagnostics")?.toString()
                                        preferenceSuggestionJson = event.optJSONObject("preferenceSuggestion")?.toString()
                                    })
                                "error" -> complete.complete(VoiceResponse(false, recovery.transcript, null,
                                    event.optString("error", "Final processing failed")).apply {
                                        failure = parseVoiceFailure(event.optJSONObject("failure")?.toString())
                                    })
                            }
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            complete.complete(VoiceResponse(false, recovery.transcript, null, "Connection to WhisperMe closed before final text; cause unknown"))
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            complete.complete(VoiceResponse(false, recovery.transcript, null, "Connection to WhisperMe interrupted (${response?.code ?: t.javaClass.simpleName}): ${t.message ?: "cause unknown"}"))
                        }
                    })
                    withTimeout(160_000) { complete.await() }
                } else {
                    check(auth.backendSupportsProcessTranscript) { "This server does not support saved transcript continuation" }
                    _voiceProcessingStatus.value = "Processing saved transcript; providers may retry automatically up to twice…"
                    withContext(Dispatchers.IO) {
                        val endpoint = if (LocalVoiceSession.configured(appContext)) "http://127.0.0.1:8877/process-transcript"
                            else voiceCapabilitiesUrl().removeSuffix("/capabilities") + "/process-transcript"
                        val connection = URL(endpoint).openConnection() as HttpURLConnection
                        try {
                            connection.requestMethod = "POST"
                            connection.instanceFollowRedirects = false
                            connection.setRequestProperty("Authorization", "Bearer $token")
                            connection.setRequestProperty("Content-Type", "application/json")
                            connection.connectTimeout = 30_000
                            connection.readTimeout = 160_000
                            connection.doOutput = true
                            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                            val status = connection.responseCode
                            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                                ?.bufferedReader()?.use { it.readText() }.orEmpty()
                            parseVoiceResponse(body, status)
                        } finally { connection.disconnect() }
                    }
                }
                if (generation != realtimeGeneration) return@launch
                if (originalField) processVoiceResponse(response)
                else {
                    recordVoiceBudget(response.budget, notify = true)
                    historyResponse(recovery.transcript, response.finalText)
                    historyEntry = historyEntry?.copy(failure = response.failure)
                    finishVoiceHistory(if (response.success) "recovered" else "failed", response.error)
                    _voiceFinalText.value = response.finalText.takeIf { useOriginalField }
                    _voiceErrorMessage.value = response.error
                    if (useOriginalField) _voiceAssistantMessage.value = "Field changed. Copy the recovered result; nothing was inserted."
                }
            } catch (e: Exception) {
                if (generation == realtimeGeneration) {
                    val error = if (e is TimeoutCancellationException) "Connection to WhisperMe timed out; cause unknown"
                        else if (e is kotlinx.coroutines.CancellationException) "Continuation paused; final transcript saved"
                        else e.message ?: "Could not continue final processing"
                    finishVoiceHistory("failed", error)
                    _voiceErrorMessage.value = error
                }
            } finally {
                socket?.cancel()
                if (generation == realtimeGeneration) {
                    heartbeatJob?.cancel()
                    _isVoiceProcessing.value = false
                    _voiceRetryStatus.value = ""
                    _isVoiceRetryable.value = useOriginalField && _voiceFinalText.value == null && recoverableHistoryEntry?.canContinueFinalProcessing() == true
                    _historyContinuationId.value = null
                }
            }
        }
    }

    /**
     * Retries voice processing with the last recorded audio.
     */
    fun retryVoiceProcessing() {
        if (_voiceFinalText.value != null) return
        if (_isVoiceRecording.value || _isVoiceProcessing.value || continuationJob?.isActive == true) return
        val saved = recoverableHistoryEntry
        if (saved?.canContinueFinalProcessing() == true) { resumeVoiceHistory(saved, useOriginalField = true); return }
        val snapshot = lastVoiceContext
        if (snapshot == null || snapshot.fieldGeneration != voiceFieldGeneration ||
            voiceInputConnection !== FlorisImeService.currentInputConnection()) {
            _voiceErrorMessage.value = "The original field is no longer verified. Open history to copy or continue the saved transcript."
            restoreUiModeAfterVoice()
            return
        }
        if (retainedVoiceAudio.isNotEmpty() && lastVoiceContext != null) {
            if (_isVoiceRecording.value || _isVoiceProcessing.value) return
            startRealtimeVoiceRecording(replay = true)
            return
        }
        val context = lastVoiceContext
        val audioData = lastBase64Audio

        if (context == null || audioData == null) {
            flogError { "Cannot retry - no cached audio data available" }
            handleVoiceError("Cannot retry - no audio data available")
            return
        }

        if (!hasNetworkConnection()) {
            flogError { "Cannot retry - no network connection" }
            _voiceErrorMessage.value = "🌐 No internet connection available"
            return
        }

        beginVoiceHistory()
        flogInfo { "Retrying voice processing with cached audio data" }

        // Reset retry state and start processing
        _isVoiceRetryable.value = false
        _voiceErrorMessage.value = null
        _isVoiceAuthError.value = false
        _isVoiceProcessing.value = true

        val attemptId = historyEntry?.id
        scope.launch(Dispatchers.IO) {
            try {
                val response = sendVoiceToBackend(audioData, context)
                withContext(Dispatchers.Main) {
                    if (historyEntry?.id == attemptId) processVoiceResponse(response)
                }
            } catch (e: Exception) {
                flogError { "Retry failed: ${e.message}" }
                withContext(Dispatchers.Main) {
                    if (historyEntry?.id == attemptId) handleVoiceError("Retry failed: ${e.message}")
                }
            }
        }
    }

    fun canContinueVoiceRecording(): Boolean = recoverableHistoryEntry?.canContinueFinalProcessing() == true &&
        recoverableHistoryEntry?.recovery?.transcript?.length?.let { it < 50_000 } == true &&
        prefs.voiceInput.transcriptionProvider.get() != VoiceTranscriptionProvider.GROQ_BATCH &&
        Auth0Manager.getInstance(appContext).backendSupportsTranscriptPrefix

    /** Reuse completed ASR as text; the new connection receives only newly recorded PCM. */
    fun continueVoiceRecording() {
        if (_isVoiceRecording.value || _isVoiceProcessing.value || realtimeStartJob?.isActive == true || !canContinueVoiceRecording()) return
        val saved = recoverableHistoryEntry ?: return
        val prefix = saved.recovery?.transcript ?: return
        val snapshot = lastVoiceContext ?: return
        if (snapshot.fieldGeneration != voiceFieldGeneration || voiceInputConnection !== FlorisImeService.currentInputConnection() ||
            snapshot.editorIdentity !== editorInstance.activeInfo.base) {
            _voiceErrorMessage.value = "The original field changed. Continue the saved transcript from Transcripts; no new audio was recorded."
            return
        }
        beginVoiceHistory()
        realtimeTranscriptPrefix = prefix
        _voiceLiveTranscript.value = prefix
        _voiceFinalText.value = null
        _voiceRetryStatus.value = "Continuing dictation. Only new speech is transcribed."
        startRealtimeVoiceRecording()
    }

    /**
     * Cancels voice processing and returns to previous UI mode.
     * This completely clears all voice state since user explicitly cancelled.
     */
    fun cancelVoiceProcessing() {
        realtimeTranscriptPrefix = null
        voiceAuthJob?.cancel()
        continuationJob?.cancel()
        _historyContinuationId.value = null
        voiceButtonGesture.reset()
        wasPendingVoiceInput = false
        _voiceRetryStatus.value = ""
        _voiceBudgetStatus.value = ""
        _voiceConnectionStatus.value = ""
        voiceAuthJob?.cancel()
        // Cancel is also available while the batch recorder is still capturing.
        val canceledRecorder = mediaRecorder
        mediaRecorder = null
        try { canceledRecorder?.stop() } catch (_: Exception) { /* It may not have started yet. */ }
        try { canceledRecorder?.release() } catch (_: Exception) { /* Already released. */ }
        voiceRecordingFile?.let { file ->
            if (file.exists() && !file.delete()) flogError { "Could not delete canceled voice recording" }
        }
        voiceRecordingFile = null
        finishVoiceHistory("canceled", "Canceled by user")
        _voiceFinalText.value = null
        _voiceAssistantMessage.value = ""
        retainedVoiceAudio.clear()
        realtimeGeneration++
        realtimeStartJob?.cancel()
        realtimeStartJob = null
        flogInfo { "Voice processing cancelled by user" }
        _voiceAssistantMessage.value = ""
        _voiceProcessingStatus.value = ""

        // Clear ALL voice states since user explicitly cancelled
        _isVoiceRecording.value = false
        _isVoiceProcessing.value = false
        _isVoiceRetryable.value = false
        _voiceErrorMessage.value = null
        _isVoiceAuthError.value = false
        if (!_isVoiceRetryable.value && _voiceFinalText.value == null) _voiceLiveTranscript.value = ""

        cleanupRealtimeCapture()

        // Clear cached data
        retainedVoiceAudio.clear()
        lastVoiceContext = null
        lastBase64Audio = null

        // Clear auth callback since voice session is fully cancelled
        val authManager = Auth0Manager.getInstance(appContext)
        authManager.clearAuthStateChangeCallback()

        // Dismiss must always leave the recovery panel, even after a view restart.
        restoreUiModeAfterVoice()
    }

    /**
     * Opens the login screen to re-authenticate the user.
     */
    fun openLoginScreen() {
        flogInfo { "Opening login screen for re-authentication" }

        Auth0Manager.getInstance(appContext).setAuthStateChangeCallback { authenticated ->
            if (authenticated) scope.launch(Dispatchers.Main) {
                _isVoiceAuthError.value = false
                _voiceErrorMessage.value = "Signed in. Return to your original field and tap Retry."
            }
        }

        // Create intent to open the main app's login screen
        val intent = Intent(appContext, FlorisAppActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("showLogin", true) // Add extra to indicate login should be shown
        }

        try {
            appContext.startActivity(intent)
            flogInfo { "Login screen opened successfully" }
        } catch (e: Exception) {
            flogError { "Failed to open login screen: ${e.message}" }
            appContext.showShortToast("Please open WhisperMe app to log in")
        }
    }

    /**
     * Cleans up voice-related state when keyboard is hidden or dismissed.
     * Preserves retry state so user can continue after fixing network issues.
     */
    fun cleanupVoiceState() {
        stopVoiceRecordingProtection()
        voiceAudioPreparationEnabled = false
        preparedVoiceRecorder.clear()
        voiceAuthJob?.cancel()
        voiceButtonGesture.reset()
        continuationJob?.cancel()
        _historyContinuationId.value = null
        finishVoiceHistory("interrupted", "Keyboard dismissed; saved text can be recovered")
        if (recoverableHistoryEntry?.canContinueFinalProcessing() == true) _isVoiceRetryable.value = true
        flogInfo { "Cleaning up voice state due to keyboard dismissal" }
        realtimeGeneration++
        realtimeStartJob?.cancel()
        if (retainedVoiceAudio.isNotEmpty() && (_isVoiceRecording.value || _isVoiceProcessing.value)) {
            _isVoiceRetryable.value = true
            _voiceErrorMessage.value = "Recording paused. Return to the original field and retry retained audio."
        }

        // Stop any ongoing recording
        if (_isVoiceRecording.value) {
            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
                mediaRecorder = null
            } catch (e: Exception) {
                flogError { "Error stopping recording during cleanup: ${e.message}" }
            }
        }

        // Clear recording and processing states, but preserve retry state
        _isVoiceRecording.value = false
        _isVoiceProcessing.value = false
        if (!_isVoiceRetryable.value) _voiceLiveTranscript.value = ""

        cleanupRealtimeCapture()

        // DO NOT clear retry state and cached data - user might need to fix network
        // _isVoiceRetryable.value = false
        // _voiceErrorMessage.value = null
        // lastVoiceContext = null
        // lastBase64Audio = null

        // Clean up temporary files
        voiceRecordingFile?.let { file ->
            try {
                if (file.exists()) {
                    file.delete()
                    flogInfo { "Deleted temporary voice file during cleanup" }
                }
            } catch (e: Exception) {
                flogError { "Failed to delete temporary voice file: ${e.message}" }
            }
        }
        voiceRecordingFile = null

        // Only reset UI mode if we're NOT in retry state
        if (activeState.imeUiMode == ImeUiMode.VOICE_RECORDING && !_isVoiceRetryable.value) {
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                flogInfo { "Restored UI mode to $previousMode during cleanup" }
                previousUiModeBeforeVoice = null
            }
        }

        // Don't clear previous mode reference if we're in retry state
        if (!_isVoiceRetryable.value) {
            previousUiModeBeforeVoice = null
        }

        flogInfo { "Voice state cleanup completed (retry state preserved: ${_isVoiceRetryable.value})" }
    }

    /**
     * Checks if the app has RECORD_AUDIO permission.
     */
    private fun hasRecordAudioPermission(): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
            appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Requests microphone permission by launching the permission activity.
     */
    private fun requestMicrophonePermission() {
        try {
            flogInfo { "Launching permission request activity..." }
            val intent = Intent("dev.patrickgold.florisboard.REQUEST_MICROPHONE_PERMISSION").apply {
                setClassName(appContext.packageName, "dev.patrickgold.florisboard.ime.voice.VoicePermissionActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            appContext.startActivity(intent)
            appContext.showShortToast("🎤 Please grant microphone permission for voice input")
        } catch (e: Exception) {
            flogError { "Failed to launch permission request activity: ${e.message}" }
            // Fallback to showing settings toast
            appContext.showShortToast("🎤 Please enable microphone in Settings → Apps → FlorisBoard → Permissions")
        }
    }

    /**
     * Checks if the microphone might be busy (basic heuristic).
     */
    private fun isMicrophoneBusy(): Boolean {
        // Try to create a test MediaRecorder to see if mic is available
        return try {
            val testRecorder = MediaRecorder()
            testRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            testRecorder.release()
            false // If we got here, mic is available
        } catch (e: Exception) {
            flogInfo { "Microphone availability test failed: ${e.message}" }
            true // Mic might be busy
        }
    }

    /**
     * Simple data class for voice response.
     */
    private data class VoiceResponse(
        val success: Boolean,
        val transcription: String?,
        val finalText: String?,
        val error: String?,
        val isAuthenticationError: Boolean = false,
        val usageJson: String? = null,
        val cursorPosition: Int? = null,
        val contextWindowJson: String? = null
    ) {
        var budget: app.whisperme.keyboard.ime.voice.VoiceBudgetStatus? = null
        var failure: app.whisperme.keyboard.ime.voice.VoiceFailure? = null
        var diagnosticsJson: String? = null
        var notice: String? = null
        var preferenceSuggestionJson: String? = null
    }

    /**
     * Data class to hold voice context information for backend processing.
     */
    private data class VoiceContextData(
        val textBeforeCursor: String,
        val textAfterCursor: String,
        val selectedText: String,
        val cursorPosition: Int,
        val fullText: String, // Complete field text for pattern matching
        val packageName: String,
        val appCategory: String?,
        val fieldType: String?,
        val isMultiline: Boolean,
        val fieldHint: String?,
        val fieldLabel: String?,
        val fieldName: String?,
        val fieldId: Int,
        val preferenceProfile: app.whisperme.keyboard.ime.voice.VoicePreferenceProfile?,
        val usePreferences: Boolean,
        val useDictionary: Boolean,
        val dictionary: List<String>,
        val suggestPreferences: Boolean,
        val diagnosticContext: Boolean,
        val insertOnly: Boolean,
        val editorIdentity: android.view.inputmethod.EditorInfo,
        val fieldGeneration: Long,
        val completeField: Boolean,
        val contextOffset: Int,
        val nativeSelectionStart: Int,
        val nativeSelectionEnd: Int,
        val isPasswordField: Boolean,
        val isRichEditor: Boolean,
        val keyboardMode: String,
        val inputShiftState: String,
        val locale: String,
        val contentMimeTypes: List<String>?,
        val imeAction: String,
        val flagNoEnterAction: Boolean,
        val flagNoPersonalizedLearning: Boolean
    )
}
