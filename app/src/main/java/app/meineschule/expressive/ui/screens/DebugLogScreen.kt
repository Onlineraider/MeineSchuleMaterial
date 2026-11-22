package app.meineschule.expressive.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.meineschule.expressive.net.DebugEntry
import app.meineschule.expressive.net.DebugLog
import app.meineschule.expressive.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug: protokollierte Server-Antworten (Rohdaten) ansehen, kopieren, teilen. Nur im Debug-Modus erreichbar. */
@Composable
fun DebugLogScreen(nav: NavHostController) {
    val entries by DebugLog.entries.collectAsState(); val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    var open by remember { mutableStateOf<DebugEntry?>(null) }; var filter by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.GERMAN) }
    val shown = entries.filter { filter.isBlank() || it.url.contains(filter, true) || it.response?.contains(filter, true) == true }
    fun share(text: String) = ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    PageScaffold("Antwort-Log", "${entries.size} Einträge · nur Arbeitsspeicher", { nav.popBackStack() }, actions = {
        IconButton({ share(shown.joinToString("\n\n==========\n\n") { DebugLog.asText(it) }) }) { Icon(Icons.Rounded.Share, "Alles teilen") }
        IconButton({ DebugLog.clear() }) { Icon(Icons.Rounded.DeleteSweep, "Leeren") }
    }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            InfoBanner("Rohdaten können persönliche Daten enthalten. Tokens, Passwörter und Anmelde-Aufrufe werden nie protokolliert. Teile Einträge nur gezielt.", Icons.Rounded.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp), singleLine = true, label = { Text("Filter (URL oder Inhalt)") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
            if (shown.isEmpty()) EmptyState(Icons.Rounded.DataObject, "Noch keine Einträge", "Rufe eine Seite auf (z. B. Stundenplan) und komm hierher zurück.")
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, e -> e.id }) { i, e ->
                    val bad = e.status !in 200..299
                    GroupedItem(i, shown.size, "${e.method} ${e.url.substringAfter("//").substringAfter('/').take(90)}", supporting = "${fmt.format(Date(e.time))} · ${if (e.status < 0) "Fehler" else e.status} · ${e.ms} ms · ${e.response?.length ?: 0} Zeichen",
                        leading = { TileIcon(if (bad) Icons.Rounded.ErrorOutline else Icons.Rounded.DataObject, container = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) }, onClick = { open = e })
                }
            }
        }
    }
    open?.let { e ->
        val text = DebugLog.asText(e)
        AlertDialog({ open = null }, title = { Text("${e.method} ${e.status}") }, confirmButton = { TextButton({ open = null }) { Text("Schließen") } },
            dismissButton = { Row { TextButton({ clip.setText(AnnotatedString(text)) }) { Text("Kopieren") }; TextButton({ share(text) }) { Text("Teilen") } } },
            text = { SelectionContainer { Text(text.take(40_000) + if (text.length > 40_000) "\n… (gekürzt – Kopieren/Teilen enthält alles)" else "", Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) } })
    }
}
