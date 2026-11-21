package app.meineschule.expressive.net

import app.meineschule.expressive.auth.AuthManager
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.HubConnectionState
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NewMessageEvent(val chatId: String, val messageId: String?)
enum class HubStatus { Disconnected, Connecting, Connected, Reconnecting }

/**
 * SignalR-Verbindung zu {origin}/notifyHub (Bearer-Token als Access-Token).
 * Der Java-Client hat keine eigene Wiederverbindung → hier: Backoff 0/2/10/30 s, bei jedem Aufbau frisches Token.
 */
class Hub(private val origin: String, private val auth: AuthManager, private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<NewMessageEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NewMessageEvent> = _events
    private val _status = MutableStateFlow(HubStatus.Disconnected)
    val status: StateFlow<HubStatus> = _status
    private var job: Job? = null
    private var conn: HubConnection? = null
    private val backoff = longArrayOf(0, 2_000, 10_000, 30_000)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive) {
                _status.value = if (attempt == 0) HubStatus.Connecting else HubStatus.Reconnecting
                val closed = CompletableDeferred<Unit>()
                try {
                    val c = HubConnectionBuilder.create("$origin/notifyHub")
                        .withAccessTokenProvider(Single.defer { Single.just(runBlocking { auth.accessToken() }) }).build()
                    c.on("newmessage", { m: Map<String, Any?> ->
                        val chat = (m["chatId"] ?: m["ChatId"])?.toString(); val id = (m["id"] ?: m["Id"] ?: m["messageId"])?.toString()
                        if (chat != null) _events.tryEmit(NewMessageEvent(chat, id))
                    }, Map::class.java as Class<Map<String, Any?>>)
                    c.onClosed { closed.complete(Unit) }
                    conn = c
                    c.start().blockingAwait()
                    if (c.connectionState == HubConnectionState.CONNECTED) { _status.value = HubStatus.Connected; attempt = 0 }
                    closed.await()
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Verbindungsfehler: Backoff */ }
                finally { runCatching { conn?.stop()?.blockingAwait() }; conn = null }
                if (!isActive || !auth.isSignedIn) break
                _status.value = HubStatus.Reconnecting
                delay(backoff[minOf(attempt, backoff.size - 1)]); attempt++
            }
            _status.value = HubStatus.Disconnected
        }
    }

    fun stop() { job?.cancel(); job = null; runCatching { conn?.stop() }; conn = null; _status.value = HubStatus.Disconnected }
}
