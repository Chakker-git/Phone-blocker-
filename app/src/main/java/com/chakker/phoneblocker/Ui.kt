package com.chakker.phoneblocker

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers so the dark, quiet look lives in one place. */
object Ui {
    val BG = Color.parseColor("#0F1115")
    val SURFACE = Color.parseColor("#181B21")
    val SURFACE_HI = Color.parseColor("#232830")
    val TEXT = Color.parseColor("#E6E8EB")
    val MUTED = Color.parseColor("#8B919A")
    val ACCENT = Color.parseColor("#8FBFAE") // muted sage
    val WARN = Color.parseColor("#D98C6F") // muted clay
    val LINE = Color.parseColor("#262A32")

    enum class Style { PRIMARY, SECONDARY, GHOST }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** "45s", "5m 30s", "2h 10m", "1d 6h". Rounds up so countdowns never show 0 early. */
    fun duration(ms: Long): String {
        val s = (maxOf(ms, 0L) + 999) / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
            m > 0 -> if (sec > 0 && m < 10) "${m}m ${sec}s" else "${m}m"
            else -> "${sec}s"
        }
    }

    fun rounded(ctx: Context, fill: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        return d
    }

    /** Full-width layout params with a top margin. */
    fun lp(ctx: Context, topDp: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(ctx, topDp)
        return p
    }

    /** Equal-weight cell for a horizontal row. */
    fun lpWeight(ctx: Context, leftDp: Int, rightDp: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        p.leftMargin = dp(ctx, leftDp)
        p.rightMargin = dp(ctx, rightDp)
        return p
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int, light: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (light) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        return t
    }

    /** Small spaced-out section label. */
    fun label(ctx: Context, s: String): TextView {
        val t = text(ctx, s.uppercase(), 11f, MUTED)
        t.letterSpacing = 0.12f
        return t
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(ctx, SURFACE, 18)
        val p = dp(ctx, 16)
        c.setPadding(p, p, p, p)
        return c
    }

    fun addDivider(parent: LinearLayout) {
        val v = View(parent.context)
        v.setBackgroundColor(LINE)
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(parent.context, 1))
        p.topMargin = dp(parent.context, 14)
        p.bottomMargin = dp(parent.context, 6)
        parent.addView(v, p)
    }

    fun button(ctx: Context, label: String, style: Style, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.textSize = 14f
        b.stateListAnimator = null
        b.minHeight = dp(ctx, 48)
        b.minimumHeight = dp(ctx, 48)
        b.setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        b.setOnClickListener { onClick() }
        applyStyle(ctx, b, style)
        return b
    }

    fun applyStyle(ctx: Context, b: Button, style: Style) {
        val fill = when (style) {
            Style.PRIMARY -> ACCENT
            Style.SECONDARY -> SURFACE_HI
            Style.GHOST -> Color.TRANSPARENT
        }
        val textColor = when (style) {
            Style.PRIMARY -> BG
            Style.SECONDARY -> TEXT
            Style.GHOST -> MUTED
        }
        b.background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),
            rounded(ctx, fill, 14),
            rounded(ctx, Color.WHITE, 14)
        )
        b.setTextColor(textColor)
    }
}
