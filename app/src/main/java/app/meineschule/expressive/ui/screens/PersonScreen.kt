package app.meineschule.expressive.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.DeepLinks
import app.meineschule.expressive.core.Urls
import app.meineschule.expressive.data.Chat
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.contentOrNull

/** Personenprofil: Chat-Dialog öffnen, Profil-Link kopieren/teilen, bestehende Chats mit dieser Person. */
@Composable
fun PersonScreen(userIdArg: String, name: String, server: String?, school: String?, schoolName: String?, nav: NavHostController, recipientKey: String? = null) {
    val userId = userIdArg.ifBlank { recipientKey?.takeIf { it.startsWith("U:USER:", true) }?.substring(7).orEmpty() }
    val key = recipientKey ?: "U:USER:$userId"
    val app = LocalApp.current; val ctx = LocalContext.current; val clip = LocalClipboardManager.current; val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }; val shareBase by app.prefs.shareBase.collectAsState()
    val me = app.session.profile.collectAsState().value; val cs = MaterialTheme.colorScheme
    val gen by app.session.generation.collectAsState()
    val foreign = server != null && !server.trimEnd('/').equals(app.origin.trimEnd('/'), true)
    val resolved = rememberLoader(gen, userId) { if (userId.isBlank()) null else runCatching { app.repos.school.users(listOf(userId))[userId] }.getOrNull() }
    val shown = name.ifBlank { (resolved.state as? UiState.Ready)?.data.orEmpty() }.ifBlank { "Person" }
    val link = if (userId.isBlank()) null else DeepLinks.profileUrl(shareBase, userId, shown, server ?: app.origin, school ?: app.session.tag.value, schoolName ?: app.session.school.value?.name)
    val isMe = userId.isNotBlank() && userId == me?.userId
    // Bestehende Chats mit dieser Person: Ersteller oder Teilnehmerliste (Info) enthält Nutzer- bzw. Personenkennung
    val shared = rememberLoader(gen, key) {
        if (foreign || isMe) emptyList<Chat>() else coroutineScope {
            val gate = Semaphore(6)
            app.repos.messenger.index().filter { !it.infoChannel }.take(60).map { c -> async {
                if (userId.isNotBlank() && c.creatorId == userId) c
                else gate.withPermit { runCatching {
                    val keys = app.repos.messenger.info(c.id).arr("recipients", "members", "participants", "users").mapNotNull { (it.raw as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.map { it.substringBefore('=').trim() }
                    if (keys.any { it.equals(key, true) || (userId.isNotBlank() && it.endsWith(userId, true)) }) c else null
                }.getOrNull() }
            } }.awaitAll().filterNotNull()
        }
    }
    PageScaffold(shown, schoolName, { nav.popBackStack() }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        LazyColumn(Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Spacer(Modifier.height(8.dp)); PersonAvatar(shown, userId.takeIf { it.isNotBlank() }?.let { app.repos.school.avatarUrl(it, 256) }, 120.dp, key = userId.ifBlank { key }) }
            item { Text(shown, style = MaterialTheme.typography.headlineSmall) }
            if (foreign) item { InfoBanner("Dieses Profil gehört zu einem anderen Server ($server). Chats können nur auf dem verbundenen Server gestartet werden.", warn = true) }
            if (isMe) item { InfoBanner("Das bist du.", Icons.Rounded.Person) }
            else if (!foreign && (userId.isNotBlank() || recipientKey != null)) item {
                Button({ nav.navigate("newchat?prefill=${Urls.seg("$key=$shown")}") }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Chat, null); Spacer(Modifier.width(8.dp)); Text("Chat starten") }
            }
            if (link != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton({ clip.setText(AnnotatedString(link)); scope.launch { snack.showSnackbar("Link kopiert") } }, Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Link kopieren") }
                    OutlinedButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null)) }, Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Teilen") }
                }
            }
            if (!isMe && !foreign) {
                item { Text("Deine Chats mit ${shown.substringAfter(", ", shown)}", Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.titleMedium) }
                when (val st = shared.state) {
                    UiState.Loading -> item { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Suche in deinen Chats …", color = cs.onSurfaceVariant) } }
                    is UiState.Failed -> item { Text("Chats konnten nicht geprüft werden.", color = cs.onSurfaceVariant) }
                    is UiState.Ready -> if (st.data.isEmpty()) item { Text("Noch keine gemeinsamen Chats.", Modifier.fillMaxWidth(), color = cs.onSurfaceVariant) }
                        else itemsIndexed(st.data) { i, c -> GroupedItem(i, st.data.size, c.name, supporting = c.teaser, unread = c.unread > 0, leading = { PersonAvatar(c.name, key = c.id) }, onClick = { nav.navigate("chat/${c.id}") }) }
                }
            }
        }
    }
}
