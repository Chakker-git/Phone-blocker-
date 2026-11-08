package com.chakker.phoneblocker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.BitSet
import java.util.Calendar

/**
 * A schedule blocks a set of apps and sites on chosen weekdays between two times of day.
 * They run on top of the always-on lists and do not depend on the protection switch.
 *
 * days: bit 0 = Monday ... bit 6 = Sunday.
 * start/end: minutes since midnight. end < start wraps past midnight (22:00-06:00), and
 * start == end means the whole day.
 */
data class Schedule(
    val id: String,
    val packages: List<String>,
    val domains: List<String>,
    val days: Int,
    val start: Int,
    val end: Int,
)

object Schedules {
    private const val KEY = "schedules"
    private const val WEEK_MINUTES = 7 * 24 * 60
    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    // ---- storage ----

    @Volatile private var cachedRaw: String? = null
    @Volatile private var cachedList: List<Schedule> = emptyList()

    fun load(ctx: Context): List<Schedule> {
        val raw = Config.prefs(ctx).getString(KEY, null) ?: return emptyList()
        if (raw == cachedRaw) return cachedList
        val list = try {
            fromJson(raw)
        } catch (e: Exception) {
            emptyList()
        }
        cachedList = list
        cachedRaw = raw
        return list
    }

    fun save(ctx: Context, list: List<Schedule>) {
        Config.prefs(ctx).edit().putString(KEY, toJson(list)).apply()
    }

    fun toJson(list: List<Schedule>): String {
        val a = JSONArray()
        list.forEach { s ->
            val o = JSONObject()
            o.put("id", s.id)
            o.put("packages", JSONArray(s.packages))
            o.put("domains", JSONArray(s.domains))
            o.put("days", s.days)
            o.put("start", s.start)
            o.put("end", s.end)
            a.put(o)
        }
        return a.toString()
    }

    fun fromJson(raw: String): List<Schedule> {
        val a = JSONArray(raw)
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Schedule(
                id = o.getString("id"),
                packages = strings(o.optJSONArray("packages")),
                domains = strings(o.optJSONArray("domains")),
                days = o.getInt("days"),
                start = o.getInt("start"),
                end = o.getInt("end"),
            )
        }
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    fun newId(): String = java.lang.Long.toString(System.nanoTime() xor System.currentTimeMillis(), 36)

    // ---- time logic ----

    private fun hasDay(s: Schedule, day: Int) = (s.days shr day) and 1 == 1

    fun isActiveAt(s: Schedule, day: Int, minute: Int): Boolean {
        if (s.start == s.end) return hasDay(s, day)
        if (s.start < s.end) return hasDay(s, day) && minute >= s.start && minute < s.end
        // Wraps past midnight: the evening belongs to this day, the early morning to the day before.
        val prev = (day + 6) % 7
        return (hasDay(s, day) && minute >= s.start) || (hasDay(s, prev) && minute < s.end)
    }

    fun isActiveNow(ctx: Context, s: Schedule): Boolean {
        val c = Calendar.getInstance()
        c.timeInMillis = Clock.now(ctx)
        val day = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Calendar: Sunday = 1 -> Monday = 0
        val minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return isActiveAt(s, day, minute)
    }

    fun activePackages(ctx: Context): Set<String> {
        val list = load(ctx)
        if (list.isEmpty()) return emptySet()
        return list.filter { isActiveNow(ctx, it) }.flatMap { it.packages }.toSet()
    }

    fun activeDomains(ctx: Context): List<String> {
        val list = load(ctx)
        if (list.isEmpty()) return emptyList()
        return list.filter { isActiveNow(ctx, it) }.flatMap { it.domains }
    }

    fun hasDomains(ctx: Context): Boolean = load(ctx).any { it.domains.isNotEmpty() }

    // ---- loosening check ----

    /** For every app and site, the set of minutes of the week in which it is blocked. */
    private fun coverage(list: List<Schedule>): Map<String, BitSet> {
        val out = HashMap<String, BitSet>()
        list.forEach { s ->
            val bits = BitSet(WEEK_MINUTES)
            for (d in 0 until 7) for (m in 0 until 1440) {
                if (isActiveAt(s, d, m)) bits.set(d * 1440 + m)
            }
            if (bits.isEmpty) return@forEach
            (s.packages.map { "p:$it" } + s.domains.map { "d:$it" }).forEach { item ->
                out.getOrPut(item) { BitSet(WEEK_MINUTES) }.or(bits)
            }
        }
        return out
    }

    /** True if [new] blocks anything, at any time, that [old] blocked. Those changes must wait. */
    fun loosens(old: List<Schedule>, new: List<Schedule>): Boolean {
        val o = coverage(old)
        val n = coverage(new)
        return o.any { (item, bits) ->
            val remaining = bits.clone() as BitSet
            n[item]?.let { remaining.andNot(it) }
            !remaining.isEmpty
        }
    }

    // ---- labels ----

    fun daysLabel(mask: Int): String = when (mask and 0x7F) {
        0x7F -> "Every day"
        0b0011111 -> "Weekdays"
        0b1100000 -> "Weekends"
        else -> DAY_NAMES.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(", ")
    }

    fun timeLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun rangeLabel(s: Schedule): String =
        if (s.start == s.end) "All day" else "${timeLabel(s.start)}-${timeLabel(s.end)}"

    val dayNames: List<String> get() = DAY_NAMES
}
