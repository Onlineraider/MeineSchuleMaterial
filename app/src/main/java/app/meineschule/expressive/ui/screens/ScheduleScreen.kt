package app.meineschule.expressive.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
fun ScheduleScreen(onSubject: (String, String?) -> Unit = { _, _ -> }) {
    val app = LocalApp.current; val cs = MaterialTheme.colorScheme
    val school by app.session.school.collectAsState(); val gen by app.session.generation.collectAsState()
    val today = remember { LocalDate.now() }
    var weekStart by remember { mutableStateOf(Dates.weekStart(if (today.dayOfWeek.value >= 6) today.plusDays(2) else today)) }
    var selected by remember { mutableStateOf(if (today.dayOfWeek.value <= 5) today else weekStart) }
    var view by remember { mutableStateOf(PlanView.Day) }
    // Ansicht wie im Portal: Vertretungen (alle) · Schüler · Klassen · Gruppen
    var studentIdx by remember { mutableIntStateOf(0) }; var target by remember { mutableStateOf<Pair<String, String>?>(null) } // kind ("S"|"C"|"G") to id
    val students = school?.students.orEmpty()
    LaunchedEffect(students.size) { if (target == null && students.isNotEmpty()) target = "S" to students[0].id }
    val classes = rememberLoader(gen) { runCatching { app.repos.school.classes() }.getOrDefault(emptyList()) }
    val groups = rememberLoader(gen) { runCatching { app.repos.school.groups() }.getOrDefault(emptyList()) }
    val cl = (classes.state as? UiState.Ready)?.data.orEmpty(); val gr = (groups.state as? UiState.Ready)?.data.orEmpty()
    val label = when (target?.first) { "S" -> students.firstOrNull { it.id == target!!.second }?.name; "C" -> cl.firstOrNull { it.id == target!!.second }?.name?.let { "Klasse $it" }; "G" -> gr.firstOrNull { it.id == target!!.second }?.name; else -> null } ?: "Vertretungen"
    val loader = rememberLoader(gen, weekStart, target, school != null, cl.size, gr.size) { app.repos.schedule(weekStart, target?.takeIf { it.first == "S" }?.second, when (target?.first) { "C" -> cl.firstOrNull { it.id == target!!.second }?.name; "G" -> gr.firstOrNull { it.id == target!!.second }?.name; else -> null }) }
    var pickerOpen by remember { mutableStateOf(false) }
    // Vertretungen des gewählten Tages (Portal-Seite); schlägt das Lesen fehl, bleibt der normale Plan ohne Einbettung
    val changes = rememberLoader(gen, selected, school != null) { runCatching { app.repos.changes(selected) } }
    val changeResult = (changes.state as? UiState.Ready)?.data
    val myNames = buildList { students.forEach { st -> st.cls?.let { add(it) } }; when (target?.first) { "S" -> students.firstOrNull { it.id == target!!.second }?.cls?.let { add(it) }; "C" -> cl.firstOrNull { it.id == target!!.second }?.name?.let { add(it) }; "G" -> gr.firstOrNull { it.id == target!!.second }?.name?.let { add(it) } } }
    val planNames = when (target?.first) { "S" -> listOfNotNull(students.firstOrNull { it.id == target!!.second }?.cls); "C" -> listOfNotNull(cl.firstOrNull { it.id == target!!.second }?.name); "G" -> listOfNotNull(gr.firstOrNull { it.id == target!!.second }?.name); else -> emptyList() }
    val end = weekStart.plusDays(4)
    var detailOf by remember { mutableStateOf<Triple<ScheduleDay, Slot, LessonEntry>?>(null) }

    PageScaffold(title = "Stundenplan", subtitle = "Deine Woche · ${Dates.range(weekStart, end)}", actions = {
        FilledTonalButton(onClick = { val t = if (today.dayOfWeek.value <= 5) today else today.plusDays((8 - today.dayOfWeek.value).toLong()); weekStart = Dates.weekStart(t); selected = t }, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp)) { Icon(Icons.Rounded.Today, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Heute") }
    }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { weekStart = weekStart.minusWeeks(1); selected = weekStart.plusDays((selected.dayOfWeek.value - 1).toLong()) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Vorherige Woche") }
                WeekDropdown(weekStart, today, Modifier.weight(1f)) { weekStart = it; selected = it.plusDays((selected.dayOfWeek.value - 1).toLong().coerceIn(0, 4)) }
                IconButton(onClick = { weekStart = weekStart.plusWeeks(1); selected = weekStart.plusDays((selected.dayOfWeek.value - 1).toLong()) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Nächste Woche") }
            }
            Box(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton({ pickerOpen = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Groups, null); Spacer(Modifier.width(8.dp)); Text(label, Modifier.weight(1f)); Icon(Icons.Rounded.ArrowDropDown, null) }
                DropdownMenu(pickerOpen, { pickerOpen = false }) {
                    @Composable fun item(t: String, sel: Boolean, indent: Boolean, onClick: () -> Unit) = DropdownMenuItem(text = { Text(t, Modifier.padding(start = if (indent) 12.dp else 0.dp)) }, onClick = { pickerOpen = false; onClick() }, trailingIcon = { RadioButton(sel, null) })
                    item("Vertretungen", target == null, false) { target = null }
                    if (students.isNotEmpty()) { Text("Schüler", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge); students.forEach { s -> item(s.name, target == "S" to s.id, true) { target = "S" to s.id } } }
                    if (cl.isNotEmpty()) { Text("Klassen", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge); cl.forEach { c -> item(c.name, target == "C" to c.id, true) { target = "C" to c.id } } }
                    if (gr.isNotEmpty()) { Text("Gruppen", Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.labelLarge); gr.forEach { c -> item(c.name, target == "G" to c.id, true) { target = "G" to c.id } } }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SingleChoiceSegmentedButtonRow { PlanView.entries.forEachIndexed { i, v -> SegmentedButton(view == v, { view = v }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(if (v == PlanView.Day) "Tag" else "Woche") } } }
            }
            if (view == PlanView.Day) PrimaryTabRow(selectedTabIndex = (selected.dayOfWeek.value - 1).coerceIn(0, 4), containerColor = cs.surface) {
                (0..4).forEach { i -> val d = weekStart.plusDays(i.toLong()); Tab(selected = d == selected, onClick = { selected = d }, text = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(Dates.weekday(d), fontWeight = FontWeight.Bold); Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall) } }) }
            }
            if (target == null) { SubstitutionView(changeResult, myNames.distinct(), Modifier.weight(1f)); return@Column }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.days.isEmpty() && it.infos.isEmpty() }, empty = { EmptyState(Icons.Rounded.EventAvailable, "Kein Stundenplan", "Für diese Woche liegt kein Stundenplan vor.") }) { sched ->
                val ch = sched.changes()
                Column {
                    if (ch.isNotEmpty()) { val nx = ch.count { it.third.cancelled }; InfoBanner("Diese Woche: " + listOfNotNull(nx.takeIf { it > 0 }?.let { "$it Entfall" + if (it > 1) "e" else "" }, (ch.size - nx).takeIf { it > 0 }?.let { "$it Änderung" + if (it > 1) "en" else "" }).joinToString(" · "), Icons.Rounded.SwapHoriz, warn = true, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    val dayShown = sched.days.firstOrNull { it.date == selected }
                    LaunchedEffect(sched) { target?.takeIf { it.first == "S" }?.let { app.subjects.remember(it.second, sched) } }
                    LaunchedEffect(sched.startTimes) { if (sched.startTimes.isNotEmpty()) app.prefs.portalStarts = sched.startTimes.joinToString(",") }
                    val eff = app.prefs.effectiveTimes(sched.startTimes)
                    if (view == PlanView.Day) sched.infoFor(selected).forEach { i -> listOfNotNull(i.public, i.teachers?.let { "Lehrkräfte: $it" }).forEach { t -> InfoBanner(t, Icons.Rounded.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) } }
                    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>(); val spatialF = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
                    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>(); val fastFade = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                    AnimatedContent(targetState = view to selected, transitionSpec = {
                        if (initialState.first != targetState.first) (fadeIn(fade) + scaleIn(spatialF, .94f)) togetherWith fadeOut(fastFade)
                        else { val dir = if (targetState.second >= initialState.second) 1 else -1
                            (slideInHorizontally(spatial) { it / 3 * dir } + fadeIn(fade)) togetherWith (slideOutHorizontally(spatial) { -it / 3 * dir } + fadeOut(fastFade)) }
                    }, label = "plan") { (v, d) ->
                        if (v == PlanView.Day) DayView(sched.days.firstOrNull { it.date == d }, d, eff) { dd, sl, e -> detailOf = Triple(dd, sl, e) } else WeekView(sched, today, eff)
                    }
                }
            }
        }
    }
    ScheduleDialogs(detailOf, app.prefs.effectiveTimes(), { n -> detailOf = null; onSubject(n, target?.takeIf { it.first == "S" }?.second ?: students.firstOrNull()?.id) }) { detailOf = null }
}

