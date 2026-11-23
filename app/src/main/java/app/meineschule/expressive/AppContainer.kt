package app.meineschule.expressive

import app.meineschule.expressive.net.DebugLog
import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import app.meineschule.expressive.auth.*
import app.meineschule.expressive.core.*
import app.meineschule.expressive.data.*
import app.meineschule.expressive.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Persönliche, nicht geheime Einstellungen (Tokens/Zugangsdaten liegen ausschließlich im Keystore-SecureStore). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    enum class Mode { System, Light, Dark }
    enum class Seed(val label: String, val hueShift: Float) { Teal("Teal", 0f), Blue("Blau", 50f), Violet("Violett", 105f), Rose("Rose", 170f), Amber("Bernstein", -120f) }

    val mode = MutableStateFlow(runCatching { Mode.valueOf(sp.getString("mode", "System")!!) }.getOrDefault(Mode.System))
    val dynamicColor = MutableStateFlow(sp.getBoolean("dynamic", true))
    val seed = MutableStateFlow(runCatching { Seed.valueOf(sp.getString("seed", "Teal")!!) }.getOrDefault(Seed.Teal))
    val notifications = MutableStateFlow(sp.getBoolean("notify", false))
    val biometricLock = MutableStateFlow(sp.getBoolean("bio", false))
    val school = MutableStateFlow(sp.getString("school", null))
    val shareBase = MutableStateFlow(sp.getString("shareBase", DeepLinks.DEFAULT_SHARE_BASE)!!)
    val debug = MutableStateFlow(sp.getBoolean("debug", false).also { DebugLog.enabled.value = it })
    fun setDebug(v: Boolean) { debug.value = v; DebugLog.enabled.value = v; sp.edit().putBoolean("debug", v).apply(); if (!v) DebugLog.clear() }
    /** Übersetzungssprache: "system" (Standard = Systemsprache), "none" (Original) oder ein Sprachschlüssel der Schule. */
    val language = MutableStateFlow(sp.getString("language", "system")!!)
    fun setLanguage(v: String) { language.value = v; sp.edit().putString("language", v).apply() }
    /** Zu verwendende Übersetzung (null = Original) – bei „system“ nur, wenn die Schule die Systemsprache anbietet. */
    fun translation(langs: List<app.meineschule.expressive.data.Language>, pref: String = language.value): String? = when (pref) {
        "none" -> null
        "system" -> java.util.Locale.getDefault().language.lowercase().let { sys -> langs.firstOrNull { it.key.lowercase().take(2) == sys && sys != "de" }?.key }
        else -> langs.firstOrNull { it.key == pref }?.key
    }
    /** Zwischengespeicherte Verfügbarkeit von Diensten je Schule („Mehr“ blendet ungenutzte aus). null = unbekannt → anzeigen. */
    fun avail(school: String, mod: String): Boolean? = if (sp.contains("avail.$school.$mod")) sp.getBoolean("avail.$school.$mod", true) else null
    fun setAvail(school: String, mod: String, v: Boolean) { sp.edit().putBoolean("avail.$school.$mod", v).apply() }
    /** Manuelle Fach-Anpassungen (Kürzel-Zuordnung, Chat-Zuordnung), je Gerät. */
    val subjectOv = MutableStateFlow(loadOv())
    private fun loadOv(): SubjectOverrides {
        val alias = mutableMapOf<String, String>(); val inc = mutableMapOf<String, MutableSet<String>>(); val exc = mutableMapOf<String, MutableSet<String>>()
        (sp.getString("subjectOv", "") ?: "").lines().forEach { l -> val p = l.split('\t'); if (p.size == 3) when (p[0]) { "A" -> alias[p[1]] = p[2]; "+" -> inc.getOrPut(p[1]) { mutableSetOf() } += p[2]; "-" -> exc.getOrPut(p[1]) { mutableSetOf() } += p[2] } }
        return SubjectOverrides(alias, inc, exc)
    }
    private fun saveOv(o: SubjectOverrides) {
        subjectOv.value = o
        val l = o.alias.map { "A\t${it.key}\t${it.value}" } + o.inc.flatMap { (k, v) -> v.map { "+\t$k\t$it" } } + o.exc.flatMap { (k, v) -> v.map { "-\t$k\t$it" } }
        sp.edit().putString("subjectOv", l.joinToString("\n")).apply()
    }
    /** Kürzel einem Fach zuordnen (null = Zuordnung entfernen). */
    fun setAlias(abbr: String, subject: String?) { val k = SubjectMatch.key(SubjectMatch.base(abbr)); if (k.isEmpty()) return; val o = subjectOv.value; saveOv(o.copy(alias = if (subject == null) o.alias - k else o.alias + (k to subject))) }
    /** Chat-Zuordnung: 1 = erzwingen, -1 = ausschließen, 0 = automatisch. */
    fun setChat(subject: String, chatId: String, mode: Int) {
        val o = subjectOv.value
        fun upd(m: Map<String, Set<String>>, on: Boolean) = m.toMutableMap().also { it[subject] = (m[subject].orEmpty() - chatId).let { s -> if (on) s + chatId else s } }
        saveOv(o.copy(inc = upd(o.inc, mode == 1), exc = upd(o.exc, mode == -1)))
    }
    val avatars = MutableStateFlow(sp.getBoolean("avatars", true))
    fun setShareBase(v: String) { shareBase.value = v; sp.edit().putString("shareBase", v).apply() }
    fun setAvatars(v: Boolean) { avatars.value = v; sp.edit().putBoolean("avatars", v).apply() }
    /** Lernstände der Unterrichtszeiten (Portal liefert keine Uhrzeiten) */
    /** Zuletzt vom Portal gelieferte Startzeiten (für die Standard-Stundenzeiten, auch außerhalb des Stundenplans). */
    var portalStarts: String get() = sp.getString("portalStarts", "") ?: ""; set(v) { sp.edit().putString("portalStarts", v).apply() }
    /** Stundenzeiten: eigene Zeiten, sonst Portal-Startzeiten (+45 min), sonst Standard. */
    fun effectiveTimes(starts: List<String> = emptyList()): List<TimeRange> {
        LessonTimes.parse(lessonTimes.takeIf { it != null }).takeIf { lessonTimes != null }?.let { return it }
        val s = starts.ifEmpty { portalStarts.split(',').filter { it.isNotBlank() } }
        return if (s.isNotEmpty()) LessonTimes.fromStarts(s).ifEmpty { LessonTimes.DEFAULT } else LessonTimes.DEFAULT
    }
    var lessonTimes: String? get() = sp.getString("lessonTimes", null); set(v) { sp.edit().putString("lessonTimes", v).apply() }

    fun setMode(v: Mode) { mode.value = v; sp.edit().putString("mode", v.name).apply() }
    fun setDynamic(v: Boolean) { dynamicColor.value = v; sp.edit().putBoolean("dynamic", v).apply() }
    fun setSeed(v: Seed) { seed.value = v; sp.edit().putString("seed", v.name).apply() }
    fun setNotifications(v: Boolean) { notifications.value = v; sp.edit().putBoolean("notify", v).apply() }
    fun setBiometric(v: Boolean) { biometricLock.value = v; sp.edit().putBoolean("bio", v).apply() }
    fun setSchool(tag: String?) { school.value = tag; sp.edit().putString("school", tag).apply() }
    /** Zufällige, pro Installation feste App-ID im Format der Web-App (`MSDAPP:<uuid>`); kein Geheimnis. */
    val appId: String = sp.getString("appId", null) ?: "MSDAPP:${java.util.UUID.randomUUID()}".also { sp.edit().putString("appId", it).apply() }
    fun clear() { val id = appId; sp.edit().clear().putString("appId", id).apply(); school.value = null }
    var lastNotifiedUnread: Int get() = sp.getInt("lastUnread", 0); set(v) { sp.edit().putInt("lastUnread", v).apply() }
}

