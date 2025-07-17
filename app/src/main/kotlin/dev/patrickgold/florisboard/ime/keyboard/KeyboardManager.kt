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

package dev.patrickgold.florisboard.ime.keyboard

import android.content.Context
import android.content.Intent
import android.icu.lang.UCharacter
import android.media.MediaRecorder
import android.util.Base64
import android.view.KeyEvent
import android.widget.Toast
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.auth.Auth0Manager
import dev.patrickgold.florisboard.app.florisPreferenceModel
import dev.patrickgold.florisboard.appContext
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.extensionManager
import dev.patrickgold.florisboard.ime.ImeUiMode
import dev.patrickgold.florisboard.ime.core.DisplayLanguageNamesIn
import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypePreset
import dev.patrickgold.florisboard.ime.editor.EditorContent
import dev.patrickgold.florisboard.ime.editor.FlorisEditorInfo
import dev.patrickgold.florisboard.ime.editor.ImeOptions
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.input.CapitalizationBehavior
import dev.patrickgold.florisboard.ime.input.InputEventDispatcher
import dev.patrickgold.florisboard.ime.input.InputKeyEventReceiver
import dev.patrickgold.florisboard.ime.input.InputShiftState
import dev.patrickgold.florisboard.ime.nlp.ClipboardSuggestionCandidate
import dev.patrickgold.florisboard.ime.nlp.PunctuationRule
import dev.patrickgold.florisboard.ime.nlp.SuggestionCandidate
import dev.patrickgold.florisboard.ime.onehanded.OneHandedMode
import dev.patrickgold.florisboard.ime.popup.PopupMappingComponent
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import dev.patrickgold.florisboard.ime.text.key.KeyCode
import dev.patrickgold.florisboard.ime.text.key.KeyType
import dev.patrickgold.florisboard.ime.text.key.UtilityKeyAction
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyboardCache
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogError
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.lib.titlecase
import dev.patrickgold.florisboard.lib.uppercase
import dev.patrickgold.florisboard.lib.util.InputMethodUtils
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.florisboard.subtypeManager
import java.lang.ref.WeakReference
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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

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
    private var lastToastReference = WeakReference<Toast>(null)
    
    // Voice recording state
    private val _isVoiceRecording = MutableStateFlow(false)
    val isVoiceRecording = _isVoiceRecording.asStateFlow()
    private val _isVoiceProcessing = MutableStateFlow(false)
    val isVoiceProcessing = _isVoiceProcessing.asStateFlow()
    private var voiceRecordingFile: File? = null
    private var mediaRecorder: MediaRecorder? = null
    private var wasPendingVoiceInput = false
    private var previousUiModeBeforeVoice: ImeUiMode? = null

    private val activeEvaluatorGuard = Mutex(locked = false)
    private var activeEvaluatorVersion = AtomicInteger(0)
    private val _activeEvaluator = MutableStateFlow<ComputingEvaluator>(DefaultComputingEvaluator)
    val activeEvaluator get() = _activeEvaluator.asStateFlow()
    private val _activeSmartbarEvaluator = MutableStateFlow<ComputingEvaluator>(DefaultComputingEvaluator)
    val activeSmartbarEvaluator get() = _activeSmartbarEvaluator.asStateFlow()
    private val _lastCharactersEvaluator = MutableStateFlow<ComputingEvaluator>(DefaultComputingEvaluator)
    val lastCharactersEvaluator get() = _lastCharactersEvaluator.asStateFlow()

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
        if (activeState.inputShiftState != InputShiftState.CAPS_LOCK && !inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
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
        scope.launch {
            candidate.sourceProvider?.notifySuggestionAccepted(subtypeManager.activeSubtype, candidate)
        }
        when (candidate) {
            is ClipboardSuggestionCandidate -> editorInstance.commitClipboardItem(candidate.clipboardItem)
            else -> editorInstance.commitCompletion(candidate)
        }
    }

    fun commitGesture(word: String) {
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
        val isShiftPressed = activeState.isManualSelectionMode || inputEventDispatcher.isPressed(KeyCode.SHIFT)
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
        lastToastReference.get()?.cancel()
        lastToastReference = WeakReference(
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
        )
    }

    /**
     * Handles a [KeyCode.TOGGLE_AUTOCORRECT] event.
     */
    private fun handleToggleAutocorrect() {
        lastToastReference.get()?.cancel()
        lastToastReference = WeakReference(
            appContext.showLongToast("Autocorrect toggle is a placeholder and not yet implemented")
        )
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
            KeyCode.VIEW_NUMERIC_ADVANCED -> activeState.keyboardMode = KeyboardMode.NUMERIC_ADVANCED
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
                if (activeState.inputShiftState != InputShiftState.CAPS_LOCK && !inputEventDispatcher.isPressed(KeyCode.SHIFT)) {
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
    private fun startVoiceRecording() {
        android.util.Log.d("KeyboardManager", "startVoiceRecording() called")
        if (_isVoiceRecording.value) {
            flogInfo { "Voice recording already in progress" }
            android.util.Log.d("KeyboardManager", "Voice recording already in progress")
            return
        }

        val editorInfo = editorInstance.activeInfo
        val editorContent = editorInstance.activeContent
        val inputConnection = FlorisImeService.currentInputConnection()

        flogInfo { "=== VOICE RECORDING START ===" }
        flogInfo { "Package: ${editorInfo.packageName}" }
        flogInfo { "Field hint: ${editorInfo.base.hintText}" }
        flogInfo { "Text before cursor: '${editorContent.textBeforeSelection.takeLast(50)}'" }
        
        // Check microphone permission first
        if (!hasRecordAudioPermission()) {
            flogError { "RECORD_AUDIO permission not granted" }
            wasPendingVoiceInput = true // Remember we wanted to start voice input
            requestMicrophonePermission()
            return
        }
        
        // Check if microphone is available
        if (isMicrophoneBusy()) {
            flogError { "Microphone is currently in use by another app" }
            appContext.showShortToast("🎤 Microphone in use by another app")
            return
        }
        
        // Start the actual recording
        try {
            // Create output file (using .wav for optimal Groq performance)
            voiceRecordingFile = File(appContext.cacheDir, "voice_recording_${System.currentTimeMillis()}.wav")
            flogInfo { "Created audio file: ${voiceRecordingFile?.absolutePath}" }
            
            // Initialize MediaRecorder with better configuration
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
            previousUiModeBeforeVoice = activeState.imeUiMode
            activeState.imeUiMode = ImeUiMode.VOICE_RECORDING
            
            flogInfo { "Voice recording started successfully" }
            flogInfo { "Output file: ${voiceRecordingFile?.absolutePath}" }
            flogInfo { "Switched from ${previousUiModeBeforeVoice} to VOICE_RECORDING mode" }
            
            // Gather context for when we stop recording
            val voiceContext = buildVoiceContext(editorInfo, editorContent, inputConnection)
            flogInfo { "Context gathered: ${voiceContext.packageName}, field: ${voiceContext.fieldType}" }
            
            // Note: Removed toast to avoid occluding text input - recording state is shown in button
            
        } catch (e: SecurityException) {
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
            
            // Note: Removed toast to avoid occluding text input - processing state is shown in button
            
            // Gather context information for the backend
            val voiceContext = buildVoiceContext(editorInfo, editorContent, inputConnection)
            
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
                    flogError { "Audio file is invalid: exists=${audioFile.exists()}, size=${audioFile.length()}" }
                    appContext.showShortToast("❌ No audio recorded")
                    _isVoiceProcessing.value = false
                }
            } ?: run {
                flogError { "No audio file to process" }
                appContext.showShortToast("❌ No audio file found")
                _isVoiceProcessing.value = false
            }
            
        } catch (e: RuntimeException) {
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

    /**
     * Builds voice context from current editor state for backend processing.
     */
    private fun buildVoiceContext(
        editorInfo: FlorisEditorInfo,
        editorContent: EditorContent,
        inputConnection: InputConnection?
    ): VoiceContextData {
        // Get text parts from input connection first (more reliable), fallback to editor content
        val textBeforeCursor = inputConnection?.getTextBeforeCursor(500, 0)?.toString() 
            ?: editorContent.textBeforeSelection
        val textAfterCursor = inputConnection?.getTextAfterCursor(500, 0)?.toString() 
            ?: editorContent.textAfterSelection
        val selectedText = inputConnection?.getSelectedText(0)?.toString() 
            ?: editorContent.selectedText
        
        // Build complete field text - this is the authoritative source
        val fullText = textBeforeCursor + selectedText + textAfterCursor
        
        val context = VoiceContextData(
            textBeforeCursor = textBeforeCursor,
            textAfterCursor = textAfterCursor,
            selectedText = selectedText,
            cursorPosition = editorContent.selection.start,
            fullText = fullText,
            packageName = editorInfo.packageName ?: "unknown",
            fieldType = determineFieldType(editorInfo),
            fieldHint = editorInfo.base.hintText?.toString(),
            fieldLabel = editorInfo.base.label?.toString(),
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
        
        // Log context for verification
        flogInfo { "=== VOICE CONTEXT GATHERED ===" }
        flogInfo { "Package: ${context.packageName}" }
        flogInfo { "Field type: ${context.fieldType}" }
        flogInfo { "Field hint: '${context.fieldHint}'" }
        flogInfo { "Full text (${fullText.length} chars): '${fullText}'" }
        flogInfo { "Text before cursor (${textBeforeCursor.length} chars): '${textBeforeCursor.takeLast(50)}'" }
        flogInfo { "Selected text (${selectedText.length} chars): '${selectedText}'" }
        flogInfo { "Text after cursor (${textAfterCursor.length} chars): '${textAfterCursor.take(50)}'" }
        flogInfo { "Cursor position: ${context.cursorPosition}" }
        flogInfo { "Is password field: ${context.isPasswordField}" }
        
        // Verify text reconstruction
        val reconstructed = textBeforeCursor + selectedText + textAfterCursor
        if (reconstructed != fullText) {
            flogError { "Text reconstruction mismatch!" }
            flogError { "Expected: '$fullText'" }
            flogError { "Got: '$reconstructed'" }
        } else {
            flogInfo { "✅ Text reconstruction verified" }
        }
        
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
        try {
            flogInfo { "=== PROCESSING VOICE RECORDING ===" }
            flogInfo { "Audio file: ${audioFile.absolutePath}" }
            flogInfo { "File size: ${audioFile.length()} bytes" }
            flogInfo { "File exists: ${audioFile.exists()}" }
            
            if (!audioFile.exists() || audioFile.length() == 0L) {
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
            
            // Send to backend
            flogInfo { "Sending to backend..." }
            val response = sendVoiceToBackend(base64Audio, context)
            flogInfo { "Backend response received: success=${response.success}" }
            
            // Process the response
            withContext(Dispatchers.Main) {
                processVoiceResponse(response)
            }
            
        } catch (e: OutOfMemoryError) {
            flogError { "Out of memory processing audio: ${e.message}" }
            withContext(Dispatchers.Main) {
                appContext.showShortToast("❌ Audio file too large")
            }
        } catch (e: Exception) {
            flogError { "Failed to process voice recording: ${e.message}" }
            flogError { "Exception type: ${e.javaClass.simpleName}" }
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                handleVoiceError("Failed to process audio: ${e.message}")
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
            
            // Reset processing state and return to previous UI mode
            _isVoiceProcessing.value = false
            voiceRecordingFile = null
            
            // Return to previous UI mode
            previousUiModeBeforeVoice?.let { previousMode ->
                activeState.imeUiMode = previousMode
                flogInfo { "Returned to previous UI mode: $previousMode" }
                previousUiModeBeforeVoice = null
            }
        }
    }
    
    /**
     * Sends voice data to the backend for processing.
     */
    private suspend fun sendVoiceToBackend(base64Audio: String, context: VoiceContextData): VoiceResponse {
        val serverUrl = "https://process-voice.whisperme.app"
        
        return withContext(Dispatchers.IO) {
            try {
                flogInfo { "=== SENDING TO BACKEND ===" }
                flogInfo { "URL: $serverUrl" }
                flogInfo { "Audio length: ${base64Audio.length} characters" }
                flogInfo { "Context: ${context.packageName} - ${context.fieldType}" }
                flogInfo { "Text before cursor: '${context.textBeforeCursor.takeLast(100)}'" }
                
                // Get authentication token from Auth0Manager
                val authManager = Auth0Manager.getInstance(appContext)
                val authToken: String? = authManager.accessToken.value
                
                // Create JSON request body
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
                connection.readTimeout = 60000 // 60 seconds
                
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
                } else {
                    flogError { "HTTP error $responseCode: $responseBody" }
                    VoiceResponse(
                        success = false,
                        transcription = null,
                        finalText = null,
                        error = "Server error: $responseCode - ${responseBody.take(200)}"
                    )
                }
                
            } catch (e: java.net.SocketTimeoutException) {
                flogError { "Request timeout: ${e.message}" }
                VoiceResponse(success = false, transcription = null, finalText = null, error = "Request timeout - check your internet connection")
            } catch (e: java.net.UnknownHostException) {
                flogError { "Network error: ${e.message}" }
                VoiceResponse(success = false, transcription = null, finalText = null, error = "Network error - check your internet connection")
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
        // Escape JSON strings
        fun String.escapeJson(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        
        return """
            {
                "audio": "$base64Audio",
                "format": "wav",
                "context": {
                    "textBeforeCursor": "${context.textBeforeCursor.escapeJson()}",
                    "textAfterCursor": "${context.textAfterCursor.escapeJson()}",
                    "selectedText": "${context.selectedText.escapeJson()}",
                    "cursorPosition": ${context.cursorPosition},
                    "fullText": "${context.fullText.escapeJson()}",
                    "packageName": "${context.packageName.escapeJson()}",
                    "fieldType": "${context.fieldType?.escapeJson() ?: ""}",
                    "fieldHint": "${context.fieldHint?.escapeJson() ?: ""}",
                    "fieldLabel": "${context.fieldLabel?.escapeJson() ?: ""}",
                    "isPasswordField": ${context.isPasswordField},
                    "isRichEditor": ${context.isRichEditor},
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
            }
        """.trimIndent()
    }
    
    /**
     * Parses the JSON response from the backend.
     */
    private fun parseVoiceResponse(responseBody: String): VoiceResponse {
        return try {
            flogInfo { "=== PARSING BACKEND RESPONSE ===" }
            flogInfo { "Response (${responseBody.length} chars): ${responseBody.take(200)}..." }
            
            // Check if request was successful
            val successPattern = """"success":\s*true""".toRegex()
            val errorPattern = """"error":\s*"([^"]+)"""".toRegex()
            
            if (!successPattern.containsMatchIn(responseBody)) {
                val error = errorPattern.find(responseBody)?.groupValues?.get(1) ?: "Unknown backend error"
                flogError { "Backend error: $error" }
                return VoiceResponse(success = false, transcription = null, finalText = null, error = error)
            }
            
            // Extract transcription text
            val transcriptionTextPattern = """"transcription":\s*\{[^}]*"text":\s*"([^"]+)"""".toRegex()
            val transcription = transcriptionTextPattern.find(responseBody)?.groupValues?.get(1) ?: ""
            
            // Extract final text (new simplified format)
            val finalTextPattern = """"finalText":\s*"([^"]*(?:\\.[^"]*)*)"""".toRegex()
            val finalText = finalTextPattern.find(responseBody)?.groupValues?.get(1)?.let { text ->
                // Unescape JSON
                text.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
            }
            
            flogInfo { "Transcription: '$transcription'" }
            flogInfo { "Final text (${finalText?.length ?: 0} chars): '$finalText'" }
            
            return VoiceResponse(
                success = true,
                transcription = transcription,
                finalText = finalText,
                error = null
            )
            
        } catch (e: Exception) {
            flogError { "Failed to parse response: ${e.message}" }
            e.printStackTrace()
            VoiceResponse(success = false, transcription = null, finalText = null, error = "Failed to parse response: ${e.message}")
        }
    }
    

    
    /**
     * Processes the response from the backend.
     */
    private fun processVoiceResponse(response: VoiceResponse) {
        flogInfo { "=== PROCESSING VOICE RESPONSE ===" }
        flogInfo { "Success: ${response.success}" }
        flogInfo { "Transcription: '${response.transcription}'" }
        flogInfo { "Final text: '${response.finalText}'" }
        flogInfo { "Error: ${response.error}" }
        
        if (!response.success) {
            val errorMessage = response.error ?: "Unknown error"
            flogError { "Voice processing failed: $errorMessage" }
            handleVoiceError(errorMessage)
            return
        }
        
        try {
            val finalText = response.finalText
            
            if (finalText != null) {
                flogInfo { "Replacing field content with final text (${finalText.length} chars)" }
                
                // Get current editor content for comparison
                val currentContent = editorInstance.activeContent
                val currentText = currentContent.textBeforeSelection + currentContent.selectedText + currentContent.textAfterSelection
                
                flogInfo { "Current text: '$currentText'" }
                flogInfo { "New text: '$finalText'" }
                
                if (finalText != currentText) {
                    // Select all current text and replace it with the final text
                    editorInstance.performClipboardSelectAll()
                    editorInstance.commitText(finalText)
                    flogInfo { "✅ Text replacement completed" }
                } else {
                    flogInfo { "ℹ️ Final text same as current - no change needed" }
                }
                
            } else {
                flogError { "No final text in successful response" }
                // Fallback to direct transcription if available
                response.transcription?.let { text ->
                    if (text.isNotBlank()) {
                        flogInfo { "Fallback: Using direct transcription" }
                        editorInstance.commitText(text)
                    } else {
                        appContext.showShortToast("🔇 No speech detected")
                    }
                } ?: run {
                    appContext.showShortToast("❌ Empty response from server")
                }
            }
        } catch (e: Exception) {
            flogError { "Failed to process voice response: ${e.message}" }
            e.printStackTrace()
            appContext.showShortToast("❌ Failed to process response")
        }
    }
    

    
    /**
     * Handles voice processing errors.
     */
    private fun handleVoiceError(error: String) {
        flogError { "Voice processing error: $error" }
        
        // Restore previous UI mode on error
        previousUiModeBeforeVoice?.let { previousMode ->
            activeState.imeUiMode = previousMode
            flogInfo { "Restored UI mode to $previousMode due to voice error" }
            previousUiModeBeforeVoice = null
        }
        
        // Show user-friendly error message
        val userMessage = when {
            error.contains("timeout", ignoreCase = true) -> "⏱️ Request timed out - try again"
            error.contains("network", ignoreCase = true) -> "🌐 Network error - check connection"
            error.contains("permission", ignoreCase = true) -> "🎤 Microphone permission required"
            error.contains("server", ignoreCase = true) -> "🔧 Server error - try again later"
            error.contains("audio", ignoreCase = true) -> "🔊 Audio recording error"
            else -> "❌ Voice input failed"
        }
        
        appContext.showShortToast(userMessage)
        flogInfo { "Displayed user message: $userMessage" }
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
        val error: String?
    )

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
        val fieldType: String?,
        val fieldHint: String?,
        val fieldLabel: String?,
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