@Composable private fun ScheduleDialogs(detail: Triple<ScheduleDay, Slot, LessonEntry>?, times: List<TimeRange>, onSubject: (String) -> Unit, onCloseDetail: () -> Unit) {
    detail?.let { DetailDialog(it, times, onSubject, onCloseDetail) }
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
                        pairSwaps(slot.entries, date) { it.move }.forEach { g ->
                            if (g.size == 2) SwapCard(SwapPart(g[0].subject, g[0].extra.toMap()["Lehrer"], g[0].extra.toMap()["Raum"], g[0].move!!), SwapPart(g[1].subject, g[1].extra.toMap()["Lehrer"], g[1].extra.toMap()["Raum"], g[1].move!!), date, cs.tertiaryContainer, cs.onTertiaryContainer, RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp)) { onOpen(day!!, slot, g[0]) }
                            else LessonCard(g[0], date) { onOpen(day!!, slot, g[0]) }
                        }
                    }
                }
            }
        }
    }
}

internal data class SwapPart(val subject: String, val teacher: String?, val room: String?, val move: Move)
private fun moveAt(d: LocalDate?, h: String?) = listOfNotNull(d?.let { Dates.weekday(it) + " " + "%02d.%02d.".format(it.dayOfMonth, it.monthValue) }, h?.let { "Std. " + it.replace("-", "–") }).joinToString(" · ")

