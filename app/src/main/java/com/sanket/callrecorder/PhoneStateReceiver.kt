package com.sanket.callrecorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Best-effort safety net: if a call starts and the persistent service isn't
 * running (e.g. after a reboot before the app was opened), try to (re)start it.
 * Every start is guarded — modern Android may refuse a background foreground-
 * service start, and that must never crash the app the way the old version did.
 * Normal operation is handled by RecordingService's own call-state listener.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Prefs(context).autoRecord) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_OFFHOOK) return

        // Never start a mic foreground service without the permission — that
        // would fail startForeground() and trip the watchdog crash.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        try {
            val svc = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_ENABLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            // Android refused a background FGS start. Nothing to do; if the
            // service is already running it will record on its own.
            Log.w("PhoneStateReceiver", "Could not start service from background: ${e.message}")
        }
    }
}
