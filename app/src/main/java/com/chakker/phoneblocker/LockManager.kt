package com.chakker.phoneblocker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The "can't talk myself out of it" part.
 *
 * Tightening changes (turning protection on, adding to the blocklists, a longer delay,
 * a shorter unlock window) apply immediately. Loosening changes are queued as requests,
 * must wait out the request delay, and then have to be confirmed before they lapse.
 *
 * Request types, each with its own slot so they don't overwrite each other:
 *  - disable:   turn protection off
 *  - config:    remove apps or sites from the blocklists
 *  - timing:    shorten the delay or lengthen the unlock window
 *  - uninstall: open the uninstall guard for the unlock window
 */
object LockManager {
    /** How long after a request becomes ready it can still be confirmed before it lapses. */
    const val CONFIRM_MS = 60 * 60_000L

    const val TYPE_DISABLE = "disable"
    const val TYPE_CONFIG = "config"
    const val TYPE_TIMING = "timing"
    const val TYPE_UNINSTALL = "uninstall"
    private val TYPES = listOf(TYPE_DISABLE, TYPE_CONFIG, TYPE_TIMING, TYPE_UNINSTALL)

    enum class RequestResult { APPLIED, QUEUED, NOOP }
    enum class ConfirmResult { OK, TOO_EARLY, NONE }

    data class Pending(
        val type: String,
        val since: Long,
        /** The delay that applied when this request was made. Later setting changes don't shorten it. */
        val delayMs: Long,
        val packages: List<String>,
        val domains: List<String>,
        val newDelayMs: Long,
        val newUnlockMs: Long,
    ) {
        fun readyAt(): Long = since + delayMs
        fun expiresAt(): Long = readyAt() + CONFIRM_MS
    }

    // ---- reading state ----

    fun allPending(ctx: Context): List<Pending> =
        TYPES.mapNotNull { pending(ctx, it) }.sortedBy { it.since }

    fun pending(ctx: Context, type: String): Pending? {
        val raw = Config.prefs(ctx).getString(key(type), null) ?: return null
        val p = try {
            decode(raw)
        } catch (e: Exception) {
            cancel(ctx, type)
            return null
        }
        if (System.currentTimeMillis() > p.expiresAt()) {
            cancel(ctx, type)
            return null
        }
        return p
    }

    fun cancel(ctx: Context, type: String) {
        Config.prefs(ctx).edit().remove(key(type)).apply()
    }

    /** The app list as the user last set it: the queued version if one is waiting, else the active one. */
    fun effectivePackages(ctx: Context): List<String> {
        val p = pending(ctx, TYPE_CONFIG)
        return p?.packages ?: Config.packages(ctx)
    }

    /** The domain list as the user last set it: the queued version if one is waiting, else the active one. */
    fun effectiveDomains(ctx: Context): List<String> {
        val p = pending(ctx, TYPE_CONFIG)
        return p?.domains ?: Config.userDomains(ctx)
    }

    // ---- uninstall unlock state ----

    fun isUnlocked(ctx: Context): Boolean = System.currentTimeMillis() < Config.unlockUntil(ctx)

    fun unlockLeftMs(ctx: Context): Long = maxOf(Config.unlockUntil(ctx) - System.currentTimeMillis(), 0L)

    fun lockNow(ctx: Context) {
        Config.setUnlockUntil(ctx, 0L)
        Config.setGraceUntil(ctx, 0L)
    }

    /** True while the guard should let Settings, uninstall and VPN screens through. */
    fun isGuardOpen(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        return now < Config.unlockUntil(ctx) || now < Config.graceUntil(ctx)
    }

    // ---- making requests ----

    fun requestDisable(ctx: Context) {
        if (!Config.isEnabled(ctx)) return
        if (pending(ctx, TYPE_DISABLE) != null) return // keep the running timer
        queue(ctx, TYPE_DISABLE)
    }

