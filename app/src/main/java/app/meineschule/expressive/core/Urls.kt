package app.meineschule.expressive.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.text.Normalizer

data class SafeLink(val url: String, val host: String, val https: Boolean)

object Urls {
    /** Nur http(s)-Ziele ohne Userinfo/Steuerzeichen. HTTPS bevorzugt; http nur mit [allowHttp]. */
    fun safeExternal(raw: String?, allowHttp: Boolean = false): SafeLink? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it.code < 0x20 || it.code == 0x7f }) return null
        val u: HttpUrl = s.toHttpUrlOrNull() ?: return null
        if (u.scheme != "https" && !(allowHttp && u.scheme == "http")) return null
        if (u.username.isNotEmpty() || u.password.isNotEmpty() || u.host.isEmpty()) return null
        return SafeLink(u.toString(), u.host, u.scheme == "https")
    }

    /** Servergelieferten Dateinamen in einen unkritischen lokalen Namen umwandeln (kein Pfad, keine Laufwerksangabe). */
    fun safeFileName(name: String?, fallback: String = "download"): String {
        var n = Normalizer.normalize(name.orEmpty(), Normalizer.Form.NFKC).substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[\\u0000-\\u001f\\u007f<>:\"|?*]"), "_").trimStart('.').trim()
        if (Regex("^[A-Za-z]:.*").matches(n)) n = n.drop(2)
        return n.ifBlank { fallback }.take(120)
    }

    private val allowedMime = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "application/pdf", "text/plain",
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    fun validateUpload(size: Long, mime: String?, imageOnly: Boolean = false, maxBytes: Long = 10L * 1024 * 1024): String? = when {
        size <= 0 -> "Die Datei ist leer."
        size > maxBytes -> "Die Datei ist größer als ${maxBytes / 1_048_576} MB."
        mime == null || mime !in allowedMime || (imageOnly && !mime.startsWith("image/")) -> "Dieser Dateityp wird nicht unterstützt."
        else -> null
    }
    fun seg(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
}
