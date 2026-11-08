package com.chakker.phoneblocker

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout

/** Full-screen "nope" that dismisses itself and reveals the launcher underneath. */
class BlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = Ui.dp(this, 24)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(pad, pad, pad, pad)
        root.addView(Ui.text(this, "Blocked.", 34f, Ui.TEXT, light = true))
        root.addView(Ui.text(this, "Go do something else.", 15f, Ui.MUTED), Ui.lp(this, 8))
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }

    @Deprecated("Back just closes the block screen")
    override fun onBackPressed() {
        finish()
    }
}
