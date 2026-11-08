package com.chakker.phoneblocker

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_PICK = 2
        private const val SETUP_GRACE_MS = 2 * 60_000L
    }

    private lateinit var stateText: TextView
    private lateinit var stateSub: TextView
    private lateinit var vpnValue: TextView
    private lateinit var a11yValue: TextView
    private lateinit var adminValue: TextView
    private lateinit var toggle: Button
    private lateinit var pendingBox: LinearLayout
    private lateinit var unlockState: TextView
    private lateinit var unlockSub: TextView
    private lateinit var unlockBtn: Button
    private lateinit var delayValue: TextView
    private lateinit var windowValue: TextView
    private lateinit var appsBox: LinearLayout
    private lateinit var domEdit: EditText

    private var appsKey: String? = null
    private var lastOn: Boolean? = null
    private var lastUnlocked: Boolean? = null
    private var pendingKey: String? = null
    private val pendingTexts = mutableMapOf<String, TextView>()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = Ui.dp(this, 20)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad * 2)

        root.addView(Ui.text(this, "Phone Blocker", 26f, Ui.TEXT, light = true))
        root.addView(Ui.text(this, "Less scrolling.", 14f, Ui.MUTED), Ui.lp(this, 2))

        // Status card
        val statusCard = Ui.card(this)
        stateText = Ui.text(this, "", 30f, Ui.TEXT, light = true)
        stateSub = Ui.text(this, "", 13f, Ui.MUTED)
        statusCard.addView(stateText)
        statusCard.addView(stateSub, Ui.lp(this, 2))
        Ui.addDivider(statusCard)
        vpnValue = addRow(statusCard, "Website filter")
        a11yValue = addRow(statusCard, "App blocker")
        adminValue = addRow(statusCard, "Device admin")
        root.addView(statusCard, Ui.lp(this, 20))

        toggle = Ui.button(this, "", Ui.Style.PRIMARY) { onToggle() }
        root.addView(toggle, Ui.lp(this, 14))

        // Queued requests (one card each, hidden when empty)
        pendingBox = LinearLayout(this)
        pendingBox.orientation = LinearLayout.VERTICAL
        pendingBox.visibility = View.GONE
        root.addView(pendingBox, Ui.lp(this, 12))

        // Setup shortcuts
        val setupRow = LinearLayout(this)
        setupRow.orientation = LinearLayout.HORIZONTAL
        setupRow.addView(
            Ui.button(this, "Accessibility", Ui.Style.SECONDARY) { openAccessibility() },
            Ui.lpWeight(this, 0, 6)
        )
        setupRow.addView(
            Ui.button(this, "Device admin", Ui.Style.SECONDARY) { requestAdmin() },
            Ui.lpWeight(this, 6, 0)
        )
        root.addView(setupRow, Ui.lp(this, 12))

        // Uninstall lock
        root.addView(Ui.label(this, "Uninstall lock"), Ui.lp(this, 28))
        val lockCard = Ui.card(this)
        unlockState = Ui.text(this, "", 22f, Ui.TEXT, light = true)
        unlockSub = Ui.text(this, "", 13f, Ui.MUTED)
        lockCard.addView(unlockState)
        lockCard.addView(unlockSub, Ui.lp(this, 2))
        root.addView(lockCard, Ui.lp(this, 8))
        unlockBtn = Ui.button(this, "", Ui.Style.SECONDARY) { onUnlockButton() }
        root.addView(unlockBtn, Ui.lp(this, 8))

        // Timing
        root.addView(Ui.label(this, "Timing"), Ui.lp(this, 28))
        val timingCard = Ui.card(this)
        delayValue = addRow(timingCard, "Request delay", 0)
        windowValue = addRow(timingCard, "Unlock window")
        root.addView(timingCard, Ui.lp(this, 8))
        root.addView(
            Ui.button(this, "Change timing", Ui.Style.SECONDARY) { openTimingDialog() },
            Ui.lp(this, 8)
        )

        // Blocked apps
        root.addView(Ui.label(this, "Blocked apps"), Ui.lp(this, 28))
        appsBox = Ui.card(this)
        root.addView(appsBox, Ui.lp(this, 8))
        root.addView(
            Ui.button(this, "Choose apps", Ui.Style.SECONDARY) { openPicker() },
            Ui.lp(this, 8)
        )

        // Blocked sites
        root.addView(Ui.label(this, "Blocked sites"), Ui.lp(this, 28))
        val sitesCard = Ui.card(this)
        domEdit = EditText(this)
        domEdit.setTextColor(Ui.TEXT)
        domEdit.setHintTextColor(Ui.MUTED)
        domEdit.hint = "example.com"
        domEdit.textSize = 14f
        domEdit.typeface = Typeface.MONOSPACE
        domEdit.minLines = 5
        domEdit.gravity = Gravity.TOP or Gravity.START
        domEdit.setBackgroundColor(Color.TRANSPARENT)
        domEdit.setPadding(0, 0, 0, 0)
        sitesCard.addView(domEdit)
        sitesCard.addView(
            Ui.text(this, "One per line. Subdomains are included.", 12f, Ui.MUTED),
            Ui.lp(this, 8)
        )
        root.addView(sitesCard, Ui.lp(this, 8))
        root.addView(
            Ui.button(this, "Save sites", Ui.Style.SECONDARY) { onSaveSites() },
            Ui.lp(this, 8)
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(root)
        setContentView(scroll)
        reloadFields()
    }

    override fun onResume() {
        super.onResume()
        // Device admin is on: the setup grace has done its job, close it.
        if (isAdminOn() && Config.graceUntil(this) > 0L) Config.setGraceUntil(this, 0L)
        DnsVpnService.ensureRunning(this)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                DnsVpnService.sync(this)
            } else {
                toast("VPN permission denied. Website blocking stays off.")
            }
        }
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val picked = data?.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED)
            if (picked != null) {
                applyConfig(picked.distinct(), LockManager.effectiveDomains(this))
            }
        }
    }

    // ---- layout helpers ----

    private fun addRow(card: LinearLayout, label: String, top: Int = 8): TextView {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val l = Ui.text(this, label, 14f, Ui.MUTED)
        val v = Ui.text(this, "", 14f, Ui.TEXT)
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(v)
        card.addView(row, Ui.lp(this, top))
        return v
    }

    private fun setValue(tv: TextView, text: String, ok: Boolean, protectionOn: Boolean) {
        tv.text = text
        tv.setTextColor(if (ok) Ui.ACCENT else if (protectionOn) Ui.WARN else Ui.MUTED)
    }

    // ---- actions ----

    private fun onToggle() {
        if (!Config.isEnabled(this)) {
            Config.setEnabled(this, true)
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, REQ_VPN)
            } else {
                DnsVpnService.sync(this)
            }
        } else {
            LockManager.requestDisable(this)
            toast("Disable queued. Ready in ${Ui.duration(Config.delayMs(this))}.")
        }
        refresh()
    }

    private fun openAccessibility() {
        if (isAccessibilityOn()) {
            toast("Already on. Changing it needs an uninstall unlock.")
            return
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestAdmin() {
        if (isAdminOn()) {
            toast("Already active. Removing it needs an uninstall unlock.")
            return
        }
        // The guard would otherwise bounce the activation screen, so give setup a short grace.
        Config.setGraceUntil(this, System.currentTimeMillis() + SETUP_GRACE_MS)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(
            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            ComponentName(this, BlockerDeviceAdmin::class.java)
        )
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Makes the blocker harder to uninstall on impulse."
        )
        startActivity(intent)
    }

    private fun onUnlockButton() {
        if (LockManager.isUnlocked(this)) {
            LockManager.lockNow(this)
            toast("Locked again.")
        } else {
            when (LockManager.requestUninstallUnlock(this)) {
                LockManager.RequestResult.QUEUED ->
                    toast("Request queued. Ready in ${Ui.duration(Config.delayMs(this))}.")
                else -> toast("Already requested.")
            }
        }
        refresh()
    }

    private fun openPicker() {
        val intent = Intent(this, AppPickerActivity::class.java)
        intent.putStringArrayListExtra(
            AppPickerActivity.EXTRA_SELECTED,
            ArrayList(LockManager.effectivePackages(this))
        )
        startActivityForResult(intent, REQ_PICK)
    }

    private fun onSaveSites() {
        val dm = Config.parseDomains(domEdit.text.toString())
        applyConfig(LockManager.effectivePackages(this), dm)
    }

    private fun applyConfig(packages: List<String>, domains: List<String>) {
        when (LockManager.requestConfig(this, packages, domains)) {
            LockManager.RequestResult.APPLIED -> toast("Saved.")
            LockManager.RequestResult.QUEUED ->
                toast("Removal queued. Ready in ${Ui.duration(Config.delayMs(this))}.")
            LockManager.RequestResult.NOOP -> toast("No change.")
        }
        appsKey = null
        refresh()
    }

    private fun onConfirm(type: String) {
        val pending = LockManager.pending(this, type)
        when (LockManager.confirm(this, type)) {
            LockManager.ConfirmResult.OK -> {
                DnsVpnService.sync(this)
                reloadFields()
                if (type == LockManager.TYPE_UNINSTALL) {
                    toast("Uninstall unlocked for ${Ui.duration(pending?.newUnlockMs ?: 0L)}.")
                } else {
                    toast("Applied.")
                }
            }
            LockManager.ConfirmResult.TOO_EARLY -> {
                val left = (pending?.readyAt() ?: 0L) - System.currentTimeMillis()
                toast("Not yet. ${Ui.duration(left)} to go.")
            }
            LockManager.ConfirmResult.NONE -> toast("Nothing pending.")
        }
        appsKey = null
        refresh()
    }

    private fun onCancel(type: String) {
        LockManager.cancel(this, type)
        reloadFields()
        toast("Cancelled.")
        appsKey = null
        refresh()
    }

    // ---- timing dialog ----

    private fun openTimingDialog() {
        val pad = Ui.dp(this, 20)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad / 2, pad, 0)

        val delayEdit = minutesField(Config.delayMs(this))
        val windowEdit = minutesField(Config.unlockMs(this))
        box.addView(Ui.text(this, "Request delay (minutes)", 13f, Ui.MUTED))
        box.addView(delayEdit)
        box.addView(Ui.text(this, "Unlock window (minutes)", 13f, Ui.MUTED), Ui.lp(this, 12))
        box.addView(windowEdit)
        box.addView(
            Ui.text(
                this,
                "60 = 1 hour, 1440 = 24 hours. A longer delay or shorter window applies at once. " +
                    "A shorter delay or longer window has to wait out the current delay first.",
                12f,
                Ui.MUTED
            ),
            Ui.lp(this, 12)
        )

        AlertDialog.Builder(this)
            .setTitle("Timing")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                saveTiming(delayEdit.text.toString(), windowEdit.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun minutesField(ms: Long): EditText {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setText((ms / 60_000L).toString())
        e.setTextColor(Ui.TEXT)
        return e
    }

    private fun saveTiming(delayText: String, windowText: String) {
        val d = delayText.trim().toLongOrNull()
        val w = windowText.trim().toLongOrNull()
        if (d == null || w == null || d !in 1L..10080L || w !in 1L..10080L) {
            toast("Use 1 to 10080 minutes.")
            return
        }
        when (LockManager.requestTiming(this, d * 60_000L, w * 60_000L)) {
            LockManager.RequestResult.APPLIED -> toast("Saved.")
            LockManager.RequestResult.QUEUED ->
                toast("Change queued. Ready in ${Ui.duration(Config.delayMs(this))}.")
            LockManager.RequestResult.NOOP -> toast("No change.")
        }
        refresh()
    }

    // ---- state ----

    private fun reloadFields() {
        domEdit.setText(LockManager.effectiveDomains(this).joinToString("\n"))
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled =
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains("$packageName/")
    }

    private fun isAdminOn(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(ComponentName(this, BlockerDeviceAdmin::class.java))
    }

    @Suppress("DEPRECATION")
    private fun renderApps() {
        val pkgs = Config.packages(this)
        val key = pkgs.joinToString(",")
        if (key == appsKey) return
        appsKey = key

        appsBox.removeAllViews()
        if (pkgs.isEmpty()) {
            appsBox.addView(Ui.text(this, "No apps blocked.", 14f, Ui.MUTED))
            return
        }
        val pm = packageManager
        pkgs.forEachIndexed { i, pkg ->
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                null
            }
            val top = if (i == 0) 0 else 12
            appsBox.addView(Ui.text(this, label ?: pkg, 15f, Ui.TEXT), Ui.lp(this, top))
            appsBox.addView(
                Ui.text(this, if (label != null) pkg else "not installed", 11f, Ui.MUTED)
            )
        }
    }

    private fun describe(p: LockManager.Pending, now: Long): String {
        val what = when (p.type) {
            LockManager.TYPE_DISABLE -> "Turn protection off"
            LockManager.TYPE_CONFIG -> "Remove items from the lists"
            LockManager.TYPE_TIMING ->
                "Change timing: delay ${Ui.duration(p.newDelayMs)}, window ${Ui.duration(p.newUnlockMs)}"
            LockManager.TYPE_UNINSTALL -> "Unlock uninstall for ${Ui.duration(p.newUnlockMs)}"
            else -> p.type
        }
        val status = if (now < p.readyAt()) {
            "Ready in ${Ui.duration(p.readyAt() - now)}"
        } else {
            "Ready. Confirm within ${Ui.duration(p.expiresAt() - now)}"
        }
        return "$what\n$status"
    }

    private fun renderPending() {
        val list = LockManager.allPending(this)
        val key = list.joinToString(",") { "${it.type}@${it.since}" }
        if (key != pendingKey) {
            pendingKey = key
            pendingBox.removeAllViews()
            pendingTexts.clear()
            list.forEachIndexed { i, p ->
                val card = Ui.card(this)
                val tv = Ui.text(this, "", 14f, Ui.TEXT)
                card.addView(tv)
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.addView(
                    Ui.button(this, "Confirm", Ui.Style.PRIMARY) { onConfirm(p.type) },
                    Ui.lpWeight(this, 0, 6)
                )
                row.addView(
                    Ui.button(this, "Cancel", Ui.Style.SECONDARY) { onCancel(p.type) },
                    Ui.lpWeight(this, 6, 0)
                )
                card.addView(row, Ui.lp(this, 12))
                pendingBox.addView(card, Ui.lp(this, if (i == 0) 0 else 8))
                pendingTexts[p.type] = tv
            }
        }
        val now = System.currentTimeMillis()
        list.forEach { p -> pendingTexts[p.type]?.text = describe(p, now) }
        pendingBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun refresh() {
        val on = Config.isEnabled(this)
        val vpn = DnsVpnService.running
        val a11y = isAccessibilityOn()
        val admin = isAdminOn()

        stateText.text = if (on) "Protected" else "Off"
        stateText.setTextColor(if (on) Ui.ACCENT else Ui.MUTED)
        stateSub.text = when {
            !on -> "Nothing is being blocked."
            vpn && a11y -> "Apps and sites are blocked."
            else -> "Partly active. Finish setup below."
        }
        setValue(vpnValue, if (vpn) "running" else "off", vpn, on)
        setValue(a11yValue, if (a11y) "on" else "off", a11y, on)
        setValue(adminValue, if (admin) "active" else "inactive", admin, on)

        toggle.text = if (on) "Disable (delayed)" else "Enable protection"
        if (lastOn != on) {
            Ui.applyStyle(this, toggle, if (on) Ui.Style.SECONDARY else Ui.Style.PRIMARY)
            lastOn = on
        }

        // Uninstall lock
        val unlocked = LockManager.isUnlocked(this)
        unlockState.text = if (unlocked) "Unlocked" else "Locked"
        unlockState.setTextColor(if (unlocked) Ui.WARN else Ui.ACCENT)
        unlockSub.text = when {
            unlocked && admin ->
                "${Ui.duration(LockManager.unlockLeftMs(this))} left. Deactivate device admin first, then uninstall."
            unlocked -> "${Ui.duration(LockManager.unlockLeftMs(this))} left. Uninstall is allowed."
            !a11y -> "Not enforced: turn on the accessibility service."
            else -> "Uninstalling or switching off the blocker is blocked."
        }
        if (lastUnlocked != unlocked) {
            unlockBtn.text = if (unlocked) "Lock again now" else "Request uninstall unlock"
            Ui.applyStyle(this, unlockBtn, if (unlocked) Ui.Style.PRIMARY else Ui.Style.SECONDARY)
            lastUnlocked = unlocked
        }

        delayValue.text = Ui.duration(Config.delayMs(this))
        windowValue.text = Ui.duration(Config.unlockMs(this))

        renderPending()
        renderApps()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