/** Gegenseitig getauschte Stunden als eine Karte: „Mathe statt Deutsch“, darunter je Fach woher → wohin. */
@Composable internal fun SwapCard(into: SwapPart, away: SwapPart, day: LocalDate?, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    @Composable fun line(p: SwapPart, incoming: Boolean) {
        val where = if (incoming) moveAt(p.move.fromDate, p.move.fromHours) + "  \u2192  hier" else "hier  \u2192  " + moveAt(p.move.toDate, p.move.toHours)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fg.copy(alpha = .12f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (incoming) Icons.Rounded.Login else Icons.Rounded.Logout, null, Modifier.size(18.dp), tint = fg)
                Text(p.subject, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = fg)
                Text(listOfNotNull(p.teacher, p.room).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = fg)
            }
            Text(where, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
    }
    Column(modifier.fillMaxWidth().clip(shape).background(bg).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.SwapHoriz, null, tint = fg)
            Text("${into.subject} statt ${away.subject}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = fg)
        }
        Text("Stunden getauscht", style = MaterialTheme.typography.labelLarge, color = fg)
        line(into, true); line(away, false)
    }
}

@Composable private fun LessonCard(e: LessonEntry, date: LocalDate, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg = if (e.cancelled) cs.errorContainer else if (e.changed) cs.tertiaryContainer else cs.surfaceContainer
    val fg = if (e.cancelled) cs.onErrorContainer else if (e.changed) cs.onTertiaryContainer else cs.onSurface
    val sub = if (e.cancelled || e.changed) fg else cs.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(if (e.cancelled) RoundedCornerShape(16.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 8.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(e.subject, style = MaterialTheme.typography.titleMedium, textDecoration = if (e.cancelled) TextDecoration.LineThrough else null, color = fg)
                if (e.cancelled) Icon(Icons.Rounded.EventBusy, "Entfällt", Modifier.size(18.dp), tint = fg) else if (e.changed) Icon(Icons.Rounded.SwapHoriz, "Änderung", Modifier.size(18.dp), tint = fg)
            }
            if (e.hint.isNotBlank()) Text(e.hint, style = MaterialTheme.typography.bodySmall, color = sub)
            if (e.cancelled) Text("Entfällt", style = MaterialTheme.typography.labelLarge, color = fg)
            e.diffs.forEach { (l, o, n) -> Text("$l: $o \u2192 $n", style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = fg) }
            if (e.changed && e.diffs.isEmpty() && e.move != null) Text(if (e.move.toDate == date && e.move.fromDate != date) "Verlegt hierher von ${moveAt(e.move.fromDate, e.move.fromHours)}" else "Verlegt auf ${moveAt(e.move.toDate, e.move.toHours)}", style = MaterialTheme.typography.labelLarge, color = fg)
            else if (e.changed && e.diffs.isEmpty()) e.extra.firstOrNull { it.first == "Verlegung" }?.let { Text("Verlegt: " + it.second, style = MaterialTheme.typography.labelLarge, color = fg) }
        }
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
                    Column(Modifier.weight(1f).padding(2.dp).clip(RoundedCornerShape(10.dp)).background(if (es.isEmpty()) androidx.compose.ui.graphics.Color.Transparent else if (es.any { it.cancelled }) cs.errorContainer else if (es.any { it.changed }) cs.tertiaryContainer else cs.secondaryContainer).defaultMinSize(minHeight = 44.dp).padding(4.dp), verticalArrangement = Arrangement.Center) {
                        es.sortedBy { e -> if (e.move != null && e.move.fromDate == d.date && e.move.toDate != d.date) 1 else 0 }.forEach { e ->
                            val away = e.move != null && e.move.fromDate == d.date && e.move.toDate != d.date   // Stunde wurde von hier wegverlegt/getauscht → durchstreichen
                            Text(e.subject, style = MaterialTheme.typography.labelSmall, maxLines = 2, fontWeight = if (e.changed && !away) FontWeight.Bold else null, color = (if (e.cancelled) cs.onErrorContainer else if (e.changed) cs.onTertiaryContainer else cs.onSecondaryContainer).let { c -> if (away) c.copy(alpha = .6f) else c }, textDecoration = if (e.cancelled || away) TextDecoration.LineThrough else null) }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailDialog(t: Triple<ScheduleDay, Slot, LessonEntry>, times: List<TimeRange>, onSubject: (String) -> Unit, onDismiss: () -> Unit) {
    val (d, slot, e) = t; val time = slot.time ?: times.getOrNull(slot.index)
    AlertDialog(onDismissRequest = onDismiss, dismissButton = { TextButton(onDismiss) { Text("Schließen") } }, confirmButton = { FilledTonalButton({ onSubject(e.subject) }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Zum Fach") } }, title = { Text(e.subject) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (e.cancelled) "Entfällt" else if (e.changed) "Änderung / Vertretung" else "Planmäßig", style = MaterialTheme.typography.labelLarge)
            Text("${Dates.dayLong(d.date)} · ${slot.index + 1}. Position im Plan")
            time?.let { Text("${it.from}–${it.to}" + if (slot.time == null) " (eingestellte Unterrichtszeit)" else "") }
            e.extra.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) }
            e.diffs.forEach { (l, o, n) -> Text("$l geändert: $o \u2192 $n", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            e.reason?.let { Text("Grund: $it", style = MaterialTheme.typography.bodyMedium) }
        }
    })
}

@Composable internal fun TimesDialog(initial: List<TimeRange>, onSave: (List<TimeRange>) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf(initial.map { it.from to it.to }) }
    val ok = Regex("\\d\\d:\\d\\d")
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Unterrichtszeiten") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Eigene Zeiten (Format HH:MM). Ohne eigene Zeiten nutzt die App die Startzeiten des Portals.", style = MaterialTheme.typography.bodySmall)
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

/** Vertretungsplan der Schule für den gewählten Tag (alle Klassen), standardmäßig auf die eigenen Klassen gefiltert. */
@Composable private fun SubstitutionView(res: Result<DayChanges>?, mine: List<String>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme; var onlyMine by remember { mutableStateOf(mine.isNotEmpty()) }
    Column(modifier) {
        when {
            res == null -> SkeletonList()
            res.isFailure -> EmptyState(Icons.Rounded.CloudOff, "Vertretungen nicht lesbar", res.exceptionOrNull()?.message ?: "Das Portal hat keine Daten geliefert.")
            else -> { val d = res.getOrThrow(); val rows = if (onlyMine) d.rows.filter { ChangesParser.relevant(it, mine) } else d.rows
                if (mine.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(onlyMine, { onlyMine = true }, { Text("Meine Klasse") }); FilterChip(!onlyMine, { onlyMine = false }, { Text("Alle (${d.rows.size})") }) }
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
                    d.notes.forEach { n -> item { InfoBanner(n, Icons.Rounded.Info, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) } }
                    if (rows.isEmpty()) item { EmptyState(Icons.Rounded.CheckCircle, "Keine Vertretungen", if (onlyMine) "Für deine Klasse gibt es an diesem Tag keine Änderungen." else "Für diesen Tag sind keine Änderungen eingetragen.") }
                    val groups = pairSwaps(rows, d.date) { it.move }
                    itemsIndexed(groups) { i, g -> if (g.size == 2) { val (bg, fg) = cs.secondaryContainer to cs.onSecondaryContainer
                        SwapCard(SwapPart(g[0].subject, g[0].teacher, g[0].room, g[0].move!!), SwapPart(g[1].subject, g[1].teacher, g[1].room, g[1].move!!), d.date, bg, fg, MsdShapes.listShape(i, groups.size), Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).animateItem()) }
                        else SubstitutionCard(g[0], i, groups.size, Modifier.animateItem()) }
                }
            }
        }
    }
}

