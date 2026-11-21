package app.meineschule.expressive.net

import app.meineschule.expressive.auth.AuthManager
import app.meineschule.expressive.core.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(ApiException(ErrorKind.Network, "Netzwerkfehler", cause = e)) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}

/**
 * Fügt das Bearer-Token NUR für die konfigurierte Origin hinzu (nie an Fremdhosts).
 * Bei 401: genau ein kontrollierter Refresh (single-flight über AuthManager) + genau ein Retry.
 */
class AuthInterceptor(private val auth: AuthManager, private val originHost: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != originHost || req.url.scheme != "https") return chain.proceed(req)
        val token = try { auth.accessTokenBlocking() } catch (e: ApiException) { throw IOException("Sitzung nicht verfügbar", e) }
        val res = chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        if (res.code != 401) return res
        res.close()
        val retryToken = try { kotlinx.coroutines.runBlocking { auth.refresh(staleToken = token) }; auth.currentToken } catch (e: ApiException) { throw IOException("Sitzung abgelaufen", e) }
            ?: throw IOException("Sitzung abgelaufen")
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $retryToken").build()) // kein weiterer Retry → keine Endlosschleife
    }
}

/** Entfernt Standard-Header-Logging: es gibt bewusst KEIN HttpLoggingInterceptor (keine Tokens/Inhalte in Logs). */
object Http {
    fun plain(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun authed(base: OkHttpClient, auth: AuthManager, originHost: String): OkHttpClient =
        base.newBuilder().addInterceptor(AuthInterceptor(auth, originHost)).followRedirects(false).build()
}
