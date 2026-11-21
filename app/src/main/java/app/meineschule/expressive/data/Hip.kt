package app.meineschule.expressive.data

import app.meineschule.expressive.core.Dates
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.time.LocalDate

/**
 * Home.InfoPoint: HTML-Fragment → native Struktur. Serverseitiges HTML wird NIE in einer WebView gerendert.
 * Es wird (1) mit Jsoup-Safelist bereinigt (keine Skripte, Handler, Styles, unsichere URLs) und (2) in Daten überführt;
 * unbekannte Inhalte erscheinen als bereinigte Textblöcke.
 * Struktur laut Portal-Referenz: „Noten“ (Fach = h4, Zeilen mit 4 Zellen), „Fehlzeiten“ (2), „Bemerkungen“ (5), „Unterricht“ (3).
 */
data class Grade(val subject: String, val date: String, val value: String, val remark: String, val information: String)
data class HipAbsence(val date: String, val remark: String)
data class Remark(val date: String, val text: String, val subject: String, val type: String, val author: String)
enum class DueSource { Text, Inferred }
data class Due(val date: LocalDate, val source: DueSource)
data class HipLesson(val date: String, val subject: String, val homework: String, val due: Due?)
data class HipData(val grades: List<Grade>, val absences: List<HipAbsence>, val remarks: List<Remark>, val lessons: List<HipLesson>, val otherText: List<String>, val recognized: Boolean) {
    val isEmpty get() = grades.isEmpty() && absences.isEmpty() && remarks.isEmpty() && lessons.isEmpty() && otherText.isEmpty()
}

object HipParser {
    private val safelist = Safelist.none().addTags("p", "br", "b", "strong", "i", "em", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tr", "td", "th", "div", "span")

    fun sanitize(html: String): String = Jsoup.clean(html, safelist)

    fun parse(html: String?): HipData {
        if (html.isNullOrBlank()) return HipData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
        val doc = Jsoup.parseBodyFragment(sanitize(html))
        val grades = mutableListOf<Grade>(); val absences = mutableListOf<HipAbsence>(); val remarks = mutableListOf<Remark>(); val lessons = mutableListOf<HipLesson>()
        var section: String? = null; var subject: String? = null; var recognized = false
        val used = HashSet<Element>()
        for (el in doc.body().allElements) {
            val tag = el.tagName()
            if (tag.matches(Regex("h[1-6]"))) {
                val t = el.text().trim().lowercase()
                val hit = listOf("noten", "fehlzeiten", "bemerkungen", "unterricht").firstOrNull { t == it || t.startsWith(it) }
                if (hit != null && tag != "h4") { section = hit; subject = null; recognized = true; used += el; continue }
                if (tag == "h4" && section == "noten") { subject = el.text().trim(); used += el; continue }
            }
            if (tag != "tr" || section == null) continue
            if (el.children().any { it.tagName() == "th" }) { used += el; continue }
            val c = el.children().filter { it.tagName() == "td" }.map { it.text().trim() }
            val ok = when {
                section == "noten" && c.size == 4 -> { grades += Grade(subject ?: "Ohne Fach", c[0], c[1], c[2], c[3]); true }
                section == "fehlzeiten" && c.size == 2 -> { absences += HipAbsence(c[0], c[1]); true }
                section == "bemerkungen" && c.size == 5 -> { remarks += Remark(c[0], c[1], c[2], c[3], c[4]); true }
                section == "unterricht" && c.size == 3 -> { lessons += HipLesson(c[0], c[1], c[2], extractDue(c[2])); true }
                else -> false
            }
            if (ok) used += el
        }
        // Rest: Textblöcke, die nicht erkannt wurden (z. B. Hinweise)
        val other = if (recognized && (grades.isNotEmpty() || absences.isNotEmpty() || remarks.isNotEmpty() || lessons.isNotEmpty())) emptyList()
        else doc.body().select("p, li, h1, h2, h3, h4, tr").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        return HipData(grades, absences, remarks, lessons, other, recognized)
    }

    /** Explizite Frist „zum DD.MM.YYYY“ im Text. */
    fun extractDue(text: String?): Due? {
        val m = Regex("""zum\s+(\d{1,2})\.(\d{1,2})\.(\d{4})""", RegexOption.IGNORE_CASE).find(text.orEmpty()) ?: return null
        return runCatching { Due(LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()), DueSource.Text) }.getOrNull()
    }

    /** Clientseitige Ableitung (muss in der UI als „ermittelt“ gekennzeichnet werden): nächste nicht abgesagte Stunde desselben Fachs. */
    fun inferDue(lesson: HipLesson, days: List<ScheduleDay>): Due? {
        val from = Dates.date(lesson.date) ?: return null
        return days.sortedBy { it.date }.firstOrNull { d -> d.date.isAfter(from) && d.slots.any { s -> s.entries.any { !it.cancelled && it.subject.equals(lesson.subject, true) } } }?.let { Due(it.date, DueSource.Inferred) }
    }
}
