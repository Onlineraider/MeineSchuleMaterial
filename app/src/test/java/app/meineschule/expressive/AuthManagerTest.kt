package app.meineschule.expressive

import app.meineschule.expressive.auth.*
import app.meineschule.expressive.core.ApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AuthManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemorySecureStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() { server = MockWebServer().also { it.start() }; store = MemorySecureStore() }
    @After fun tearDown() { scope.cancel(); server.shutdown() }

    private fun auth() = AuthManager(OkHttpClient(), server.url("/Api/v1.0").toString().trimEnd('/'), "test-app", store, scope)
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val tokenBody = """{"token":"t1","refreshToken":"r1","expireIn":3600}"""

    @Test fun loginSuccess_keepsSessionInMemoryOnly_withoutRemember() = runTest {
        server.enqueue(json(tokenBody))
        val a = auth()
        assertEquals(LoginResult.Success, a.login("u", "pw", null, remember = false))
        assertEquals("t1", a.currentToken)
        assertNull(store.get("session"))
    }

    @Test fun loginWithRemember_persistsEncryptedRecord_butNeverThePassword() = runTest {
        server.enqueue(json(tokenBody))
        auth().login("u", "geheim123", null, remember = true)
        val raw = store.get("session")!!
        assertFalse(raw.contains("geheim123")); assertTrue(raw.contains("r1"))
    }

    @Test fun twoFactorNeeded_isReported_thenCodeIsSent() = runTest {
        server.enqueue(json("""{"twoFactorNeeded":true}""", 401)); server.enqueue(json(tokenBody))
        val a = auth()
        assertEquals(LoginResult.TwoFactorNeeded, a.login("u", "pw", null, false))
        assertEquals(LoginResult.Success, a.login("u", "pw", "123456", false))
        server.takeRequest(); assertTrue(server.takeRequest().body.readUtf8().contains("\"twoFactorCode\":\"123456\""))
    }

    @Test fun wrongPassword_throwsAuthError() = runTest {
        server.enqueue(json("""{}""", 401))
        try { auth().login("u", "x", null, false); fail() } catch (e: ApiException) { assertEquals(401, e.status) }
    }

    @Test fun parallelRefresh_isSingleFlight() = runTest {
        server.enqueue(json(tokenBody)); server.enqueue(json("""{"token":"t2","refreshToken":"r2","expireIn":3600}""").setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        val a = auth(); a.login("u", "pw", null, false)
        withContext(Dispatchers.Default) { (1..8).map { async { a.refresh(staleToken = "t1") } }.awaitAll() }
        assertEquals(1, a.refreshCount); assertEquals("t2", a.currentToken)
        assertEquals(2, server.requestCount)
    }

    @Test fun invalidRefreshToken_expiresSession() = runTest {
        server.enqueue(json(tokenBody)); server.enqueue(json("""{}""", 401))
        val a = auth(); a.login("u", "pw", null, true)
        try { a.refresh(force = true); fail() } catch (_: ApiException) {}
        assertEquals(AuthState.SessionExpired, a.state.value); assertNull(a.currentToken); assertNull(store.get("session"))
    }

    @Test fun restore_usesStoredSession_andLogoutWipesIt() = runTest {
        server.enqueue(json(tokenBody)); server.enqueue(json("""{"token":"t9","refreshToken":"r9","expireIn":3600}"""))
        auth().login("u", "pw", null, true)
        val b = auth()
        assertTrue(b.restore()); assertTrue(b.isSignedIn)
        b.logout(); assertNull(store.get("session")); assertEquals(AuthState.SignedOut, b.state.value)
    }

    @Test fun resolveExpiry_understandsSecondsAndEpochs() {
        val now = 1_700_000_000_000L
        assertEquals(now + 3_600_000, AuthManager.resolveExpiry("opaque", kotlinx.serialization.json.JsonPrimitive(3600), now))
    }
}
