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
 *  2. Uninstall guard: Settings, uninstall and VPN-manage screens that are about this app get the
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

        val cls = event.className?.toString() ?: ""
        if (isGuarded(pkg) && guardActive() && !isSettingsHome(cls)) {
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

    /**
     * The top-level Settings screen only links to other pages, and it can show the app name in a
     * summary ("Recently sent: ...") without being a page that can change anything. Never guard it.
     */
    private fun isSettingsHome(cls: String): Boolean =
        cls.contains("Homepage", ignoreCase = true) || cls.endsWith(".Settings")

    private fun checkGuard(eventClass: String) {
        if (!guardActive()) return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (!isGuarded(pkg)) return

        val hit = when {
            // Only the "manage / disconnect" dialog, not the one that asks permission to start the VPN.
            pkg.contains("vpndialogs") ->
                eventClass.contains("ManageDialog") && scan(root, 0, IntArray(1), exact = false)
            // The uninstall dialog is only ever about one app, so a mention is enough.
            pkg.contains("packageinstaller") -> scan(root, 0, IntArray(1), exact = false)
            // Settings: only a row or title that is exactly the app name, not a summary that mentions it.
            else -> scan(root, 0, IntArray(1), exact = true)
        }
        if (hit) {
            block("Locked.", "Request an uninstall unlock in Phone Blocker to change or remove it.")
        }
    }

    private fun matches(s: CharSequence?, exact: Boolean): Boolean {
        if (s == null) return false
        return if (exact) {
            s.toString().trim().equals(appLabel, ignoreCase = true)
        } else {
            s.contains(appLabel, ignoreCase = true)
        }
    }

    private fun scan(node: AccessibilityNodeInfo?, depth: Int, budget: IntArray, exact: Boolean): Boolean {
        if (node == null || depth > 30) return false
        budget[0]++
        if (budget[0] > 600) return false

        if (matches(node.text, exact) || matches(node.contentDescription, exact)) return true

        for (i in 0 until node.childCount) {
            if (scan(node.getChild(i), depth + 1, budget, exact)) return true
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
