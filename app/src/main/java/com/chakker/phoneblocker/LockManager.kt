package com.chakker.phoneblocker

import android.content.Context

/**
 * The "can't talk myself out of it" part.
 *
 * Tightening changes (turning protection on, adding to the blocklists) apply immediately.
 * Loosening changes (turning protection off, removing anything from the blocklists) are
 * queued, must sit for DELAY_MS, and then have to be explicitly confirmed within WINDOW_MS.
 */
object LockManager {
    /** 1 minute for testing. Raise this (e.g. 1h or 24h) once the thing works. */
    const val DELAY_MS = 60_000L

    /** How long after becoming confirmable the request stays valid before it lapses. */
    const val WINDOW_MS = 10 * 60_000L

    private const val TYPE_DISABLE = "disable"
    private const val TYPE_CONFIG = "config"

    private const val P_TYPE = "p_type"
    private const val P_SINCE = "p_since"
    private const val P_PACKAGES = "p_packages"
    private const val P_DOMAINS = "p_domains"

    enum class RequestResult { APPLIED, QUEUED, NOOP }
    enum class ConfirmResult { OK, TOO_EARLY, NONE }

    data class Pending(
        val type: String,
        val since: Long,
        val packages: List<String>,
        val domains: List<String>,
    ) {
        fun readyAt(): Long = since + DELAY_MS
        fun expiresAt(): Long = since + DELAY_MS + WINDOW_MS
    }

    fun pending(ctx: Context): Pending? {
        val p = Config.prefs(ctx)
        val type = p.getString(P_TYPE, null) ?: return null
        val pending = Pending(
            type = type,
            since = p.getLong(P_SINCE, 0L),
            packages = Config.parsePackages(p.getString(P_PACKAGES, "") ?: ""),
            domains = Config.parseDomains(p.getString(P_DOMAINS, "") ?: ""),
        )
        if (System.currentTimeMillis() > pending.expiresAt()) {
            cancel(ctx)
            return null
        }
        return pending
    }

    fun cancel(ctx: Context) {
        Config.prefs(ctx).edit()
            .remove(P_TYPE).remove(P_SINCE).remove(P_PACKAGES).remove(P_DOMAINS)
            .apply()
    }

    /** The app list as the user last set it: the queued version if one is waiting, else the active one. */
    fun effectivePackages(ctx: Context): List<String> {
        val p = pending(ctx)
        return if (p != null && p.type == TYPE_CONFIG) p.packages else Config.packages(ctx)
    }

    /** The domain list as the user last set it: the queued version if one is waiting, else the active one. */
    fun effectiveDomains(ctx: Context): List<String> {
        val p = pending(ctx)
        return if (p != null && p.type == TYPE_CONFIG) p.domains else Config.userDomains(ctx)
    }

    fun requestDisable(ctx: Context) {
        if (!Config.isEnabled(ctx)) return
        val existing = pending(ctx)
        if (existing != null && existing.type == TYPE_DISABLE) return // keep the running timer
        queue(ctx, TYPE_DISABLE, emptyList(), emptyList())
    }

    fun requestConfig(ctx: Context, packages: List<String>, domains: List<String>): RequestResult {
        val curP = Config.packages(ctx).toSet()
        val curD = Config.userDomains(ctx).toSet()
        val newP = packages.toSet()
        val newD = domains.toSet()

        if (curP == newP && curD == newD) {
            // Back to exactly what's active: any queued removal is moot.
            if (pending(ctx)?.type == TYPE_CONFIG) cancel(ctx)
            return RequestResult.NOOP
        }

        val loosens = curP.any { it !in newP } || curD.any { it !in newD }
        if (!loosens || !Config.isEnabled(ctx)) {
            Config.setLists(ctx, packages, domains)
            if (pending(ctx)?.type == TYPE_CONFIG) cancel(ctx)
            return RequestResult.APPLIED
        }
        queue(ctx, TYPE_CONFIG, packages, domains)
        return RequestResult.QUEUED
    }

    fun confirm(ctx: Context): ConfirmResult {
        val p = pending(ctx) ?: return ConfirmResult.NONE
        if (System.currentTimeMillis() < p.readyAt()) return ConfirmResult.TOO_EARLY
        when (p.type) {
            TYPE_DISABLE -> Config.setEnabled(ctx, false)
            TYPE_CONFIG -> Config.setLists(ctx, p.packages, p.domains)
        }
        cancel(ctx)
        return ConfirmResult.OK
    }

    private fun queue(ctx: Context, type: String, packages: List<String>, domains: List<String>) {
        Config.prefs(ctx).edit()
            .putString(P_TYPE, type)
            .putLong(P_SINCE, System.currentTimeMillis())
            .putString(P_PACKAGES, packages.joinToString("\n"))
            .putString(P_DOMAINS, domains.joinToString("\n"))
            .apply()
    }
}
