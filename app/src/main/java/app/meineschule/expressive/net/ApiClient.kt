package app.meineschule.expressive.net

import app.meineschule.expressive.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Zentraler API-Client: Basis-URL, Bearer (via Interceptor), JSON, einheitliche Fehler, Cancellation, Timeouts. */
class ApiClient(private val client: OkHttpClient, val origin: String) {
    /** Aktueller Schul-Tag (Auth.School). Wird bei Schulwechsel gesetzt. */
    @Volatile var school: String? = null
    private val api = "$origin/Api/v1.0"
    private val jsonMt = "application/json; charset=utf-8".toMediaType()

    // Globale Routen: {origin}/Api/v1.0/App/App/...   Schulbezogen: {origin}/Api/v1.0/App/{school}/...
    fun global(p: String) = "$api/App/App/${p.trimStart('/')}"
    fun scoped(p: String): String = "$api/App/${Urls.seg(requireSchool())}/${p.trimStart('/')}"
    /** Download-/Bildrouten liegen unter /{school}/... (nicht im App-Präfix). */
    fun web(p: String): String = "$origin/${Urls.seg(requireSchool())}/${p.trimStart('/')}"
    fun account(p: String) = "$api/Account/${p.trimStart('/')}"
    private fun requireSchool() = school ?: throw ApiException(ErrorKind.Client, "Keine Schule gewählt")

    suspend fun request(builder: Request.Builder): Response {
        val res = try { client.newCall(builder.header("Accept", "application/json").build()).await() }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) { throw e }
        catch (e: IOException) { throw (e.cause as? ApiException) ?: ApiException(ErrorKind.Network, "Netzwerkfehler", cause = e) }
        if (!res.isSuccessful) { val s = res.code; res.close(); throw ApiException.fromStatus(s) }
        return res
    }

    private suspend fun json(builder: Request.Builder): JsonElement? = request(builder).use { r ->
        val text = r.body?.string().orEmpty()
        if (text.isBlank()) null else runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
    }

    suspend fun get(url: String): JsonElement? = json(Request.Builder().url(url).get())
    suspend fun post(url: String, body: JsonElement? = null): JsonElement? =
        json(Request.Builder().url(url).post((body?.toString() ?: "").toRequestBody(jsonMt)))
    suspend fun patch(url: String, body: JsonElement): JsonElement? = json(Request.Builder().url(url).patch(body.toString().toRequestBody(jsonMt)))
    suspend fun delete(url: String): JsonElement? = json(Request.Builder().url(url).delete())
    suspend fun upload(url: String, bytes: ByteArray, mime: String, fileName: String): JsonElement? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", Urls.safeFileName(fileName), bytes.toRequestBody(mime.toMediaType())).build()
        return json(Request.Builder().url(url).post(body))
    }
    suspend fun bytes(url: String): ByteArray = request(Request.Builder().url(url).get().header("Accept", "*/*")).use { it.body?.bytes() ?: ByteArray(0) }
}
