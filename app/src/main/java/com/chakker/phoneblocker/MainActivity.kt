package com.chakker.phoneblocker

import android.app.Activity
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
    }

    private lateinit var stateText: TextView
    private lateinit var stateSub: TextView
    private lateinit var vpnValue: TextView
    private lateinit var a11yValue: TextView
    private lateinit var adminValue: TextView
    private lateinit var toggle: Button
    private lateinit var pendingCard: LinearLayout
    private lateinit var pendingText: TextView
    private lateinit var appsBox: LinearLayout
    private lateinit var domEdit: EditText

    private var appsKey: String? = null
    private var lastOn: Boolean? = null

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

        // Pending change card (hidden unless a delayed change is queued)
        pendingCard = Ui.card(this)
        pendingText = Ui.text(this, "", 14f, Ui.TEXT)
        pendingCard.addView(pendingText)
        val pendingRow = LinearLayout(this)
        pendingRow.orientation = LinearLayout.HORIZONTAL
        pendingRow.addView(
            Ui.button(this, "Confirm", Ui.Style.PRIMARY) { onConfirm() },
            Ui.lpWeight(this, 0, 6)
        )
        pendingRow.addView(
            Ui.button(this, "Cancel", Ui.Style.SECONDARY) {
                LockManager.cancel(this)
                reloadFields()
                toast("Cancelled.")
                refresh()
            },
            Ui.lpWeight(this, 6, 0)
        )
        pendingCard.addView(pendingRow, Ui.lp(this, 12))
        pendingCard.visibility = View.GONE
        root.addView(pendingCard, Ui.lp(this, 12))

        // Setup shortcuts
        val setupRow = LinearLayout(this)
        setupRow.orientation = LinearLayout.HORIZONTAL
        setupRow.addView(
            Ui.button(this, "Accessibility", Ui.Style.SECONDARY) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Ui.lpWeight(this, 0, 6)
        )
        setupRow.addView(
            Ui.button(this, "Device admin", Ui.Style.SECONDARY) { requestAdmin() },
            Ui.lpWeight(this, 6, 0)
        )
        root.addView(setupRow, Ui.lp(this, 12))

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

        root.addView(
            Ui.text(
                this,
                "Disable delay: ${LockManager.DELAY_MS / 1000}s (testing value)",
                12f,
                Ui.MUTED
            ),
            Ui.lp(this, 24)
        )

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(root)
        setContentView(scroll)
        reloadFields()
    }

    override fun onResume() {
        super.onResume()
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

    private fun addRow(card: LinearLayout, label: String): TextView {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val l = Ui.text(this, label, 14f, Ui.MUTED)
        val v = Ui.text(this, "", 14f, Ui.TEXT)
        row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(v)
        card.addView(row, Ui.lp(this, 8))
        return v
    }

    private fun setValue(tv: TextView, text: String, ok: Boolean, protectionOn: Boolean) {
        tv.text = text
        tv.setTextColor(if (ok) Ui.ACCENT else if (protectionOn) Ui.WARN else Ui.MUTED)
    }

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
            toast("Disable queued. Confirmable in ${LockManager.DELAY_MS / 1000}s.")
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
                toast("Removal queued. Confirmable in ${LockManager.DELAY_MS / 1000}s.")
            LockManager.RequestResult.NOOP -> toast("No change.")
        }
        appsKey = null
        refresh()
    }

    private fun onConfirm() {
        val pending = LockManager.pending(this)
        when (LockManager.confirm(this)) {
            LockManager.ConfirmResult.OK -> {
                DnsVpnService.sync(this)
                reloadFields()
                toast("Applied.")
            }
            LockManager.ConfirmResult.TOO_EARLY -> {
                val left = ((pending?.readyAt() ?: 0L) - System.currentTimeMillis()) / 1000 + 1
                toast("Not yet. ${left}s to go.")
            }
            LockManager.ConfirmResult.NONE -> toast("Nothing pending.")
        }
        appsKey = null
        refresh()
    }

    private fun requestAdmin() {
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

        val p = LockManager.pending(this)
        if (p == null) {
            pendingCard.visibility = View.GONE
        } else {
            val now = System.currentTimeMillis()
            val what = if (p.type == "disable") "Turn protection off" else "Remove items from the lists"
            pendingText.text = if (now < p.readyAt()) {
                "$what\nAvailable in ${(p.readyAt() - now) / 1000 + 1}s"
            } else {
                "$what\nReady. Confirm within ${(p.expiresAt() - now) / 1000}s"
            }
            pendingCard.visibility = View.VISIBLE
        }

        renderApps()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
