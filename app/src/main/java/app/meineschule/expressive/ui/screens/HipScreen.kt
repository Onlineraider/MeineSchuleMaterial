package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import java.time.LocalDate
import app.meineschule.expressive.ui.theme.MsdShapes
import kotlinx.coroutines.launch
import app.meineschule.expressive.core.asApi

/** Home.InfoPoint: serverseitiges HTML wird bereinigt (Jsoup-Safelist) und nativ dargestellt – nie in einer WebView. */
@Composable
fun HipScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    val hipStudents = rememberLoader(gen, school != null) { app.repos.hip.students() }
    val students = (hipStudents.state as? UiState.Ready)?.data.orEmpty(); var idx by remember { mutableIntStateOf(0) }; var tab by remember { mutableStateOf(HipClass.Abs) }
    val student = students.getOrNull(idx)
    val loader = rememberLoader(gen, student?.id, students.size) { if (student == null) HipData(emptyList(), emptyList()) else app.repos.hip.student(student.id) }
    val absIndex = rememberLoader(gen) { app.repos.absence.index() }
    val reports = rememberLoader(gen, student?.id) { student?.let { runCatching { app.repos.absence.reports(it.id) }.getOrDefault(emptyList()) } ?: emptyList() }
    val cats = (absIndex.state as? UiState.Ready)?.data?.categories.orEmpty()
    var editing by remember { mutableStateOf<AbsenceReport?>(null) }; var creating by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<AbsenceReport?>(null) }
    var detail by remember { mutableStateOf<HipRow?>(null) }
    val snack = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
    val canReport = school?.canCreateAbsences == true && student != null
    PageScaffold(title = "Home.InfoPoint", subtitle = student?.name, onBack = onBack, snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = { if (canReport && tab == HipClass.Abs) ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Abwesenheit melden") }, shape = MsdShapes.Fab) }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            if (students.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { students.forEachIndexed { i, s -> FilterChip(i == idx, { idx = i }, { Text(s.name) }) } }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.isEmpty }, empty = { EmptyState(Icons.Rounded.Grade, "Keine Informationen verfügbar", "Für diese Person liegen im Home.InfoPoint aktuell keine Daten vor.") }) { d ->
                val kinds = (listOf(HipClass.Abs, HipClass.Rem) + HipClass.entries.drop(2).filter { c -> d.of(c).isNotEmpty() }).let { if (d.text.isNotEmpty()) it + HipClass.Oth else it }.distinct()
                val cur = if (tab in kinds) tab else kinds.first()
                Column {
                    PrimaryScrollableTabRow(kinds.indexOf(cur), edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface) { kinds.forEach { k -> Tab(cur == k, { tab = k }, text = { Text(k.label) }) } }
                    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp)) {
                        if (cur == HipClass.Abs) absenceItems(HipParser.absSummary(d.of(HipClass.Abs)), (reports.state as? UiState.Ready)?.data.orEmpty(), cats, if (canReport) ({ editing = it }) else null)
                        else {
                            val ts = d.of(cur)
                            if (ts.isEmpty() && d.text.isEmpty()) item { EmptyState(Icons.Rounded.Notes, "Keine Einträge", null) }
                            ts.forEach { t ->
                                if (t.title.isNotBlank()) item { SectionTitle(t.title) }
                                itemsIndexed(t.rows) { i, r ->
                                    val row = HipRow.of(cur, t, r)
                                    GroupedItem(i, t.rows.size, row.title, supporting = row.body, meta = null,
                                        leading = if (row.grade != null) ({ GradeBadge(row.grade) }) else null,
                                        trailing = { row.date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                        onClick = { detail = row })
                                }
                            }
                            if (cur == HipClass.Oth && d.text.isNotEmpty()) { item { SectionTitle("Weitere Informationen") }; itemsIndexed(d.text) { i, t -> GroupedItem(i, d.text.size, t) } }
                        }
                    }
                }
            }
        }
    }
    detail?.let { HipDetailSheet(it) { detail = null } }
    if ((creating || editing != null) && student != null) AbsenceForm(student, absIndex, editing, onDismiss = { creating = false; editing = null }, onSaved = { creating = false; editing = null; reports.reload(); scope.launch { snack.showSnackbar("Gespeichert") } }, onDelete = { deleting = editing; editing = null })
    deleting?.let { r -> ConfirmDialog("Meldung löschen?", "Die Abwesenheitsmeldung wird gelöscht, soweit du dazu berechtigt bist.", "Löschen", { scope.launch { try { app.repos.absence.remove(r.studentId, r.id!!); reports.reload() } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } }; deleting = null }, { deleting = null }) }
}

