package app.meineschule.expressive.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * Home.InfoPoint: HTML-Fragment → native Tabellen (Port von `hipParse` der Web-App). Serverseitiges HTML wird NIE in einer
 * WebView gerendert: erst Jsoup-Safelist, dann Auswertung. Tabellen werden an Überschrift/Spaltenköpfen als
 * Fehlzeiten/Bemerkungen/Noten/Hausaufgaben/Unterricht/Stundenplan klassifiziert, alles andere ist „Weitere“.
 */
enum class HipClass(val label: String) { Abs("Fehlzeiten"), Rem("Bemerkungen"), Grd("Noten"), Hw("Hausaufgaben"), Les("Unterricht"), Plan("Stundenplan"), Oth("Weitere") }
data class HipTable(val title: String, val head: List<String>, val rows: List<List<String>>, val cls: HipClass)
data class AbsEntry(val day: String, val text: String)
data class AbsSummary(val daysExcused: Int, val daysUnexcused: Int, val hoursExcused: Int, val hoursUnexcused: Int, val entries: List<AbsEntry>)
data class HipData(val tables: List<HipTable>, val text: List<String>) {
    val isEmpty get() = tables.isEmpty() && text.isEmpty()
    fun of(c: HipClass) = tables.filter { it.cls == c }
}

object HipParser {
    private val safelist = Safelist.none().addTags("p", "br", "b", "strong", "i", "em", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "div", "span", "legend", "summary", "dt", "dd", "dl")
    private val HEAD = Regex("^(h[1-6]|strong|b|legend|summary|dt)$")
    fun sanitize(html: String): String = Jsoup.clean(html, safelist)
    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun parse(html: String?): HipData {
        if (html.isNullOrBlank()) return HipData(emptyList(), emptyList())
        val doc = Jsoup.parseBodyFragment(sanitize(html))
        val out = mutableListOf<HipTable>(); val sigs = mutableListOf<String>()
        var ph = ""; var pl = ""
        for (el in doc.body().select("table,h1,h2,h3,h4,h5,h6,strong,b,legend,summary,dt,p,div,span,li")) {
            val tag = el.tagName()
            if (tag != "table") {
                if (el.parents().any { it.tagName() == "table" }) continue
                if (!HEAD.matches(tag) && el.children().isNotEmpty()) continue
                val x = clean(el.text()); if (x.isNotEmpty() && x.length <= 100) { if (HEAD.matches(tag)) ph = x else pl = x }
                continue
            }
            if (el.select("table").size > 1) continue
            val rs = el.select("tr").map { r -> r.children().filter { it.tagName() == "th" || it.tagName() == "td" }.map { clean(it.text()) } }.filter { r -> r.any { it.isNotEmpty() } }.toMutableList()
            if (rs.isEmpty()) { ph = ""; pl = ""; continue }
            var old = el.selectFirst("caption")?.text().orEmpty(); var e: Element? = el.previousElementSibling(); var n = 0
            while (old.isBlank() && e != null && n++ < 3) { val x = clean(e.text()); if (x.isNotEmpty() && x.length < 80) old = x; e = e.previousElementSibling() }
            var ti = clean(el.selectFirst("caption")?.text().orEmpty()).ifEmpty { ph.ifEmpty { pl } }; ph = ""; pl = ""
            if (rs.size > 1 && rs[0].size == 1 && rs[1].size > 1) ti = rs.removeAt(0)[0]
            val head = rs[0]; val hs = head.joinToString(" ").lowercase(); val s = "$old $hs".lowercase()
            val body = rs.drop(1).filter { it.joinToString("|") != head.joinToString("|") }
            val cls = when {
                Regex("hausaufg").containsMatchIn(hs) -> HipClass.Hw
                Regex("stundenplan", RegexOption.IGNORE_CASE).containsMatchIn(old) -> HipClass.Plan
                Regex("fehl|absenz|abwesen|entschuldigt").containsMatchIn(s) -> HipClass.Abs
                Regex("bemerk|vermerk").containsMatchIn(s) -> HipClass.Rem
                Regex("note|zensur").containsMatchIn(s) -> HipClass.Grd
                Regex("unterricht|inhalt").containsMatchIn(s) -> HipClass.Les
                else -> HipClass.Oth
            }
            val sig = head.joinToString("|"); val p = out.lastOrNull()
            if (p != null && p.cls == cls && sigs.last() == sig && (ti.isEmpty() || ti == p.title)) out[out.lastIndex] = p.copy(rows = p.rows + body) else { out += HipTable(ti, head, body, cls); sigs += sig }
        }
        val text = if (out.isNotEmpty()) emptyList() else doc.body().select("p, li, h1, h2, h3, h4").map { clean(it.text()) }.filter { it.isNotEmpty() }.distinct()
        return HipData(out, text)
    }