    fun requestConfig(ctx: Context, packages: List<String>, domains: List<String>): RequestResult {
        val curP = Config.packages(ctx).toSet()
        val curD = Config.userDomains(ctx).toSet()
        val newP = packages.toSet()
        val newD = domains.toSet()

        if (curP == newP && curD == newD) {
            // Back to exactly what's active: any queued removal is moot.
            cancel(ctx, TYPE_CONFIG)
            return RequestResult.NOOP
        }

        val loosens = curP.any { it !in newP } || curD.any { it !in newD }
        if (!loosens || !Config.isEnabled(ctx)) {
            Config.setLists(ctx, packages, domains)
            cancel(ctx, TYPE_CONFIG)
            return RequestResult.APPLIED
        }
        queue(ctx, TYPE_CONFIG, packages = packages, domains = domains)
        return RequestResult.QUEUED
    }

    /** Longer delay or shorter unlock window applies now; the opposite waits out the current delay. */
    fun requestTiming(ctx: Context, newDelayMs: Long, newUnlockMs: Long): RequestResult {
        val curDelay = Config.delayMs(ctx)
        val curUnlock = Config.unlockMs(ctx)

        if (newDelayMs == curDelay && newUnlockMs == curUnlock) {
            cancel(ctx, TYPE_TIMING)
            return RequestResult.NOOP
        }

        val loosens = newDelayMs < curDelay || newUnlockMs > curUnlock
        if (!loosens) {
            Config.setDelayMs(ctx, newDelayMs)
            Config.setUnlockMs(ctx, newUnlockMs)
            cancel(ctx, TYPE_TIMING)
            return RequestResult.APPLIED
        }
        queue(ctx, TYPE_TIMING, newDelayMs = newDelayMs, newUnlockMs = newUnlockMs)
        return RequestResult.QUEUED
    }

    fun requestUninstallUnlock(ctx: Context): RequestResult {
        if (isUnlocked(ctx) || pending(ctx, TYPE_UNINSTALL) != null) return RequestResult.NOOP
        // The window length is fixed at request time, so changing the setting later can't stretch it.
        queue(ctx, TYPE_UNINSTALL, newUnlockMs = Config.unlockMs(ctx))
        return RequestResult.QUEUED
    }

    fun confirm(ctx: Context, type: String): ConfirmResult {
        val p = pending(ctx, type) ?: return ConfirmResult.NONE
        val now = System.currentTimeMillis()
        if (now < p.readyAt()) return ConfirmResult.TOO_EARLY
        when (p.type) {
            TYPE_DISABLE -> Config.setEnabled(ctx, false)
            TYPE_CONFIG -> Config.setLists(ctx, p.packages, p.domains)
            TYPE_TIMING -> {
                Config.setDelayMs(ctx, p.newDelayMs)
                Config.setUnlockMs(ctx, p.newUnlockMs)
            }
            TYPE_UNINSTALL -> Config.setUnlockUntil(ctx, now + p.newUnlockMs)
        }
        cancel(ctx, type)
        return ConfirmResult.OK
    }

    // ---- storage ----

    private fun key(type: String) = "pending_$type"

    private fun queue(
        ctx: Context,
        type: String,
        packages: List<String> = emptyList(),
        domains: List<String> = emptyList(),
        newDelayMs: Long = 0L,
        newUnlockMs: Long = 0L,
    ) {
        val p = Pending(
            type = type,
            since = System.currentTimeMillis(),
            delayMs = Config.delayMs(ctx),
            packages = packages,
            domains = domains,
            newDelayMs = newDelayMs,
            newUnlockMs = newUnlockMs,
        )
        val o = JSONObject()
        o.put("type", p.type)
        o.put("since", p.since)
        o.put("delay", p.delayMs)
        o.put("packages", JSONArray(p.packages))
        o.put("domains", JSONArray(p.domains))
        o.put("newDelay", p.newDelayMs)
        o.put("newUnlock", p.newUnlockMs)
        Config.prefs(ctx).edit().putString(key(type), o.toString()).apply()
    }

    private fun decode(raw: String): Pending {
        val o = JSONObject(raw)
        return Pending(
            type = o.getString("type"),
            since = o.getLong("since"),
            delayMs = o.getLong("delay"),
            packages = strings(o.optJSONArray("packages")),
            domains = strings(o.optJSONArray("domains")),
            newDelayMs = o.optLong("newDelay", 0L),
            newUnlockMs = o.optLong("newUnlock", 0L),
        )
    }

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return List(a.length()) { a.getString(it) }
    }
}
