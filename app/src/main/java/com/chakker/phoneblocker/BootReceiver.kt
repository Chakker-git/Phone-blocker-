package com.chakker.phoneblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the DNS filter back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DnsVpnService.ensureRunning(context)
    }
}
