package app.meineschule.expressive.ui.screens

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
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch

/** Profil und Schulwechsel. Beim Wechsel werden alle schulbezogenen Daten verworfen (Session.generation), bevor neue geladen werden. */
@Composable
fun ProfileScreen(nav: NavHostController) {
    val app = LocalApp.current; val scope = rememberCoroutineScope()
    val profile by app.session.profile.collectAsState(); val tag by app.session.tag.collectAsState(); val school by app.session.school.collectAsState()
    var switching by remember { mutableStateOf<String?>(null) }; var confirm by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val p = profile
    PageScaffold("Profil", null, { nav.popBackStack() }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        if (p == null) { EmptyState(Icons.Rounded.Person, "Kein Profil geladen"); return@PageScaffold }
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PersonAvatar(p.displayName, p.userId.takeIf { it.isNotBlank() }?.let { app.repos.school.avatarUrl(it, 160) }, 72.dp, key = p.userId)
                    Column { Text(p.displayName, style = MaterialTheme.typography.headlineSmall); school?.roles?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
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