/** Karte für eine Vertretung: kräftige, kontrastreiche Farben je Art (Entfall / Änderung / Verlegung) und klare „alt → neu“-Zeilen. */
@Composable private fun SubstitutionCard(r: ChangeRow, i: Int, n: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when { r.cancelled -> cs.errorContainer to cs.onErrorContainer; r.moved -> cs.secondaryContainer to cs.onSecondaryContainer; else -> cs.tertiaryContainer to cs.onTertiaryContainer }
    fun arrow(s: String) = s.replace(" > ", " \u2192 ")
    val big = r.hours.substringBefore(' ').ifBlank { "–" }
    Row(modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).fillMaxWidth().clip(MsdShapes.listShape(i, n)).background(bg).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(56.dp).clip(RoundedCornerShape(16.dp)).background(fg.copy(alpha = .14f)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Std.", style = MaterialTheme.typography.labelSmall, color = fg); Text(big.substringBefore('>').trim(), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = fg)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(arrow(r.subject.ifBlank { "Änderung" }), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = fg, textDecoration = if (r.cancelled) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f, fill = false))
                Icon(if (r.cancelled) Icons.Rounded.EventBusy else Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp), tint = fg)
            }
            if (r.group.isNotBlank()) Text(arrow(r.group), style = MaterialTheme.typography.labelLarge, color = fg)
            Text(when { r.cancelled -> "Entfällt"; r.moved -> "Verlegt: ${arrow(r.hours)}"; else -> "Änderung" }, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = fg)
            if (r.teacher.isNotBlank()) Text("Lehrkraft: ${arrow(r.teacher)}", style = MaterialTheme.typography.bodyMedium, fontWeight = if (" > " in r.teacher) androidx.compose.ui.text.font.FontWeight.Bold else null, color = fg)
            if (r.room.isNotBlank()) Text("Raum: ${arrow(r.room)}", style = MaterialTheme.typography.bodyMedium, fontWeight = if (" > " in r.room) androidx.compose.ui.text.font.FontWeight.Bold else null, color = fg)
            if (r.info.isNotBlank()) Text(r.info, style = MaterialTheme.typography.bodyMedium, color = fg)
        }
    }
}
