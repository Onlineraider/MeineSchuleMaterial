package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.CalendarEvent
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

private fun CalendarEvent.days(): ClosedRange<LocalDate>? { val s = Dates.date(start) ?: return null; val e = Dates.date(end)?.takeIf { !it.isBefore(s) } ?: s; return s..e }
internal val CalendarEvent.allDay get() = Dates.parse(start)?.let { it.hour == 0 && it.minute == 0 } == true
private val CalendarEvent.open get() = confirmReceive && (answer ?: 0) == 0
private val CalendarEvent.accepted get() = confirmReceive && answer == 1
private val CalendarEvent.declined get() = confirmReceive && answer == 2
private fun CalendarEvent.on(d: LocalDate) = days()?.let { d >= it.start && d <= it.endInclusive } == true

@Composable
fun CalendarScreen() {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    val loader = rememberLoader(gen) { app.repos.calendar.index() }
    var month by remember { mutableStateOf(YearMonth.now()) }; var selected by remember { mutableStateOf(LocalDate.now()) }
    var mode by remember { mutableStateOf(CalMode.Month) }
    var detail by remember { mutableStateOf<CalendarEvent?>(null) }
    val snack = remember { SnackbarHostState() }
    val weekStart = Dates.weekStart(selected)
    fun step(n: Long) { when (mode) { CalMode.Month -> { month = month.plusMonths(n); selected = month.atDay(minOf(selected.dayOfMonth, month.lengthOfMonth())) }; CalMode.Week -> { selected = selected.plusWeeks(n); month = YearMonth.from(selected) }; CalMode.Agenda -> {} } }
    val subtitle = when (mode) { CalMode.Month -> month.month.getDisplayName(TextStyle.FULL, Dates.DE) + " ${month.year}"; CalMode.Week -> "KW ${Dates.isoWeek(weekStart)} · ${Dates.range(weekStart, weekStart.plusDays(6))}"; CalMode.Agenda -> "Kommende Termine" }

    PageScaffold(title = "Kalender", subtitle = subtitle, snackbarHost = { SnackbarHost(snack) }, actions = {
        if (mode != CalMode.Agenda) { IconButton(onClick = { step(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Zurück") }; IconButton(onClick = { step(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Weiter") } }
        TextButton(onClick = { month = YearMonth.now(); selected = LocalDate.now() }) { Text("Heute") }
    }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                SingleChoiceSegmentedButtonRow { CalMode.entries.forEachIndexed { i, m -> SegmentedButton(mode == m, { mode = m }, SegmentedButtonDefaults.itemShape(i, CalMode.entries.size)) { Text(m.label) } } }
            }
            StateHost(loader, Modifier.weight(1f)) { events ->
                val sorted = events.sortedBy { Dates.parse(it.start) }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    when (mode) {
                        CalMode.Month -> {
                            item { MonthGrid(month, selected, events, onPick = { selected = it; if (YearMonth.from(it) != month) month = YearMonth.from(it) }) }
                            val day = sorted.filter { it.on(selected) }
                            item { SectionTitle(Dates.dayLong(selected)) }
                            if (day.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Keine Termine für diesen Tag", "Wähle einen anderen Tag im Kalender.") }
                            itemsIndexed(day) { i, e -> EventRow(e, i, day.size) { detail = e } }
                        }
                        CalMode.Week -> {
                            item { WeekStrip(weekStart, selected, events) { selected = it } }
                            val days = (0..6L).map { weekStart.plusDays(it) }
                            if (days.none { d -> events.any { it.on(d) } }) item { EmptyState(Icons.Rounded.EventAvailable, "Keine Termine in dieser Woche", null) }
                            days.forEach { d ->
                                val day = sorted.filter { it.on(d) }
                                if (day.isNotEmpty()) { item { SectionTitle(Dates.dayLong(d) + if (d == LocalDate.now()) " · Heute" else "") }; itemsIndexed(day) { i, e -> EventRow(e, i, day.size) { detail = e } } }
                            }
                        }
                        CalMode.Agenda -> {
                            val today = LocalDate.now(); val upcoming = sorted.filter { (it.days()?.endInclusive ?: today) >= today }
                            if (upcoming.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Keine kommenden Termine", null) }
                            val groups = upcoming.groupBy { e -> maxOf(e.days()?.start ?: today, today) }.toSortedMap()
                            groups.forEach { (d, day) -> item { SectionTitle(Dates.dayLong(d) + if (d == today) " · Heute" else if (d == today.plusDays(1)) " · Morgen" else "") }; itemsIndexed(day) { i, e -> EventRow(e, i, day.size) { detail = e } } }
                        }
                    }
                }
            }
        }
    }
    detail?.let { e -> EventSheet(e, school?.languages.orEmpty(), onDismiss = { detail = null }, onConfirmed = { detail = null; loader.reload(); scope.launch { app.session.refreshState(); snack.showSnackbar("Termin bestätigt") } }) }
}

