package app.meineschule.expressive.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Urls
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.LostItem
import app.meineschule.expressive.ui.components.*
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Fundsachen: „Gefunden“ und „Vermisst“. Anlegen nur mit canCreateLostAndFound, Bearbeiten/Löschen nur mit canEdit (Server ist maßgeblich). */
@Composable
fun LostScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val gen by app.session.generation.collectAsState()
    val school by app.session.school.collectAsState()
    val items = rememberLoader(gen) { app.repos.lost.index() }
    var tab by remember { mutableIntStateOf(0) }; var editing by remember { mutableStateOf<LostItem?>(null) }; var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<LostItem?>(null) }; var zoom by remember { mutableStateOf<LostItem?>(null) }
    val snack = remember { SnackbarHostState() }
    val canCreate = school?.canCreateLostAndFound == true
    PageScaffold("Fundsachen", "Gefunden und vermisst", onBack, snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (canCreate) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Melden") }) }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            PrimaryTabRow(tab) { listOf("Gefunden", "Vermisst").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            StateHost(items, Modifier.weight(1f), isEmpty = { l -> l.none { it.found == (tab == 0) && it.isActive } },
                empty = { EmptyState(Icons.Rounded.Inventory2, if (tab == 0) "Nichts gefunden gemeldet" else "Nichts vermisst gemeldet") }) { all ->
                val list = all.filter { it.found == (tab == 0) && it.isActive }
                LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)) {
                    itemsIndexed(list, key = { _, it -> it.id }) { i, it ->
                        GroupedItem(i, list.size, it.name, supporting = listOf(it.location, it.text).filter { s -> s.isNotBlank() }.joinToString(" · "),
                            leading = {
                                if (it.hasAttachment) Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))) { AsyncImage(app.repos.lost.imageUrl(it.id, 192), "Foto von ${it.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                else TileIcon(Icons.Rounded.Inventory2)
                            },
                            onClick = { zoom = it })
                    }
                }
            }
        }
    }
    if (creating || editing != null) LostEditor(editing, onDismiss = { creating = false; editing = null }, onDone = { creating = false; editing = null; items.reload(); scope.launch { snack.showSnackbar("Gespeichert") } })
    toDelete?.let { d -> ConfirmDialog("Eintrag löschen?", "„${d.name}“ wird dauerhaft entfernt.", "Löschen", { scope.launch { try { app.repos.lost.remove(d.id); items.update { l -> l.filterNot { x -> x.id == d.id } } } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } } }, { toDelete = null }, destructive = true) }
    zoom?.let { z -> LostDetailSheet(z, onDismiss = { zoom = null }, onEdit = { zoom = null; editing = z }, onDelete = { zoom = null; toDelete = z }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LostDetailSheet(z: LostItem, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val app = LocalApp.current; val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (z.hasAttachment) AsyncImage(app.repos.lost.imageUrl(z.id, 1024), "Foto von ${z.name}", Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(24.dp)), contentScale = ContentScale.Fit)
            Text(z.name, style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (z.found) "Gefunden" else "Vermisst") }, leadingIcon = { Icon(if (z.found) Icons.Rounded.Inventory2 else Icons.Rounded.Search, null, Modifier.size(18.dp)) })
                if (!z.isActive) AssistChip(onClick = {}, label = { Text("Inaktiv") })
            }
            @Composable fun field(k: String, v: String?) { if (!v.isNullOrBlank()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) { Text(k, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyLarge) } }
            field(if (z.found) "Fundort" else "Zuletzt gesehen / Ort", z.location); field("Beschreibung", z.text); field("Gemeldet von", z.creator)
            if (z.canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onEdit, Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Edit, null); Spacer(Modifier.width(8.dp)); Text("Bearbeiten") }
                OutlinedButton(onDelete, Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(8.dp)); Text("Löschen") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LostEditor(edit: LostItem?, onDismiss: () -> Unit, onDone: () -> Unit) {
    val app = LocalApp.current; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(edit?.name.orEmpty()) }; var location by remember { mutableStateOf(edit?.location.orEmpty()) }; var text by remember { mutableStateOf(edit?.text.orEmpty()) }
    var found by remember { mutableStateOf(edit?.found ?: true) }; var active by remember { mutableStateOf(edit?.isActive ?: true) }
    var photo by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { val (b, m, n) = Files.read(ctx, u); Urls.validateUpload(b.size.toLong(), m, imageOnly = true)?.let { err = it } ?: run { photo = Triple(b, m!!, n); err = null } }
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (edit == null) "Fundsache melden" else "Eintrag bearbeiten", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(found, { found = true }, { Text("Gefunden") }, enabled = edit != null); FilterChip(!found, { found = false }, { Text("Vermisst") }, enabled = edit != null)
            }
            if (edit == null) Text("Neue Einträge werden zunächst nur mit Name und Ort angelegt; Art, Beschreibung und Foto kannst du anschließend ergänzen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Bezeichnung") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), label = { Text("Fundort / Ort") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Beschreibung") }, minLines = 2, shape = RoundedCornerShape(16.dp))
            if (edit != null) Row(verticalAlignment = Alignment.CenterVertically) { Switch(active, { active = it }); Spacer(Modifier.width(12.dp)); Text("Eintrag ist aktiv") }
            OutlinedButton({ picker.launch("image/*") }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text(photo?.third ?: "Foto hinzufügen") }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LoadingButton("Speichern", busy, {
                busy = true; err = null
                scope.launch {
                    try {
                        val base = edit ?: app.repos.lost.create(name.trim(), location.trim())
                        val next = base.copy(name = name.trim(), location = location.trim(), text = text.trim(), found = found, isActive = active)
                        if (edit != null || text.isNotBlank() || !found) app.repos.lost.update(next)
                        photo?.let { app.repos.lost.attach(base.id, it.first, it.second) }
                        onDone()
                    } catch (e: Throwable) { err = e.asApi().userText } finally { busy = false }
                }
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && location.isNotBlank(), icon = Icons.Rounded.Check)
        }
    }
}
