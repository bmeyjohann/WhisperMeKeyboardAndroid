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

package org.florisboard.lib.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes
import org.florisboard.lib.kotlin.CurlyArg
import java.util.concurrent.atomic.AtomicBoolean

private val toastHandler by lazy { Handler(Looper.getMainLooper()) }

/** A notification that can safely be cancelled from any thread, even before it is shown. */
class ToastHandle internal constructor() {
    private val cancelled = AtomicBoolean(false)
    // Accessed only on Main. Callers never receive a thread-affine Android Toast.
    private var toast: Toast? = null

    internal fun show(context: Context, duration: Int, text: () -> String) {
        onMain {
            if (!cancelled.get()) {
                toast = Toast.makeText(context, text(), duration).also { it.show() }
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        onMain {
            toast?.cancel()
            toast = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else toastHandler.post { action() }
    }
}

private fun Context.showToast(duration: Int, text: () -> String): ToastHandle =
    ToastHandle().also { it.show(this, duration, text) }

/**
 * Shows a short toast with specified text.
 *
 * @param text The text to show in the toast popup.
 */
fun Context.showShortToast(text: String): ToastHandle = showToast(Toast.LENGTH_SHORT) { text }

/**
 * Shows a short toast with the string resource specified by [id].
 *
 * @param id The string resource id of the text to display. Must not be 0.
 */
fun Context.showShortToast(@StringRes id: Int): ToastHandle = showToast(Toast.LENGTH_SHORT) { stringRes(id) }

/**
 * Shows a short toast with the string resource specified by [id], additionally curly formatting the string with
 * supplied arguments [args].
 *
 * @param id The string resource id of the text to display. Must not be 0.
 * @param args The curly arguments which will be filled into the string template identified by [id].
 */
fun Context.showShortToast(@StringRes id: Int, vararg args: CurlyArg): ToastHandle =
    showToast(Toast.LENGTH_SHORT) { stringRes(id, *args) }

/**
 * Shows a long toast with specified text.
 *
 * @param text The text to show in the toast popup.
 */
fun Context.showLongToast(text: String): ToastHandle = showToast(Toast.LENGTH_LONG) { text }

/**
 * Shows a long toast with the string resource specified by [id].
 *
 * @param id The string resource id of the text to display. Must not be 0.
 */
fun Context.showLongToast(@StringRes id: Int): ToastHandle = showToast(Toast.LENGTH_LONG) { stringRes(id) }

/**
 * Shows a long toast with the string resource specified by [id], additionally curly formatting the string with
 * supplied arguments [args].
 *
 * @param id The string resource id of the text to display. Must not be 0.
 * @param args The curly arguments which will be filled into the string template identified by [id].
 */
fun Context.showLongToast(@StringRes id: Int, vararg args: CurlyArg): ToastHandle =
    showToast(Toast.LENGTH_LONG) { stringRes(id, *args) }
