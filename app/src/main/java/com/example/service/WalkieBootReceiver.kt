package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.DeviceIdentityStore
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Automatically restarts Mamun Walkie Talkie's background Foreground Service after device reboot
 * when the user is signed in and has Keep Active in Background + Auto-Start on Boot enabled.
 */
class WalkieBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = DeviceIdentityStore(context.applicationContext).readSnapshot()
                val signedIn = Firebase.auth.currentUser != null
                if (signedIn && prefs.poweredOn && prefs.keepActiveInBackground && prefs.autoStartOnBoot) {
                    WalkieForegroundService.startOnBoot(context.applicationContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
