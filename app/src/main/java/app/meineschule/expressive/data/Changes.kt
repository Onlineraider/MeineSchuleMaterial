package app.meineschule.expressive.data

import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.J
import kotlinx.serialization.json.JsonElement
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Eine Vertretung/Änderung aus `replacements[]` der Portal-Antwort (Felder: lessonIndex/lessons/date → newLessonIndex/newDate,
 * subject→newSubject, teachers→newTeachers, rooms→newRooms, classesOrGroups, reasonText, message/text).
 * Anzeige wie im Portal: „3-4 (19.11.2026) > 6-7 (23.11.2026)“, „Löc > Löc, Sra“ usw.
 */
data class ChangeRow(
    val hours: String, val group: String, val subject: String, val teacher: String, val room: String, val info: String, val highlighted: Boolean,
    val cancelled: Boolean = false, val moved: Boolean = false, val reason: String? = null,
    /** Geänderte Felder (Bezeichnung, alt, neu), z. B. „Raum“, „313“, „312“. */ val diffs: List<Triple<String, String, String>> = emptyList(),
    /** Bei Verlegungen: woher (Tag/Stunden) und wohin. */ val move: Move? = null,
)
/** Verlegung einer Stunde: von (Tag, Stunden) nach (Tag, Stunden). */
data class Move(val fromDate: LocalDate?, val fromHours: String?, val toDate: LocalDate?, val toHours: String?) {
    /** Gegenstück eines Tauschs: A verschiebt von X nach Y, B von Y nach X. */
    fun swapsWith(o: Move) = fromDate == o.toDate && fromHours == o.toHours && toDate == o.fromDate && toHours == o.fromHours
}
/** Fasst gegenseitig getauschte Stunden zu Paaren zusammen (Rest als Einzelelemente). Das Paar steht in der Reihenfolge „kommt hierher“, „geht weg“, bezogen auf [day]. */
fun <T> pairSwaps(items: List<T>, day: LocalDate?, move: (T) -> Move?): List<List<T>> {
    val used = mutableSetOf<Int>(); val out = mutableListOf<List<T>>()
    items.forEachIndexed { i, a ->
        if (i in used) return@forEachIndexed
        val ma = move(a); val j = if (ma == null) -1 else items.indices.firstOrNull { it > i && it !in used && move(items[it])?.let { mb -> ma.swapsWith(mb) } == true } ?: -1
        if (j < 0) { out += listOf(a); return@forEachIndexed }
        used += j; val b = items[j]
        out += if (day != null && ma!!.toDate == day) listOf(a, b) else if (day != null && move(b)!!.toDate == day) listOf(b, a) else listOf(a, b)
    }
    return out
}
enum class ChangeKind(val label: String) { Cancelled("Entfällt"), Moved("Verlegt"), New("Neue Stunde"), Teacher("Vertretung"), Room("Raumwechsel"), Subject("Fachwechsel"), Mixed("Änderung"), Info("Hinweis") }
fun changeKind(cancelled: Boolean, moved: Boolean, reason: String?, diffs: List<Triple<String, String, String>>): ChangeKind {
    val l = diffs.map { it.first }.toSet()
    return when {
        cancelled -> ChangeKind.Cancelled; moved -> ChangeKind.Moved
        reason?.contains("neu", true) == true -> ChangeKind.New
        "Fach" in l -> ChangeKind.Subject; l == setOf("Lehrer") -> ChangeKind.Teacher; l == setOf("Raum") -> ChangeKind.Room
        l.isEmpty() -> ChangeKind.Info; else -> ChangeKind.Mixed
    }
}
fun ChangeRow.kind() = changeKind(cancelled, moved, reason, diffs)
data class DayChanges(val date: LocalDate, val notes: List<String>, val rows: List<ChangeRow>)

object ChangesParser {
    private val DE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Zeilen aus `replacements` – unabhängig vom Tag (Aufrufer filtert/verwendet `rix` aus dem Plan). */
    fun rows(root: J): List<ChangeRow> = root.arr("replacements").map { r ->
        fun s(k: String) = r.str(k)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun d(k: String) = Dates.date(s(k)?.take(10))
        val idx = s("lessonIndex")?.toIntOrNull(); val n = (s("lessons")?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val nIdx = s("newLessonIndex")?.toIntOrNull(); val date = d("date"); val nDate = d("newDate")
        fun range(i: Int?) = i?.let { if (n > 1) "$it-${it + n - 1}" else "$it" }
        fun at(h: String?, dt: LocalDate?) = listOfNotNull(h, dt?.let { "(${it.format(DE)})" }).joinToString(" ")
        val moved = (nIdx != null && nIdx != idx) || (nDate != null && nDate != date)
        val hours = if (moved) at(range(idx), date) + " > " + at(range(nIdx ?: idx), nDate ?: date) else range(idx).orEmpty()
        fun change(old: String?, new: String?) = when { new == null -> old.orEmpty(); old == null || old == new -> new; else -> "$old > $new" }
        fun df(l: String, o: String?, n: String?) = if (n != null && o != n) Triple(l, o ?: "–", n) else null
        val diffs = listOfNotNull(df("Fach", s("subject"), s("newSubject")), df("Lehrer", s("teachers"), s("newTeachers")), df("Raum", s("rooms"), s("newRooms")), df("Klasse/Gruppe", s("classesOrGroups"), s("newClassesOrGroups")))
        val reason = s("reasonText")
        val nothingNew = s("newSubject") == null && s("newTeachers") == null && s("newRooms") == null && !moved
        val cancelled = reason?.contains("ntf", true) == true || reason?.contains("ausf", true) == true || nothingNew
        ChangeRow(hours, change(s("classesOrGroups"), s("newClassesOrGroups")), change(s("subject"), s("newSubject")), change(s("teachers"), s("newTeachers")), change(s("rooms"), s("newRooms")),
            listOfNotNull(reason, s("message"), s("text")).joinToString(" · "), true, cancelled, moved, reason, diffs,
            if (moved) Move(date, range(idx), nDate ?: date, range(nIdx ?: idx)) else null)
    }

    fun fromJson(date: LocalDate, json: JsonElement): DayChanges {
        val root = J(json)
        val notes = root.arr("infos").filter { it.bool("isActive") != false }.filter { (it.str("date") ?: "").take(10).let { d -> d.isEmpty() || d == date.toString() } }
            .flatMap { listOfNotNull(it.str("informationPublic"), it.str("informationTeachers")?.let { t -> "Lehrkräfte: $t" }) }.filter { it.isNotBlank() }
        return DayChanges(date, notes.distinct(), rows(root))
    }

    /** Ist die Zeile für diese Klasse/Gruppe/Person relevant? (Klassen-/Gruppennamen aus „classesOrGroups“) */
    fun relevant(row: ChangeRow, names: Collection<String>): Boolean {
        val n = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }; if (n.isEmpty()) return false
        return row.group.split(Regex("[,;/>\\s]+")).any { t -> t.isNotBlank() && n.contains(t.lowercase()) }
    }
}
