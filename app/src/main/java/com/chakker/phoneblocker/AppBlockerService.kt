package com.chakker.phoneblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Two jobs:
 *  1. App blocking: a blocked package in the foreground gets a Home press plus a block screen.
 *  2. Uninstall guard: Settings, uninstall and VPN-manage screens that mention this app get the
 *     same treatment, unless an uninstall unlock (or the short setup grace) is open.
 *
 * It also acts as a watchdog that restarts the DNS VPN if it died.
 */
class AppBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastBlockAt = 0L
    private var appLabel = "Phone Blocker"

    override fun onServiceConnected() {
        super.onServiceConnected()
        appLabel = applicationInfo.loadLabel(packageManager).toString()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (Config.isEnabled(this)) {
            DnsVpnService.ensureRunning(this)
            if (pkg in Config.packages(this)) {
                block("Blocked.", "Go do something else.")
                return
            }
        }

        if (isGuarded(pkg) && guardActive()) {
            val cls = event.className?.toString() ?: ""
            checkGuard(cls)
            // Screen content often fills in after the window event, so look again shortly after.
            handler.postDelayed({ checkGuard(cls) }, 400)
            handler.postDelayed({ checkGuard(cls) }, 1200)
        }
    }

    private fun guardActive(): Boolean = !LockManager.isGuardOpen(this)

    /** Packages whose screens can remove, disable or disconnect this app. */
    private fun isGuarded(pkg: String): Boolean =
        pkg.contains(".settings") ||
            pkg.contains("packageinstaller") ||
            pkg.contains("vpndialogs")

    private fun checkGuard(eventClass: String) {
        if (!guardActive()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!isGuarded(pkg)) return

        val hit = if (pkg.contains("vpndialogs")) {
            // Only the "manage / disconnect" dialog, not the one that asks permission to start the VPN.
            eventClass.contains("ManageDialog") && mentionsApp(root)
        } else {
            mentionsApp(root)
        }
        if (hit) {
            block("Locked.", "Request an uninstall unlock in Phone Blocker to change or remove it.")
        }
    }

    private fun mentionsApp(root: AccessibilityNodeInfo): Boolean = scan(root, 0, IntArray(1))

    private fun scan(node: AccessibilityNodeInfo?, depth: Int, budget: IntArray): Boolean {
        if (node == null || depth > 30) return false
        budget[0]++
        if (budget[0] > 600) return false

        val text = node.text
        if (text != null && text.contains(appLabel, ignoreCase = true)) return true
        val desc = node.contentDescription
        if (desc != null && desc.contains(appLabel, ignoreCase = true)) return true

        for (i in 0 until node.childCount) {
            if (scan(node.getChild(i), depth + 1, budget)) return true
        }
        return false
    }

    private fun block(title: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 1000) return
        lastBlockAt = now

        // The home press is the guaranteed part; the block screen is a courtesy on top.
        performGlobalAction(GLOBAL_ACTION_HOME)
        try {
            val i = Intent(this, BlockActivity::class.java)
            i.putExtra(BlockActivity.EXTRA_TITLE, title)
            i.putExtra(BlockActivity.EXTRA_MESSAGE, message)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
        } catch (e: Exception) {
            // Background activity start can be refused on some builds. Home press already happened.
        }
    }

    override fun onInterrupt() {}
}
