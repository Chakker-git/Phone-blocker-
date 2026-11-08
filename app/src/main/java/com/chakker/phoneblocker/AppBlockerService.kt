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

    // Labels of the other installed apps, used to tell "a list of apps" from "this app's own page".
    private var otherLabels: Set<String> = emptySet()
    private var otherLabelsAt = 0L

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

        val texts = ArrayList<String>()
        collectTexts(root, 0, IntArray(1), texts)

        val hit = when {
            // Only the "manage / disconnect" dialog, not the one that asks permission to start the VPN.
            pkg.contains("vpndialogs") ->
                eventClass.contains("ManageDialog") && texts.any { mentions(it) }
            // The uninstall dialog is only ever about one app, so a mention is enough.
            pkg.contains("packageinstaller") -> texts.any { mentions(it) }
            else -> isOwnSettingsPage(texts)
        }
        if (hit) {
            block("Locked.", "Request an uninstall unlock in Phone Blocker to change or remove it.")
        }
    }

    private fun mentions(s: String): Boolean = s.contains(appLabel, ignoreCase = true)

    /**
     * A Settings page counts as "about this app" when the app name shows up as a row or title
     * on its own, and the screen is not a long list of other apps (Apps, notification app list...).
     * Tapping the app's row in such a list opens its own page, which passes this test and is guarded.
     */
    private fun isOwnSettingsPage(texts: List<String>): Boolean {
        if (texts.none { it.trim().equals(appLabel, ignoreCase = true) }) return false
        val others = otherAppLabels()
        val otherRows = texts.map { it.trim().lowercase() }.filter { it in others }.toSet().size
        return otherRows < 3
    }

    private fun otherAppLabels(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (otherLabels.isNotEmpty() && now - otherLabelsAt < 10 * 60_000L) return otherLabels
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        otherLabels = pm.queryIntentActivities(launcher, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(pm).toString().trim().lowercase() }
            .filter { it.length >= 3 }
            .toSet()
        otherLabelsAt = now
        return otherLabels
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray,
        out: MutableList<String>
    ) {
        if (node == null || depth > 30) return
        budget[0]++
        if (budget[0] > 800) return

        node.text?.let { out.add(it.toString()) }
        node.contentDescription?.let { out.add(it.toString()) }

        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), depth + 1, budget, out)
        }
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
