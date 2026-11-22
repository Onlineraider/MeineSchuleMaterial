package app.meineschule.expressive.net

import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-Protokoll der Server-Antworten (nur im Arbeitsspeicher, nur wenn der Debug-Modus aktiv ist).
 * Niemals protokolliert: Authorization-Header, Cookies, Anmelde-/Token-Aufrufe sowie Token/Passwort-Felder in Bodies.
 * Die Rohdaten können persönliche Daten enthalten – deshalb nur lokal ansehen/teilen, wenn du es willst.
 */
data class DebugEntry(val id: Long, val time: Long, val method: String, val url: String, val status: Int, val ms: Long, val request: String?, val response: String?, val note: String? = null)

object DebugLog {
    val enabled = MutableStateFlow(false)
    val entries = MutableStateFlow<List<DebugEntry>>(emptyList())
    private val seq = AtomicLong(0)
    private const val MAX = 150
    private const val MAX_BODY = 400_000
    private val SECRET = Regex("(?i)\"(access_?token|refresh_?token|id_?token|token|password|passwort|secret|code|ott|cookie)\"\\s*:\\s*\"[^\"]*\"")
    private val QUERY_SECRET = Regex("(?i)([?&](?:token|ott|code|access_token|password)=)[^&]*")
    private val SKIP_URL = Regex("(?i)/(auth|token|login|connect|oauth|account/(login|token))")

    fun redact(s: String) = SECRET.replace(s) { m -> m.value.substringBefore(':') + ": \"***\"" }
    fun add(e: DebugEntry) { val l = entries.value; entries.value = (listOf(e) + l).take(MAX) }
    fun clear() { entries.value = emptyList() }
    fun nextId() = seq.incrementAndGet()

    fun asText(e: DebugEntry) = buildString {
        append("${e.method} ${e.url}\nStatus ${e.status} · ${e.ms} ms\n")
        e.note?.let { append("Hinweis: $it\n") }
        e.request?.let { append("\n--- Anfrage ---\n$it\n") }
        append("\n--- Antwort ---\n${e.response ?: "(leer)"}\n")
    }
}

class DebugInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!DebugLog.enabled.value) return chain.proceed(req)
        val url = DebugLog.run { req.url.toString().let { QUERY_SECRET_REDACT(it) } }
        val t0 = System.currentTimeMillis()
        val res = try { chain.proceed(req) } catch (e: Exception) { DebugLog.add(DebugEntry(DebugLog.nextId(), t0, req.method, url, -1, System.currentTimeMillis() - t0, null, null, "Fehler: ${e.javaClass.simpleName}: ${e.message}")); throw e }
        val skip = Regex("(?i)/(auth|token|login|connect|oauth)").containsMatchIn(req.url.encodedPath)
        val type = res.header("Content-Type").orEmpty().lowercase()
        val textual = type.contains("json") || type.contains("text") || type.contains("xml") || type.contains("html")
        val body = when {
            skip -> "(Anmelde-/Token-Aufruf – Inhalt wird nie protokolliert)"
            !textual -> "(${type.ifBlank { "unbekannter Typ" }}, ${res.header("Content-Length") ?: "?"} Bytes – nicht protokolliert)"
            else -> runCatching { DebugLog.redact(res.peekBody(400_000).string()) }.getOrNull()
        }
        val reqBody = if (skip) null else req.body?.let { b -> runCatching { val buf = okio.Buffer(); b.writeTo(buf); if (b.contentType()?.toString().orEmpty().let { it.contains("json") || it.contains("text") }) DebugLog.redact(buf.readUtf8().take(100_000)) else "(${b.contentType()}, ${b.contentLength()} Bytes)" }.getOrNull() }
        DebugLog.add(DebugEntry(DebugLog.nextId(), t0, req.method, url, res.code, System.currentTimeMillis() - t0, reqBody, body))
        return res
    }
}
private fun DebugLog.QUERY_SECRET_REDACT(u: String) = Regex("(?i)([?&](?:token|ott|code|access_token|password)=)[^&]*").replace(u) { it.groupValues[1] + "***" }
