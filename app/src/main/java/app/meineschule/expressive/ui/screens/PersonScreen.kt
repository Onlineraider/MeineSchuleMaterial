package app.meineschule.expressive.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
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
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch

/** Personenprofil (Nutzer-ID aus Kontakt-Link, Chat oder Empfängersuche): Chat starten, Profil-Link kopieren/teilen. */
@Composable
fun PersonScreen(userIdArg: String, name: String, server: String?, school: String?, schoolName: String?, nav: NavHostController, recipientKey: String? = null) {
    val userId = userIdArg.ifBlank { recipientKey?.takeIf { it.startsWith("U:USER:", true) }?.substring(7).orEmpty() }
    val key = recipientKey ?: "U:USER:$userId"
    val app = LocalApp.current; val ctx = LocalContext.current; val clip = LocalClipboardManager.current; val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }; val shareBase by app.prefs.shareBase.collectAsState()
    val me = app.session.profile.collectAsState().value
    var busy by remember { mutableStateOf(false) }
    val gen by app.session.generation.collectAsState()
    val foreign = server != null && !server.trimEnd('/').equals(app.origin.trimEnd('/'), true)
    val resolved = rememberLoader(gen, userId) { runCatching { app.repos.school.users(listOf(userId))[userId] }.getOrNull() }
    val shown = name.ifBlank { (resolved.state as? UiState.Ready)?.data.orEmpty() }.ifBlank { "Person" }
    val link = DeepLinks.profileUrl(shareBase, userId, shown, server ?: app.origin, school ?: app.session.tag.value, schoolName ?: app.session.school.value?.name)
    PageScaffold(shown, schoolName, { nav.popBackStack() }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            PersonAvatar(shown, app.repos.school.avatarUrl(userId, 256), 120.dp, key = userId)
            Text(shown, style = MaterialTheme.typography.headlineSmall)
            if (foreign) InfoBanner("Dieses Profil gehört zu einem anderen Server (${server}). Chats können nur auf dem verbundenen Server gestartet werden.", warn = true)
            if (userId == me?.userId) InfoBanner("Das bist du.", Icons.Rounded.Person)
            else if (!foreign && (userId.isNotBlank() || recipientKey != null)) LoadingButton("Chat starten", busy, {
                busy = true
                scope.launch {
                    runCatching { app.repos.messenger.create(null, listOf("$key=$shown"), reuse = true) }
                        .onSuccess { id -> busy = false; if (id != null) { nav.popBackStack(); nav.navigate("chat/$id") } else snack.showSnackbar("Chat konnte nicht gestartet werden.") }
                        .onFailure { busy = false; snack.showSnackbar(it.asApi().userText) }
                }
            }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Chat)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton({ clip.setText(AnnotatedString(link)); scope.launch { snack.showSnackbar("Link kopiert") } }, Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Link kopieren") }
                OutlinedButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null)) }, Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Teilen") }
            }
        }
    }
}
