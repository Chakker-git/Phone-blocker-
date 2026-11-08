package com.chakker.phoneblocker

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Create, edit or delete one schedule. Changes go through [LockManager] like every other setting. */
class ScheduleEditActivity : Activity() {

    companion object {
        const val EXTRA_ID = "schedule_id"
        private const val REQ_PICK = 1
        private const val REQ_VPN = 2
    }

    private lateinit var original: Schedule
    private var isNew = true

    private var days = 0
    private var start = 0
    private var end = 0
    private var packages = listOf<String>()

    private val dayChips = mutableListOf<TextView>()
    private lateinit var startBtn: Button
    private lateinit var endBtn: Button
    private lateinit var appsBtn: Button
    private lateinit var domEdit: EditText
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID) ?: ""
        val existing = LockManager.effectiveSchedules(this).firstOrNull { it.id == id }
        isNew = existing == null
        original = existing ?: Schedule(
            id = Schedules.newId(),
            packages = emptyList(),
            domains = emptyList(),
            days = 0b0011111, // Mon-Fri
            start = 8 * 60,
            end = 22 * 60,
        )
        days = original.days
        start = original.start
        end = original.end
        packages = original.packages

        val pad = Ui.dp(this, 20)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad * 2)

        root.addView(Ui.text(this, if (isNew) "New schedule" else "Edit schedule", 26f, Ui.TEXT, light = true))
        root.addView(
            Ui.text(this, "Blocks these apps and sites during the chosen hours.", 14f, Ui.MUTED),
            Ui.lp(this, 2)
        )

        // Days
        root.addView(Ui.label(this, "Days"), Ui.lp(this, 24))
        val dayRow = LinearLayout(this)
        dayRow.orientation = LinearLayout.HORIZONTAL
        Schedules.dayNames.forEachIndexed { i, name ->
            val chip = TextView(this)
            chip.text = name.take(3)
            chip.textSize = 13f
            chip.gravity = Gravity.CENTER
            chip.setOnClickListener {
                days = days xor (1 shl i)
                styleChips()
                updateHint()
            }
            dayChips.add(chip)
            val lp = LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f)
            lp.leftMargin = if (i == 0) 0 else Ui.dp(this, 4)
            dayRow.addView(chip, lp)
        }
        root.addView(dayRow, Ui.lp(this, 8))

        // Times
        root.addView(Ui.label(this, "Time"), Ui.lp(this, 24))
        val timeRow = LinearLayout(this)
        timeRow.orientation = LinearLayout.HORIZONTAL
        startBtn = Ui.button(this, "", Ui.Style.SECONDARY) {
            TimePickerDialog(this, { _, h, m -> start = h * 60 + m; renderTimes() }, start / 60, start % 60, true).show()
        }
        endBtn = Ui.button(this, "", Ui.Style.SECONDARY) {
            TimePickerDialog(this, { _, h, m -> end = h * 60 + m; renderTimes() }, end / 60, end % 60, true).show()
        }
        timeRow.addView(startBtn, Ui.lpWeight(this, 0, 6))
        timeRow.addView(endBtn, Ui.lpWeight(this, 6, 0))
        root.addView(timeRow, Ui.lp(this, 8))
        hint = Ui.text(this, "", 12f, Ui.MUTED)
        root.addView(hint, Ui.lp(this, 8))

        // Apps
        root.addView(Ui.label(this, "Apps"), Ui.lp(this, 24))
        appsBtn = Ui.button(this, "", Ui.Style.SECONDARY) { openPicker() }
        root.addView(appsBtn, Ui.lp(this, 8))

        // Sites
        root.addView(Ui.label(this, "Sites"), Ui.lp(this, 24))
        val sitesCard = Ui.card(this)
        domEdit = EditText(this)
        domEdit.setTextColor(Ui.TEXT)
        domEdit.setHintTextColor(Ui.MUTED)
        domEdit.hint = "example.com"
        domEdit.textSize = 14f
        domEdit.typeface = Typeface.MONOSPACE
        domEdit.minLines = 4
        domEdit.gravity = Gravity.TOP or Gravity.START
        domEdit.setBackgroundColor(Color.TRANSPARENT)
        domEdit.setPadding(0, 0, 0, 0)
        domEdit.setText(original.domains.joinToString("\n"))
        sitesCard.addView(domEdit)
        sitesCard.addView(
            Ui.text(this, "One per line. Subdomains are included.", 12f, Ui.MUTED),
            Ui.lp(this, 8)
        )
        root.addView(sitesCard, Ui.lp(this, 8))

        root.addView(Ui.button(this, "Save", Ui.Style.PRIMARY) { onSave() }, Ui.lp(this, 28))
        if (!isNew) {
            root.addView(Ui.button(this, "Delete (delayed)", Ui.Style.SECONDARY) { onDelete() }, Ui.lp(this, 8))
        }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.addView(root)
        setContentView(scroll)

        styleChips()
        renderTimes()
        renderApps()
    }

    private fun styleChips() {
        dayChips.forEachIndexed { i, chip ->
            val on = (days shr i) and 1 == 1
            chip.background = Ui.rounded(this, if (on) Ui.ACCENT else Ui.SURFACE_HI, 12)
            chip.setTextColor(if (on) Ui.BG else Ui.TEXT)
        }
    }

    private fun renderTimes() {
        startBtn.text = "From  ${Schedules.timeLabel(start)}"
        endBtn.text = "To  ${Schedules.timeLabel(end)}"
        updateHint()
    }

    private fun updateHint() {
        hint.text = when {
            start == end -> "Same start and end means the whole day."
            end < start -> "Runs past midnight into the next day."
            else -> "Blocked from ${Schedules.timeLabel(start)} to ${Schedules.timeLabel(end)} on ${
                Schedules.daysLabel(days).lowercase()
            }."
        }
    }

    private fun renderApps() {
        appsBtn.text = if (packages.isEmpty()) "Choose apps" else "${packages.size} apps selected. Change"
    }

    private fun openPicker() {
        val i = Intent(this, AppPickerActivity::class.java)
        i.putStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED, ArrayList(packages))
        startActivityForResult(i, REQ_PICK)
    }

    private fun collect(): Schedule? {
        val domains = Config.parseDomains(domEdit.text.toString())
        if (days and 0x7F == 0) {
            toast("Pick at least one day.")
            return null
        }
        if (packages.isEmpty() && domains.isEmpty()) {
            toast("Add at least one app or site.")
            return null
        }
        return original.copy(packages = packages, domains = domains, days = days, start = start, end = end)
    }

    private fun onSave() {
        val s = collect() ?: return
        val current = LockManager.effectiveSchedules(this)
        val list = if (current.any { it.id == s.id }) current.map { if (it.id == s.id) s else it } else current + s
        apply(list)
    }

    private fun onDelete() {
        apply(LockManager.effectiveSchedules(this).filter { it.id != original.id })
    }

    private fun apply(list: List<Schedule>) {
        when (LockManager.requestSchedules(this, list)) {
            LockManager.RequestResult.APPLIED -> toast("Saved.")
            LockManager.RequestResult.QUEUED ->
                toast("Change queued. Ready in ${Ui.duration(Config.delayMs(this))}.")
            LockManager.RequestResult.NOOP -> toast("No change.")
        }
        // Scheduled sites need the DNS filter even when protection is off, which needs VPN consent once.
        val consent = if (Config.vpnWanted(this)) VpnService.prepare(this) else null
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN)
        } else {
            DnsVpnService.sync(this)
            finish()
        }
    }

    @Deprecated("Fine for a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> if (resultCode == RESULT_OK) {
                packages = data?.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED)?.distinct() ?: packages
                renderApps()
            }
            REQ_VPN -> {
                if (resultCode == RESULT_OK) DnsVpnService.sync(this)
                else toast("VPN permission denied. Scheduled sites won't be blocked.")
                finish()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
