package app.meineschule.expressive.data

import app.meineschule.expressive.AppContainer
import app.meineschule.expressive.core.Dates
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Ein Schulfach (aus `School/Student/{id}.subjectInfo`) mit den Lehrkräften. */
data class Subject(val name: String, val teachers: List<Person>)

/** Manuelle Anpassungen: Kürzel→Fach sowie je Fach zusätzlich/ausgeschlossene Chats. */
data class SubjectOverrides(val alias: Map<String, String> = emptyMap(), val inc: Map<String, Set<String>> = emptyMap(), val exc: Map<String, Set<String>> = emptyMap())

data class SubjectDoc(val chatId: String, val chatName: String, val msgId: String, val attachmentId: String, val name: String, val at: String?, val author: String?)

object SubjectMatch {
    fun key(s: String) = s.lowercase().filter { it.isLetter() }
    /** „En1_10_8b“ → „En“, „Phy“ → „Phy“. */
    fun base(token: String): String = token.substringBefore('_').trimEnd { it.isDigit() }.trim()

    /** Kürzel → Fach: Präfix (Ma→Mathematik) oder Buchstabenfolge (Gg→Geografie, kR→Katholische Religionslehre); bei Mehrdeutigkeit null. */
    fun heuristic(token: String, subjects: List<String>): String? {
        val t = key(base(token)); if (t.length < 2 || t.length > 5) return null
        val scored = subjects.mapNotNull { n ->
            val k = key(n); if (k.isEmpty() || k[0] != t[0]) return@mapNotNull null
            if (k.startsWith(t)) return@mapNotNull n to 0
            var from = 0; var last = -1; var gaps = 0
            for (ch in t) { val j = k.indexOf(ch, from); if (j < 0) return@mapNotNull null; gaps += j - last - 1; last = j; from = j + 1 }
            n to 1 + gaps
        }
        val best = scored.minOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    /** Abkürzungen aus den Noten-Überschriften des Home.InfoPoint („sk - Sozialkunde“). */
    fun hipMap(h: HipData?): Map<String, String> = h?.tables.orEmpty().mapNotNull { t ->
        val m = Regex("^\\s*([\\p{L}]{1,6})\\s*[-–]\\s*(.+)$").find(t.title) ?: return@mapNotNull null
        key(m.groupValues[1]) to m.groupValues[2].trim()
    }.toMap()

    fun resolve(token: String, subjects: List<String>, hip: Map<String, String>, alias: Map<String, String>, fuzzy: Boolean = true): String? {
        val k = key(token); if (k.isEmpty()) return null
        subjects.firstOrNull { key(it) == k }?.let { return it }
        val b = key(base(token))
        (alias[b] ?: alias[k])?.let { return it }
        hip[b]?.let { n -> subjects.firstOrNull { key(it) == key(n) }?.let { return it } }
        return if (fuzzy) heuristic(token, subjects) else null
    }

    /** Erwähnt der Text (Termin-/Chattitel) das Fach – Vollname oder Kürzel? */
    fun mentions(text: String, subject: String, subjects: List<String>, hip: Map<String, String>, alias: Map<String, String>, fuzzy: Boolean = true): Boolean {
        if (text.contains(subject, ignoreCase = true)) return true
        return Regex("[\\p{L}\\p{N}_]+").findAll(text).any { w -> w.value.length >= 2 && resolve(w.value, subjects, hip, alias, fuzzy) == subject }
    }

    /**
     * Zu welchem Fach gehört ein Kalendertermin? Der Ersteller ist meist die Fachlehrkraft: Stimmt er mit genau einer Lehrkraft eines Fachs überein, ist das (bei mehreren Fächern der Lehrkraft zusätzlich per Titel) das Fach.
     * Ist der Ersteller keine Lehrkraft der eigenen Fächer, wird nur noch auf eindeutige Fachnamen/Kürzel geprüft (kein „ungefähres“ Raten) – so landen fremde Tests (z. B. anderer Englischkurs) nicht fälschlich im Fach.
     */
    fun eventSubject(e: CalendarEvent, subs: List<Subject>, hip: Map<String, String>, alias: Map<String, String>): Subject? {
        val names = subs.map { it.name }
        val cid = e.creatorId?.lowercase()
        val byTeacher = if (cid == null) emptyList() else subs.filter { s -> s.teachers.any { p -> p.userId?.lowercase() == cid || p.id?.lowercase() == cid } }
        if (byTeacher.size == 1) return byTeacher[0]
        if (byTeacher.size > 1) return byTeacher.singleOrNull { mentions(e.title, it.name, names, hip, alias) } ?: byTeacher.singleOrNull { mentions(e.title, it.name, names, hip, alias, false) }
        val hits = subs.filter { mentions(e.title, it.name, names, hip, alias, false) }
        return hits.singleOrNull()
    }
}

data class ChatMatch(val chats: List<Chat>, val all: List<Chat>, val auto: Set<String>)

class Subjects(private val c: AppContainer) {
    private val cache = ConcurrentHashMap<String, List<Subject>>()
    private val members = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    private val weeks = ConcurrentHashMap<String, Schedule>()

