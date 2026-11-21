package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import java.time.LocalDate

private enum class PlanView { Day, Week }

@Composable
fun ScheduleScreen() {
    val app = LocalApp.current; val cs = MaterialTheme.colorScheme
    val school by app.session.school.collectAsState(); val gen by app.session.generation.collectAsState()
    val today = remember { LocalDate.now() }
    var weekStart by remember { mutableStateOf(Dates.weekStart(if (today.dayOfWeek.value >= 6) today.plusDays(2) else today)) }
    var selected by remember { mutableStateOf(if (today.dayOfWeek.value <= 5) today else weekStart) }
    var view by remember { mutableStateOf(PlanView.Day) }
    var studentIdx by remember { mutableIntStateOf(0) }
    val students = school?.students.orEmpty(); val student = students.getOrNull(studentIdx)
    val loader = rememberLoader(gen, weekStart, student?.id, school != null) { app.repos.schedule(weekStart, student?.id) }
    val end = weekStart.plusDays(4)
    var times by remember { mutableStateOf(LessonTimes.parse(app.prefs.lessonTimes)) }; var editTimes by remember { mutableStateOf(false) }
    var detailOf by remember { mutableStateOf<Triple<ScheduleDay, Slot, LessonEntry>?>(null) }

    PageScaffold(title = "Stundenplan", subtitle = "Deine Woche · ${Dates.range(weekStart, end)}", actions = {
        IconButton(onClick = { editTimes = true }) { Icon(Icons.Rounded.Schedule, "Unterrichtszeiten") }
        FilledTonalButton(onClick = { val t = if (today.dayOfWeek.value <= 5) today else today.plusDays((8 - today.dayOfWeek.value).toLong()); weekStart = Dates.weekStart(t); selected = t }, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Rounded.Today, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Heute") }
    }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { weekStart = weekStart.minusWeeks(1); selected = weekStart.plusDays((selected.dayOfWeek.value - 1).toLong()) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Vorherige Woche") }
                WeekDropdown(weekStart, today, Modifier.weight(1f)) { weekStart = it; selected = it.plusDays((selected.dayOfWeek.value - 1).toLong().coerceIn(0, 4)) }
                IconButton(onClick = { weekStart = weekStart.plusWeeks(1); selected = weekStart.plusDays((selected.dayOfWeek.value - 1).toLong()) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Nächste Woche") }
            }
            if (students.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { students.forEachIndexed { i, s -> FilterChip(i == studentIdx, { studentIdx = i }, { Text(s.name) }) } }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SingleChoiceSegmentedButtonRow { PlanView.entries.forEachIndexed { i, v -> SegmentedButton(view == v, { view = v }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (v == PlanView.Day) "Tag" else "Woche") } } }
            }
            if (view == PlanView.Day) PrimaryTabRow(selectedTabIndex = (selected.dayOfWeek.value - 1).coerceIn(0, 4), containerColor = cs.surface) {
                (0..4).forEach { i -> val d = weekStart.plusDays(i.toLong()); Tab(selected = d == selected, onClick = { selected = d }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(Dates.weekday(d), fontWeight = FontWeight.Bold); Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall) } }) }
            }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.days.isEmpty() }, empty = { EmptyState(Icons.Rounded.EventAvailable, "Kein Stundenplan", "Für diese Woche liegt kein Stundenplan vor.") }) { sched ->
                val ch = sched.changes()
                Column {
                    if (ch.isNotEmpty()) { val nx = ch.count { it.third.cancelled }; InfoBanner("Diese Woche: " + listOfNotNull(nx.takeIf { it > 0 }?.let { "$it Entfall" + if (it > 1) "e" else "" }, (ch.size - nx).takeIf { it > 0 }?.let { "$it Änderung" + if (it > 1) "en" else "" }).joinToString(" · "), Icons.Rounded.SwapHoriz, warn = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    if (view == PlanView.Day) DayView(sched.days.firstOrNull { it.date == selected }, selected, times) { d, sl, e -> detailOf = Triple(d, sl, e) } else WeekView(sched, today, times)
                }
            }
        }
    }
    ScheduleDialogs(detailOf, times, editTimes, { detailOf = null }, { editTimes = false }) { v -> times = v; app.prefs.lessonTimes = LessonTimes.serialize(v); editTimes = false }
}

