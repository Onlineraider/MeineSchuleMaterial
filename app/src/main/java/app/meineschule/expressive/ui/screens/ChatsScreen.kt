package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.Chat
import app.meineschule.expressive.data.Recipient
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ChatFilter(val label: String) { All("Alle"), Unread("Ungelesen"), Pinned("Angepinnt"), Groups("Gruppen"), Direct("Einzelchats") }

@Composable
fun ChatsScreen(onOpen: (String) -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    var query by remember { mutableStateOf("") }; var debounced by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ChatFilter.All) }
    var showNew by remember { mutableStateOf(false) }; var deleteTarget by remember { mutableStateOf<Chat?>(null) }; var menuFor by remember { mutableStateOf<Chat?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(query) { delay(300); debounced = query.trim() }
    val loader = rememberLoader(gen, debounced) { app.repos.messenger.index(search = debounced.takeIf { it.length >= 2 }) }

    // Nur den betroffenen Chat aktualisieren statt alle neu zu laden
    LaunchedEffect(Unit) {
        app.hub.events.collect { ev ->
            val fresh = runCatching { app.repos.messenger.chat(ev.chatId) }.getOrNull()
            if (fresh == null) { loader.reload(); return@collect }
            loader.update { list -> if (list.any { it.id == fresh.id }) list.map { if (it.id == fresh.id) fresh else it } else listOf(fresh) + list }
        }
    }
    fun act(msg: String? = null, block: suspend () -> Unit) = scope.launch { try { block(); msg?.let { snack.showSnackbar(it) }; loader.reload() } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } }

    PageScaffold(title = "Nachrichten", snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (school?.canUseMessenger != false) ExtendedFloatingActionButton(onClick = { showNew = true }, shape = MsdShapes.Fab, containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer, icon = { Icon(Icons.Rounded.Edit, null) }, text = { Text("Neuer Chat") }) },
    ) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Chats und Personen suchen") }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Suche leeren") } },
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = cs.surfaceContainerHigh, focusedContainerColor = cs.surfaceContainerHigh, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }, leadingIcon = when (f) { ChatFilter.Unread -> ({ Icon(Icons.Rounded.MarkEmailUnread, null, Modifier.size(18.dp)) }); ChatFilter.Pinned -> ({ Icon(Icons.Rounded.PushPin, null, Modifier.size(18.dp)) }); else -> null }) }
            }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.ChatBubbleOutline, "Noch keine Nachrichten", "Starte einen neuen Chat mit dem Stift-Button.") }) { all ->
                val list = all.filter { when (filter) { ChatFilter.All -> true; ChatFilter.Unread -> it.unread > 0; ChatFilter.Pinned -> it.pinned; ChatFilter.Groups -> it.group == true; ChatFilter.Direct -> it.group != true } }
                    .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { Dates.parse(it.lastAt) })
                if (list.isEmpty()) EmptyState(Icons.Rounded.SearchOff, "Keine Treffer", "Für diesen Filter gibt es keine Chats.")
                else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                        Box {
                            GroupedItem(i, list.size, c.name, supporting = c.last, meta = Dates.chatTime(c.lastAt), unread = c.unread > 0, leading = { PersonAvatar(c.name, key = c.id) },
                                trailing = {
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (c.unread > 0) Badge { Text(c.unread.toString()) }
                                        Row { if (c.pinned) Icon(Icons.Rounded.PushPin, "Angepinnt", Modifier.size(16.dp), tint = cs.onSurfaceVariant); if (c.muted) Icon(Icons.Rounded.VolumeOff, "Stummgeschaltet", Modifier.size(16.dp), tint = cs.onSurfaceVariant) }
                                    }
                                }, onClick = { onOpen(c.id) }, onLongClick = { menuFor = c })
                            DropdownMenu(menuFor?.id == c.id, { menuFor = null }) {
                                DropdownMenuItem({ Text(if (c.pinned) "Lösen" else "Anpinnen") }, { menuFor = null; act { app.repos.messenger.pin(c.id, !c.pinned) } }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                                DropdownMenuItem({ Text(if (c.muted) "Ton an" else "Stummschalten") }, { menuFor = null; act { app.repos.messenger.mute(c.id, !c.muted) } }, leadingIcon = { Icon(Icons.Rounded.VolumeOff, null) })
                                DropdownMenuItem({ Text("Als ungelesen markieren") }, { menuFor = null; act { app.repos.messenger.markUnread(c.id) } }, leadingIcon = { Icon(Icons.Rounded.MarkEmailUnread, null) })
                                DropdownMenuItem({ Text("Chat löschen", color = cs.error) }, { menuFor = null; deleteTarget = c }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                            }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { c -> ConfirmDialog("Chat löschen?", "„${c.name}“ wird gelöscht, soweit du dazu berechtigt bist. Das kann nicht rückgängig gemacht werden.", "Löschen", { act("Chat gelöscht") { app.repos.messenger.remove(c.id) } }, { deleteTarget = null }, destructive = true) }
    if (showNew) NewChatSheet(onDismiss = { showNew = false }, onCreated = { showNew = false; it?.let(onOpen) ?: loader.reload() })
}

/** Neuer Chat: Empfänger ausschließlich aus RecipientIndexData/SearchRecipients; `key` wird unverändert übernommen. */
@Composable
fun NewChatSheet(onDismiss: () -> Unit, onCreated: (String?) -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<Recipient>()) }; var results by remember { mutableStateOf<List<Recipient>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(q) {
        delay(300); error = null
        results = try { if (q.trim().length >= 2) app.repos.school.searchRecipients(q.trim()) else app.repos.school.recipients().take(40) } catch (e: Throwable) { error = e.asApi().userText; emptyList() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxHeight(.92f).padding(horizontal = 16.dp)) {
            Text("Neuer Chat", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Empfänger suchen") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            if (selected.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEach { r -> InputChip(true, { selected = selected - r }, { Text(r.text) }, trailingIcon = { Icon(Icons.Rounded.Close, "Entfernen", Modifier.size(18.dp)) }) }
            }
            if (selected.size > 1) OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Gruppenname") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            error?.let { Text(it, color = cs.error, modifier = Modifier.padding(8.dp)) }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                itemsIndexed(results, key = { _, r -> r.key }) { i, r ->
                    val on = selected.any { it.key == r.key }
                    GroupedItem(i, results.size, r.text, supporting = r.info, container = if (on) cs.secondaryContainer else cs.surfaceContainerLow, leading = { PersonAvatar(r.text, key = r.key) }, trailing = { if (on) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary) }, onClick = { selected = if (on) selected.filterNot { it.key == r.key } else selected + r }, modifier = Modifier.padding(0.dp))
                }
                if (results.isEmpty()) item { EmptyState(Icons.Rounded.PersonSearch, "Keine Empfänger", "Versuche einen anderen Suchbegriff.") }
            }
            LoadingButton(if (selected.size > 1) "Gruppe erstellen" else "Chat starten", busy, {
                busy = true; scope.launch { try { onCreated(app.repos.messenger.create(name.takeIf { selected.size > 1 }, selected.map { it.key })) } catch (e: Throwable) { error = e.asApi().userText } finally { busy = false } }
            }, Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = selected.isNotEmpty(), icon = Icons.Rounded.Check)
        }
    }
}
