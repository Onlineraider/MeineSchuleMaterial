package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.DeepLinks
import app.meineschule.expressive.core.Urls
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.Person
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch

/** Profil und Schulwechsel. Beim Wechsel werden alle schulbezogenen Daten verworfen (Session.generation), bevor neue geladen werden. */
@Composable
fun ProfileScreen(nav: NavHostController) {
    val app = LocalApp.current; val scope = rememberCoroutineScope()
    val profile by app.session.profile.collectAsState(); val tag by app.session.tag.collectAsState(); val school by app.session.school.collectAsState()
    var switching by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current; val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val shareBase by app.prefs.shareBase.collectAsState(); val gen by app.session.generation.collectAsState()
    val kids = school?.students.orEmpty()
    val infos = rememberLoader(gen, kids.map { it.id }) { kids.map { st -> st to runCatching { app.repos.school.student(st.id) }.getOrNull() } }
    fun key(x: Person): String? = x.userId?.let { "U:USER:$it" } ?: x.id?.takeIf { x.type != null }?.let { "P:${x.type!!.uppercase()}:$it" }
    val p = profile
    var zoom by remember { mutableStateOf(false) }
    if (zoom && p != null) ImageZoomDialog(app.repos.school.avatarUrl(p.userId, 512), p.displayName) { zoom = false }
    PageScaffold("Profil", null, { nav.popBackStack() }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        if (p == null) { EmptyState(Icons.Rounded.Person, "Kein Profil geladen"); return@PageScaffold }
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.clickable(enabled = p.userId.isNotBlank()) { zoom = true }) { PersonAvatar(p.displayName, p.userId.takeIf { it.isNotBlank() }?.let { app.repos.school.avatarUrl(it, 160) }, 72.dp, key = p.userId) }
                    Column { Text(p.displayName, style = MaterialTheme.typography.headlineSmall); school?.roles?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
            }
            profile?.takeIf { it.userId.isNotBlank() }?.let { me ->
                val link = DeepLinks.profileUrl(shareBase, me.userId, me.displayName, app.origin, tag, school?.name)
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Dein Anschreib-Link", style = MaterialTheme.typography.titleSmall)
                        Text("Wer diesen Link öffnet (App oder Web), kann dir direkt schreiben.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(link)); scope.launch { snack.showSnackbar("Link kopiert") } }, Modifier.weight(1f)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Kopieren") }
                            Button({ ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link), null)) }, Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp)); Text("Teilen") }
                        }
                    }
                }
            }
            (infos.state as? UiState.Ready)?.data.orEmpty().forEach { (st, info) ->
                if (info == null) return@forEach
                item { SectionTitle(st.name + (st.cls?.let { " · Klasse $it" } ?: "")) }
                val groups = buildList { add((info.parents.title?.takeIf { it.isNotBlank() } ?: "Eltern") to info.parents.persons); add((info.partners.title?.takeIf { it.isNotBlank() } ?: "Partner") to info.partners.persons)
                    info.classInfo.forEach { add((it.title?.takeIf { t -> t.isNotBlank() } ?: "Klasse") to it.persons) }; info.groupInfo.forEach { add((it.title?.takeIf { t -> t.isNotBlank() } ?: "Gruppe") to it.persons) }
                    info.subjects.forEach { add((it.title ?: "Fach") to it.persons) } }.filter { it.second.isNotEmpty() }
                groups.forEach { (title, persons) ->
                    item { Text(title, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    itemsIndexed(persons) { i, x ->
                        GroupedItem(i, persons.size, x.name, supporting = x.type, leading = { PersonAvatar(x.name, x.userId?.let { u -> app.repos.school.avatarUrl(u) }, 40.dp, key = x.userId ?: x.name) },
                            trailing = { if (key(x) != null) IconButton({ nav.navigate("newchat?prefill=${Urls.seg("${key(x)}=${x.name}")}") }) { Icon(Icons.Rounded.Chat, "Chat starten") } },
                            onClick = key(x)?.let { k -> { nav.navigate("person?k=${Urls.seg(k)}&n=${Urls.seg(x.name)}" + (x.userId?.let { u -> "&u=${Urls.seg(u)}" } ?: "")) } })
                    }
                }
            }
            item { SectionTitle("Schulen") }
            itemsIndexed(p.schools) { i, s ->
                val active = s.tag == tag
                GroupedItem(i, p.schools.size, s.name, supporting = if (active) "Aktiv" else "Zum Wechseln tippen", leading = { TileIcon(Icons.Rounded.School, container = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, content = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer) },
                    trailing = { if (switching == s.tag) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp) else if (active) Icon(Icons.Rounded.CheckCircle, "Aktive Schule") },
                    container = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    onClick = if (active || switching != null) null else ({ confirm = s.tag }))
            }
            if (p.schools.size < 2) item { Text("Dein Konto ist nur einer Schule zugeordnet.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    confirm?.let { t ->
        val name = p?.schools?.firstOrNull { it.tag == t }?.name ?: t
        ConfirmDialog("Schule wechseln?", "Alle Daten der aktuellen Schule werden aus der Anzeige entfernt und für „$name“ neu geladen.", "Wechseln",
            { scope.launch { switching = t; try { app.session.switchSchool(t) } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } finally { switching = null } } }, { confirm = null })
    }
}
