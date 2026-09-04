package com.sanket.callrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A persistent foreground service that the user ENABLES from inside the app
 * (a foreground start, which Android always allows). Once running it listens
 * for call-state changes itself and records the microphone only while a call
 * is connected. Nothing is ever started from a background broadcast, which is
 * what crashed the earlier version on modern Android.
 *
 * WHAT IT CAN CAPTURE: on a non-rooted Android 10+ phone the true call stream
 * is off-limits to third-party apps, so this records the mic. Use SPEAKERPHONE
 * for clear two-way audio.
 */
class RecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var listening = false

    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    companion object {
        const val ACTION_ENABLE = "com.sanket.callrecorder.ENABLE"
        const val ACTION_DISABLE = "com.sanket.callrecorder.DISABLE"
        const val ACTION_RECORD_NOW = "com.sanket.callrecorder.RECORD_NOW"
        const val ACTION_STOP_NOW = "com.sanket.callrecorder.STOP_NOW"

        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1001
        private const val TAG = "RecordingService"

        @Volatile var isRecording = false
            private set

        fun recordingsDir(context: Context): File {
            val dir = File(context.getExternalFilesDir(null), "recordings")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_DISABLE -> {
                    stopRecording()
                    stopListening()
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                ACTION_STOP_NOW -> {
                    stopRecording()
                    updateNotification("Listening for calls")
                }
                ACTION_RECORD_NOW -> {
                    if (!ensureForeground()) return START_NOT_STICKY
                    startListening()
                    startRecording(null)
                }
                else -> { // ACTION_ENABLE or restart
                    if (!ensureForeground()) return START_NOT_STICKY
                    startListening()
                    // If a call is already connected when we start (e.g. the
                    // receiver started us mid-call), begin recording now.
                    if (Prefs(this).autoRecord && currentCallState() == TelephonyManager.CALL_STATE_OFFHOOK) {
                        startRecording(null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand failed: ${e.message}", e)
        }
        return START_STICKY
    }

    // ---- Foreground notification ----------------------------------------

    /**
     * Enters the foreground. Returns false if it could not (e.g. Android
     * refused a background mic-FGS start, or RECORD_AUDIO is missing). On
     * failure we stopSelf() so the "startForegroundService did not call
     * startForeground" watchdog never crashes the app.
     */
    private fun ensureForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call recording", NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            nm?.createNotificationChannel(channel)
        }
        val notif = buildNotification("Listening for calls")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            stopSelf()
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    // ---- Call-state listening (in-service, no background broadcasts) -----

    private fun startListening() {
        if (listening) return
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleCallState(state)
                }
                telephonyCallback = cb
                tm.registerTelephonyCallback(mainExecutor, cb)
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in API 31")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleCallState(state)
                }
                phoneStateListener = l
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
            listening = true
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}", e)
        }
    }

    private fun stopListening() {
        val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    tm?.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (_: Exception) {}
        telephonyCallback = null
        phoneStateListener = null
        listening = false
    }

    private fun currentCallState(): Int {
        return try {
            val tm = getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            tm?.callState ?: TelephonyManager.CALL_STATE_IDLE
        } catch (e: Exception) {
            TelephonyManager.CALL_STATE_IDLE
        }
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (Prefs(this).autoRecord) startRecording(null)
            TelephonyManager.CALL_STATE_IDLE -> stopRecording()
        }
    }

    // ---- Recording -------------------------------------------------------

    private fun startRecording(number: String?) {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No RECORD_AUDIO permission; skipping.")
            return
        }
        val prefs = Prefs(this)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safe = number?.replace(Regex("[^0-9+]"), "")?.takeIf { it.isNotBlank() } ?: "call"
        val file = File(recordingsDir(this), "${stamp}_$safe.m4a")

        for (source in AUDIO_SOURCES) {
            try {
                @Suppress("DEPRECATION")
                val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    MediaRecorder(this) else MediaRecorder()
                r.setAudioSource(source)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(44100)
                r.setAudioEncodingBitRate(prefs.bitrate)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                outputFile = file
                isRecording = true
                updateNotification("Recording…")
                Log.i(TAG, "Recording via source $source -> ${file.name}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $source failed: ${e.message}")
            }
        }
        Log.e(TAG, "No usable audio source.")
        file.delete()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        isRecording = false
        try {
            r.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop() failed (call too short?): ${e.message}")
            outputFile?.delete()
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
        outputFile = null
    }

    private fun stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopRecording()
        stopListening()
        super.onDestroy()
    }
}