    private val DATE = Regex("\\b\\d{1,2}\\.\\d{1,2}\\.(?:\\d{4}|\\d{2})\\b")
    private data class Seg(val whole: Boolean = false, val a: Int? = null, val b: Int? = null, val raw: String? = null, val un: Boolean = false, val st: String = "")
    private fun segs(txt: String): List<Seg> = txt.split(Regex("\\s*,\\s*")).filter { it.isNotBlank() }.map { p ->
        val un = Regex("un-?entschuldigt|nicht entschuldigt", RegexOption.IGNORE_CASE).containsMatchIn(p)
        val st = if (un) "Unentschuldigt" else if (Regex("entschuldigt", RegexOption.IGNORE_CASE).containsMatchIn(p)) "Entschuldigt" else ""
        val range = Regex("(\\d+)\\.?\\s*(?:bis|-|–)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(p)
        val single = Regex("(\\d+)\\.?\\s*(?:Std|Stunde)", RegexOption.IGNORE_CASE).find(p)
        when {
            Regex("^\\s*(ganzer\\s+)?tag\\b", RegexOption.IGNORE_CASE).containsMatchIn(p) || Regex("ganz", RegexOption.IGNORE_CASE).containsMatchIn(p) -> Seg(whole = true, un = un, st = st)
            range != null -> Seg(a = range.groupValues[1].toInt(), b = range.groupValues[2].toInt(), un = un, st = st)
            single != null -> Seg(a = single.groupValues[1].toInt(), b = single.groupValues[1].toInt(), un = un, st = st)
            else -> Seg(raw = p, un = un, st = st)
        }
    }
    private fun segText(l: List<Seg>): String {
        val g = mutableListOf<Triple<Int, Int, String>>()
        l.filter { it.a != null }.sortedBy { it.a }.forEach { s -> val last = g.lastOrNull(); if (last != null && last.third == s.st && s.a!! <= last.second + 1) g[g.lastIndex] = Triple(last.first, maxOf(last.second, s.b!!), last.third) else g += Triple(s.a!!, s.b!!, s.st) }
        fun f(st: String) = if (st.isNotEmpty()) " ($st)" else ""
        return (l.filter { it.whole }.map { "Ganzer Tag${f(it.st)}" } + g.map { (a, b, st) -> (if (a == b) "$a. Std." else "$a. bis $b. Std.") + f(st) } + l.filter { it.raw != null }.map { it.raw!! }).joinToString(", ")
    }

    /** Fehlzeiten-Auswertung wie `absView` der Web-App (Summen aus „Fehltage/Fehlstunden“-Zeilen, sonst aus den Einträgen). */
    fun absSummary(ts: List<HipTable>): AbsSummary {
        val all = ts.flatMap { it.rows }; val ent = mutableListOf<AbsEntry>(); val days = intArrayOf(0, 0); val hrs = intArrayOf(0, 0)
        all.forEach { r ->
            val di = r.indexOfFirst { DATE.containsMatchIn(it) }; if (di < 0) return@forEach
            val sg = segs(r.filterIndexed { i, c -> i != di && c.isNotEmpty() && !Regex("^[-–—]+$").matches(c) }.joinToString(", "))
            sg.forEach { s -> if (s.whole) days[if (s.un) 1 else 0]++ else if (s.a != null) hrs[if (s.un) 1 else 0] += s.b!! - s.a + 1 }
            ent += AbsEntry(DATE.find(r[di])!!.value, segText(sg))
        }
        fun nums(l: String): List<Int>? = all.firstOrNull { Regex("^$l", RegexOption.IGNORE_CASE).containsMatchIn(it.firstOrNull().orEmpty().trim()) }?.drop(1)?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size >= 2 }
        val td = nums("Fehltag") ?: listOf(days[0], days[1]); val th = nums("Fehlstunde") ?: listOf(hrs[0], hrs[1])
        return AbsSummary(td[0], td[1], th[0], th[1], ent)
    }
}