@Composable private fun ScheduleDialogs(detail: Triple<ScheduleDay, Slot, LessonEntry>?, times: List<TimeRange>, edit: Boolean, onCloseDetail: () -> Unit, onCloseEdit: () -> Unit, onSave: (List<TimeRange>) -> Unit) {
    detail?.let { DetailDialog(it, times, onCloseDetail) }
    if (edit) TimesDialog(times, onSave, onCloseEdit)
}

@Composable private fun WeekDropdown(current: LocalDate, today: LocalDate, modifier: Modifier, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }; val base = Dates.weekStart(today)
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField("KW ${Dates.isoWeek(current)} · ${Dates.range(current, current.plusDays(4))}", {}, readOnly = true, singleLine = true, label = { Text("Kalenderwoche") }, shape = RoundedCornerShape(16.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            (-4L..8L).forEach { off -> val w = base.plusWeeks(off)
                DropdownMenuItem(text = { Text("KW ${Dates.isoWeek(w)} · ${Dates.range(w, w.plusDays(4))}${if (off == 0L) " · Diese Woche" else ""}") }, onClick = { open = false; onPick(w) }) }
        }
    }
}

@Composable private fun DayView(day: ScheduleDay?, date: LocalDate, times: List<TimeRange>, onOpen: (ScheduleDay, Slot, LessonEntry) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val slots = day?.activeSlots.orEmpty()
    if (slots.isEmpty()) { EmptyState(Icons.Rounded.EventAvailable, "Heute ist kein Unterricht eingetragen".replace("Heute", if (date == LocalDate.now()) "Heute" else Dates.dayLong(date)), "Genieße die freie Zeit."); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { Text("${Dates.dayLong(date)} · ${day!!.lessonCount} Stunden", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp)) }
        if (date == LocalDate.now()) { val (cur, next) = LessonTimes.current(day, times); (cur ?: next)?.let { sl -> item { InfoBanner((if (cur != null) "Jetzt: " else "Als Nächstes: ") + sl.entries.joinToString(" / ") { it.subject }, Icons.Rounded.Schedule, modifier = Modifier.padding(bottom = 12.dp)) } } }
        slots.forEachIndexed { i, slot ->
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) { // Zeitachse
                        Box(Modifier.size(32.dp).clip(CircleShape).background(if (slot.entries.isEmpty()) cs.surfaceContainerHigh else cs.primaryContainer), contentAlignment = Alignment.Center) { Text("${slot.index + 1}", style = MaterialTheme.typography.labelLarge, color = if (slot.entries.isEmpty()) cs.onSurfaceVariant else cs.onPrimaryContainer) }
                        (slot.time ?: times.getOrNull(slot.index))?.let { Text(it.from, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                        if (i < slots.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(cs.outlineVariant))
                    }
                    Column(Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (slot.entries.isEmpty()) Text("Freistunde", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        slot.entries.forEach { e -> LessonCard(e) { onOpen(day!!, slot, e) } }
                    }
                }
            }
        }
    }
}

@Composable private fun LessonCard(e: LessonEntry, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(if (e.cancelled) RoundedCornerShape(16.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp)).background(if (e.cancelled) cs.errorContainer.copy(alpha = .55f) else if (e.changed) cs.tertiaryContainer else cs.surfaceContainerLow).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(e.subject, style = MaterialTheme.typography.titleMedium, textDecoration = if (e.cancelled) TextDecoration.LineThrough else null, color = if (e.cancelled) cs.onErrorContainer else cs.onSurface)
            if (e.hint.isNotBlank()) Text(e.hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) // undokumentierte Zusatzfelder (z. B. Raum/Lehrkraft), falls das Portal sie liefert
        }
        if (e.cancelled) AssistChip(onClick = onClick, label = { Text("Entfällt") }, leadingIcon = { Icon(Icons.Rounded.EventBusy, null, Modifier.size(16.dp)) }) else if (e.changed) AssistChip(onClick = onClick, label = { Text("Änderung") }, leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(16.dp)) })
    }
}

