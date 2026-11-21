package app.meineschule.expressive.data

import kotlinx.serialization.json.contentOrNull

import java.util.concurrent.ConcurrentHashMap

/** Ein Empfänger im Serverformat. [alts] enthält gleichwertige Kennungen derselben Person (z. B. `P:STUDENT:…` und `U:USER:…`). */
data class RecipientItem(val key: String, val text: String, val info: String?, val alts: List<Recipient>, val unresolved: Boolean = false) {
    val kind: String get() = kindLabel(key)
    val isPerson: Boolean get() = Regex("^(U|P):", RegexOption.IGNORE_CASE).containsMatchIn(key)
    /** Serverformat `key=Text` */
    val record: String get() = "$key=$text"
}

fun kindLabel(key: String): String {
    val m = Regex("^([A-Z]):([^:=]+)", RegexOption.IGNORE_CASE).find(key) ?: return "Gruppe / Rolle"
    val a = m.groupValues[1].uppercase(); val t = m.groupValues[2].uppercase()
    if (a == "U") return "Nutzer"
    if (a == "P" && !Regex("GROUP|CLASS|ROLE|^S[CG]$").containsMatchIn(t)) return when (t) { "TEACHER" -> "Lehrkraft"; "STUDENT" -> "Schüler/in"; "PARENT" -> "Elternteil"; else -> "Person" }
    return "Gruppe / Rolle"
}

/** `key=text`-Datensatz zerlegen. */
fun parseRecord(v: String): Pair<String, String> { val i = v.indexOf('='); return if (i < 0) v.trim() to "" else v.substring(0, i).trim() to v.substring(i + 1) }

/**
 * Kontaktverzeichnis wie in der Web-App: bekannte Schlüssel → Namen (aus Schülern, Chat-Teilnehmern, Nachrichtenautoren, Empfängerindex),
 * Alias-Verknüpfung zwischen Schüler- und Nutzerkennung, Zusammenfassung von Duplikaten. Schulbezogen → bei Schulwechsel [reset].
 */
class Contacts(private val repos: Repos) {
    private val known = ConcurrentHashMap<String, String>()
    private val alias = ConcurrentHashMap<String, String>()
    private val userOf = ConcurrentHashMap<String, String>()     // kanonischer Schlüssel → userId
    @Volatile private var index: List<Recipient> = emptyList()
    @Volatile private var indexLoaded = false
    private val scanned = ConcurrentHashMap.newKeySet<String>()

    fun reset() { known.clear(); alias.clear(); userOf.clear(); index = emptyList(); indexLoaded = false; scanned.clear() }
    fun canon(k: String): String { var r = k.trim().lowercase(); var n = 0; while (alias.containsKey(r) && n++ < 20) r = alias[r]!!; return r }
    fun name(key: String): String? = known[key.trim()]?.takeIf { it.isNotBlank() }
    fun add(key: String?, text: String?) { val k = key?.trim().orEmpty(); if (k.isEmpty()) return; if (!text.isNullOrBlank() || !known.containsKey(k)) known[k] = text?.takeIf { it.isNotBlank() } ?: known[k].orEmpty() }
    fun addRecord(v: String) { val (k, t) = parseRecord(v); add(k, t) }
    fun fill(v: String): String { val (k, t) = parseRecord(v); return "$k=${t.ifBlank { known[k].orEmpty() }}" }
    fun userIdOf(key: String): String? = userOf[canon(key)]
    private fun link(a: String, b: String) { val ra = canon(a); val rb = canon(b); if (ra != rb) alias[rb] = ra }

    fun learnStudent(d: StudentInfo) {
        val sk = "P:STUDENT:${d.student.id}"; add(sk, d.student.name)
        d.userId?.let { u -> val uk = "U:USER:$u"; add(uk, d.student.name); link(sk, uk); userOf[canon(sk)] = u }
        (listOf(d.parents, d.partners) + d.classInfo + d.groupInfo + d.subjects).forEach { g -> g.persons.forEach { p ->
            val id = p.id ?: return@forEach; val pk = "P:${p.type}:$id"; add(pk, p.name)
            p.userId?.let { u -> val uk = "U:USER:$u"; add(uk, p.name); link(pk, uk); userOf[canon(pk)] = u }
        } }
    }

