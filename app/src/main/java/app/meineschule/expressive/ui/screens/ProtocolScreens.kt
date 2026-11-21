package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch

/** Protokolle: nur Lesen und Anhänge laden – Anlegen ist in der API nicht vorgesehen. */
@Composable
fun ProtocolsScreen(nav: NavHostController) {
    val app = LocalApp.current; val gen by app.session.generation.collectAsState()
    val list = rememberLoader(gen) { app.repos.protocols.index() }
    PageScaffold("Protokolle", "Sitzungen und Tagesordnungen", { nav.popBackStack() }) { pad ->
        StateHost(list, Modifier.padding(top = pad.calculateTopPadding()), isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.Description, "Keine Protokolle", "Für dich sind keine Protokolle freigegeben.") }) { l ->
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                itemsIndexed(l, key = { _, it -> it.id }) { i, p ->
                    GroupedItem(i, l.size, p.title, supporting = Dates.date(p.date)?.let { Dates.dayLong(it) }, leading = { TileIcon(Icons.Rounded.Description) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }, onClick = { nav.navigate("protocol/${p.id}") })
                }
            }
        }
    }
}

@Composable
fun ProtocolDetailScreen(id: String, onBack: () -> Unit) {
    val app = LocalApp.current; val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val gen by app.session.generation.collectAsState()
    val detail = rememberLoader(gen, id) { app.repos.protocols.detail(id) }
    val snack = remember { SnackbarHostState() }
    val title = (detail.state as? UiState.Ready)?.data?.item?.title ?: "Protokoll"
    PageScaffold(title, null, onBack, snackbarHost = { SnackbarHost(snack) }) { pad ->
        StateHost(detail, Modifier.padding(top = pad.calculateTopPadding())) { d ->
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                Dates.date(d.item.date)?.let { item { Text(Dates.dayLong(it), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                if (!d.agenda.isNullOrBlank()) { item { SectionTitle("Tagesordnung") }; item { Text(plain(d.agenda), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyLarge) } }
                if (!d.text.isNullOrBlank()) { item { SectionTitle("Protokoll") }; item { Text(plain(d.text), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyLarge) } }
                if (d.attendees.isNotEmpty()) { item { SectionTitle("Teilnehmende") }; itemsIndexed(d.attendees) { i, a -> GroupedItem(i, d.attendees.size, a, leading = { PersonAvatar(a, size = 40.dp) }) } }
                if (d.attachments.isNotEmpty()) {
                    item { SectionTitle("Anhänge") }
                    itemsIndexed(d.attachments) { i, a ->
                        GroupedItem(i, d.attachments.size, a.name, leading = { TileIcon(Icons.Rounded.AttachFile) }, trailing = { Icon(Icons.Rounded.Download, "Laden") },
                            onClick = { scope.launch { try { Files.open(ctx, app.api.bytes(app.repos.protocols.downloadUrl(id, a.id)), a.name) } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } } })
                    }
                }
            }
        }
    }
}

/** Serverseitiger Text kann HTML enthalten → nie rendern, sondern strikt zu reinem Text reduzieren. */
internal fun plain(html: String?): String {
    val withBreaks = (html ?: "").replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</div>"), "\n")
    val cleaned = org.jsoup.Jsoup.clean(withBreaks, "", org.jsoup.safety.Safelist.none(), org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
    return org.jsoup.parser.Parser.unescapeEntities(cleaned, false).lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
}
