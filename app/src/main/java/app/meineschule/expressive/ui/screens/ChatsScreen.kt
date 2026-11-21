package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.Chat
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class ChatFilter(val label: String) { All("Alle"), Info("Infokanäle"), Pinned("Angeheftet"), Unread("Ungelesen") }

@Composable
fun ChatsScreen(nav: NavHostController) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    var query by remember { mutableStateOf("") }; var debounced by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ChatFilter.All) }; var category by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Chat?>(null) }; var menuFor by remember { mutableStateOf<Chat?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(query) { delay(300); debounced = query.trim() }
    val cats = rememberLoader(gen) { runCatching { app.repos.messenger.categories() }.getOrDefault(emptyList()) }
    val loader = rememberLoader(gen, debounced, category) { app.repos.messenger.index(search = debounced.takeIf { it.isNotEmpty() }, category = category) }
    // Liste alle 20 s aktualisieren (wie die Web-App) und bei SignalR-Ereignissen
    LaunchedEffect(gen) { while (isActive) { delay(20_000); loader.reload() } }
    LaunchedEffect(Unit) { app.hub.events.collect { loader.reload() } }
    fun act(msg: String? = null, block: suspend () -> Unit) = scope.launch { try { block(); msg?.let { snack.showSnackbar(it) }; loader.reload() } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } }

    PageScaffold(title = "Nachrichten", snackbarHost = { SnackbarHost(snack) }, actions = { IconButton(onClick = { nav.navigate("newchat") }) { Icon(Icons.Rounded.Add, "Neuer Chat") } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { nav.navigate("newchat") }, shape = MsdShapes.Fab, containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer, icon = { Icon(Icons.Rounded.Edit, null) }, text = { Text("Neuer Chat") }) },
    ) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Chats durchsuchen") }, singleLine = true, shape = CircleShape,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Suche leeren") } },
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = cs.surfaceContainerHigh, focusedContainerColor = cs.surfaceContainerHigh, unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }, leadingIcon = if (filter == f) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null) }
                (cats.state as? UiState.Ready)?.data?.forEach { c -> FilterChip(category == c.id, { category = if (category == c.id) null else c.id }, { Text(c.name) }, leadingIcon = { Icon(Icons.Rounded.Label, null, Modifier.size(18.dp)) }) }
            }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.ChatBubbleOutline, "Keine Chats", if (debounced.isNotEmpty() || category != null) "Für diese Suche gibt es keine Treffer." else "Starte einen neuen Chat mit „Neuer Chat“.") }) { all ->
                val list = all.distinctBy { it.id.ifBlank { it.name } }.filter { when (filter) { ChatFilter.All -> true; ChatFilter.Info -> it.infoChannel; ChatFilter.Pinned -> it.pinned; ChatFilter.Unread -> it.unread > 0 } }
                if (list.isEmpty()) EmptyState(Icons.Rounded.SearchOff, "Keine Treffer", "Für diesen Filter gibt es keine Chats.")
                else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    itemsIndexed(list, key = { _, c -> c.id.ifBlank { c.name } }) { i, c ->
                        Box {
                            GroupedItem(i, list.size, c.name, supporting = c.teaser, unread = c.unread > 0,
                                leading = { if (c.infoChannel) TileIcon(Icons.Rounded.Campaign) else PersonAvatar(c.name, key = c.id) },
                                trailing = { Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (c.unread > 0) Badge { Text(c.unread.toString()) }
                                    Row { if (c.pinned) Icon(Icons.Rounded.PushPin, "Angeheftet", Modifier.size(16.dp), tint = cs.onSurfaceVariant); if (c.silent) Icon(Icons.Rounded.NotificationsOff, "Stumm", Modifier.size(16.dp), tint = cs.onSurfaceVariant) } } },
                                onClick = { nav.navigate("chat/${c.id}") }, onLongClick = { menuFor = c })
                            DropdownMenu(menuFor?.id == c.id, { menuFor = null }) {
                                DropdownMenuItem({ Text(if (c.pinned) "Lösen" else "Anheften") }, { menuFor = null; act { app.repos.messenger.pin(c.id, !c.pinned) } }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                                DropdownMenuItem({ Text(if (c.silent) "Ton an" else "Stummschalten") }, { menuFor = null; act { app.repos.messenger.mute(c.id, !c.silent) } }, leadingIcon = { Icon(Icons.Rounded.NotificationsOff, null) })
                                DropdownMenuItem({ Text("Als ungelesen markieren") }, { menuFor = null; act { app.repos.messenger.markUnread(c.id) } }, leadingIcon = { Icon(Icons.Rounded.MarkEmailUnread, null) })
                                if (c.isOwner) DropdownMenuItem({ Text("Chat löschen", color = cs.error) }, { menuFor = null; deleteTarget = c }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = cs.error) })
                            }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { c -> ConfirmDialog("Chat löschen?", "„${c.name}“ wird für alle gelöscht. Das kann nicht rückgängig gemacht werden.", "Löschen", { act("Chat gelöscht") { app.repos.messenger.deleteChat(c.id) } }, { deleteTarget = null }, destructive = true) }
}
