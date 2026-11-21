package app.meineschule.expressive.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.*
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

val REACTION_EMOJIS = listOf("👍", "😂", "❤️", "🙏", "👎", "😢", "😠")
private val PICKER_EMOJIS = "😀 😃 😄 😁 😆 😅 😂 🙂 😉 😊 😍 🥰 😘 😎 🤔 😐 😴 😢 😭 😡 🤯 😱 🤗 🙄 👍 👎 👏 🙏 💪 👋 🤝 👌 🎉 🎂 ❤️ 💙 💚 🔥 ⭐ ✅ ❌ ❓ ❗ 📚 ✏️ 🎒 🏫 ⏰ 🌟".split(" ")

private fun tempId(): String { val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"; val r = java.security.SecureRandom(); return buildString { repeat(40) { append(a[r.nextInt(a.length)]) } } }

/** Zustand/Logik eines Chats: Items → ItemsBefore → ItemsAfter, keine Duplikate, optimistisches Senden, Aktualisierung per Polling. */
class ChatController(private val scope: CoroutineScope, private val repos: Repos, val chatId: String) {
    private val store = MessageStore()
    var version by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var loadingOlder by mutableStateOf(false); private set
    var error by mutableStateOf<ApiException?>(null); private set
    val messages: List<Message> get() { version; return store.list }
    val reachedStart: Boolean get() = store.oldestReached
    private fun bump() { version++ }
    private val PAGE = 30

    fun loadInitial() = scope.launch {
        loading = true; error = null
        try { store.clear(); store.merge(repos.messenger.items(chatId, 1, PAGE)); if (store.list.size < PAGE) store.oldestReached = true; markRead() }
        catch (e: Throwable) { error = e.asApi() } finally { loading = false; bump() }
    }
    fun loadOlder() { if (loadingOlder || store.oldestReached || store.firstId == null) return
        scope.launch { loadingOlder = true
            try { val older = repos.messenger.before(chatId, store.firstId!!); if (store.merge(older) == 0) store.oldestReached = true } catch (_: Throwable) {} finally { loadingOlder = false; bump() } } }
    /** Neue Nachrichten + aktuelle Reaktionen der letzten Seite. */
    fun refresh() = scope.launch {
        try { var ch = false; store.lastId?.let { id -> if (store.merge(repos.messenger.after(chatId, id)) > 0) { ch = true; markRead() } }
            val cur = repos.messenger.items(chatId, 1, PAGE); if (cur.any { c -> store.get(c.id)?.reactions != c.reactions }) ch = true; store.merge(cur); if (ch) bump() } catch (_: Throwable) {}
    }
    private suspend fun markRead() { val ids = store.list.filter { !it.pending && !it.isRead }.map { it.id }; runCatching { repos.messenger.markRead(chatId, ids) }; ids.forEach { id -> store.update(id) { it.copy(isRead = true) } } }

    /** Text und/oder Dateien senden. Eine Datei pro Nachricht (Upload → Create mit tempId), Text hängt an der ersten. */
    fun send(text: String, files: List<Triple<ByteArray, String, String>>, replyTo: String?, onError: (String) -> Unit) {
        val parts = if (files.isEmpty()) listOf<Triple<ByteArray, String, String>?>(null) else files
        parts.forEachIndexed { i, f ->
            val local = UUID.randomUUID().toString(); val body = if (i == 0) text else ""
            store.addPending(local, body, f?.third); bump()
            scope.launch {
                try {
                    val tid = f?.let { tempId().also { t -> repos.messenger.upload(chatId, t, it.first, it.second, it.third) } }
                    val sent = repos.messenger.send(chatId, body, tid, if (i == 0) replyTo else null)
                    store.remove(listOf("tmp:$local")); store.merge(listOf(sent))
                } catch (e: Throwable) { store.fail(local); onError(e.asApi().userText) } finally { bump() }
            }
        }
    }
    fun retry(m: Message, onError: (String) -> Unit) { store.remove(listOf(m.id)); send(m.content, emptyList(), null, onError) }
    fun delete(ids: List<String>, onError: (String) -> Unit) = scope.launch { try { repos.messenger.delete(chatId, ids); store.remove(ids); bump() } catch (e: Throwable) { onError(e.asApi().userText) } }
    fun react(m: Message, emoji: String?, onError: (String) -> Unit) = scope.launch {
        val old = m.myVote; val target = emoji?.takeIf { it != old }
        store.update(m.id) { it.copy(reactions = applyVote(it.reactions, old, target), myVote = target) }; bump()
        try { repos.messenger.react(chatId, m.id, target) } catch (e: Throwable) { store.update(m.id) { m }; bump(); onError(e.asApi().userText) }
    }
    private fun applyVote(list: List<Reaction>, old: String?, new: String?): List<Reaction> {
        var l = list.mapNotNull { r -> if (r.emoji == old && r.mine) (if (r.count <= 1) null else r.copy(count = r.count - 1, mine = false)) else r }
        if (new != null) l = if (l.any { it.emoji == new }) l.map { if (it.emoji == new) it.copy(count = it.count + 1, mine = true) else it } else l + Reaction(new, 1, true, emptyList())
        return l
    }
}

private sealed interface Row { data class Day(val label: String) : Row; data class Msg(val m: Message, val first: Boolean, val last: Boolean) : Row }

@Composable
fun ChatScreen(chatId: String, nav: NavHostController) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val ctx = LocalContext.current; val cs = MaterialTheme.colorScheme; val clip = LocalClipboardManager.current
    val me = app.session.profile.collectAsState().value?.userId
    val chat = rememberLoader(chatId) { app.repos.messenger.chat(chatId) }
    val ctl = remember(chatId) { ChatController(scope, app.repos, chatId).also { it.loadInitial() } }
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }; var replyTo by remember { mutableStateOf<Message?>(null) }; var picked by remember { mutableStateOf(listOf<Triple<ByteArray, String, String>>()) }
    var actionFor by remember { mutableStateOf<Message?>(null) }; var deleteFor by remember { mutableStateOf<Message?>(null) }; var emojiFor by remember { mutableStateOf<Message?>(null) }
    var showInfo by remember { mutableStateOf(false) }; var confirmDeleteChat by remember { mutableStateOf(false) }; var emojiPanel by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf(false) }; var query by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }
    fun toast(t: String) { scope.launch { snack.showSnackbar(t) } }
    val c = (chat.state as? UiState.Ready)?.data

    // Aktualisierung alle 15 s, solange der Chat sichtbar ist (die Web-App nutzt dasselbe Verfahren), plus SignalR-Ereignis, falls verfügbar
    LaunchedEffect(chatId) { while (isActive) { delay(15_000); ctl.refresh() } }
    LaunchedEffect(chatId) { app.hub.events.collect { if (it.chatId == chatId) ctl.refresh() } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val (bytes, mime, name) = Files.read(ctx, uri); val err = Urls.validateUpload(bytes.size.toLong(), mime)
        if (err != null) toast(err) else picked = picked + Triple(bytes, mime!!, name)
    }

    val rows = remember(ctl.version, query) {
        val q = query.trim().lowercase()
        val own = { m: Message -> m.own ?: (me != null && m.authorId == me) }
        val out = mutableListOf<Row>()
        val all = if (q.isEmpty()) ctl.messages else ctl.messages.filter { "${it.content} ${it.authorName.orEmpty()} ${it.attachmentName.orEmpty()}".lowercase().contains(q) }
        MessageStore.groupByDay(all).forEach { g -> out += Row.Day(g.label); g.items.forEachIndexed { i, m -> val prev = g.items.getOrNull(i - 1); val next = g.items.getOrNull(i + 1)
            out += Row.Msg(m.copy(own = own(m)), prev == null || prev.authorId != m.authorId || own(prev) != own(m), next == null || next.authorId != m.authorId || own(next) != own(m)) } }
        out.reversed()
    }
    val nearEnd by remember { derivedStateOf { val l = listState.layoutInfo; (l.visibleItemsInfo.lastOrNull()?.index ?: 0) >= l.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, ctl.version, query) { if (nearEnd || query.isNotBlank()) ctl.loadOlder() }

    Scaffold(
        containerColor = cs.surface, snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                    title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clickable { showInfo = true }) {
                        PersonAvatar(c?.name ?: "Chat", size = 40.dp, key = chatId); Column { Text(c?.name ?: "Chat", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                            val sub = when { c?.canPost == false -> "Schreiben nicht erlaubt"; c?.isOwner == true -> "Du bist Besitzer"; else -> c?.categoryName }
                            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1) } } },
                    actions = {
                        IconButton({ find = !find; if (!find) query = "" }) { Icon(Icons.Rounded.Search, "Im Verlauf suchen") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Weitere Optionen") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Chat-Info") }, { menu = false; showInfo = true }, leadingIcon = { Icon(Icons.Rounded.Info, null) })
                            DropdownMenuItem({ Text("Bearbeiten") }, { menu = false; nav.navigate("newchat?edit=$chatId") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                            DropdownMenuItem({ Text(if (c?.silent == true) "Ton an" else "Stummschalten") }, { menu = false; scope.launch { runCatching { app.repos.messenger.mute(chatId, c?.silent != true) }; chat.reload() } }, leadingIcon = { Icon(Icons.Rounded.NotificationsOff, null) })
                            DropdownMenuItem({ Text(if (c?.pinned == true) "Lösen" else "Anheften") }, { menu = false; scope.launch { runCatching { app.repos.messenger.pin(chatId, c?.pinned != true) }; chat.reload() } }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                            DropdownMenuItem({ Text("Als ungelesen markieren") }, { menu = false; scope.launch { runCatching { app.repos.messenger.markUnread(chatId) }; nav.popBackStack() } }, leadingIcon = { Icon(Icons.Rounded.MarkEmailUnread, null) })
                            if (c?.isOwner == true) DropdownMenuItem({ Text("Chat löschen", color = cs.error) }, { menu = false; confirmDeleteChat = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                        } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainer),
                )
                if (find) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), placeholder = { Text("In diesem Chat suchen …") }, singleLine = true, shape = CircleShape, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) Text("${rows.count { it is Row.Msg }} Treffer", Modifier.padding(end = 12.dp), style = MaterialTheme.typography.labelMedium) })
            }
        },
        bottomBar = {
            if (c?.canPost != false) Column(Modifier.background(cs.surface).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                replyTo?.let { r -> Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(cs.secondaryContainer).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Antwort an ${r.authorName ?: "Nachricht"}: ${r.content.ifBlank { r.attachmentName.orEmpty() }}", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium); IconButton(onClick = { replyTo = null }) { Icon(Icons.Rounded.Close, "Antwort verwerfen") } } }
                if (picked.isNotEmpty()) Row(Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { picked.forEach { p -> InputChip(true, { picked = picked - p }, { Text(Urls.safeFileName(p.third), maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Entfernen", Modifier.size(18.dp)) }) } }
                if (emojiPanel) Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) { PICKER_EMOJIS.forEach { e -> Text(e, Modifier.clickable { input += e }.padding(6.dp), style = MaterialTheme.typography.titleLarge) } }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { emojiPanel = !emojiPanel }, Modifier.size(48.dp)) { Icon(Icons.Rounded.SentimentSatisfied, "Emoji wählen") }
                    IconButton(onClick = { pick.launch("*/*") }, Modifier.size(48.dp)) { Icon(Icons.Rounded.AttachFile, "Anhang hinzufügen") }
                    TextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Nachricht schreiben …") }, maxLines = 5, shape = RoundedCornerShape(26.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, unfocusedContainerColor = cs.surfaceContainerHigh, focusedContainerColor = cs.surfaceContainerHigh))
                    FilledIconButton(onClick = { ctl.send(input.trim(), picked, replyTo?.id, ::toast); input = ""; picked = emptyList(); replyTo = null; emojiPanel = false }, Modifier.size(52.dp), enabled = input.isNotBlank() || picked.isNotEmpty(), shape = RoundedCornerShape(18.dp)) { Icon(Icons.AutoMirrored.Rounded.Send, "Senden") }
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
                            is Row.Msg -> Bubble(r, chatId, query, onLong = { actionFor = r.m }, onRetry = { ctl.retry(r.m, ::toast) }, onReact = { e -> ctl.react(r.m, e, ::toast) }, onMore = { emojiFor = r.m },
                                onAuthor = { r.m.authorId?.let { id -> nav.navigate("person?kind=user&uid=$id&n=${Urls.seg(r.m.authorName.orEmpty())}") } })
                        }
                    }
                    if (ctl.loadingOlder) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) } }
                }
            }
        }
    }

    actionFor?.let { m -> ModalBottomSheet({ actionFor = null }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) { Column(Modifier.padding(bottom = 24.dp)) {
        fun close(f: () -> Unit) { actionFor = null; f() }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REACTION_EMOJIS.forEach { e -> FilterChip(m.myVote == e, { close { ctl.react(m, e, ::toast) } }, { Text(e, style = MaterialTheme.typography.titleMedium) }) }
            FilledTonalIconButton({ close { emojiFor = m } }) { Icon(Icons.Rounded.AddReaction, "Weitere Reaktionen") }
        }
        if (c?.canPost != false) ListItem({ Text("Antworten") }, Modifier.clickable { close { replyTo = m } }, leadingContent = { Icon(Icons.AutoMirrored.Rounded.Reply, null) })
        if (m.content.isNotBlank()) ListItem({ Text("Text kopieren") }, Modifier.clickable { close { clip.setText(AnnotatedString(m.content)); toast("Kopiert") } }, leadingContent = { Icon(Icons.Rounded.ContentCopy, null) })
        if (m.canDelete || m.own == true || c?.isOwner == true) ListItem({ Text("Löschen", color = cs.error) }, Modifier.clickable { close { deleteFor = m } }, leadingContent = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
    } } }
    emojiFor?.let { m -> ModalBottomSheet({ emojiFor = null }, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) { Text("Reagieren", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            FlowRowCompat { PICKER_EMOJIS.forEach { e -> Text(e, Modifier.clip(CircleShape).clickable { emojiFor = null; ctl.react(m, e, ::toast) }.padding(10.dp), style = MaterialTheme.typography.headlineSmall) } }
            if (m.myVote != null) TextButton({ emojiFor = null; ctl.react(m, null, ::toast) }) { Text("Meine Reaktion entfernen") } } } }
    deleteFor?.let { m -> ConfirmDialog("Nachricht löschen?", "Die Nachricht wird gelöscht, soweit du dazu berechtigt bist.", "Löschen", { ctl.delete(listOf(m.id), ::toast) }, { deleteFor = null }, destructive = true) }
    if (confirmDeleteChat) ConfirmDialog("Chat löschen?", "„${c?.name}“ wird für alle gelöscht. Das kann nicht rückgängig gemacht werden.", "Löschen", { scope.launch { try { app.repos.messenger.deleteChat(chatId); nav.popBackStack() } catch (e: Throwable) { toast(e.asApi().userText) } } }, { confirmDeleteChat = false }, destructive = true)
    if (showInfo) ChatInfoSheet(chatId, c, onDismiss = { showInfo = false }, nav = nav)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun FlowRowCompat(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.Center) { content() }

