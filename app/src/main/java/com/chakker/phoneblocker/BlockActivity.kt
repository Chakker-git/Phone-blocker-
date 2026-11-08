package com.chakker.phoneblocker

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

/** Full-screen "nope" that dismisses itself and reveals the launcher underneath. */
class BlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val tv = TextView(this)
        tv.text = "Blocked.\n\nGo do something else."
        tv.textSize = 28f
        tv.gravity = Gravity.CENTER
        tv.setPadding(pad, pad, pad, pad)
        setContentView(tv)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
    }

    @Deprecated("Back just closes the block screen")
    override fun onBackPressed() {
        finish()
    }
}
