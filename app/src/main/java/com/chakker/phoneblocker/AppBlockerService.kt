package com.chakker.phoneblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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

        val texts = ArrayList<String>()
        val titleHit = BooleanArray(1)
        collectTexts(root, 0, IntArray(1), texts, false, titleHit)

        val hit = when {
            // Only the "manage / disconnect" dialog, not the one that asks permission to start the VPN.
            pkg.contains("vpndialogs") ->
                eventClass.contains("ManageDialog") && texts.any { mentions(it) }
            // The uninstall dialog is only ever about one app, so a mention is enough.
            pkg.contains("packageinstaller") -> texts.any { mentions(it) }
            else -> titleHit[0]
        }
        if (hit) {
            block("Locked.", "Request an uninstall unlock in Phone Blocker to change or remove it.")
        }
    }

    private fun mentions(s: String): Boolean = s.contains(appLabel, ignoreCase = true)

    /**
     * Walks the window. [titleHit] is set when the app name appears exactly, as a title or header:
     * either a node outside any scrolling list (toolbar, page title), or one whose view id says
     * it is a title/header (App info's header sits inside the list). A name that is only a row in
     * a scrolling list (Apps, VPN list under Network) does not count.
     */
    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: IntArray,
        out: MutableList<String>,
        inList: Boolean,
        titleHit: BooleanArray
    ) {
        if (node == null || depth > 30) return
        budget[0]++
        if (budget[0] > 800) return

        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        t?.let { out.add(it) }
        d?.let { out.add(it) }

        val id = node.viewIdResourceName ?: ""
        val exact = (t != null && t.trim().equals(appLabel, true)) ||
            (d != null && d.trim().equals(appLabel, true))
        if (exact) {
            Log.d("PhoneBlocker", "label node id=$id cls=${node.className} inList=$inList")
            // Rows in a list also have ids ending in "title" (android:id/title), so a title id only
            // counts for the known header ids, not for any row.
            val headerId = id.contains("entity_header", true) || id.contains("action_bar", true) ||
                id.contains("toolbar", true) || id.contains("collapsing", true)
            if (headerId || !inList) titleHit[0] = true
        }

        val scrolls = inList || node.isScrollable ||
            (node.className?.toString()?.let {
                it.contains("RecyclerView") || it.contains("ListView") || it.contains("ScrollView")
            } ?: false)
        for (i in 0 until node.childCount) {
            collectTexts(node.getChild(i), depth + 1, budget, out, scrolls, titleHit)
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