@Composable private fun ChatInfoSheet(chatId: String, chat: Chat?, onDismiss: () -> Unit, nav: NavHostController) {
    val app = LocalApp.current; val cs = MaterialTheme.colorScheme
    val info = rememberLoader(chatId) {
        val i = app.repos.messenger.info(chatId)
        val members = i.arr("recipients", "members", "participants", "users").map { x -> (x.raw as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.let(::parseRecord)?.let { Recipient(it.first, it.second, null, null) } ?: Recipient(x.str("key", "id", "userId").orEmpty(), x.str("text", "displayName", "name").orEmpty(), null, null) }.filter { it.key.isNotBlank() }
        members.forEach { app.contacts.add(it.key, it.text) }; app.contacts.resolveNames()
        i to members.map { it.copy(text = it.text.ifBlank { app.contacts.name(it.key).orEmpty() }) }
    }
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(chat?.name ?: "Chat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp))
            chat?.categoryName?.let { SuggestionChip({}, { Text(it) }, Modifier.padding(horizontal = 8.dp)) }
            fun kv(a: String, b: String?) { }
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOfNotNull(
                    chat?.let { "Besitzer" to if (it.isOwner) "Du" else it.creatorName }, chat?.created?.let { "Erstellt" to (Dates.parse(it)?.let { d -> Dates.dayShort(d.toLocalDate()) + " " + Dates.time(d) } ?: it) },
                    chat?.let { "Schreibrechte" to if (it.authorsLimited) "Nur Chat-Ersteller / Autoren" else "Alle Teilnehmer" }, chat?.let { c -> listOfNotNull(if (c.pinned) "angeheftet" else null, if (c.silent) "stumm" else null).joinToString(", ").takeIf { it.isNotEmpty() }?.let { "Status" to it } },
                ).forEach { (k, v) -> if (!v.isNullOrBlank()) Row { Text(k, Modifier.width(120.dp), color = cs.onSurfaceVariant); Text(v, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium) } }
            }
            StateHost(info, skeleton = { SkeletonList(3) }) { (_, members) ->
                SectionTitle("Teilnehmer (${members.size})")
                if (members.isEmpty()) Text("Keine Teilnehmer geliefert.", Modifier.padding(8.dp), color = cs.onSurfaceVariant)
                members.forEachIndexed { i, r ->
                    val n = r.text.ifBlank { r.key }; val person = Regex("^(U|P):", RegexOption.IGNORE_CASE).containsMatchIn(r.key)
                    GroupedItem(i, members.size, n, supporting = if (person) "Person" else "Gruppe / Rolle",
                        leading = { PersonAvatar(n, if (r.key.startsWith("U:USER:", true)) app.repos.school.avatarUrl(r.key.substring(7)) else null, key = r.key) },
                        onClick = { onDismiss(); nav.navigate("person?key=${Urls.seg(r.key)}&n=${Urls.seg(n)}") })
                }
            }
        }
    }
}

