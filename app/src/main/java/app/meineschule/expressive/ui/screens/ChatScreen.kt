package app.meineschule.expressive.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.*
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/** Zustand/Logik eines Chats: saubere Pagination (Items → ItemsBefore → ItemsAfter), keine Duplikate, optimistisches Senden. */
class ChatController(private val scope: CoroutineScope, private val repos: Repos, val chatId: String) {
    private val store = MessageStore()
    var version by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var loadingOlder by mutableStateOf(false); private set
    var error by mutableStateOf<ApiException?>(null); private set
    val messages: List<Message> get() { version; return store.list }
    val reachedStart: Boolean get() = store.oldestReached
    private fun bump() { version++ }

    fun loadInitial() = scope.launch {
        loading = true; error = null
        try { store.clear(); store.merge(repos.messenger.items(chatId, 1, 25)); if (store.list.size < 25) store.oldestReached = true; markRead() }
        catch (e: Throwable) { error = e.asApi() } finally { loading = false; bump() }
    }
    fun loadOlder() { if (loadingOlder || store.oldestReached || store.firstId == null) return
        scope.launch { loadingOlder = true
            try { val older = repos.messenger.before(chatId, store.firstId!!); if (store.merge(older) == 0) store.oldestReached = true } catch (_: Throwable) {} finally { loadingOlder = false; bump() } } }
    fun loadNewer() = scope.launch { try { val id = store.lastId ?: return@launch; if (store.merge(repos.messenger.after(chatId, id)) > 0) { markRead(); bump() } } catch (_: Throwable) {} }
    private suspend fun markRead() { val ids = store.list.filter { !it.pending && it.own != true }.takeLast(25).map { it.id }; if (ids.isNotEmpty()) runCatching { repos.messenger.markRead(chatId, ids) } }

    fun send(text: String, attachments: List<Triple<ByteArray, String, String>>, replyTo: String?, onError: (String) -> Unit) {
        val tempId = UUID.randomUUID().toString()
        store.addPending(tempId, text); bump()
        scope.launch {
            try {
                attachments.forEach { (bytes, mime, name) -> repos.messenger.upload(chatId, tempId, bytes, mime, name) }
                store.merge(listOf(repos.messenger.send(chatId, text, tempId, replyTo).copy(tempId = tempId)))
            } catch (e: Throwable) { store.fail(tempId); onError(e.asApi().userText) } finally { bump() }
        }
    }
    fun retry(m: Message, onError: (String) -> Unit) { val t = m.tempId ?: return; store.remove(listOf(m.id)); send(m.content, emptyList(), null, onError) }
    fun delete(ids: List<String>, onError: (String) -> Unit) = scope.launch { try { repos.messenger.delete(chatId, ids); store.remove(ids); bump() } catch (e: Throwable) { onError(e.asApi().userText) } }
}

private sealed interface Row { data class Day(val label: String) : Row; data class Msg(val m: Message, val first: Boolean, val last: Boolean) : Row }

