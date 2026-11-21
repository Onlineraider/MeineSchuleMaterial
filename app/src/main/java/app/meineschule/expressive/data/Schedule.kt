package app.meineschule.expressive.data

import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.J
import kotlinx.serialization.json.*
import java.time.LocalDate

/**
 * Stundenplan-JSON (Portal) → Modell, nach der Logik der Web-App:
 * Slot = Array von Einträgen ODER Objekt `{e:[…]}`; Eintrag = Objekt (`st` = Fach) oder String.
 * Zusatzfelder (Raum/Lehrkraft …) werden ohne `null`, leere Werte, `-1` und verschachtelte Objekte angezeigt;
 * „Entfall“ und „Änderung“ werden an Feldnamen/Texten erkannt.
 */
data class LessonEntry(val subject: String, val cancelled: Boolean, val changed: Boolean, val extra: List<Pair<String, String>>, val reason: String?) {
    /** Kurzinfo für die Kachel (wie `ttHint` der Web-App: erste drei Werte). */
    val hint: String get() = extra.take(3).joinToString(" · ") { it.second }
}
data class TimeRange(val from: String, val to: String) { fun minutes(): Pair<Int, Int> = toMin(from) to toMin(to) }
private fun toMin(s: String) = s.substringBefore(':').toInt() * 60 + s.substringAfter(':').toInt()
data class Slot(val index: Int, val entries: List<LessonEntry>, val time: TimeRange? = null)
data class ScheduleDay(val date: LocalDate, val slots: List<Slot>) {
    /** Leere Randslots sind keine Stunden; Lücken (Freistunden) bleiben erhalten. */
    val activeSlots: List<Slot> get() { val a = slots.indexOfFirst { it.entries.isNotEmpty() }; val b = slots.indexOfLast { it.entries.isNotEmpty() }; return if (a < 0) emptyList() else slots.subList(a, b + 1) }
    val lessonCount: Int get() = slots.count { s -> s.entries.any { !it.cancelled } }
}
data class Schedule(val days: List<ScheduleDay>) {
    fun changes(): List<Triple<ScheduleDay, Slot, LessonEntry>> = days.flatMap { d -> d.slots.flatMap { s -> s.entries.filter { it.cancelled || it.changed }.map { Triple(d, s, it) } } }
}

object LessonTimes {
    val DEFAULT = listOf("07:50" to "08:35", "08:40" to "09:25", "09:45" to "10:30", "10:35" to "11:20", "11:40" to "12:25", "12:30" to "13:15", "13:35" to "14:20", "14:25" to "15:10", "15:15" to "16:00", "16:05" to "16:50").map { TimeRange(it.first, it.second) }
    fun parse(s: String?): List<TimeRange> = runCatching { s?.split(';')?.mapNotNull { p -> p.split('-').takeIf { it.size == 2 && Regex("\\d\\d:\\d\\d").matches(it[0]) && Regex("\\d\\d:\\d\\d").matches(it[1]) }?.let { TimeRange(it[0], it[1]) } }.orEmpty() }.getOrDefault(emptyList()).ifEmpty { DEFAULT }
    fun serialize(l: List<TimeRange>) = l.joinToString(";") { "${it.from}-${it.to}" }
    /** (aktuelle, nächste) Stunde für den Tag. */
    fun current(day: ScheduleDay?, times: List<TimeRange>, now: java.time.LocalTime = java.time.LocalTime.now()): Pair<Slot?, Slot?> {
        val m = now.hour * 60 + now.minute; var cur: Slot? = null; var next: Slot? = null
        day?.slots?.forEach { s -> val t = s.time ?: times.getOrNull(s.index) ?: return@forEach; if (s.entries.isEmpty()) return@forEach; val (a, b) = t.minutes(); if (m in a until b) cur = s else if (a > m && next == null) next = s }
        return cur to next
    }
}

private val CHANGE_KEY = Regex("(vertret|subst|replac|change|änder|aend|moved|verleg|ersatz)", RegexOption.IGNORE_CASE)
private val CANCEL_TXT = Regex("\\b(entfall|entfällt|ausfall|fällt aus)\\b", RegexOption.IGNORE_CASE)
private val CHANGE_TXT = Regex("vertretung|raum ?änder|raumwechsel|verlegt", RegexOption.IGNORE_CASE)
private val TIME = Regex("(\\d{1,2})[:.](\\d{2})\\s*(?:-|–|bis)\\s*(\\d{1,2})[:.](\\d{2})")

internal fun parseEntry(e: JsonElement): LessonEntry {
    val o = e as? JsonObject ?: JsonObject(mapOf("st" to JsonPrimitive((e as? JsonPrimitive)?.contentOrNull.orEmpty())))
    val extra = o.entries.filter { (k, v) -> k != "st" && k != "cancelled" && v is JsonPrimitive && v !is JsonNull && v.content.isNotBlank() && v.content != "null" && v.content != "-1" }.map { (k, v) -> k to (v as JsonPrimitive).content.trim() }
    val txt = extra.joinToString(" ") { it.second }
    val flag = extra.firstOrNull { (k, v) -> v != "false" && CHANGE_KEY.containsMatchIn(k) }
    val cancelled = (o["cancelled"] as? JsonPrimitive)?.content == "true" || CANCEL_TXT.containsMatchIn(txt)
    val subject = listOf("st", "subject", "s").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.trim()?.takeIf { s -> s.isNotEmpty() && s != "null" } }.orEmpty()
    return LessonEntry(subject.ifEmpty { "–" }, cancelled, !cancelled && (flag != null || CHANGE_TXT.containsMatchIn(txt)), extra.filter { it.first != "s" && it.first != "subject" }, flag?.first)
}

private fun slotTime(slot: JsonElement): TimeRange? {
    val o = slot as? JsonObject ?: return null
    for ((k, v) in o) { if (k == "e" || v !is JsonPrimitive || !v.isString) continue
        val m = TIME.find(v.content) ?: continue
        return TimeRange(m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2], m.groupValues[3].padStart(2, '0') + ":" + m.groupValues[4]) }
    return null
}

fun parseSchedule(json: JsonElement?): Schedule {
    val root = J(json); val sched = root.obj("schedule") ?: root.obj("Schedule") ?: root
    return Schedule(sched.arr("days").mapNotNull { d ->
        val date = Dates.date((d.str("d") ?: d.str("date"))?.take(10)) ?: return@mapNotNull null
        ScheduleDay(date, d.arr("l").mapIndexed { i, slot ->
            val raw = slot.raw
            val entries = when (raw) { is JsonArray -> raw.toList(); is JsonObject -> (raw["e"] as? JsonArray)?.toList().orEmpty(); else -> emptyList() }
            Slot(i, entries.filter { it !is JsonNull }.map(::parseEntry), raw?.let(::slotTime))
        })
    })
}
