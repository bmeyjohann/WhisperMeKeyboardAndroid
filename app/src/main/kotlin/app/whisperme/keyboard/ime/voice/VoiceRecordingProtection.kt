package app.whisperme.keyboard.ime.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.whisperme.keyboard.FlorisImeService
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Promotes only the existing system-bound IME. Call start on Main before opening the microphone.
 * A false result means the caller must not promise background capture: keep recording visible or
 * stop/flush safely on hide. No service is started/restarted here and no permission UI is opened.
 *
 * The notification callback must finish/flush through the caller's normal recording path. It must
 * preserve detached results for copying rather than force insertion. Call stop after the recorder
 * has stopped/drained (also on failed startup/cancellation); a notification tap alone does not tear
 * down microphone protection before that work completes. Foreground protection is not a CPU wake
 * lock and cannot guarantee survival after process death, force-stop or permission revocation.
 */
object VoiceRecordingProtection {
    private const val CHANNEL_ID = "whisperme_voice_recording"
    private const val NOTIFICATION_ID = 0x574d01
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var active: Session? = null
    @Volatile var lastFailure: String? = null
        private set
    /** Permission/channel snapshot at promotion, not proof that Android rendered the notification. */
    @Volatile var notificationAvailable: Boolean = false
        private set
    private var awakeWindow: WeakReference<Window>? = null
    private var awakeIme: WeakReference<FlorisImeService>? = null
    private var originalWindowFlag = false
    private var originalViewFlag = false
    private var awakeObserver: LifecycleEventObserver? = null

    private class Session(val ime: FlorisImeService, onStop: () -> Unit) {
        val command = VoiceRecordingStopAction("${ime.packageName}.STOP_DICTATION.${UUID.randomUUID()}", onStop)
        var receiver: BroadcastReceiver? = null
        var observer: LifecycleEventObserver? = null
        var pendingStop: PendingIntent? = null
        var foregroundAttempted = false
    }

    /** True only after Android accepts foreground promotion; idempotent for the current session. */
    @MainThread
    fun start(ime: FlorisImeService, onStop: () -> Unit): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            lastFailure = "Start microphone protection on the main thread."
            return false
        }
        lastFailure = null
        active?.let { existing ->
            if (existing.ime === ime && !existing.command.stopRequested) return true
            lastFailure = "An earlier dictation is still finishing."
            return false
        }
        if (ime.lifecycle.currentState == Lifecycle.State.DESTROYED || !ime.isInputViewShown) {
            lastFailure = "Start dictation while the WhisperMe keyboard is visible."
            return false
        }

        var session: Session? = null
        return try {
            if (ContextCompat.checkSelfPermission(ime, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastFailure = "Microphone permission is unavailable."
                return false
            }
            val notifications = ime.getSystemService(NotificationManager::class.java)
                ?: throw IllegalStateException("Notification service unavailable")
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Voice recording", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows active dictation and a Stop action."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(null, null)
                enableVibration(false)
            })
            // Notification denial does not prohibit Android-supported microphone FGS. No prompt:
            // the caller retains its keyboard Stop control and Android retains Active Apps Stop.
            val canNotify = notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

            val current = Session(ime, onStop).also { session = it }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (active !== current) return
                    try {
                        current.command.receive(intent?.action)
                    } catch (failure: RuntimeException) {
                        // Keep protection until the parent's cleanup path has drained the recorder.
                        lastFailure = "Could not finish dictation (${failure.javaClass.simpleName})."
                    }
                }
            }
            ContextCompat.registerReceiver(ime, receiver, IntentFilter(current.command.action), ContextCompat.RECEIVER_NOT_EXPORTED)
            current.receiver = receiver
            current.pendingStop = PendingIntent.getBroadcast(ime, 0,
                Intent(current.command.action).setPackage(ime.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            val notification = NotificationCompat.Builder(ime, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("WhisperMe is recording")
                .setContentText("Tap Stop to finish dictation.")
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(android.R.drawable.ic_media_pause, "Stop", current.pendingStop)
                .build()
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> clearScreenAwake(ime)
                    Lifecycle.Event.ON_DESTROY -> release(current)
                    else -> Unit
                }
            }
            current.observer = observer
            ime.lifecycle.addObserver(observer)
            current.foregroundAttempted = true
            // The selected-IME background-start exemption does not by itself grant microphone
            // while-in-use access. Android's actual promotion check remains authoritative.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ime.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                ime.startForeground(NOTIFICATION_ID, notification)
            }
            active = current
            notificationAvailable = canNotify
            true
        } catch (failure: RuntimeException) {
            session?.let(::release)
            lastFailure = "Microphone foreground protection was not granted (${failure.javaClass.simpleName})."
            false
        }
    }

    /** Safe from IO too. A delayed cleanup cannot tear down a replacement protection session. */
    fun stop() {
        val current = active
        onMain {
            if (current != null) release(current)
            else if (active == null) clearScreenAwake()
        }
    }

    /** Owner-scoped cleanup for an IME instance's destruction; stale instances cannot stop a new one. */
    fun stop(ime: FlorisImeService) {
        val current = active?.takeIf { it.ime === ime }
        onMain {
            if (current != null) release(current)
            clearScreenAwake(ime)
        }
    }

    /** Only the visible IME window/view is kept awake. Never wakes the display or bypasses its lock. */
    fun setScreenAwake(ime: FlorisImeService, visible: Boolean, recording: Boolean) = onMain {
        val window = ime.window?.window
        val keep = visible && recording && ime.isInputViewShown &&
            ime.lifecycle.currentState != Lifecycle.State.DESTROYED
        if (!keep || window == null) {
            clearScreenAwake(ime)
        } else {
            if (awakeWindow?.get() !== window) {
                clearScreenAwake()
                awakeIme = WeakReference(ime)
                awakeWindow = WeakReference(window)
                originalWindowFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                originalViewFlag = window.decorView.keepScreenOn
                val owner = WeakReference(ime)
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                        owner.get()?.let(::clearScreenAwake)
                    }
                }
                awakeObserver = observer
                ime.lifecycle.addObserver(observer)
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.keepScreenOn = true
        }
    }

    private fun clearScreenAwake(ime: FlorisImeService? = null) {
        if (ime != null && awakeIme?.get() !== ime) return
        val owner = awakeIme?.get()
        val observer = awakeObserver
        val window = awakeWindow?.get()
        awakeWindow = null
        awakeIme = null
        awakeObserver = null
        if (owner != null && observer != null) owner.lifecycle.removeObserver(observer)
        if (window != null) {
            window.decorView.keepScreenOn = originalViewFlag
            if (!originalWindowFlag) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        originalWindowFlag = false
        originalViewFlag = false
    }

    private fun release(session: Session) {
        // A lifecycle callback for an older instance must never remove the current notification.
        if (active != null && active !== session) return
        if (active === session) {
            active = null
            notificationAvailable = false
        }
        session.command.close()
        clearScreenAwake(session.ime)
        session.observer?.let { session.ime.lifecycle.removeObserver(it) }
        session.observer = null
        session.receiver?.let { receiver ->
            try { session.ime.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        }
        session.receiver = null
        session.pendingStop?.cancel()
        session.pendingStop = null
        if (session.foregroundAttempted) {
            try { session.ime.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
            catch (failure: RuntimeException) { lastFailure = "Could not remove recording protection (${failure.javaClass.simpleName})." }
            session.foregroundAttempted = false
        }
    }

    private inline fun onMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }
}
