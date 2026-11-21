package app.meineschule.expressive.net

import app.meineschule.expressive.core.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Stundenplan über das separate, cookie-authentifizierte Webportal.
 * Die Portal-Sitzung wird über die dokumentierte One-Time-Token-Brücke
 * (`Account/Login?token=…&inapp=1&returnurl=/{school}/schedule`) aufgebaut – ohne Passwort.
 * Cookies liegen nur im Arbeitsspeicher dieses Clients (kein Disk-Cache), Redirects nur innerhalb derselben Origin.
 */
class PortalClient(private val api: ApiClient, base: OkHttpClient, private val origin: String) {
    private class MemoryJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { val l = store.getOrPut(url.host) { mutableListOf() }; synchronized(l) { cookies.forEach { c -> l.removeAll { it.name == c.name }; if (c.expiresAt > System.currentTimeMillis()) l.add(c) } } }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host]?.let { l -> synchronized(l) { l.filter { it.matches(url) } } } ?: emptyList()
        fun clear() = store.clear()
    }
    private val jar = MemoryJar()
    private val originHost = origin.toHttpUrl().host
    private val client = base.newBuilder().cookieJar(jar).followRedirects(false).followSslRedirects(false).build()
    @Volatile private var establishedFor: String? = null

    fun reset() { jar.clear(); establishedFor = null }

    private suspend fun getFollowing(start: HttpUrl, hops: Int = 6): Response {
        var url = start
        repeat(hops) { i ->
            val res = client.newCall(Request.Builder().url(url).header("Accept", "application/json, text/html").build()).await()
            if (res.isRedirect) {
                val next = res.header("Location")?.let { res.request.url.resolve(it) }; res.close()
                if (next == null || next.host != originHost || next.scheme != "https") throw ApiException(ErrorKind.Forbidden, "Unerwarteter Redirect")
                if (i > 0 && Regex("/account/(login|accessdenied)", RegexOption.IGNORE_CASE).containsMatchIn(next.encodedPath)) throw ApiException(ErrorKind.Forbidden, "Portal-Zugriff verweigert")
                url = next
            } else return res
        }
        throw ApiException(ErrorKind.Server, "Zu viele Weiterleitungen")
    }

    private suspend fun establish(school: String) {
        reset()
        val ott = J(api.post(api.global("OneTimeToken"))).str("token") ?: throw ApiException(ErrorKind.Server, "Kein One-Time-Token erhalten")
        val url = "$origin/Account/Login".toHttpUrl().newBuilder().addQueryParameter("token", ott).addQueryParameter("inapp", "1").addQueryParameter("returnurl", "/$school/schedule").build()
        getFollowing(url).use { }
        establishedFor = school
    }

    /** JSON der Stundenplanwoche, die [date] enthält. [studentId] optional (Schüleransicht). */
    suspend fun scheduleJson(school: String, date: LocalDate, studentId: String?, cog: String? = null): JsonElement {
        suspend fun fetch(): JsonElement {
            val url = "$origin/${Urls.seg(school)}/schedule".toHttpUrl().newBuilder().addQueryParameter("handler", "Schedule").addQueryParameter("date", date.toString())
                .addQueryParameter("cog", cog.orEmpty()).addQueryParameter("t", "").addQueryParameter("s", studentId.orEmpty()).addQueryParameter("view", "").build()
            getFollowing(url).use { r ->
                val text = r.body?.string().orEmpty()
                val isJson = r.header("Content-Type")?.contains("json", true) == true
                if (!r.isSuccessful || !isJson) throw ApiException(ErrorKind.Forbidden, "Portal hat kein JSON geliefert")
                return Json.parseToJsonElement(text)
            }
        }
        if (establishedFor != school) establish(school)
        return try { fetch() } catch (e: ApiException) {
            if (e.kind != ErrorKind.Forbidden) throw e
            establish(school); fetch() // abgelaufene Portal-Sitzung: einmal neu aufbauen
        }
    }
}