/** Eine Zeile des Home.InfoPoint, geordnet: Note/Hauptinhalt oben, darunter Beschreibung, Datum zuletzt. */
internal data class HipRow(val title: String, val body: String?, val date: String?, val category: String, val grade: String?, val fields: List<Pair<String, String>>) {
    companion object {
        private val DATE = Regex("^\\d{1,2}\\.\\d{1,2}\\.(\\d{2}|\\d{4})$")
        fun of(cls: HipClass, t: HipTable, r: List<String>): HipRow {
            val heads = r.indices.map { t.head.getOrNull(it).orEmpty().trim() }
            val di = r.indices.firstOrNull { DATE.matches(r[it].trim()) || heads[it].contains("datum", true) && r[it].isNotBlank() } ?: -1
            val gi = if (cls == HipClass.Grd) heads.indexOfFirst { Regex("note|zensur|bewertung", RegexOption.IGNORE_CASE).containsMatchIn(it) } else -1
            val rest = r.indices.filter { it != di && it != gi && r[it].isNotBlank() }
            val date = r.getOrNull(di)?.takeIf { di >= 0 }
            val fields = buildList {
                if (gi >= 0 && r[gi].isNotBlank()) add("Note" to r[gi])
                rest.forEach { add((heads[it].ifBlank { "Info" }) to r[it]) }
                date?.let { add("Datum" to it) }
            }
            return if (gi >= 0 && r[gi].isNotBlank()) HipRow("Note ${r[gi]}", rest.joinToString("\n") { r[it] }.ifBlank { null }, date, t.title, r[gi], fields)
            else HipRow(rest.firstOrNull()?.let { r[it] } ?: date ?: "–", rest.drop(1).joinToString("\n") { c -> heads[c].takeIf { it.isNotBlank() }?.let { h -> "$h: ${r[c]}" } ?: r[c] }.ifBlank { null }, date, t.title, null, fields)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HipDetailSheet(r: HipRow, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                r.grade?.let { GradeBadge(it) }
                Column { Text(r.title, style = MaterialTheme.typography.headlineSmall); if (r.category.isNotBlank()) Text(r.category, style = MaterialTheme.typography.titleSmall, color = cs.primary) }
            }
            r.fields.filter { it.first != "Note" }.forEach { (k, v) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).padding(14.dp)) {
                    Text(k, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private val DE_DATE = java.time.format.DateTimeFormatter.ofPattern("d.M.[yyyy][yy]")
private fun parseDe(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), DE_DATE) }.getOrNull()

/** Fehlzeiten: eine Übersichtskarte, darunter eine nach Monaten gruppierte Zeitleiste. Eigene Meldungen werden dem passenden Tag zugeordnet (keine Doppelanzeige). */
private fun androidx.compose.foundation.lazy.LazyListScope.absenceItems(a: AbsSummary, reports: List<AbsenceReport>, cats: List<IdName>, onEdit: ((AbsenceReport) -> Unit)?) {
    item { AbsSummaryCard(a) }
    val rows = a.entries.map { e -> val d = parseDe(e.day); Triple(d, e, reports.filter { r -> d != null && Dates.date(r.from)?.let { f -> !d.isBefore(f) && !d.isAfter(Dates.date(r.to) ?: f) } == true }) }.sortedByDescending { it.first }
    val matched = rows.flatMap { it.third }.mapNotNull { it.id }.toSet()
    val pending = reports.filter { it.id !in matched }.sortedByDescending { Dates.date(it.from) }
    if (pending.isNotEmpty()) {
        item { SectionTitle("Gemeldet – noch nicht eingetragen") }
        itemsIndexed(pending) { i, r -> ReportRow(i, pending.size, r, cats, onEdit) }
    }
    if (rows.isEmpty() && pending.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Keine Fehlzeiten", "Es sind keine Fehlzeiten eingetragen.") }
    rows.groupBy { it.first?.let { d -> d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.GERMAN) + " " + d.year } ?: "Weitere" }.forEach { (month, list) ->
        item { SectionTitle(month) }
        itemsIndexed(list) { i, (d, e, rs) ->
            val un = e.text.contains("unentschuldigt", true); val text = e.text.replace(Regex("\\s*\\((un)?entschuldigt\\)", RegexOption.IGNORE_CASE), "").trim()
            val cs = MaterialTheme.colorScheme
            GroupedItem(i, list.size, text.ifBlank { "Fehlzeit" }, supporting = rs.firstOrNull()?.let { r -> listOfNotNull("Gemeldet: " + (cats.firstOrNull { it.id == r.categoryId }?.name ?: "Abwesenheit"), r.text?.takeIf { it.isNotBlank() }).joinToString(" · ") },
                leading = { DateBadge(d, e.day, if (un) cs.errorContainer else cs.secondaryContainer) },
                trailing = { AssistChip(onClick = { rs.firstOrNull()?.let { r -> onEdit?.invoke(r) } }, label = { Text(if (un) "Unentschuldigt" else "Entschuldigt") }, colors = AssistChipDefaults.assistChipColors(labelColor = if (un) cs.error else cs.primary)) },
                onClick = rs.firstOrNull()?.let { r -> onEdit?.let { f -> { f(r) } } })
        }
    }
}

@Composable private fun ReportRow(i: Int, n: Int, r: AbsenceReport, cats: List<IdName>, onEdit: ((AbsenceReport) -> Unit)?) {
    val f = Dates.date(r.from); val t = Dates.date(r.to)
    val range = listOfNotNull(f?.let(Dates::dayShort), t?.takeIf { it != f }?.let(Dates::dayShort)).joinToString(" – ")
    GroupedItem(i, n, cats.firstOrNull { it.id == r.categoryId }?.name ?: "Abwesenheit", supporting = listOfNotNull(range, if (r.fullDay) "ganztägig" else r.fromLesson?.let { "Stunde $it${r.toLesson?.let { x -> "–$x" } ?: ""}" }).joinToString(" · "),
        leading = { DateBadge(f, range, MaterialTheme.colorScheme.tertiaryContainer) }, onClick = onEdit?.let { fn -> { fn(r) } })
}

@Composable private fun DateBadge(d: LocalDate?, fallback: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(color), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (d != null) { Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium); Text(d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMAN), style = MaterialTheme.typography.labelSmall) }
        else Text(fallback.take(5), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun AbsSummaryCard(a: AbsSummary) {
    val cs = MaterialTheme.colorScheme
    @Composable fun cell(t: String, m: Modifier, bold: Boolean = false, c: androidx.compose.ui.graphics.Color = cs.onSurface) = Text(t, m, style = if (bold) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium, color = c, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerHigh).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { Spacer(Modifier.weight(1.2f)); cell("entschuldigt", Modifier.weight(1f), c = cs.onSurfaceVariant); cell("unentschuldigt", Modifier.weight(1f), c = cs.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Fehltage", Modifier.weight(1.2f), style = MaterialTheme.typography.titleSmall); cell("${a.daysExcused}", Modifier.weight(1f), true); cell("${a.daysUnexcused}", Modifier.weight(1f), true, if (a.daysUnexcused > 0) cs.error else cs.onSurface) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Fehlstunden", Modifier.weight(1.2f), style = MaterialTheme.typography.titleSmall); cell("${a.hoursExcused}", Modifier.weight(1f), true); cell("${a.hoursUnexcused}", Modifier.weight(1f), true, if (a.hoursUnexcused > 0) cs.error else cs.onSurface) }
    }
}

@Composable internal fun GradeBadge(value: String) { val cs = MaterialTheme.colorScheme; Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer) } }
