package app.whisperme.keyboard.ime.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showShortToast

/** Realtime startup runs on IO; creating a Toast requires the main looper. */
internal suspend fun showInsertionOnlyNotice(context: Context) = withContext(Dispatchers.Main.immediate) {
    context.showShortToast("Insertion-only dictation: editing existing text is unavailable in this field.")
    Unit
}
