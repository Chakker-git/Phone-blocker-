package com.chakker.phoneblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Watches which app is in the foreground. If it is on the blocklist, send the user home
 * and flash the block screen. Also acts as a watchdog that restarts the DNS VPN if it died.
 */
class AppBlockerService : AccessibilityService() {

    private var lastBlockAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Config.isEnabled(this)) return

        DnsVpnService.ensureRunning(this)

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in Config.packages(this)) block()
    }

    private fun block() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 1000) return
        lastBlockAt = now

        // The home press is the guaranteed part; the block screen is a courtesy on top.
        performGlobalAction(GLOBAL_ACTION_HOME)
        try {
            startActivity(
                Intent(this, BlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
            // Background activity start can be refused on some builds. Home press already happened.
        }
    }

    override fun onInterrupt() {}
}