class AppContainer(val context: Context) {
    val origin: String = BuildConfig.MSD_ORIGIN
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(context)
    val store: SecureStore = KeystoreSecureStore(context)
    private val plain = Http.plain()
    val auth = AuthManager(plain, "$origin/Api/v1.0", prefs.appId, store, scope)
    val originHost: String = origin.substringAfter("://").substringBefore('/').substringBefore(':')
    val authedClient = Http.authed(plain, auth, originHost)      // Bearer nur für die eigene Origin (auch für Coil)
    val api = ApiClient(authedClient, origin)
    val portal = PortalClient(api, plain, origin)                // Cookie-Portal: bewusst OHNE Bearer-Interceptor
    val repos = Repos(api, portal)
    val contacts = Contacts(repos)
    val subjects = Subjects(this)
    val hub = Hub(origin, auth, scope)
    val session = Session(this)
    @Volatile var inForeground = false
    /** Von außen eingehende Links/Teilen-Inhalte; wird im angemeldeten Zustand konsumiert. */
    val pendingLink = MutableStateFlow<DeepLink?>(null)
}

/** Aktive Schule, Profil, Schulstatus. Beim Schulwechsel werden alle schulbezogenen Daten verworfen. */
class Session(private val c: AppContainer) {
    val profile = MutableStateFlow<Profile?>(null)
    val school = MutableStateFlow<SchoolInfo?>(null)
    val tag = MutableStateFlow<String?>(null)
    val state = MutableStateFlow<SchoolState?>(null)
    /** Wird bei jedem Schulwechsel erhöht → Screens laden neu und zeigen keine Daten der vorherigen Schule. */
    val generation = MutableStateFlow(0)

    suspend fun bootstrap() {
        val p = c.repos.school.profile(); profile.value = p; c.repos.meId = p.userId
        val wanted = c.prefs.school.value ?: c.auth.savedSchool
        val chosen = p.schools.firstOrNull { it.tag == wanted }?.tag ?: p.schools.firstOrNull()?.tag
        if (chosen != null) activate(chosen)
    }
    private suspend fun activate(t: String) {
        c.api.school = t; c.portal.reset(); c.contacts.reset(); tag.value = t; school.value = null; state.value = null
        c.prefs.setSchool(t); c.auth.rememberSchool(t)
        generation.update { it + 1 }
        coroutineScope { launch { school.value = runCatching { c.repos.school.index() }.getOrNull() }; launch { state.value = runCatching { c.repos.school.state() }.getOrNull() } }
        val st = school.value?.students.orEmpty(); val gen = generation.value
        c.scope.launch { runCatching { c.contacts.harvest(st, c.repos.messenger.index()) }; if (generation.value != gen) c.contacts.reset() }
    }
    suspend fun switchSchool(t: String) { if (t != tag.value) activate(t) }
    suspend fun refreshState() { if (tag.value != null) state.value = runCatching { c.repos.school.state() }.getOrNull() ?: state.value }
    fun clear() { profile.value = null; school.value = null; state.value = null; tag.value = null; c.api.school = null; c.portal.reset() }
}

val LocalApp = compositionLocalOf<AppContainer> { error("AppContainer fehlt") }
