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
    var detail by remember { mutableStateOf<CalendarEvent?>(null) }
    val snack = remember { SnackbarHostState() }

    PageScaffold(title = "Kalender", subtitle = month.month.getDisplayName(TextStyle.FULL, Dates.DE) + " ${month.year}", snackbarHost = { SnackbarHost(snack) }, actions = {
        IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Vorheriger Monat") }
        IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Nächster Monat") }
        TextButton(onClick = { month = YearMonth.now(); selected = LocalDate.now() }) { Text("Heute") }
    }) { pad ->
        StateHost(loader, Modifier.padding(top = pad.calculateTopPadding())) { events ->
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item { MonthGrid(month, selected, events, onPick = { selected = it; if (YearMonth.from(it) != month) month = YearMonth.from(it) }) }
                val day = events.filter { it.on(selected) }.sortedBy { Dates.parse(it.start) }
                item { SectionTitle(Dates.dayLong(selected)) }
                if (day.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Keine Termine für diesen Tag", "Wähle einen anderen Tag im Kalender.") }
                itemsIndexed(day) { i, e ->
                    GroupedItem(i, day.size, e.title, supporting = listOfNotNull(if (e.allDay) "Ganztägig" else Dates.parse(e.start)?.let { Dates.time(it) }, e.location, e.category).joinToString(" · "),
                        leading = { TileIcon(Icons.Rounded.Event, container = if (e.open) cs.tertiaryContainer else cs.secondaryContainer) },
                        trailing = { if (e.open) AssistChip(onClick = { detail = e }, label = { Text("Antwort offen") }) else if (e.accepted) Icon(Icons.Rounded.CheckCircle, "Zugesagt", tint = cs.primary) else if (e.declined) Icon(Icons.Rounded.Cancel, "Abgesagt", tint = cs.error) }, onClick = { detail = e })
                }
            }
        }
    }
    detail?.let { e -> EventSheet(e, school?.languages.orEmpty(), onDismiss = { detail = null }, onConfirmed = { detail = null; loader.reload(); scope.launch { app.session.refreshState(); snack.showSnackbar("Termin bestätigt") } }) }
}

@Composable private fun MonthGrid(month: YearMonth, selected: LocalDate, events: List<CalendarEvent>, onPick: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme; val first = month.atDay(1); val lead = (first.dayOfWeek.value - 1); val today = LocalDate.now()
    Column(Modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainerLow).padding(12.dp)) {
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
    var lang by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }
    val detail = rememberLoader(e.id, lang) { app.repos.calendar.detail(e.id, lang) }
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val d = (detail.state as? UiState.Ready)?.data ?: e
            Text(d.title, style = MaterialTheme.typography.headlineMedium)
            Text(listOfNotNull(Dates.parse(d.start)?.let { if (d.allDay) Dates.dayLong(it.toLocalDate()) else "${Dates.dayLong(it.toLocalDate())} · ${Dates.time(it)}" }, d.location, d.category).joinToString(" · "), color = cs.onSurfaceVariant)
            if (langs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(lang == null, { lang = null }, { Text("Original") }); langs.take(4).forEach { l -> FilterChip(lang == l.key, { lang = l.key }, { Text(l.name) }) } }
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