@Composable
fun ChatScreen(chatId: String, onBack: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val ctx = LocalContext.current; val cs = MaterialTheme.colorScheme; val clip = LocalClipboardManager.current
    val me = app.session.profile.collectAsState().value?.userId
    val chat = rememberLoader(chatId) { app.repos.messenger.chat(chatId) }
    val info = rememberLoader(chatId) { app.repos.messenger.info(chatId) }
    val ctl = remember(chatId) { ChatController(scope, app.repos, chatId).also { it.loadInitial() } }
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }; var replyTo by remember { mutableStateOf<Message?>(null) }; var picked by remember { mutableStateOf(listOf<Triple<ByteArray, String, String>>()) }
    var actionFor by remember { mutableStateOf<Message?>(null) }; var deleteFor by remember { mutableStateOf<Message?>(null) }; var reportFor by remember { mutableStateOf<Message?>(null) }; var forwardFor by remember { mutableStateOf<Message?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    fun toast(t: String) { scope.launch { snack.showSnackbar(t) } }

    LaunchedEffect(chatId) { app.hub.events.collect { if (it.chatId == chatId) ctl.loadNewer() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (bytes, mime, name) = Files.read(ctx, uri); val err = Urls.validateUpload(bytes.size.toLong(), mime)
        if (err != null) toast(err) else picked = picked + Triple(bytes, mime!!, name)
    }

    val rows = remember(ctl.version) {
        val own = { m: Message -> m.own ?: (me != null && m.authorId == me) }
        val out = mutableListOf<Row>()
        MessageStore.groupByDay(ctl.messages).forEach { g -> out += Row.Day(g.label); g.items.forEachIndexed { i, m -> val prev = g.items.getOrNull(i - 1); val next = g.items.getOrNull(i + 1)
            out += Row.Msg(m.copy(own = own(m)), prev == null || prev.authorId != m.authorId || own(prev) != own(m), next == null || next.authorId != m.authorId || own(next) != own(m)) } }
        out.reversed() // reverseLayout: neueste zuerst
    }
    val nearEnd by remember { derivedStateOf { val l = listState.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, ctl.version) { if (nearEnd) ctl.loadOlder() }

    Scaffold(
        containerColor = cs.surface, snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                title = { val c = (chat.state as? UiState.Ready)?.data; val n = (info.state as? UiState.Ready)?.data?.arr("recipients")?.size
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clickable { showInfo = true }) {
                        PersonAvatar(c?.name ?: "Chat", size = 40.dp, key = chatId); Column { Text(c?.name ?: "Chat", maxLines = 1, style = MaterialTheme.typography.titleMedium); if (n != null && n > 0) Text("$n Teilnehmer", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) } } },
                actions = { var m by remember { mutableStateOf(false) }; IconButton(onClick = { m = true }) { Icon(Icons.Rounded.MoreVert, "Weitere Optionen") }
                    DropdownMenu(m, { m = false }) { DropdownMenuItem({ Text("Teilnehmer") }, { m = false; showInfo = true }, leadingIcon = { Icon(Icons.Rounded.Groups, null) })
                        DropdownMenuItem({ Text("Als ungelesen markieren") }, { m = false; scope.launch { runCatching { app.repos.messenger.markUnread(chatId) }; onBack() } }, leadingIcon = { Icon(Icons.Rounded.MarkEmailUnread, null) }) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainer),
            )
        },
        bottomBar = {
            Column(Modifier.background(cs.surface).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                replyTo?.let { r -> Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(r.content, Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium); IconButton(onClick = { replyTo = null }) { Icon(Icons.Rounded.Close, "Antwort verwerfen") } } }
                if (picked.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { picked.forEach { p -> InputChip(true, { picked = picked - p }, { Text(Urls.safeFileName(p.third), maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Entfernen", Modifier.size(18.dp)) }) } }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton(onClick = { pick.launch("*/*") }, Modifier.size(52.dp)) { Icon(Icons.Rounded.AttachFile, "Anhang hinzufügen") }
                    TextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Nachricht") }, maxLines = 5, shape = RoundedCornerShape(26.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = cs.surfaceContainerHigh, focusedContainerColor = cs.surfaceContainerHigh))
                    FilledIconButton(onClick = { ctl.send(input.trim(), picked, replyTo?.id, ::toast); input = ""; picked = emptyList(); replyTo = null }, Modifier.size(52.dp), enabled = input.isNotBlank() || picked.isNotEmpty(), shape = RoundedCornerShape(18.dp)) { Icon(Icons.AutoMirrored.Rounded.Send, "Senden") }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                ctl.loading && ctl.messages.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(5) { SkeletonBox(Modifier.fillMaxWidth(if (it % 2 == 0) .6f else .45f).height(48.dp).align(if (it % 2 == 0) Alignment.Start else Alignment.End), RoundedCornerShape(22.dp)) } }
                ctl.error != null && ctl.messages.isEmpty() -> ErrorState(ctl.error!!) { ctl.loadInitial() }
                ctl.messages.isEmpty() -> EmptyState(Icons.Rounded.ChatBubbleOutline, "Noch keine Nachrichten", "Schreib die erste Nachricht.")
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    itemsIndexed(rows, key = { _, r -> when (r) { is Row.Day -> "d:${r.label}"; is Row.Msg -> "m:${r.m.id}" } }) { _, r ->
                        when (r) {
                            is Row.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Text(r.label, Modifier.clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
                            is Row.Msg -> Bubble(r, chatId, onLong = { actionFor = r.m }, onRetry = { ctl.retry(r.m, ::toast) })
                        }
                    }
                    if (ctl.loadingOlder) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) } }
                }
            }
        }
    }

    actionFor?.let { m -> ModalBottomSheet({ actionFor = null }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) { Column(Modifier.padding(bottom = 24.dp)) {
        fun close(f: () -> Unit) { actionFor = null; f() }
        ListItem({ Text("Antworten") }, Modifier.clickable { close { replyTo = m } }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Reply, null) })
        ListItem({ Text("Weiterleiten") }, Modifier.clickable { close { forwardFor = m } }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Forward, null) })
        ListItem({ Text("Text kopieren") }, Modifier.clickable { close { clip.setText(AnnotatedString(m.content)); toast("Kopiert") } }, leadingContent = { Icon(Icons.Rounded.ContentCopy, null) })
        if (m.own == true) ListItem({ Text("Löschen", color = cs.error) }, Modifier.clickable { close { deleteFor = m } }, leadingContent = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
        else ListItem({ Text("Melden") }, Modifier.clickable { close { reportFor = m } }, leadingContent = { Icon(Icons.Rounded.Flag, null) })
    } } }
    deleteFor?.let { m -> ConfirmDialog("Nachricht löschen?", "Die Nachricht wird gelöscht, soweit du dazu berechtigt bist.", "Löschen", { ctl.delete(listOf(m.id), ::toast) }, { deleteFor = null }, destructive = true) }
    reportFor?.let { m -> var reason by remember { mutableStateOf("") }
        AlertDialog({ reportFor = null }, shape = RoundedCornerShape(28.dp), title = { Text("Nachricht melden") }, text = { OutlinedTextField(reason, { reason = it }, label = { Text("Grund") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { val id = m.id; reportFor = null; scope.launch { try { app.repos.messenger.report(chatId, id, reason); toast("Gemeldet") } catch (e: Throwable) { toast(e.asApi().userText) } } }, enabled = reason.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Melden") } }, dismissButton = { TextButton({ reportFor = null }) { Text("Abbrechen") } }) }
    forwardFor?.let { m -> ForwardSheet(onDismiss = { forwardFor = null }) { target -> forwardFor = null; scope.launch { try { app.repos.messenger.forward(target, m.id); toast("Weitergeleitet") } catch (e: Throwable) { toast(e.asApi().userText) } } } }
    if (showInfo) ModalBottomSheet({ showInfo = false }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) { Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Teilnehmer", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp))
        val rec = (info.state as? UiState.Ready)?.data?.arr("recipients").orEmpty()
        if (rec.isEmpty()) Text("Keine Teilnehmerinformationen verfügbar.", Modifier.padding(8.dp), color = cs.onSurfaceVariant)
        rec.forEachIndexed { i, r -> val n = r.str("text", "displayName", "name") ?: r.raw?.toString()?.substringAfter('=')?.trim('"') ?: "Teilnehmer"; GroupedItem(i, rec.size, n, leading = { PersonAvatar(n) }) }
    } }
}

@Composable private fun ForwardSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = LocalApp.current; val l = rememberLoader { app.repos.messenger.index() }
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) { Column(Modifier.fillMaxHeight(.7f)) {
        Text("Weiterleiten an …", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp, 8.dp))
        StateHost(l, isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.ChatBubbleOutline, "Keine Chats", null) }) { chats -> LazyColumn { itemsIndexed(chats) { i, c -> GroupedItem(i, chats.size, c.name, leading = { PersonAvatar(c.name, key = c.id) }, onClick = { onPick(c.id) }) } } }
    } }
}

@Composable private fun Bubble(r: Row.Msg, chatId: String, onLong: () -> Unit, onRetry: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val m = r.m; val own = m.own == true; val ctx = LocalContext.current; val app = LocalApp.current; val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(top = if (r.first) 8.dp else 2.dp), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!own) Box(Modifier.width(40.dp)) { if (r.last) PersonAvatar(m.authorName ?: "?", size = 32.dp, key = m.authorId ?: m.authorName ?: "") }
        Column(Modifier.widthIn(max = 300.dp).clip(if (own) MsdShapes.BubbleOut else MsdShapes.BubbleIn).background(if (own) cs.primaryContainer else cs.surfaceContainerHigh).combinedClickable(onClick = { if (m.failed) onRetry() }, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 8.dp)) {
            if (!own && r.first && !m.authorName.isNullOrBlank()) Text(m.authorName, style = MaterialTheme.typography.labelMedium, color = cs.primary)
            if (m.forwarded) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.AutoMirrored.Rounded.Forward, null, Modifier.size(14.dp), tint = cs.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text("Weitergeleitet", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            if (m.replyTo != null) Text("↩ Antwort", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            m.attachments.forEach { a ->
                if (a.isImage) AsyncImage(ImageRequest.Builder(ctx).data(app.repos.messenger.imageUrl(chatId, a.id)).build(), a.name, Modifier.padding(vertical = 4.dp).fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).clickable { scope.launch { runCatching { Files.open(ctx, app.api.bytes(app.repos.messenger.downloadUrl(chatId, a.id)), a.name) } } }, contentScale = ContentScale.Crop)
                else AssistChip(onClick = { scope.launch { runCatching { Files.open(ctx, app.api.bytes(app.repos.messenger.downloadUrl(chatId, a.id)), a.name) } } }, label = { Text(Urls.safeFileName(a.name), maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) })
            }
            if (m.deleted) Text("Nachricht gelöscht", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            else if (m.content.isNotBlank()) Text(m.content, style = MaterialTheme.typography.bodyLarge, color = if (own) cs.onPrimaryContainer else cs.onSurface)
            if (m.hasPoll) InfoBanner("Umfrage – Abstimmen ist in der dokumentierten API nur mit unbekanntem Wertformat belegt und deshalb hier nicht verfügbar.", Icons.Rounded.Poll, modifier = Modifier.padding(0.dp))
            if (r.last) Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(if (m.failed) "Senden fehlgeschlagen – tippen zum Wiederholen" else Dates.parse(m.at)?.let { Dates.time(it) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = if (m.failed) cs.error else cs.onSurfaceVariant)
                if (own) Icon(if (m.failed) Icons.Rounded.ErrorOutline else if (m.pending) Icons.Rounded.Schedule else Icons.Rounded.DoneAll, null, Modifier.padding(start = 4.dp).size(14.dp), tint = if (m.failed) cs.error else cs.onSurfaceVariant)
            }
        }
    }
}
