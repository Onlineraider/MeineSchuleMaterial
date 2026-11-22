package app.meineschule.expressive.data

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Eine Zeile der Portal-Tabelle „Änderungen“ (Stunde · Klasse/Gruppe · Fach · Lehrer · Raum · Info). Gelb hinterlegte Zeilen sind Verschiebungen/Änderungen. */
data class ChangeRow(val hours: String, val group: String, val subject: String, val teacher: String, val room: String, val info: String, val highlighted: Boolean)
data class DayChanges(val date: LocalDate, val notes: List<String>, val rows: List<ChangeRow>)

object ChangesParser {
    private val DE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun parse(date: LocalDate, html: String): DayChanges {
        val doc = Jsoup.parse(Jsoup.clean(html, "", Safelist.none().addTags("table", "thead", "tbody", "tr", "td", "th", "div", "p", "span", "h1", "h2", "h3", "h4", "li", "ul").addAttributes(":all", "class", "style", "bgcolor"), org.jsoup.nodes.Document.OutputSettings().prettyPrint(false)))
        val rows = mutableListOf<ChangeRow>()
        for (t in doc.select("table")) {
            if (t.select("table").size > 1) continue
            val trs = t.select("tr"); val head = trs.firstOrNull { r -> r.children().any { it.tagName() == "th" } } ?: continue
            val h = head.children().map { clean(it.text()).lowercase() }
            fun col(vararg k: String) = h.indexOfFirst { c -> k.any { c.contains(it) } }
            val iH = col("stunde"); val iG = col("klasse", "gruppe"); val iF = col("fach"); val iL = col("lehrer"); val iR = col("raum"); val iI = col("info", "bemerk")
            if (iH < 0 && iF < 0) continue
            for (tr in trs) {
                if (tr === head) continue
                val c = tr.children().filter { it.tagName() == "td" }.map { clean(it.text()) }; if (c.all { it.isEmpty() }) continue
                fun g(i: Int) = if (i >= 0) c.getOrNull(i).orEmpty() else ""
                val cls = (tr.className() + " " + tr.attr("style") + " " + tr.attr("bgcolor") + " " + tr.children().joinToString(" ") { it.className() + " " + it.attr("style") }).lowercase()
                val hl = Regex("warning|yellow|gelb|changed|ffff|fff[0-9a-f]|background").containsMatchIn(cls) || g(iH).contains('>') || g(iL).contains('>') || g(iR).contains('>')
                rows += ChangeRow(g(iH), g(iG), g(iF), g(iL), g(iR), g(iI), hl)
            }
        }
        val notes = doc.select("div, p, li").filter { it.children().none { c -> c.tagName() in setOf("div", "p", "table", "ul") } && it.parents().none { p -> p.tagName() == "table" } }
            .map { clean(it.text()) }.filter { it.length in 3..300 && Regex("^(präsenz|abwesen|hinweis|info|fehlt|fehlende|vertretung)", RegexOption.IGNORE_CASE).containsMatchIn(it) }.distinct()
        return DayChanges(date, notes, rows)
    }

    private val SIDE = Regex("(\\d+)\\s*(?:-\\s*(\\d+))?\\s*(?:\\((\\d{2}\\.\\d{2}\\.\\d{4})\\))?")
    private data class Side(val from: Int, val to: Int, val date: LocalDate?)
    private fun side(s: String, default: LocalDate): Side? = SIDE.find(s)?.let { m -> val a = m.groupValues[1].toInt(); Side(a, m.groupValues[2].toIntOrNull() ?: a, m.groupValues[3].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it, DE) }.getOrNull() } ?: default) }

    /** Ist die Zeile für diese Person/Klasse/Gruppe relevant? */
    fun relevant(row: ChangeRow, names: Collection<String>): Boolean {
        val n = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }; if (n.isEmpty()) return false
        return row.group.split(Regex("[,;/\\s]+")).any { t -> t.isNotBlank() && n.contains(t.lowercase()) } || n.contains(row.group.trim().lowercase())
    }

    /** Bettet die relevanten Änderungen in den Tagesplan ein: neue/verlegte Stunden werden markiert, die ursprüngliche Stunde entfällt. */
    fun overlay(day: ScheduleDay?, date: LocalDate, rows: List<ChangeRow>): ScheduleDay {
        val slots = (day?.slots ?: emptyList()).toMutableList()
        fun ensure(i: Int) { while (slots.size <= i) slots += Slot(slots.size, emptyList()) }
        fun put(i: Int, e: LessonEntry, replaceSubject: String?) { ensure(i); val s = slots[i]; val keep = s.entries.filterNot { replaceSubject != null && it.subject.equals(replaceSubject, true) }; slots[i] = s.copy(entries = keep + e) }
        for (r in rows) {
            val parts = r.hours.split('>').map { it.trim() }; val left = side(parts[0], date); val right = parts.getOrNull(1)?.let { side(it, date) }
            val extra = listOf("Lehrer" to r.teacher, "Raum" to r.room, "Info" to r.info).filter { it.second.isNotBlank() }
            val target = right ?: left
            if (right != null && left != null && left.date == date) for (i in left.from..left.to) put(i - 1, LessonEntry(r.subject.ifBlank { "–" }, true, false, listOf("Info" to "verlegt auf ${right.from}${if (right.to != right.from) "–${right.to}" else ""}. Std. (${right.date?.format(DateTimeFormatter.ofPattern("dd.MM."))})"), "verlegt"), r.subject.takeIf { it.isNotBlank() })
            if (target != null && target.date == date) for (i in target.from..target.to) put(i - 1, LessonEntry(r.subject.ifBlank { "–" }, false, true, extra, "Vertretungsplan"), r.subject.takeIf { it.isNotBlank() })
        }
        return ScheduleDay(date, slots.mapIndexed { i, s -> s.copy(index = i) })
    }
}
