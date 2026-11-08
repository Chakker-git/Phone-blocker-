package com.chakker.phoneblocker

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Time the locks can trust. The wall clock is user-editable, so delays are measured against a
 * monotonic anchor instead: trusted = anchor time + elapsed realtime since the anchor, which keeps
 * ticking through sleep and ignores clock changes. A new anchor is taken on first run and after
 * a reboot (elapsed realtime resets), and then never moves backwards past the last time seen.
 *
 * Gap: setting the clock forward while the phone is off, then booting, still jumps ahead.
 */
object Clock {
    private const val SAVE_EVERY_MS = 30_000L

    @Synchronized
    fun now(ctx: Context): Long {
        val p = Config.prefs(ctx)
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

        val anchorBoot = p.getInt("clk_boot", Int.MIN_VALUE)
        val anchorElapsed = p.getLong("clk_elapsed", -1L)
        val anchorTime = p.getLong("clk_time", 0L)

        val valid = anchorTime != 0L && boot == anchorBoot && elapsed >= anchorElapsed
        val trusted: Long
        if (valid) {
            trusted = anchorTime + (elapsed - anchorElapsed)
        } else {
            // First run or reboot: never go back past the last trusted time, never trust a lower wall clock.
            val lastSeen = p.getLong("clk_seen", 0L)
            trusted = maxOf(wall, lastSeen)
            p.edit()
                .putInt("clk_boot", boot)
                .putLong("clk_elapsed", elapsed)
                .putLong("clk_time", trusted)
                .putLong("clk_seen", trusted)
                .apply()
            return trusted
        }
        if (trusted - p.getLong("clk_seen", 0L) >= SAVE_EVERY_MS) {
            p.edit().putLong("clk_seen", trusted).apply()
        }
        return trusted
    }
}
