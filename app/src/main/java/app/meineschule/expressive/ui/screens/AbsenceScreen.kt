package app.meineschule.expressive.ui.screens

import app.meineschule.expressive.ui.theme.MsdShapes
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.*
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun AbsenceScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    val index = rememberLoader(gen) { app.repos.absence.index() }
    var idx by remember { mutableIntStateOf(0) }
    val students = (index.state as? UiState.Ready)?.data?.students?.ifEmpty { null } ?: school?.students.orEmpty(); val student = students.getOrNull(idx)
    val reports = rememberLoader(gen, student?.id) { student?.let { app.repos.absence.reports(it.id) } ?: emptyList() }
    var editing by remember { mutableStateOf<AbsenceReport?>(null) }; var creating by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<AbsenceReport?>(null) }
    val snack = remember { SnackbarHostState() }
    val cats = (index.state as? UiState.Ready)?.data?.categories.orEmpty()

    PageScaffold("Abwesenheiten", student?.name, onBack, snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (school?.canCreateAbsences == true && student != null) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Abwesenheit melden") }, shape = MsdShapes.Fab) }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            if (students.size > 1) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { students.forEachIndexed { i, s -> FilterChip(i == idx, { idx = i }, { Text(s.name) }) } }
            StateHost(reports, Modifier.weight(1f), isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.EventAvailable, "Keine Abwesenheitsmeldungen", "Hier erscheinen gemeldete Abwesenheiten.") }) { list ->
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp)) {
                    itemsIndexed(list.sortedByDescending { Dates.parse(it.from) }) { i, r ->
                        val range = listOfNotNull(Dates.date(r.from)?.let(Dates::dayShort), Dates.date(r.to)?.takeIf { it != Dates.date(r.from) }?.let(Dates::dayShort)).joinToString(" – ")
                        GroupedItem(i, list.size, cats.firstOrNull { it.id == r.categoryId }?.name ?: "Abwesenheit", supporting = listOfNotNull(range, if (r.fullDay) "ganztägig" else r.fromLesson?.let { "Stunde $it${r.toLesson?.let { t -> "–$t" } ?: ""}" }, r.text).joinToString(" · "),
                            leading = { TileIcon(Icons.Rounded.EventBusy) }, onClick = if (school?.canCreateAbsences == true) ({ editing = r }) else null)
                    }
                }
            }
        }
    }
    if (creating || editing != null) AbsenceForm(student!!, index, editing, onDismiss = { creating = false; editing = null }, onSaved = { creating = false; editing = null; reports.reload(); scope.launch { snack.showSnackbar("Gespeichert") } }, onDelete = { deleting = editing; editing = null })
    deleting?.let { r -> ConfirmDialog("Meldung löschen?", "Die Abwesenheitsmeldung wird gelöscht, soweit du dazu berechtigt bist.", "Löschen", { scope.launch { try { app.repos.absence.remove(r.studentId, r.id!!); reports.reload() } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } } }, { deleting = null }, destructive = true) }
}

@Composable private fun AbsenceForm(student: Student, index: Loader<AbsenceIndex>, edit: AbsenceReport?, onDismiss: () -> Unit, onSaved: () -> Unit, onDelete: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val ctx = LocalContext.current; val cs = MaterialTheme.colorScheme
    val data = (index.state as? UiState.Ready)?.data
    var cat by remember { mutableStateOf(edit?.categoryId ?: data?.categories?.firstOrNull()?.id) }
    var from by remember { mutableStateOf(Dates.date(edit?.from) ?: LocalDate.now()) }; var to by remember { mutableStateOf(Dates.date(edit?.to) ?: from) }
    var full by remember { mutableStateOf(edit?.fullDay ?: true) }; var fromL by remember { mutableStateOf(edit?.fromLesson) }; var toL by remember { mutableStateOf(edit?.toLesson) }
    var text by remember { mutableStateOf(edit?.text.orEmpty()) }; var file by remember { mutableStateOf<Triple<ByteArray, String, String>?>(null) }
    var pickFrom by remember { mutableStateOf(false) }; var pickTo by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) { val (b, m, n) = Files.read(ctx, u); Urls.validateUpload(b.size.toLong(), m, imageOnly = true)?.let { err = it } ?: run { file = Triple(b, m!!, n) } } }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (edit == null) "Abwesenheit melden" else "Meldung bearbeiten", style = MaterialTheme.typography.headlineSmall); Text(student.name, color = cs.onSurfaceVariant)
            Dropdown("Kategorie", data?.categories.orEmpty().map { it.id to it.name }, cat) { cat = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickFrom = true }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Von ${Dates.dayShort(from)}") }
                OutlinedButton({ pickTo = true }, Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) { Text("Bis ${Dates.dayShort(to)}") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Ganztägig", Modifier.weight(1f)); Switch(full, { full = it }) }
            if (!full) { val ls = data?.lessons.orEmpty().map { (it.id.toIntOrNull() ?: 0).toString() to it.name }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Box(Modifier.weight(1f)) { Dropdown("Von Stunde", ls, fromL?.toString()) { fromL = it.toInt() } }; Box(Modifier.weight(1f)) { Dropdown("Bis Stunde", ls, toL?.toString()) { toL = it.toInt() } } } }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Beschreibung") }, minLines = 3, shape = RoundedCornerShape(16.dp))
            OutlinedButton({ picker.launch("image/*") }, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.AttachFile, null); Spacer(Modifier.width(8.dp)); Text(file?.let { Urls.safeFileName(it.third) } ?: "Foto anhängen (optional)") }
            err?.let { Text(it, color = cs.error) }
            if (to.isBefore(from)) Text("Das Enddatum liegt vor dem Startdatum.", color = cs.error)
            LoadingButton("Speichern", busy, {
                busy = true; err = null
                scope.launch {
                    try {
                        val r = AbsenceReport(edit?.id, student.id, cat, "${from}T00:00:00", "${to}T00:00:00", if (full) null else fromL, if (full) null else toL, full, text.ifBlank { null }, false, null, null)
                        val id = if (edit == null) app.repos.absence.create(r).id else { app.repos.absence.update(r); edit.id }
                        if (file != null && id != null) app.repos.absence.attach(student.id, id, file!!.first, file!!.second)
                        onSaved()
                    } catch (e: Throwable) { err = e.asApi().userText } finally { busy = false }
                }
            }, Modifier.fillMaxWidth(), enabled = cat != null && !to.isBefore(from), icon = Icons.Rounded.Check)
            if (edit != null) TextButton(onDelete, Modifier.fillMaxWidth()) { Text("Meldung löschen", color = cs.error) }
        }
    }
    if (pickFrom) DateDialog(from, { from = it; if (to.isBefore(it)) to = it; pickFrom = false }) { pickFrom = false }
    if (pickTo) DateDialog(to, { to = it; pickTo = false }) { pickTo = false }
}

@Composable fun DateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val st = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = { TextButton({ st.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text("OK") } }, dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } }) { DatePicker(st) }
}

@Composable fun Dropdown(label: String, options: List<Pair<String, String>>, value: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(options.firstOrNull { it.first == value }?.second.orEmpty(), {}, readOnly = true, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(16.dp), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) { options.forEach { (k, v) -> DropdownMenuItem({ Text(v) }, { open = false; onPick(k) }) } }
    }
}