@Composable private fun WeekView(sched: Schedule, today: LocalDate, times: List<TimeRange>) {
    val cs = MaterialTheme.colorScheme
    val days = sched.days.sortedBy { it.date }.take(5)
    val rows = days.maxOfOrNull { it.slots.size } ?: 0
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item { Row(Modifier.fillMaxWidth()) { Spacer(Modifier.width(28.dp)); days.forEach { d -> Box(Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(12.dp)).background(if (d.date == today) cs.primary else cs.surfaceContainerHigh).padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text(Dates.weekday(d.date), style = MaterialTheme.typography.labelLarge, color = if (d.date == today) cs.onPrimary else cs.onSurface) } } } }
        items(rows) { r ->
            if (days.all { it.slots.getOrNull(r)?.entries.isNullOrEmpty() } && (r > 0)) return@items
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r + 1}" + (times.getOrNull(r)?.let { "\n" + it.from.take(5) } ?: ""), Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                days.forEach { d ->
                    val es = d.slots.getOrNull(r)?.entries.orEmpty()
                    Column(Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(10.dp)).background(if (es.isEmpty()) androidx.compose.ui.graphics.Color.Transparent else cs.secondaryContainer).defaultMinSize(minHeight = 44.dp).padding(4.dp), verticalArrangement = Arrangement.Center) {
                        es.forEach { e -> Text(e.subject, style = MaterialTheme.typography.labelSmall, maxLines = 2, color = if (e.cancelled) cs.error else cs.onSecondaryContainer, textDecoration = if (e.cancelled) TextDecoration.LineThrough else null) }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailDialog(t: Triple<ScheduleDay, Slot, LessonEntry>, times: List<TimeRange>, onDismiss: () -> Unit) {
    val (d, slot, e) = t; val time = slot.time ?: times.getOrNull(slot.index)
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Schließen") } }, title = { Text(e.subject) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (e.cancelled) "Entfällt" else if (e.changed) "Änderung / Vertretung" else "Planmäßig", style = MaterialTheme.typography.labelLarge)
            Text("${Dates.dayLong(d.date)} · ${slot.index + 1}. Position im Plan")
            time?.let { Text("${it.from}–${it.to}" + if (slot.time == null) " (eingestellte Unterrichtszeit)" else "") }
            e.extra.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            e.reason?.let { Text("Änderung erkannt an Feld: $it", style = MaterialTheme.typography.bodySmall) }
        }
    })
}

@Composable private fun TimesDialog(initial: List<TimeRange>, onSave: (List<TimeRange>) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf(initial.map { it.from to it.to }) }
    val ok = Regex("\\d\\d:\\d\\d")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Unterrichtszeiten") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Das Portal liefert keine Uhrzeiten – hier stellst du die Zeiten deiner Schule ein (Format HH:MM).", style = MaterialTheme.typography.bodySmall)
            rows.forEachIndexed { i, (a, b) -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.", Modifier.width(24.dp))
                OutlinedTextField(a, { v -> rows = rows.toMutableList().also { it[i] = v to b } }, Modifier.weight(1f), singleLine = true, isError = !ok.matches(a))
                OutlinedTextField(b, { v -> rows = rows.toMutableList().also { it[i] = a to v } }, Modifier.weight(1f), singleLine = true, isError = !ok.matches(b))
            } }
            Row { TextButton({ rows = rows + ("" to "") }) { Text("Stunde") }; TextButton({ rows = LessonTimes.DEFAULT.map { it.from to it.to } }) { Text("Standard") } }
        } },
        confirmButton = { TextButton({ val v = rows.filter { ok.matches(it.first) && ok.matches(it.second) && it.first < it.second }.map { TimeRange(it.first, it.second) }; if (v.isNotEmpty()) onSave(v) }) { Text("Speichern") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } })
}
