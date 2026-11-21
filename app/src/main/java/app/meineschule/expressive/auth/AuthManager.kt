package app.meineschule.expressive.auth

import app.meineschule.expressive.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Tokens(val token: String, val refreshToken: String, val expiresAtMs: Long)
sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val since: Long) : AuthState
    /** Refresh-Token ungültig → Benutzer zum Login leiten, mit verständlicher Meldung. */
    data object SessionExpired : AuthState
}
sealed interface LoginResult { data object Success : LoginResult; data object TwoFactorNeeded : LoginResult }

/**
 * Zentrale Sitzungsverwaltung.
 *  • Access-Token nur im Speicher; Persistenz (Keystore-verschlüsselt) ausschließlich nach ausdrücklicher Einwilligung.
 *  • Proaktive Erneuerung ca. 2 Minuten vor Ablauf; parallele Refreshes serialisiert (Mutex + „stale token“-Prüfung = single-flight).
 *  • Das Passwort wird nie gespeichert; Tokens werden nie geloggt.
 */
class AuthManager(
    private val plainClient: OkHttpClient,          // OHNE Auth-Interceptor (kein Refresh-Loop)
    private val apiBase: String,                    // {origin}/Api/v1.0
    private val appId: String,
    private val store: SecureStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val REFRESH_LEAD_MS = 2 * 60 * 1000L
        private const val VAULT = "session"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()

        /** Ablaufzeit: bevorzugt JWT-`exp`; sonst `expireIn` (Sekunden | Epoche s/ms | ISO) – die Einheit ist in der Doku nicht belegt. */
        fun resolveExpiry(token: String, expireIn: JsonElement?, now: Long): Long {
            jwtExp(token)?.let { return it }
            val p = expireIn as? JsonPrimitive
            p?.longOrNull?.let { n -> return when { n < 100_000_000L -> now + n * 1000; n < 100_000_000_000L -> n * 1000; else -> n } }
            p?.contentOrNull?.let { s -> runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()?.let { return it } }
            return now + 10 * 60 * 1000L
        }
        fun jwtExp(token: String): Long? = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(token.split('.')[1].padEnd((token.split('.')[1].length + 3) / 4 * 4, '=')))
            Json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.longOrNull?.times(1000)
        }.getOrNull()
    }

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()
    @Volatile private var tokens: Tokens? = null
    @Volatile var remember: Boolean = false; private set
    private val refreshMutex = Mutex()
    private var timer: Job? = null
    @Volatile var refreshCount = 0; private set
    /** Persistierter Schul-Tag (nicht geheim, aber mit der Sitzung gebunden). */
    @Volatile var savedSchool: String? = null; private set

    val currentToken: String? get() = tokens?.token
    val isSignedIn: Boolean get() = tokens != null

    // ---------- Login ----------
    suspend fun login(userName: String, password: String, twoFactorCode: String?, remember: Boolean): LoginResult {
        val body = jsonObject { put("userName", userName); put("password", password); put("twoFactorCode", twoFactorCode?.takeIf { it.isNotBlank() }); put("appId", appId) }
        val (code, json) = post("/Token/CreateToken", body)
        val j = J(json)
        when {
            code in 200..299 -> {
                val token = j.str("token"); val rt = j.str("refreshToken")
                if (token == null || rt == null) throw ApiException(ErrorKind.Server, "Unerwartete Antwort der Anmeldung", code)
                adopt(Tokens(token, rt, resolveExpiry(token, (json as? JsonObject)?.get("expireIn") ?: (json as? JsonObject)?.entries?.firstOrNull { it.key.equals("expireIn", true) }?.value, now())), remember)
                return LoginResult.Success
            }
            code == 401 && j.bool("twoFactorNeeded") == true -> return LoginResult.TwoFactorNeeded
            code == 400 || code == 401 || code == 403 ->
                throw ApiException(ErrorKind.Auth, if (!twoFactorCode.isNullOrBlank()) "Der Bestätigungscode ist ungültig." else "Benutzername oder Passwort stimmen nicht.", code)
            else -> throw ApiException(if (code >= 500) ErrorKind.Server else ErrorKind.Client, "Anmeldung fehlgeschlagen", code)
        }
    }

    private suspend fun adopt(t: Tokens, remember: Boolean) {
        tokens = t; this.remember = remember
        persist(); schedule(); _state.value = AuthState.SignedIn(now())
    }
    private fun persist() {
        val t = tokens
        if (remember && t != null) store.put(VAULT, jsonObject { put("t", t.token); put("r", t.refreshToken); put("e", t.expiresAtMs); savedSchool?.let { put("s", it) } }.toString())
        else store.remove(VAULT)
    }
    fun rememberSchool(tag: String) { savedSchool = tag; persist() }

    // ---------- Sitzungswiederherstellung ----------
    /** Nach App-Neustart. true = Sitzung aktiv. */
    suspend fun restore(): Boolean {
        val raw = store.get(VAULT)
        if (raw == null) { _state.value = AuthState.SignedOut; return false }
        val j = runCatching { J(Json.parseToJsonElement(raw)) }.getOrNull()
        val t = j?.str("t"); val r = j?.str("r")
        if (t == null || r == null) { store.remove(VAULT); _state.value = AuthState.SignedOut; return false }
        tokens = Tokens(t, r, j.long("e") ?: 0L); remember = true; savedSchool = j.str("s")
        return try {
            if (tokens!!.expiresAtMs - REFRESH_LEAD_MS <= now()) refresh(force = true) else schedule()
            _state.value = AuthState.SignedIn(now()); true
        } catch (e: ApiException) {
            if (e.kind == ErrorKind.Network) { schedule(); _state.value = AuthState.SignedIn(now()); true } // offline: Sitzung behalten
            else { _state.value = AuthState.SessionExpired; false }
        }
    }

    // ---------- Token-Zugriff & Refresh ----------
    suspend fun accessToken(): String {
        val t = tokens ?: throw ApiException(ErrorKind.Session, "Nicht angemeldet")
        if (t.expiresAtMs - REFRESH_LEAD_MS <= now()) refresh()
        return tokens?.token ?: throw ApiException(ErrorKind.Session, "Nicht angemeldet")
    }
    /** Blockierend für OkHttp-Interceptor-Threads. */
    fun accessTokenBlocking(): String = runBlocking { accessToken() }

    /**
     * @param staleToken Token, mit dem ein Request 401 erhielt. Hat ein anderer Aufrufer das Token inzwischen erneuert,
     * wird nicht erneut refresht (verhindert Doppel-Refresh und Endlosschleifen).
     */
    suspend fun refresh(force: Boolean = false, staleToken: String? = null) = refreshMutex.withLock {
        val snap = tokens ?: throw ApiException(ErrorKind.Session, "Nicht angemeldet")
        if (staleToken != null && snap.token != staleToken) return@withLock
        if (!force && staleToken == null && snap.expiresAtMs - REFRESH_LEAD_MS > now()) return@withLock
        val (code, json) = post("/Token/RefreshToken", jsonObject { put("token", snap.token); put("refreshToken", snap.refreshToken); put("appId", appId) })
        val j = J(json)
        when {
            code in 200..299 -> {
                val token = j.str("token") ?: throw ApiException(ErrorKind.Server, "Unerwartete Antwort bei der Token-Erneuerung", code)
                val rt = j.str("refreshToken") ?: snap.refreshToken
                refreshCount++
                // atomar: neues Paar ersetzt das alte in einem Schritt (Speicher + ein verschlüsselter Datensatz)
                val exp = resolveExpiry(token, (json as? JsonObject)?.entries?.firstOrNull { it.key.equals("expireIn", true) }?.value, now())
                adopt(Tokens(token, rt, exp), remember)
            }
            code == 400 || code == 401 || code == 403 -> { expire(); throw ApiException(ErrorKind.Session, "Refresh-Token ungültig", code) }
            else -> throw ApiException(if (code >= 500) ErrorKind.Server else ErrorKind.Client, "Token-Erneuerung fehlgeschlagen", code)
        }
    }

    private fun schedule() {
        timer?.cancel(); val t = tokens ?: return
        val delayMs = (t.expiresAtMs - REFRESH_LEAD_MS - now()).coerceAtLeast(1000L)
        timer = scope.launch { delay(delayMs); runCatching { refresh() } }
    }

    private fun expire() { clear(); _state.value = AuthState.SessionExpired }
    private fun clear() { timer?.cancel(); tokens = null; store.remove(VAULT) }

    /** Abmelden: Tokens im Speicher und im verschlüsselten Speicher löschen. */
    fun logout() { clear(); savedSchool = null; _state.value = AuthState.SignedOut }
    /** „Gespeicherte Anmeldung löschen“: Sitzung läuft weiter, auf dem Gerät bleibt nichts. */
    fun forgetStored() { remember = false; store.remove(VAULT) }
    fun enableRemember() { remember = true; persist() }
    fun acknowledgeExpired() { if (_state.value == AuthState.SessionExpired) _state.value = AuthState.SignedOut }

    // ---------- HTTP ----------
    private suspend fun post(path: String, body: JsonObject): Pair<Int, JsonElement?> {
        val req = Request.Builder().url(apiBase + path).header("Accept", "application/json").post(body.toString().toRequestBody(JSON_MT)).build()
        return try {
            suspendCancellableCoroutine { cont ->
                val call = plainClient.newCall(req); cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(ApiException(ErrorKind.Network, "Netzwerkfehler", cause = e)) }
                    override fun onResponse(call: Call, response: Response) = response.use { r ->
                        val text = runCatching { r.body?.string() }.getOrNull().orEmpty()
                        cont.resume(r.code to runCatching { Json.parseToJsonElement(text) }.getOrNull())
                    }
                })
            }
        } catch (e: ApiException) { throw e }
    }
}