    suspend fun list(studentId: String): List<Subject> {
        val k = "${c.api.school}:$studentId"; cache[k]?.let { return it }
        val info = c.repos.school.student(studentId)
        return info.subjects.mapNotNull { g -> g.title?.takeIf { it.isNotBlank() }?.let { Subject(it, g.persons) } }.also { cache[k] = it }
    }

    suspend fun memberKeys(chatId: String): List<String> {
        members[chatId]?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000 }?.let { return it.second }
        val keys = c.repos.messenger.info(chatId).arr("recipients", "members", "participants", "users").mapNotNull { (it.raw as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.map { it.substringBefore('=').trim() }
        members[chatId] = System.currentTimeMillis() to keys; return keys
    }

    /** Automatische Zuordnung: Name nennt das Fach – oder Mitglieder sind nur Lehrkräfte des Fachs (+ ich) plus mindestens eine Gruppe/Klasse. */
    fun autoMatch(sub: Subject, all: List<String>, hip: Map<String, String>, alias: Map<String, String>, chat: Chat, keys: List<String>?): Boolean {
        if (SubjectMatch.mentions(chat.name, sub.name, all, hip, alias)) return true
        if (keys == null) return false
        val teachers = sub.teachers.mapNotNull { it.userId?.lowercase() }.toSet(); if (teachers.isEmpty()) return false
        val me = c.repos.meId?.lowercase()
        val persons = keys.filter { it.startsWith("U:USER:", true) }.map { it.substringAfterLast(':').lowercase() }
        val groups = keys.filter { !it.startsWith("U:USER:", true) }
        return persons.any { it in teachers } && persons.all { it in teachers || it == me } && groups.isNotEmpty()
    }

    /** Alle Chats, die zum Fach gehören (automatisch + manuell), und alle Chats (für die Anpassung). */
    suspend fun chatsFor(sub: Subject, all: List<String>, hip: Map<String, String>, ov: SubjectOverrides): ChatMatch = coroutineScope {
        val chats = c.repos.messenger.index().filter { !it.infoChannel }
        val gate = Semaphore(6); val alias = ov.alias
        val matched = chats.take(80).map { ch -> async {
            val byName = SubjectMatch.mentions(ch.name, sub.name, all, hip, alias)
            val keys = if (byName) null else gate.withPermit { runCatching { memberKeys(ch.id) }.getOrNull() }
            ch to autoMatch(sub, all, hip, alias, ch, keys)
        } }.awaitAll().filter { it.second }.map { it.first }
        val inc = ov.inc[sub.name].orEmpty(); val exc = ov.exc[sub.name].orEmpty()
        val finalList = (matched.filter { it.id !in exc } + chats.filter { it.id in inc }).distinctBy { it.id }
        ChatMatch(finalList, chats, matched.map { it.id }.toSet())
    }

    /** Dateianhänge aus den Chats des Fachs (neueste zuerst). */
    suspend fun documents(chats: List<Chat>): List<SubjectDoc> = coroutineScope {
        val gate = Semaphore(4)
        chats.take(12).map { ch -> async { gate.withPermit { runCatching { c.repos.messenger.items(ch.id, 1, 80) }.getOrDefault(emptyList()) }
            .filter { it.attachmentId != null }.map { SubjectDoc(ch.id, ch.name, it.id, it.attachmentId!!, it.attachmentName ?: "Anhang", it.at, it.authorName) } } }.awaitAll().flatten()
            .sortedByDescending { it.at.orEmpty() }
    }

    // ---- Lokaler Stundenplan-Speicher: das Portal liefert nur etwa die letzten 3 Wochen, Fehlzeiten reichen weiter zurück ----
    private val ttLock = Any()
    private fun ttFile(studentId: String) = java.io.File(c.context.filesDir, "timetable-${(c.api.school ?: "x").filter { it.isLetterOrDigit() }}-${studentId.filter { it.isLetterOrDigit() }}.json")
    private val ttSer = kotlinx.serialization.serializer<Map<String, Map<String, List<String>>>>()
    private fun ttLoad(studentId: String): MutableMap<String, Map<String, List<String>>> = synchronized(ttLock) { runCatching { kotlinx.serialization.json.Json.decodeFromString(ttSer, ttFile(studentId).readText()).toMutableMap() }.getOrDefault(mutableMapOf()) }

    /** Gelesene Plantage lokal merken (Fach je Stunde, mit „!“ vor entfallenen Stunden). */
    fun remember(studentId: String, sch: Schedule) {
        if (sch.days.isEmpty()) return
        synchronized(ttLock) {
            val m = ttLoad(studentId)
            sch.days.forEach { d -> if (d.slots.any { it.entries.isNotEmpty() }) m[d.date.toString()] = d.slots.filter { it.entries.isNotEmpty() }.associate { sl -> (sl.index + 1).toString() to sl.entries.map { (if (it.cancelled) "!" else "") + it.subject } } }
            runCatching { ttFile(studentId).writeText(kotlinx.serialization.json.Json.encodeToString(ttSer, m)) }
        }
    }
    /** Einmal täglich die erreichbaren Wochen (heute −3 … +1) sichern, damit später auch ältere Fehlzeiten zugeordnet werden können. */
    suspend fun sync(studentId: String) {
        val today = LocalDate.now().toString(); val f = java.io.File(c.context.filesDir, "ttsync-${(c.api.school ?: "x").filter { it.isLetterOrDigit() }}-${studentId.filter { it.isLetterOrDigit() }}")
        if (runCatching { f.readText() }.getOrNull() == today) return
        val ws = Dates.weekStart(LocalDate.now()); var ok = 0
        for (o in -3L..1L) runCatching { remember(studentId, c.repos.schedule(ws.plusWeeks(o), studentId)); ok++ }
        if (ok > 0) runCatching { f.writeText(today) }
    }

    data class AbsRow(val date: LocalDate, val hours: Int, val info: String?)
    data class AbsResult(val rows: List<AbsRow>, val unknownDays: Int, val checked: Int, val detail: List<String> = emptyList())

    /** Fehlstunden je Fach: Fehlzeiten des Home.InfoPoint werden über den (lokal gespeicherten) Stundenplan dem Fach zugeordnet; entfallene Stunden zählen nicht. Dazu der Unterrichtsinhalt/die Hausaufgabe jenes Tages. */
    suspend fun absences(sub: Subject, studentId: String, hip: HipData, all: List<String>, hipMap: Map<String, String>, alias: Map<String, String>): AbsResult = coroutineScope {
        val ent = HipParser.absSummary(hip.of(HipClass.Abs)).entries
        val hit = ent.mapNotNull { e -> Dates.date(Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})").find(e.day)?.let { m -> "%s-%02d-%02d".format(m.groupValues[3].let { y -> if (y.length == 2) "20$y" else y }, m.groupValues[2].toInt(), m.groupValues[1].toInt()) })?.let { it to e } }
            .groupBy({ it.first }, { it.second }).toList().sortedByDescending { it.first }
        var store = ttLoad(studentId); val gate = Semaphore(3)
        // fehlende Wochen noch beim Portal versuchen (klappt nur für die letzten Wochen) und lokal sichern
        hit.map { it.first }.filter { store[it.toString()] == null }.map { Dates.weekStart(it) }.distinct().take(8).map { ws -> async { gate.withPermit { runCatching { remember(studentId, c.repos.schedule(ws, studentId)) } } } }.awaitAll()
        store = ttLoad(studentId)
        var unknown = 0; val detail = mutableListOf<String>()
        val rows = hit.mapNotNull { (d, es) ->
            val day = store[d.toString()]; if (day == null) { unknown++; return@mapNotNull null }
            val hours = mutableSetOf<Int>()
            es.forEach { e -> e.text.split(Regex("\\s*,\\s*")).forEach { seg ->
                val r = Regex("(\\d{1,2})\\.?\\s*(?:bis|-|–)\\s*(\\d{1,2})").find(seg); val one = Regex("(\\d{1,2})\\.?\\s*(?:Std|Stunde)", RegexOption.IGNORE_CASE).find(seg)
                when { Regex("ganz", RegexOption.IGNORE_CASE).containsMatchIn(seg) -> hours += day.keys.mapNotNull { it.toIntOrNull() }; r != null -> hours += (r.groupValues[1].toInt()..r.groupValues[2].toInt()); one != null -> hours += one.groupValues[1].toInt() }
            } }
            detail += "${Dates.dayShort(d)}: Std. ${hours.sorted().joinToString(",")} → " + hours.sorted().joinToString(", ") { h -> day[h.toString()].orEmpty().joinToString("/").ifBlank { "–" } }
            val n = hours.count { h -> day[h.toString()].orEmpty().any { s -> !s.startsWith("!") && SubjectMatch.resolve(s, all, hipMap, alias) == sub.name } }
            if (n == 0) return@mapNotNull null
            // Was war an dem Tag? Unterrichtsinhalt/Hausaufgaben des Fachs vom selben Datum
            val dd = Regex("\\d{1,2}\\.\\d{1,2}\\.(\\d{4}|\\d{2})")
            val info = (hip.of(HipClass.Les) + hip.of(HipClass.Hw)).flatMap { t -> t.rows.filter { r -> r.any { cell -> dd.find(cell)?.let { m -> Dates.date(m.value.split('.').let { p -> "%s-%02d-%02d".format(p[2].let { y -> if (y.length == 2) "20$y" else y }, p[1].toInt(), p[0].toInt()) }) == d } == true } && (r.any { SubjectMatch.mentions(it, sub.name, all, hipMap, alias) } || SubjectMatch.mentions(t.title, sub.name, all, hipMap, alias)) }.map { r -> r.filter { it.isNotBlank() && dd.matchEntire(it.trim()) == null }.joinToString(" · ") } }.filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { null }
            AbsRow(d, n, info)
        }
        AbsResult(rows, unknown, hit.size, detail)
    }

    private val testWords = Regex("test|klassenarbeit|schulaufgabe|\\bex\\b|\\bka\\b|pr\\u00fcfung|arbeit|lernkontrolle|klausur|leistungsnachweis|stegreif", RegexOption.IGNORE_CASE)
    fun isTest(title: String) = testWords.containsMatchIn(title)

    data class EventSubject(val subject: Subject, val missed: AbsResult)

    /** Fach zu einem Terminnamen (z. B. „Phy Test 10c“ → Physik) samt Nachholbedarf (Fehlstunden in diesem Fach). */
    suspend fun forEvent(e: CalendarEvent, studentId: String? = null): EventSubject? {
        val sid = studentId ?: (c.session.school.value ?: runCatching { c.repos.school.index() }.getOrNull())?.students?.firstOrNull()?.id ?: return null
        val subs = list(sid); val names = subs.map { it.name }
        val hip = runCatching { c.repos.hip.student(sid) }.getOrNull() ?: return null
        val hm = SubjectMatch.hipMap(hip); val alias = c.prefs.subjectOv.value.alias
        val sub = SubjectMatch.eventSubject(e, subs, hm, alias) ?: return null
        return EventSubject(sub, absences(sub, sid, hip, names, hm, alias))
    }
}
