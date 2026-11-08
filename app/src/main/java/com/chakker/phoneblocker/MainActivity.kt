package com.chakker.phoneblocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 1
    }

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var pkgEdit: EditText
    private lateinit var domEdit: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        status = TextView(this)
        status.textSize = 15f
        root.addView(status)

        toggle = addButton(root, "") { onToggle() }
        addButton(root, "1. Grant app blocking (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(root, "2. Activate device admin (uninstall friction)") { requestAdmin() }
        addButton(root, "Confirm pending change") { onConfirm() }
        addButton(root, "Cancel pending change") {
            LockManager.cancel(this)
            reloadFields()
            toast("Cancelled")
        }

        val pkgLabel = TextView(this)
        pkgLabel.text = "\nBlocked app packages (one per line)"
        root.addView(pkgLabel)
        pkgEdit = EditText(this)
        pkgEdit.minLines = 3
        root.addView(pkgEdit)

        val domLabel = TextView(this)
        domLabel.text = "Blocked domains (subdomains included)"
        root.addView(domLabel)
        domEdit = EditText(this)
        domEdit.minLines = 6
        root.addView(domEdit)

        addButton(root, "Save lists") { onSave() }

        val scroll = ScrollView(this)
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
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        parent.addView(b)
        return b
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

    private fun onSave() {
        val pk = Config.parse(pkgEdit.text.toString())
        val dm = Config.parseDomains(domEdit.text.toString())
        when (LockManager.requestConfig(this, pk, dm)) {
            LockManager.RequestResult.APPLIED -> toast("Saved.")
            LockManager.RequestResult.QUEUED ->
                toast("Removal queued. Confirmable in ${LockManager.DELAY_MS / 1000}s.")
            LockManager.RequestResult.NOOP -> toast("No change.")
        }
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
        pkgEdit.setText(Config.packages(this).joinToString("\n"))
        domEdit.setText(Config.userDomains(this).joinToString("\n"))
    }

    private fun refresh() {
        val on = Config.isEnabled(this)
        toggle.text = if (on) "Disable protection (delayed)" else "Enable protection"

        val p = LockManager.pending(this)
        val now = System.currentTimeMillis()
        val pendingText = if (p == null) {
            "none"
        } else if (now < p.readyAt()) {
            "${p.type}, confirmable in ${(p.readyAt() - now) / 1000}s"
        } else {
            "${p.type}, ready. Confirm within ${(p.expiresAt() - now) / 1000}s"
        }

        val enabledServices =
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val a11y = enabledServices.contains("$packageName/")
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = dpm.isAdminActive(ComponentName(this, BlockerDeviceAdmin::class.java))

        status.text = "Protection: ${if (on) "ON" else "OFF"}\n" +
            "Website filter (VPN): ${if (DnsVpnService.running) "running" else "not running"}\n" +
            "App blocker (Accessibility): ${if (a11y) "on" else "OFF, grant it below"}\n" +
            "Device admin: ${if (admin) "active" else "inactive"}\n" +
            "Pending change: $pendingText\n" +
            "Disable delay: ${LockManager.DELAY_MS / 1000}s (testing value)"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
