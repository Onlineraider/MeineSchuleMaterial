package app.meineschule.expressive.core

import android.content.Intent
import android.net.Uri

/** Profil-Link wie in der Web-App: `…/#/profil?u={userId}&n={Name}[&b={Server}][&sc={Schule}][&sn={Schulname}]`. */
data class ProfileLink(val userId: String, val name: String, val server: String?, val school: String?, val schoolName: String?)

sealed interface DeepLink {
    data class Profile(val link: ProfileLink) : DeepLink
    data class Chat(val id: String) : DeepLink
    /** Zielbereich: home, chats, calendar, plan, more, newchat … */
    data class Section(val route: String) : DeepLink
    data class Share(val text: String?, val uris: List<Uri>) : DeepLink
}

object DeepLinks {
    private val UUID = Regex("^[0-9a-fA-F-]{36}$")
    const val DEFAULT_SERVER = "https://meineschule.digital"
    /** Adresse der Web-App, unter der geteilte Profil-Links geöffnet werden (anpassbar in den Einstellungen). */
    const val DEFAULT_SHARE_BASE = "https://msd.de.cool/"

    fun profileUrl(shareBase: String, userId: String, name: String, server: String, school: String?, schoolName: String?): String {
        val q = buildList { add("u=${Urls.seg(userId)}"); add("n=${Urls.seg(name)}"); if (server.trimEnd('/') != DEFAULT_SERVER) add("b=${Urls.seg(server)}"); school?.let { add("sc=${Urls.seg(it)}") }; schoolName?.let { add("sn=${Urls.seg(it)}") } }
        return "${shareBase.substringBefore('#').trimEnd('/')}/#/profil?${q.joinToString("&")}"
    }

    fun parseProfile(query: String): ProfileLink? {
        val p = query.split('&').mapNotNull { it.split('=', limit = 2).takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to runCatching { java.net.URLDecoder.decode(kv[1], "UTF-8") }.getOrDefault(kv[1]) } }.toMap()
        val u = p["u"] ?: return null; if (!UUID.matches(u)) return null
        val b = p["b"]?.takeIf { it.startsWith("https://") }?.trimEnd('/')
        return ProfileLink(u, (p["n"] ?: "").take(120), b, p["sc"], p["sn"]?.take(120))
    }

    /** Verarbeitet VIEW-/SEND-Intents sowie die Web-Schreibweise `#/profil?…`, `#/chat/{id}`, `#/{bereich}`, und `meineschule://…`. */
    fun parse(intent: Intent?): DeepLink? {
        intent ?: return null
        when (intent.action) {
            Intent.ACTION_SEND -> { val t = intent.getStringExtra(Intent.EXTRA_TEXT); val u = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM); return DeepLink.Share(listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), t).joinToString("\n").ifBlank { null }, listOfNotNull(u)) }
            Intent.ACTION_SEND_MULTIPLE -> return DeepLink.Share(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
        }
        val data = intent.data ?: return null
        val frag = data.fragment?.takeIf { it.startsWith("/") } ?: (data.host?.let { "/$it${data.path.orEmpty()}" } ?: data.path.orEmpty()).let { if (data.scheme == "meineschule") it + (data.encodedQuery?.let { q -> "?$q" } ?: "") else null } ?: return null
        val path = frag.substringBefore('?'); val query = frag.substringAfter('?', "")
        Regex("^/chat/([0-9a-fA-F-]{36})$").find(path)?.let { return DeepLink.Chat(it.groupValues[1]) }
        if (path == "/profil" || path == "/profile") return parseProfile(query)?.let { DeepLink.Profile(it) }
        return when (path.trim('/')) { "chats" -> DeepLink.Section("chats"); "home" -> DeepLink.Section("home"); "kalender", "calendar" -> DeepLink.Section("calendar"); "stundenplan", "plan" -> DeepLink.Section("plan")
            "medien", "media" -> DeepLink.Section("media"); "infopoint", "hip" -> DeepLink.Section("hip"); "fundsachen", "lost" -> DeepLink.Section("lost"); "personen" -> DeepLink.Section("more"); "neuerchat", "newchat" -> DeepLink.Section("newchat"); else -> null }
    }
}
