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

    fun resolve(token: String, subjects: List<String>, hip: Map<String, String>, alias: Map<String, String>): String? {
        val k = key(token); if (k.isEmpty()) return null
        subjects.firstOrNull { key(it) == k }?.let { return it }
        val b = key(base(token))
        (alias[b] ?: alias[k])?.let { return it }
        hip[b]?.let { n -> subjects.firstOrNull { key(it) == key(n) }?.let { return it } }
        return heuristic(token, subjects)
    }

    /** Erwähnt der Text (Termin-/Chattitel) das Fach – Vollname oder Kürzel? */
    fun mentions(text: String, subject: String, subjects: List<String>, hip: Map<String, String>, alias: Map<String, String>): Boolean {
        if (text.contains(subject, ignoreCase = true)) return true
        return Regex("[\\p{L}\\p{N}_]+").findAll(text).any { w -> w.value.length >= 2 && resolve(w.value, subjects, hip, alias) == subject }
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

    /** Fehlstunden je Fach: Fehlzeiten des Home.InfoPoint werden über den Stundenplan der jeweiligen Woche dem Fach zugeordnet. */
    suspend fun absences(sub: Subject, studentId: String, hip: HipData, all: List<String>, hipMap: Map<String, String>, alias: Map<String, String>, from: LocalDate = LocalDate.now().minusDays(120)): List<Pair<LocalDate, Int>> = coroutineScope {
        val ent = HipParser.absSummary(hip.of(HipClass.Abs)).entries
        val hit = ent.mapNotNull { e -> Dates.date(Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").find(e.day)?.let { m -> "%s-%02d-%02d".format(m.groupValues[3], m.groupValues[2].toInt(), m.groupValues[1].toInt()) }) ?.let { it to e } }
            .filter { it.first >= from }.sortedByDescending { it.first }.take(40)
        val gate = Semaphore(3)
        hit.map { (d, e) -> async {
            val ws = Dates.weekStart(d); val key = "${c.api.school}:$studentId:$ws"
            val sch = weeks[key] ?: gate.withPermit { runCatching { c.repos.schedule(ws, studentId) }.getOrNull() }?.also { weeks[key] = it } ?: return@async null
            val day = sch.days.firstOrNull { it.date == d } ?: return@async null
            val whole = Regex("ganz|tag", RegexOption.IGNORE_CASE).containsMatchIn(e.text) && !Regex("stunde", RegexOption.IGNORE_CASE).containsMatchIn(e.text)
            val hours = if (whole) day.slots.indices.map { it + 1 }.toSet() else Regex("(\\d{1,2})\\.?\\s*(?:-|–|bis)\\s*(\\d{1,2})\\.?").findAll(e.text).flatMap { m -> (m.groupValues[1].toInt()..m.groupValues[2].toInt()).toList() }.toSet().ifEmpty { Regex("(\\d{1,2})\\.?\\s*Stunde").findAll(e.text).map { it.groupValues[1].toInt() }.toSet() }
            val n = hours.count { h -> (day.slots.firstOrNull { it.index == h - 1 } ?: day.slots.getOrNull(h - 1))?.entries.orEmpty().any { en -> SubjectMatch.resolve(en.subject, all, hipMap, alias) == sub.name } }
            if (n > 0) d to n else null
        } }.awaitAll().filterNotNull().sortedByDescending { it.first }
    }
}
