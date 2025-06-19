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

package dev.patrickgold.florisboard.ime.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.florisboard.lib.android.showShortToast

/**
 * Activity to handle voice recording permission requests from the IME service.
 * This is needed because IME services cannot directly request runtime permissions.
 */
class VoicePermissionActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            showShortToast("🎤 Microphone permission granted! Try voice input again.")
        } else {
            showShortToast("🎤 Permission denied. Voice input requires microphone access.")
            
            // If permission was denied, offer to open app settings
            if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showShortToast("Please enable microphone in Settings → Apps → FlorisBoard → Permissions")
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        when (intent.action) {
            ACTION_REQUEST_MICROPHONE_PERMISSION -> {
                requestMicrophonePermission()
            }
            ACTION_OPEN_APP_SETTINGS -> {
                openAppSettings()
            }
            else -> {
                finish()
            }
        }
    }

    private fun requestMicrophonePermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
                showShortToast("🎤 Microphone permission already granted!")
                finish()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show explanation and request permission
                showShortToast("FlorisBoard needs microphone access for voice input")
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> {
                // Request permission directly
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            showShortToast("Enable microphone permission for voice input")
        } catch (e: Exception) {
            showShortToast("Please manually enable microphone permission in Settings")
        }
        finish()
    }

    companion object {
        const val ACTION_REQUEST_MICROPHONE_PERMISSION = "dev.patrickgold.florisboard.REQUEST_MICROPHONE_PERMISSION"
        const val ACTION_OPEN_APP_SETTINGS = "dev.patrickgold.florisboard.OPEN_APP_SETTINGS"
    }
} 