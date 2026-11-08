package com.chakker.phoneblocker

import android.content.Context
import android.content.SharedPreferences

/** Persistent settings: on/off flag plus the app and domain blocklists. */
object Config {
    private const val PREFS = "blocker_prefs"
    private const val K_ENABLED = "enabled"
    private const val K_PACKAGES = "packages"
    private const val K_DOMAINS = "domains"

    val DEFAULT_PACKAGES = listOf(
        "com.reddit.frontpage",
        "com.discord",
    )

    val DEFAULT_DOMAINS = listOf(
        "reddit.com",
        "redd.it",
        "redditmedia.com",
        "redditstatic.com",
        "discord.com",
        "discord.gg",
        "discordapp.com",
        "discordapp.net",
        "discord.media",
    )

    /**
     * DNS-over-HTTPS providers. Always blocked while protection is on, so a browser
     * with "secure DNS" cannot tunnel around the local DNS filter.
     */
    val DOH_DOMAINS = listOf(
        "dns.google",
        "cloudflare-dns.com",
        "dns.quad9.net",
        "dns.nextdns.io",
        "doh.opendns.com",
        "dns.adguard-dns.com",
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(K_ENABLED, enabled).apply()
    }

    private const val K_DELAY_MS = "delay_ms"
    private const val K_UNLOCK_MS = "unlock_ms"
    private const val K_UNLOCK_UNTIL = "unlock_until"
    private const val K_GRACE_UNTIL = "grace_until"

    /** How long a loosening request has to wait before it can be confirmed. Default 1 minute (testing). */
    fun delayMs(ctx: Context): Long = prefs(ctx).getLong(K_DELAY_MS, 60_000L)

    fun setDelayMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_DELAY_MS, ms).apply()
    }

    /** How long uninstall stays unlocked after an approved request. Default 10 minutes. */
    fun unlockMs(ctx: Context): Long = prefs(ctx).getLong(K_UNLOCK_MS, 10 * 60_000L)

    fun setUnlockMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_UNLOCK_MS, ms).apply()
    }

    /** Wall-clock time until which the uninstall guard is open. 0 = locked. */
    fun unlockUntil(ctx: Context): Long = prefs(ctx).getLong(K_UNLOCK_UNTIL, 0L)

    fun setUnlockUntil(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_UNLOCK_UNTIL, ms).apply()
    }

    /** Short setup grace so device admin can be activated while the guard is running. */
    fun graceUntil(ctx: Context): Long = prefs(ctx).getLong(K_GRACE_UNTIL, 0L)

    fun setGraceUntil(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(K_GRACE_UNTIL, ms).apply()
    }

    fun packages(ctx: Context): List<String> =
        parsePackages(prefs(ctx).getString(K_PACKAGES, null) ?: DEFAULT_PACKAGES.joinToString("\n"))

    /** Package names are case-sensitive, so unlike domains they are not lowercased. */
    fun parsePackages(text: String): List<String> =
        text.split(Regex("[,\\s]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun userDomains(ctx: Context): List<String> =
        parseDomains(prefs(ctx).getString(K_DOMAINS, null) ?: DEFAULT_DOMAINS.joinToString("\n"))

    /** Everything the DNS filter should refuse to resolve. */
    fun blockedDomains(ctx: Context): List<String> = userDomains(ctx) + DOH_DOMAINS

    fun setLists(ctx: Context, packages: List<String>, domains: List<String>) {
        prefs(ctx).edit()
            .putString(K_PACKAGES, packages.joinToString("\n"))
            .putString(K_DOMAINS, domains.joinToString("\n"))
            .apply()
    }

    fun parse(text: String): List<String> =
        text.split(Regex("[,\\s]+"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun parseDomains(text: String): List<String> =
        parse(text)
            .map { it.removePrefix("*.").removePrefix("www.") }
            .filter { it.isNotEmpty() }
            .distinct()
}
