package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.ui.components.*

private data class Note(val title: String, val text: String, val icon: ImageVector, val route: String)

/**
 * Benachrichtigungen = Hinweise aus School/State (ungelesene Chats, unbestätigte Kalender-/Medieneinträge).
 * Eine eigene Benachrichtigungs-Historie der Plattform ist in der Doku nicht beschrieben und wird nicht erfunden.
 */
@Composable
fun NotificationsScreen(nav: NavHostController) {
    val app = LocalApp.current; val gen by app.session.generation.collectAsState(); val state by app.session.state.collectAsState()
    val loader = rememberLoader(gen) { app.session.refreshState(); app.session.state.value }
    PageScaffold("Hinweise", "Das braucht deine Aufmerksamkeit", { nav.popBackStack() }) { pad ->
        StateHost(loader, Modifier.padding(top = pad.calculateTopPadding()), skeleton = { SkeletonList(3) }) { initial ->
            val s = state ?: initial
            val notes = buildList {
                if (s != null) {
                    if (s.unreadChatMessageCount > 0 || s.unreadChatIds.isNotEmpty()) add(Note("Ungelesene Nachrichten", "${s.unreadChatMessageCount} Nachricht(en) in ${s.unreadChatIds.size} Chat(s)", Icons.Rounded.Forum, "chats"))
                    if (s.notConfirmedCalendar.isNotEmpty()) add(Note("Termine bestätigen", "${s.notConfirmedCalendar.size} Termin(e) warten auf deine Antwort", Icons.Rounded.EventAvailable, "calendar"))
                    if (s.notConfirmedMedia.isNotEmpty()) add(Note("Beiträge bestätigen", "${s.notConfirmedMedia.size} Beitrag/Beiträge warten auf Bestätigung", Icons.Rounded.PermMedia, "media"))
                }
            }
            if (notes.isEmpty()) EmptyState(Icons.Rounded.NotificationsNone, "Alles erledigt", "Keine offenen Hinweise.")
            else LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                itemsIndexed(notes) { i, n -> GroupedItem(i, notes.size, n.title, supporting = n.text, leading = { TileIcon(n.icon, container = MaterialTheme.colorScheme.tertiaryContainer, content = MaterialTheme.colorScheme.onTertiaryContainer) },
                    trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }, onClick = { nav.navigate(n.route) }) }
            }
        }
    }
}
