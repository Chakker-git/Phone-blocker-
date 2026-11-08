package com.chakker.phoneblocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Pick which installed apps to block. Returns the full selected package list on Done. */
class AppPickerActivity : Activity() {

    companion object {
        const val EXTRA_SELECTED = "selected"
    }

    private class Entry(val pkg: String, val label: String, val icon: Drawable?)

    private val selected = linkedSetOf<String>()
    private var all: List<Entry> = emptyList()
    private var shown: List<Entry> = emptyList()
    private lateinit var adapter: PickerAdapter
    private lateinit var loading: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(intent.getStringArrayListExtra(EXTRA_SELECTED) ?: arrayListOf())

        val pad = Ui.dp(this, 20)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, Ui.dp(this, 12))

        root.addView(Ui.text(this, "Choose apps", 26f, Ui.TEXT, light = true))
        root.addView(Ui.text(this, "Ticked apps get blocked.", 14f, Ui.MUTED), Ui.lp(this, 2))

        val search = EditText(this)
        search.hint = "Search"
        search.setHintTextColor(Ui.MUTED)
        search.setTextColor(Ui.TEXT)
        search.textSize = 15f
        search.maxLines = 1
        search.isSingleLine = true
        search.background = Ui.rounded(this, Ui.SURFACE, 14)
        val sp = Ui.dp(this, 14)
        search.setPadding(sp, sp, sp, sp)
        root.addView(search, Ui.lp(this, 16))

        loading = Ui.text(this, "Loading apps...", 13f, Ui.MUTED)
        root.addView(loading, Ui.lp(this, 12))

        val list = ListView(this)
        list.divider = ColorDrawable(Ui.LINE)
        list.dividerHeight = 1
        list.cacheColorHint = Color.TRANSPARENT
        list.selector = ColorDrawable(Color.parseColor("#14FFFFFF"))
        adapter = PickerAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos].pkg) }
        val listLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        listLp.topMargin = Ui.dp(this, 8)
        root.addView(list, listLp)

        root.addView(Ui.button(this, "Done", Ui.Style.PRIMARY) { finishWithResult() }, Ui.lp(this, 12))
        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString() ?: "")
            }
        })

        loadApps()
    }

    private fun loadApps() {
        val initial = selected.toSet()
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val found = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { Entry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }

            // Keep blocked packages that aren't launchable or installed, so Done never drops them silently.
            val known = found.map { it.pkg }.toSet()
            val missing = initial.filter { it !in known }.map { Entry(it, it, null) }

            val sorted = (found + missing).sortedWith(
                compareByDescending<Entry> { it.pkg in initial }.thenBy { it.label.lowercase() }
            )
            runOnUiThread {
                all = sorted
                shown = sorted
                loading.visibility = View.GONE
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun applyFilter(q: String) {
        val s = q.trim().lowercase()
        shown = if (s.isEmpty()) all else all.filter {
            it.label.lowercase().contains(s) || it.pkg.lowercase().contains(s)
        }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(pkg: String) {
        if (!selected.add(pkg)) selected.remove(pkg)
        adapter.notifyDataSetChanged()
    }

    private fun finishWithResult() {
        val data = Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))
        setResult(RESULT_OK, data)
        finish()
    }

    private class Holder(ctx: Context) {
        val icon = ImageView(ctx)
        val name = Ui.text(ctx, "", 15f, Ui.TEXT)
        val pkg = Ui.text(ctx, "", 11f, Ui.MUTED)
        val check = CheckBox(ctx)
        val root = LinearLayout(ctx)

        init {
            root.orientation = LinearLayout.HORIZONTAL
            root.gravity = Gravity.CENTER_VERTICAL
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val v = Ui.dp(ctx, 10)
            root.setPadding(0, v, 0, v)

            val size = Ui.dp(ctx, 40)
            root.addView(icon, LinearLayout.LayoutParams(size, size))

            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.addView(name)
            col.addView(pkg)
            val colLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            colLp.leftMargin = Ui.dp(ctx, 14)
            colLp.rightMargin = Ui.dp(ctx, 8)
            root.addView(col, colLp)

            check.buttonTintList = ColorStateList.valueOf(Ui.ACCENT)
            check.isClickable = false
            check.isFocusable = false
            root.addView(check)
        }
    }

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount(): Int = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: Holder
            val view: View
            if (convertView == null) {
                holder = Holder(this@AppPickerActivity)
                view = holder.root
                view.tag = holder
            } else {
                view = convertView
                holder = convertView.tag as Holder
            }
            val e = shown[position]
            holder.icon.setImageDrawable(e.icon)
            holder.name.text = e.label
            holder.pkg.text = e.pkg
            holder.check.isChecked = e.pkg in selected
            return view
        }
    }
}
