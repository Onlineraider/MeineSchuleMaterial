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

@Composable internal fun AbsenceForm(student: Student, index: Loader<AbsenceIndex>, edit: AbsenceReport?, onDismiss: () -> Unit, onSaved: () -> Unit, onDelete: () -> Unit) {
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
