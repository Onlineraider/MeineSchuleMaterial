package app.meineschule.expressive.data

import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.J
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate

/**
 * Stundenplan-JSON (Portal) → neutrales Modell. Dokumentiert sind nur: schedule.days[].d (ISO-Datum), .l[] (Slots),
 * .l[].e[] (Einträge) mit .st (Fach) und optional .cancelled. Der nullbasierte Slot-Index ist NICHT zwingend die sichtbare
 * Stundennummer → er wird nur als „Slot n“ gezeigt. Lehrkraft/Raum/Uhrzeit sind nicht belegt; undokumentierte Felder bleiben in [extra].
 */
data class LessonEntry(val subject: String, val cancelled: Boolean, val extra: Map<String, String>)
data class Slot(val index: Int, val entries: List<LessonEntry>)
data class ScheduleDay(val date: LocalDate, val slots: List<Slot>) {
    /** Leere Randslots sind keine Stunden; Lücken (Freistunden) bleiben erhalten. */
    val activeSlots: List<Slot> get() { val a = slots.indexOfFirst { it.entries.isNotEmpty() }; val b = slots.indexOfLast { it.entries.isNotEmpty() }; return if (a < 0) emptyList() else slots.subList(a, b + 1) }
    val lessonCount: Int get() = slots.count { s -> s.entries.any { !it.cancelled } }
}
data class Schedule(val days: List<ScheduleDay>)

fun parseSchedule(json: JsonElement?): Schedule {
    val root = J(json); val sched = root.obj("schedule") ?: root
    return Schedule(sched.arr("days").mapNotNull { d ->
        val date = Dates.date(d.str("d")?.take(10)) ?: return@mapNotNull null
        ScheduleDay(date, d.arr("l").mapIndexed { i, slot ->
            Slot(i, slot.arr("e").map { e ->
                val known = setOf("st", "cancelled")
                LessonEntry(e.str("st")?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unbekanntes Fach", e.bool("cancelled") == true,
                    (e.raw as? kotlinx.serialization.json.JsonObject)?.filterKeys { it.lowercase() !in known }?.mapValues { it.value.toString().trim('"') } ?: emptyMap())
            })
        })
    })
}
