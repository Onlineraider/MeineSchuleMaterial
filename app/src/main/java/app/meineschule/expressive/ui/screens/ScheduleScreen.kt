package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
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

    PageScaffold(title = "Stundenplan", subtitle = "Deine Woche · ${Dates.range(weekStart, end)}", actions = {
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
                if (view == PlanView.Day) DayView(sched.days.firstOrNull { it.date == selected }, selected) else WeekView(sched, today)
            }
        }
    }
}

@Composable private fun WeekDropdown(current: LocalDate, today: LocalDate, modifier: Modifier, onPick: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }; val base = Dates.weekStart(today)
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField("KW ${Dates.isoWeek(current)} · ${Dates.range(current, current.plusDays(4))}", {}, readOnly = true, singleLine = true, label = { Text("Kalenderwoche") }, shape = RoundedCornerShape(16.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            (-4L..8L).forEach { off -> val w = base.plusWeeks(off)
                DropdownMenuItem(text = { Text("KW ${Dates.isoWeek(w)} · ${Dates.range(w, w.plusDays(4))}${if (off == 0L) " · Diese Woche" else ""}") }, onClick = { open = false; onPick(w) }) }
        }
    }
}

@Composable private fun DayView(day: ScheduleDay?, date: LocalDate) {
    val cs = MaterialTheme.colorScheme
    val slots = day?.activeSlots.orEmpty()
    if (slots.isEmpty()) { EmptyState(Icons.Rounded.EventAvailable, "Heute ist kein Unterricht eingetragen".replace("Heute", if (date == LocalDate.now()) "Heute" else Dates.dayLong(date)), "Genieße die freie Zeit."); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item { Text("${Dates.dayLong(date)} · ${day!!.lessonCount} Stunden", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp)) }
        item { InfoBanner("Die Nummerierung zeigt die Position im Stundenplan. Sie entspricht nicht zwingend der Schulstunde.", modifier = Modifier.padding(bottom = 12.dp)) }
        slots.forEachIndexed { i, slot ->
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) { // Zeitachse
                        Box(Modifier.size(32.dp).clip(CircleShape).background(if (slot.entries.isEmpty()) cs.surfaceContainerHigh else cs.primaryContainer), contentAlignment = Alignment.Center) { Text("${slot.index + 1}", style = MaterialTheme.typography.labelLarge, color = if (slot.entries.isEmpty()) cs.onSurfaceVariant else cs.onPrimaryContainer) }
                        if (i < slots.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(cs.outlineVariant))
                    }
                    Column(Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (slot.entries.isEmpty()) Text("Freistunde", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        slot.entries.forEach { e -> LessonCard(e) }
                    }
                }
            }
        }
    }
}

@Composable private fun LessonCard(e: LessonEntry) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(if (e.cancelled) RoundedCornerShape(16.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp)).background(if (e.cancelled) cs.errorContainer.copy(alpha = .55f) else cs.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(e.subject, style = MaterialTheme.typography.titleMedium, textDecoration = if (e.cancelled) TextDecoration.LineThrough else null, color = if (e.cancelled) cs.onErrorContainer else cs.onSurface)
            if (e.extra.isNotEmpty()) Text(e.extra.values.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) // undokumentierte Zusatzfelder (z. B. Raum/Lehrkraft), falls das Portal sie liefert
        }
        if (e.cancelled) AssistChip(onClick = {}, label = { Text("Entfällt") }, leadingIcon = { Icon(Icons.Rounded.EventBusy, null, Modifier.size(16.dp)) })
    }
}

@Composable private fun WeekView(sched: Schedule, today: LocalDate) {
    val cs = MaterialTheme.colorScheme
    val days = sched.days.sortedBy { it.date }.take(5)
    val rows = days.maxOfOrNull { it.slots.size } ?: 0
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        item { Row(Modifier.fillMaxWidth()) { Spacer(Modifier.width(28.dp)); days.forEach { d -> Box(Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(12.dp)).background(if (d.date == today) cs.primary else cs.surfaceContainerHigh).padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text(Dates.weekday(d.date), style = MaterialTheme.typography.labelLarge, color = if (d.date == today) cs.onPrimary else cs.onSurface) } } } }
        items(rows) { r ->
            if (days.all { it.slots.getOrNull(r)?.entries.isNullOrEmpty() } && (r > 0)) return@items
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
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
