package app.meineschule.expressive.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Urls
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.InfoGroup
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch

/**
 * Schülerdaten. Fachlehrkräfte werden hierarchisch aus subjectInfo.details[] dargestellt (Fach → Personen).
 * Eigenschaften (studentProperties) sind in der App nur lesbar; Änderungen laufen über die Web-Oberfläche mit One-Time-Token.
 */
@Composable
fun StudentScreen(id: String, onBack: () -> Unit) {
    val app = LocalApp.current; val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val gen by app.session.generation.collectAsState()
    val school by app.session.school.collectAsState()
    val info = rememberLoader(gen, id) { app.repos.school.student(id) }
    val snack = remember { SnackbarHostState() }
    val name = (info.state as? UiState.Ready)?.data?.student?.name ?: school?.students?.firstOrNull { it.id == id }?.name ?: "Schüler"
    PageScaffold(name, (info.state as? UiState.Ready)?.data?.student?.cls?.let { "Klasse $it" }, onBack, snackbarHost = { SnackbarHost(snack) }) { pad ->
        StateHost(info, Modifier.padding(top = pad.calculateTopPadding())) { s ->
            val tag = app.session.tag.value
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                fun group(title: String, g: InfoGroup) {
                    if (g.persons.isEmpty()) return
                    item { SectionTitle(g.title?.takeIf { it.isNotBlank() } ?: title) }
                    itemsIndexed(g.persons) { i, p -> GroupedItem(i, g.persons.size, p.name, supporting = p.type, leading = { PersonAvatar(p.name, p.userId?.let { u -> app.repos.school.avatarUrl(u) }, 44.dp, key = p.userId ?: p.name) }) }
                }
                group("Eltern", s.parents); group("Partner", s.partners)
                s.classInfo.forEach { group("Klasse", it) }
                s.groupInfo.forEach { group("Gruppe", it) }
                if (s.subjects.isNotEmpty()) {
                    item { SectionTitle("Fachlehrkräfte") }
                    s.subjects.forEach { sub ->
                        item { Text(sub.title ?: "Fach", Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                        itemsIndexed(sub.persons) { i, p -> GroupedItem(i, sub.persons.size, p.name, supporting = p.type, leading = { PersonAvatar(p.name, p.userId?.let { u -> app.repos.school.avatarUrl(u) }, 40.dp, key = p.userId ?: p.name) }) }
                    }
                }
                if (s.properties.isNotEmpty()) {
                    item { SectionTitle("Eigenschaften") }
                    itemsIndexed(s.properties) { i, p -> GroupedItem(i, s.properties.size, p.name, supporting = p.value ?: "—", leading = { TileIcon(Icons.Rounded.Tune) }) }
                    if (school?.hasStudentProperties == true && tag != null) item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoBanner("Eigenschaften lassen sich nur im Web bearbeiten. Du wirst mit einem Einmal-Token angemeldet; er wird nicht gespeichert.")
                            FilledTonalButton({
                                scope.launch {
                                    try {
                                        val url = app.repos.school.webLoginUrl("explorer/studentproperties/details/$id", app.origin, tag)
                                        // Ziel ist die eigene Origin; trotzdem wie jeden externen Link validieren.
                                        val safe = Urls.safeExternal(url) ?: error("Ungültiger Link")
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) }
                                }
                            }, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text("Im Web bearbeiten") }
                        }
                    }
                }
                if (s.parents.persons.isEmpty() && s.partners.persons.isEmpty() && s.classInfo.isEmpty() && s.groupInfo.isEmpty() && s.subjects.isEmpty() && s.properties.isEmpty())
                    item { EmptyState(Icons.Rounded.Badge, "Keine Angaben", "Für diese Person liefert der Server keine weiteren Daten.") }
            }
        }
    }
}
