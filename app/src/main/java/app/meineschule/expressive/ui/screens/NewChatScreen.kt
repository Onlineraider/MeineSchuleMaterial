package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Neuer Chat / Chat bearbeiten. Empfänger liegen im Serverformat `key=Anzeigename` vor (wie in der Original-App);
 * Suche lokal (Empfängerindex + bekannte Kontakte) und per SearchRecipients, Duplikate (Nutzer- vs. Personenkennung) werden zusammengeführt.
 * [prefill] = ein oder mehrere `key=Name`-Records (z. B. aus einem Kontaktprofil oder Link).
 */
@Composable
fun NewChatScreen(nav: NavHostController, editId: String?, prefill: List<String>) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme; val contacts = app.contacts
    var name by remember { mutableStateOf("") }; var reuse by remember { mutableStateOf(true) }; var limited by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(prefill.map { contacts.fill(it) }) }
    var authors by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var q by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<RecipientItem>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var manual by remember { mutableStateOf(false) }; var manualText by remember { mutableStateOf("") }
    var loadingEdit by remember { mutableStateOf(editId != null) }

    LaunchedEffect(editId) {
        contacts.loadIndex()
        if (editId != null) runCatching {
            val d = app.repos.messenger.editData(editId)
            val rec = d.arr("recipients").mapNotNull { (it.raw as? kotlinx.serialization.json.JsonPrimitive)?.content }; rec.forEach(contacts::addRecord); contacts.resolveNames()
            name = d.str("name").orEmpty(); limited = d.bool("authorsLimited") == true; selected = rec.map(contacts::fill); authors = d.arr("authors").mapNotNull { it.raw }
        }.onFailure { error = it.asApi().userText }
        loadingEdit = false
    }
    LaunchedEffect(q) {
        val term = q.trim(); if (term.isEmpty()) { results = emptyList(); return@LaunchedEffect }
        delay(250); results = contacts.search(term) { results = it }
    }
    fun has(it: RecipientItem) = selected.any { s -> val k = parseRecord(s).first; contacts.canon(k) == contacts.canon(it.key) || it.alts.any { a -> contacts.canon(a.key) == contacts.canon(k) } }
    fun add(rec: String) { val (k, t) = parseRecord(rec); contacts.add(k, t); selected = selected + rec; q = ""; results = emptyList() }
    fun submit() {
        if (selected.isEmpty()) { error = "Mindestens ein Empfänger ist nötig."; return }
        busy = true; error = null
        scope.launch {
            try {
                if (editId != null) { app.repos.messenger.editSave(editId, selected, name.trim().ifBlank { null }, limited, authors); nav.popBackStack() }
                else { val id = app.repos.messenger.create(name.trim().ifBlank { null }, selected, reuse, limited) ?: throw IllegalStateException("Kein Chat erhalten"); nav.popBackStack(); nav.navigate("chat/$id") }
            } catch (e: Throwable) { error = e.asApi().userText } finally { busy = false }
        }
    }

    PageScaffold(if (editId != null) "Chat bearbeiten" else "Neuer Chat", null, { nav.popBackStack() }) { pad ->
        if (loadingEdit) { Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { LoadingIndicator() }; return@PageScaffold }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Name (optional)") }, singleLine = true, shape = RoundedCornerShape(16.dp)) }
            item { SectionTitle("Empfänger") }
            if (selected.isNotEmpty()) item {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selected.forEach { r -> val (k, t) = parseRecord(r); InputChip(true, { selected = selected - r }, { Column { Text(t.ifBlank { contacts.name(k) ?: k }); Text(kindLabel(k), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) } }, trailingIcon = { Icon(Icons.Rounded.Close, "Entfernen", Modifier.size(18.dp)) }) }
                }
            }
            item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), placeholder = { Text("Name, Klasse oder Rolle suchen …") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = CircleShape,
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Rounded.Close, "Leeren") } }) }
            val shown = results.filter { !has(it) }.sortedBy { it.text.lowercase() }.take(40)
            if (q.isNotBlank() && shown.isEmpty()) item { Text("Keine Treffer.", Modifier.padding(16.dp), color = cs.onSurfaceVariant) }
            itemsIndexed(shown, key = { _, r -> r.key }) { i, r ->
                if (r.alts.size > 1) GroupedItem(i, shown.size, r.text.ifBlank { r.key }, supporting = listOfNotNull(r.info, "mehrere Kennungen – bitte wählen").joinToString(" · "), leading = { PersonAvatar(r.text, key = r.key) },
                    trailing = { Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { r.alts.sortedByDescending { it.key.startsWith("U:", true) }.forEach { a -> AssistChip({ add("${a.key}=${a.text.ifBlank { r.text }}") }, { Text(kindLabel(a.key)) }) } } })
                else GroupedItem(i, shown.size, r.text.ifBlank { r.key }, supporting = listOfNotNull(r.info, r.kind).joinToString(" · "), leading = { PersonAvatar(r.text, key = r.key) }, trailing = { Icon(Icons.Rounded.Add, "Hinzufügen") }, onClick = { add("${r.key}=${r.text}") })
            }
            item { TextButton({ manual = !manual }, Modifier.padding(horizontal = 8.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Manuell per ID hinzufügen") } }
            if (manual) item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(manualText, { manualText = it }, Modifier.fillMaxWidth(), label = { Text("Nutzer-ID oder U:USER:{id}=Name") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    FilledTonalButton({
                        val l = manualText.trim(); val m = Regex("^([0-9a-f-]{36})\\s*[=,;]?\\s*(.*)$", RegexOption.IGNORE_CASE).find(l)
                        val v = when { m != null -> "U:USER:${m.groupValues[1]}=${m.groupValues[2]}"; Regex("^\\w+:\\w+:\\S+").containsMatchIn(l) -> if (l.contains('=')) l else "$l="; else -> null }
                        if (v == null) error = "Ungültige ID" else if (selected.any { contacts.canon(parseRecord(it).first) == contacts.canon(parseRecord(v).first) }) error = "Bereits ausgewählt." else { add(contacts.fill(v)); manualText = ""; error = null; scope.launch { contacts.resolveNames(); selected = selected.map(contacts::fill) } }
                    }, shapes = ButtonDefaults.shapes()) { Text("Hinzufügen") }
                }
            }
            if (editId == null) item { SwitchLine("Bestehenden Einzelchat wiederverwenden", reuse) { reuse = it } }
            item { SwitchLine("Nur Chat-Ersteller darf schreiben", limited) { limited = it } }
            error?.let { e -> item { Text(e, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), color = cs.error) } }
            item { LoadingButton(if (editId != null) "Speichern" else "Erstellen", busy, { submit() }, Modifier.fillMaxWidth().padding(16.dp), enabled = selected.isNotEmpty(), icon = Icons.Rounded.Check) }
        }
    }
}

@Composable private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { Text(text, Modifier.weight(1f)); Switch(checked, onChange) }
}
