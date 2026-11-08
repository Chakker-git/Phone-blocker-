package com.chakker.phoneblocker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen "nope" that dismisses itself and reveals the launcher underneath. */
class BlockActivity : Activity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val closer = Runnable { finish() }
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = Ui.dp(this, 24)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(pad, pad, pad, pad)

        titleView = Ui.text(this, "", 34f, Ui.TEXT, light = true)
        messageView = Ui.text(this, "", 15f, Ui.MUTED)
        messageView.gravity = Gravity.CENTER
        root.addView(titleView)
        root.addView(messageView, Ui.lp(this, 8))
        setContentView(root)

        show(intent)
    }

    // singleTask: a second block while this is still up arrives here instead of a new instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(i: Intent?) {
        titleView.text = i?.getStringExtra(EXTRA_TITLE) ?: "Blocked."
        messageView.text = i?.getStringExtra(EXTRA_MESSAGE) ?: "Go do something else."
        handler.removeCallbacks(closer)
        handler.postDelayed(closer, 2000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(closer)
        super.onDestroy()
    }

    @Deprecated("Back just closes the block screen")
    override fun onBackPressed() {
        finish()
    }
}