/** URLs im Text antippbar machen (nur http/https). */
private val URL_RE = Regex("""\b(?:https?://|www\.)[^\s<>"]+""", RegexOption.IGNORE_CASE)
private fun linkify(text: String, hl: String, linkColor: Color, hlColor: Color) = buildAnnotatedString {
    var last = 0
    fun plain(s: String) { if (hl.isBlank()) append(s) else { var i = 0; val l = s.lowercase(); while (i < s.length) { val k = l.indexOf(hl.lowercase(), i); if (k < 0) { append(s.substring(i)); break }; append(s.substring(i, k)); withStyle(SpanStyle(background = hlColor)) { append(s.substring(k, k + hl.length)) }; i = k + hl.length } } }
    for (m in URL_RE.findAll(text)) {
        plain(text.substring(last, m.range.first)); var u = m.value; val tail = Regex("""[.,;:!?)\]}'"»”]+$""").find(u)?.value.orEmpty(); if (tail.isNotEmpty()) u = u.dropLast(tail.length)
        val href = if (u.startsWith("www.", true)) "https://$u" else u
        if (Urls.safeExternal(href) != null) { pushStringAnnotation("URL", href); withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(u) }; pop() } else append(u)
        append(tail); last = m.range.last + 1
    }
    plain(text.substring(last))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Bubble(r: Row.Msg, chatId: String, query: String, onLong: () -> Unit, onRetry: () -> Unit, onReact: (String?) -> Unit, onMore: () -> Unit, onAuthor: () -> Unit) {
    val cs = MaterialTheme.colorScheme; val m = r.m; val own = m.own == true; val ctx = LocalContext.current; val app = LocalApp.current; val scope = rememberCoroutineScope()
    fun open() { val id = m.attachmentId ?: return; scope.launch { runCatching { Files.open(ctx, app.api.bytes(app.repos.messenger.downloadUrl(chatId, id)), m.attachmentName ?: "Anhang") } } }
    Row(Modifier.fillMaxWidth().padding(top = if (r.first) 8.dp else 2.dp), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!own) Box(Modifier.width(40.dp).clickable(enabled = r.last && m.authorId != null, onClick = onAuthor)) { if (r.last) PersonAvatar(m.authorName ?: "?", m.authorId?.let { app.repos.school.avatarUrl(it) }, 32.dp, key = m.authorId ?: m.authorName ?: "") }
        Column(Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.clip(if (own) MsdShapes.BubbleOut else MsdShapes.BubbleIn).background(if (own) cs.primaryContainer else cs.surfaceContainerHigh).combinedClickable(onClick = { if (m.failed) onRetry() }, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 8.dp)) {
                if (!own && r.first && !m.authorName.isNullOrBlank()) Text(m.authorName, style = MaterialTheme.typography.labelMedium, color = cs.primary)
                if (m.replyId != null || m.replyContent != null) Text(m.replyContent.orEmpty().ifBlank { "Anhang" }, Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(cs.surface.copy(alpha = .5f)).padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (m.hasAttachment) {
                    if (m.attachmentIsImage) AsyncImage(app.repos.messenger.downloadUrl(chatId, m.attachmentId!!), m.attachmentName, Modifier.padding(vertical = 4.dp).fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).clickable { open() }, contentScale = ContentScale.Crop)
                    else AssistChip(onClick = { open() }, label = { Text(m.attachmentName ?: "Anhang", maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) })
                } else if (m.pending && m.attachmentName != null) AssistChip(onClick = {}, label = { Text(m.attachmentName, maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) })
                if (m.content.isNotBlank()) {
                    val txt = linkify(m.content, query.trim(), cs.primary, cs.tertiaryContainer)
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    androidx.compose.foundation.text.ClickableText(txt, style = MaterialTheme.typography.bodyLarge.copy(color = if (own) cs.onPrimaryContainer else cs.onSurface), onClick = { off -> txt.getStringAnnotations("URL", off, off).firstOrNull()?.let { runCatching { uri.openUri(it.item) } } ?: onLong() })
                }
                if (r.last) Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (m.failed) "Senden fehlgeschlagen – tippen zum Wiederholen" else Dates.parse(m.at)?.let { Dates.time(it) }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = if (m.failed) cs.error else cs.onSurfaceVariant)
                    if (own) Icon(if (m.failed) Icons.Rounded.ErrorOutline else if (m.pending) Icons.Rounded.Schedule else Icons.Rounded.DoneAll, null, Modifier.padding(start = 4.dp).size(14.dp), tint = if (m.failed) cs.error else cs.onSurfaceVariant)
                }
            }
            if (m.reactions.isNotEmpty()) FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                m.reactions.forEach { re -> AssistChip(onClick = { onReact(if (re.mine) null else re.emoji) }, label = { Text(if (re.count > 1) "${re.emoji} ${re.count}" else re.emoji) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = if (re.mine) cs.secondaryContainer else cs.surfaceContainerHigh), modifier = Modifier.height(30.dp)) }
            }
        }
    }
}