    suspend fun loadIndex() { if (indexLoaded) return; runCatching { index = repos.school.recipients(); index.forEach { add(it.key, it.text) }; indexLoaded = true } }

    suspend fun resolveNames() {
        val ids = known.filter { (k, t) -> t.isBlank() && Regex("^U:USER:[0-9a-f-]{36}$", RegexOption.IGNORE_CASE).matches(k) }.keys.map { it.removePrefix("U:USER:") }
        ids.chunked(100).forEach { c -> runCatching { repos.school.users(c).forEach { (id, n) -> add("U:USER:$id", n) } } }
    }

    /** Schüler, Chat-Teilnehmer und Autoren einsammeln (im Hintergrund, Fehler werden ignoriert). */
    suspend fun harvest(students: List<Student>, chats: List<Chat>) {
        students.forEach { add("P:STUDENT:${it.id}", it.name) }
        chats.forEach { c -> c.creatorId?.let { add("U:USER:$it", c.creatorName) } }
        chats.filter { scanned.add(it.id) }.take(40).forEach { c -> runCatching { repos.messenger.info(c.id).arr("recipients").forEach { r -> (r.raw as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let(::addRecord) ?: add(r.str("key", "id", "userId"), r.str("text", "displayName", "name")) } } }
        students.take(10).forEach { s -> runCatching { learnStudent(repos.school.student(s.id)) } }
        resolveNames()
    }

    private fun nameKey(x: String) = x.lowercase().replace(Regex("\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()

    /** Duplikate zusammenfassen: gleiche kanonische Kennung oder gleicher Name bei Nutzer- vs. Personenkennung. */
    fun dedupe(list: List<Recipient>): List<RecipientItem> {
        val byKey = LinkedHashMap<String, MutableRI>(); val byName = HashMap<String, Pair<String, MutableRI>>(); val out = ArrayList<MutableRI>()
        for (x in list) {
            if (x.key.isBlank()) continue; val k = canon(x.key)
            byKey[k]?.let { o -> o.addAlt(x); if (o.text.isBlank() && x.text.isNotBlank()) o.text = x.text; if (o.info == null) o.info = x.info; continue }
            val nk = nameKey(x.text); val kind = if (x.key.startsWith("U:USER:", true)) "u" else if (x.key.startsWith("P:", true)) "p" else "o"
            if (nk.isNotEmpty() && kind != "o") {
                val o = byName[nk]
                if (o != null && o.first != kind) { o.second.addAlt(x); byKey[k] = o.second; if (o.second.info == null) o.second.info = x.info; continue }
                if (o == null) { val it = MutableRI(x.key, x.text, x.info).also { r -> r.addAlt(x) }; byName[nk] = kind to it; byKey[k] = it; out += it; continue }
            }
            val it = MutableRI(x.key, x.text, x.info).also { r -> r.addAlt(x) }; byKey[k] = it; out += it
        }
        return out.map { RecipientItem(it.key, it.text, it.info, it.alts) }
    }
    private class MutableRI(var key: String, var text: String, var info: String?) { val alts = mutableListOf<Recipient>(); fun addAlt(x: Recipient) { if (alts.none { it.key.equals(x.key, true) }) alts += x } }

    /** Lokale Treffer sofort, API-Treffer ergänzend ([onLocal] wird zuerst aufgerufen). */
    suspend fun search(term: String, onLocal: (List<RecipientItem>) -> Unit): List<RecipientItem> {
        val lc = term.lowercase()
        fun hit(r: Recipient) = "${r.text} ${r.key} ${r.info.orEmpty()}".lowercase().contains(lc)
        val local = { index.plus(known.map { Recipient(it.key, it.value, null, null) }).filter(::hit) }
        onLocal(dedupe(local()))
        val api = runCatching { repos.school.searchRecipients(term) }.getOrDefault(emptyList()); api.forEach { add(it.key, it.text) }
        return dedupe(api + local())
    }
}