private enum class CalMode(val label: String) { Month("Monat"), Week("Woche"), Agenda("Agenda") }

@Composable private fun EventRow(e: CalendarEvent, i: Int, n: Int, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    GroupedItem(i, n, e.title, supporting = listOfNotNull(if (e.allDay) "Ganztägig" else Dates.parse(e.start)?.let { Dates.time(it) }, e.location, e.category).joinToString(" · "),
        leading = { TileIcon(Icons.Rounded.Event, container = if (e.open) cs.tertiaryContainer else cs.secondaryContainer) },
        trailing = { if (e.open) AssistChip(onClick = onClick, label = { Text("Antwort offen") }) else if (e.accepted) Icon(Icons.Rounded.CheckCircle, "Zugesagt", tint = cs.primary) else if (e.declined) Icon(Icons.Rounded.Cancel, "Abgesagt", tint = cs.error) },
        onClick = onClick)
}

/** Wochenleiste Mo–So mit Terminzähler je Tag. */
@Composable private fun WeekStrip(weekStart: LocalDate, selected: LocalDate, events: List<CalendarEvent>, onPick: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme; val today = LocalDate.now()
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..6L).forEach { o -> val d = weekStart.plusDays(o); val n = events.count { it.on(d) }; val sel = d == selected
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (sel) cs.primary else if (d == today) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent).clickable { onPick(d) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Dates.DE).take(2), style = MaterialTheme.typography.labelMedium, color = if (sel) cs.onPrimary else cs.onSurfaceVariant)
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = if (sel) cs.onPrimary else cs.onSurface)
                Text(if (n > 0) "$n" else "·", style = MaterialTheme.typography.labelSmall, color = if (sel) cs.onPrimary else if (n > 0) cs.tertiary else cs.outline)
            } }
    }
}

@Composable private fun MonthGrid(month: YearMonth, selected: LocalDate, events: List<CalendarEvent>, onPick: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme; val first = month.atDay(1); val lead = (first.dayOfWeek.value - 1); val today = LocalDate.now()
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer).padding(12.dp)) {
        Row { DayOfWeek.entries.forEach { Text(it.getDisplayName(TextStyle.SHORT, Dates.DE).take(2), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        val cells = lead + month.lengthOfMonth(); val rows = (cells + 6) / 7
        repeat(rows) { r -> Row(Modifier.padding(top = 4.dp)) { repeat(7) { c ->
            val n = r * 7 + c - lead + 1
            if (n < 1 || n > month.lengthOfMonth()) Box(Modifier.weight(1f).aspectRatio(1f)) else {
                val d = month.atDay(n); val has = events.any { it.on(d) }; val sel = d == selected
                Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(if (sel) RoundedCornerShape(16.dp) else CircleShape).background(if (sel) cs.primary else if (d == today) cs.primaryContainer else androidx.compose.ui.graphics.Color.Transparent).clickable { onPick(d) }, contentAlignment = Alignment.Center) {
                    Text("$n", color = if (sel) cs.onPrimary else cs.onSurface, style = MaterialTheme.typography.bodyMedium)
                    if (has) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(5.dp).clip(CircleShape).background(if (sel) cs.onPrimary else cs.tertiary))
                }
            }
        } } }
    }
}

@Composable private fun EventSheet(e: CalendarEvent, langs: List<app.meineschule.expressive.data.Language>, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    val uiLang by app.prefs.language.collectAsState(); val lang = app.prefs.translation(langs, uiLang); var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
    val detail = rememberLoader(e.id, lang) { app.repos.calendar.detail(e.id, lang) }
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = (detail.state as? UiState.Ready)?.data ?: e
            Text(d.title, style = MaterialTheme.typography.headlineMedium)
            Text(listOfNotNull(Dates.parse(d.start)?.let { if (d.allDay) Dates.dayLong(it.toLocalDate()) else "${Dates.dayLong(it.toLocalDate())} · ${Dates.time(it)}" }, d.location, d.category).joinToString(" · "), color = cs.onSurfaceVariant)
            if (detail.state is UiState.Loading) SkeletonBox(Modifier.fillMaxWidth().height(60.dp)) else d.text?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            err?.let { Text(it, color = cs.error) }
            if (d.confirmReceive) {
                fun answer(a: Int) { busy = true; scope.launch { try { app.repos.calendar.confirm(e.id, a); onConfirmed() } catch (x: Throwable) { err = x.asApi().userText } finally { busy = false } } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (d.answer == 1) FilledTonalButton({ answer(1) }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Rounded.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text("Zugesagt") } else Button({ answer(1) }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Zusagen") }
                    if (d.answer == 2) FilledTonalButton({ answer(2) }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Rounded.Cancel, null); Spacer(Modifier.width(6.dp)); Text("Abgesagt") } else OutlinedButton({ answer(2) }, Modifier.weight(1f), enabled = !busy) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("Absagen") }
                }
            }
        }
    }
}
