package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun CareScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme; val gen by app.session.generation.collectAsState()
    val index = rememberLoader(gen) { app.repos.care.index() }
    var date by remember { mutableStateOf(LocalDate.now()) }; var idx by remember { mutableIntStateOf(0) }; var showPicker by remember { mutableStateOf(false) }
    val entries = rememberLoader(gen, date) { app.repos.care.byDate(date) }
    val snack = remember { SnackbarHostState() }
    PageScaffold("Betreuung", "Abholzeiten und Optionen", onBack, snackbarHost = { SnackbarHost(snack) }) { pad ->
        StateHost(index, Modifier.padding(top = pad.calculateTopPadding()), isEmpty = { it.students.isEmpty() }, empty = { EmptyState(Icons.Rounded.Schedule, "Keine Betreuung verfügbar", "Für dein Konto sind keine Betreuungsdaten freigegeben.") }) { ix ->
            val student = ix.students.getOrNull(idx) ?: return@StateHost
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ix.students.size > 1) item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ix.students.size) { i -> FilterChip(i == idx, { idx = i }, { Text(ix.students[i].name) }) } } }
                item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0..13).map { LocalDate.now().plusDays(it.toLong()) }) { d -> val sel = d == date
                        Column(Modifier.clip(RoundedCornerShape(if (sel) 20.dp else 28.dp)).background(if (sel) cs.primary else cs.surfaceContainerHigh).clickable { date = d }.padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(Dates.weekday(d), style = MaterialTheme.typography.labelMedium, color = if (sel) cs.onPrimary else cs.onSurfaceVariant); Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleLarge, color = if (sel) cs.onPrimary else cs.onSurface) } }
                    item { IconButton({ showPicker = true }) { Icon(Icons.Rounded.CalendarMonth, "Anderes Datum") } } } }
                item { SectionTitle(Dates.dayLong(date)) }
                val current = (entries.state as? UiState.Ready)?.data?.firstOrNull { it.studentId == null || it.studentId == student.id }
                item { CareEditor(ix, student, date, current, onSaved = { entries.reload(); scope.launch { snack.showSnackbar("Gespeichert") } }, onDeleted = { entries.reload(); scope.launch { snack.showSnackbar("Eintrag gelöscht") } }, onError = { scope.launch { snack.showSnackbar(it) } }) }
            }
        }
    }
    if (showPicker) DateDialog(date, { date = it; showPicker = false }) { showPicker = false }
}

@Composable private fun CareEditor(ix: CareIndex, student: Student, date: LocalDate, current: CareEntry?, onSaved: () -> Unit, onDeleted: () -> Unit, onError: (String) -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    var option by remember(current, date) { mutableStateOf(current?.optionId) }; var memo by remember(current, date) { mutableStateOf(current?.memo.orEmpty()) }; var future by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    val canEdit = current?.canEdit != false
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ix.options.isEmpty()) EmptyState(Icons.Rounded.Schedule, "Keine Abholoptionen", "Es sind keine Optionen hinterlegt.")
        ix.options.forEach { o -> val sel = option == o.id
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(if (sel) 28.dp else 16.dp)).background(if (sel) cs.primaryContainer else cs.surfaceContainerLow).clickable(enabled = canEdit) { option = o.id }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sel, { option = o.id }, enabled = canEdit); Spacer(Modifier.width(8.dp)); Text(o.name, style = MaterialTheme.typography.titleMedium, color = if (sel) cs.onPrimaryContainer else cs.onSurface) } }
        OutlinedTextField(memo, { memo = it }, Modifier.fillMaxWidth(), label = { Text("Nachricht an die Betreuung") }, enabled = canEdit, minLines = 2, shape = RoundedCornerShape(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(future, { future = it }, enabled = canEdit); Text("Als Standard für künftige Tage übernehmen") }
        if (!canEdit) InfoBanner("Für diesen Tag kann der Eintrag nicht mehr geändert werden.", Icons.Rounded.Lock)
        LoadingButton("Speichern", busy, { busy = true; scope.launch { try { if (app.repos.care.save(student.id, date, option, memo.ifBlank { null }, futureDefault = future)) onSaved() else onError("Speichern nicht bestätigt") } catch (e: Throwable) { onError(e.asApi().userText) } finally { busy = false } } }, Modifier.fillMaxWidth(), enabled = canEdit && option != null, icon = Icons.Rounded.Check)
        if (current?.optionId != null && canEdit) TextButton({ confirmDelete = true }, Modifier.fillMaxWidth()) { Text("Eintrag für diesen Tag löschen", color = cs.error) }
    }
    if (confirmDelete) ConfirmDialog("Eintrag löschen?", "Der Eintrag für ${Dates.dayShort(date)} wird gelöscht.", "Löschen", { scope.launch { try { app.repos.care.remove(student.id, date); onDeleted() } catch (e: Throwable) { onError(e.asApi().userText) } } }, { confirmDelete = false }, destructive = true)
}
