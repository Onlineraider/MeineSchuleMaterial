package app.meineschule.expressive.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Fach-Übersicht: Lehrkräfte, anstehende Termine, Noten, Hausaufgaben, Fehlstunden, Dokumente und Chats des Fachs. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubjectScreen(name: String, studentId: String?, nav: NavHostController) {
    val app = LocalApp.current; val school by app.session.school.collectAsState(); val gen by app.session.generation.collectAsState()
    val sid = studentId ?: school?.students?.firstOrNull()?.id
    val ov by app.prefs.subjectOv.collectAsState(); val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<HipRow?>(null) }; var adjust by remember { mutableStateOf(false) }
    if (sid == null) { PageScaffold(name, null, { nav.popBackStack() }) { pad -> EmptyState(Icons.Rounded.School, "Kein Schüler", "Für diese Ansicht wird ein Schülerkonto benötigt.", Modifier.padding(top = pad.calculateTopPadding())) }; return }

    val subjectsL = rememberLoader(gen, sid) { app.subjects.list(sid) }
    val hipL = rememberLoader(gen, sid) { runCatching { app.repos.hip.student(sid) }.getOrNull() }
    val calL = rememberLoader(gen) { runCatching { app.repos.calendar.index() }.getOrDefault(emptyList()) }
    val subs = (subjectsL.state as? UiState.Ready)?.data; val hip = (hipL.state as? UiState.Ready)?.data
    val names = subs.orEmpty().map { it.name }; val hm = SubjectMatch.hipMap(hip)
    val resolved = subs?.let { SubjectMatch.resolve(name, names, hm, ov.alias) }; val subject = subs?.firstOrNull { it.name == resolved }

    val chatsL = rememberLoader(gen, subject?.name, ov, hm.size) { subject?.let { app.subjects.chatsFor(it, names, hm, ov) } }
    val match = (chatsL.state as? UiState.Ready)?.data
    val docsL = rememberLoader(gen, match?.chats?.map { it.id }) { match?.chats?.let { app.subjects.documents(it) } ?: emptyList() }
    val absL = rememberLoader(gen, sid, subject?.name, hm.size, hip != null) { if (subject != null && hip != null) app.subjects.absences(subject, sid, hip, names, hm, ov.alias) else emptyList() }

    PageScaffold(title = subject?.name ?: name, subtitle = school?.students?.firstOrNull { it.id == sid }?.let { it.name }, onBack = { nav.popBackStack() },
        actions = { if (subject != null) IconButton({ adjust = true }) { Icon(Icons.Rounded.Tune, "Fach anpassen") } }) { pad ->
        StateHost(subjectsL, Modifier.padding(top = pad.calculateTopPadding())) { _ ->
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                if (subject == null) {
                    item { InfoBanner("Das Kürzel „$name“ ist noch keinem Fach zugeordnet. Wähle das passende Fach:", Icons.Rounded.HelpOutline, modifier = Modifier.padding(16.dp)) }
                    itemsIndexed(names) { i, n -> GroupedItem(i, names.size, n, leading = { TileIcon(Icons.Rounded.MenuBook) }, onClick = { app.prefs.setAlias(name, n) }) }
                    return@LazyColumn
                }
                // Kopf: Kürzel
                val aliases = (ov.alias.filterValues { it == subject.name }.keys + hm.filterValues { SubjectMatch.key(it) == SubjectMatch.key(subject.name) }.keys).distinct()
                if (aliases.isNotEmpty()) item { FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { aliases.forEach { AssistChip(onClick = {}, label = { Text(it.replaceFirstChar { c -> c.uppercase() }) }, leadingIcon = { Icon(Icons.Rounded.Label, null, Modifier.size(18.dp)) }) } } }

                // Lehrkräfte
                if (subject.teachers.isNotEmpty()) {
                    item { SectionTitle(if (subject.teachers.size == 1) "Lehrkraft" else "Lehrkräfte") }
                    itemsIndexed(subject.teachers) { i, p -> GroupedItem(i, subject.teachers.size, p.name, supporting = p.type, leading = { PersonAvatar(p.name, p.userId?.let { u -> app.repos.school.avatarUrl(u) }, 44.dp, key = p.userId ?: p.name) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { p.userId?.let { u -> nav.navigate("person?u=${Uri.encode(u)}&n=${Uri.encode(p.name)}") } }) }
                }

                // Anstehende Termine (Tests …)
                val today = LocalDate.now()
                val events = (calL.state as? UiState.Ready)?.data.orEmpty().filter { e -> SubjectMatch.mentions(e.title, subject.name, names, hm, ov.alias) && (Dates.parse(e.end ?: e.start)?.toLocalDate() ?: today) >= today }.sortedBy { it.start.orEmpty() }.take(8)
                if (events.isNotEmpty()) {
                    item { SectionTitle("Anstehende Termine") }
                    itemsIndexed(events) { i, e -> GroupedItem(i, events.size, e.title, supporting = Dates.parse(e.start)?.let { Dates.dayShort(it.toLocalDate()) + if (e.start?.contains('T') == true) " · " + Dates.time(it) else "" }, leading = { TileIcon(Icons.Rounded.Event) }, onClick = { nav.navigate("calendar") }) }
                }

                // Noten
                val grades = hip?.tables.orEmpty().filter { it.cls == HipClass.Grd || it.cls == HipClass.Rem }.filter { t ->
                    val n = Regex("\\p{L}{1,6}\\s*[-–]\\s*([^:]+)$").find(t.title.trim())?.groupValues?.get(1)?.trim() ?: t.title.substringAfterLast(':').trim()
                    SubjectMatch.resolve(n, names, hm, ov.alias) == subject.name || SubjectMatch.mentions(t.title, subject.name, names, hm, ov.alias)
                }
                val gRows = grades.flatMap { t -> t.rows.map { HipRow.of(t.cls, t, it) } }.filter { it.grade != null }
                if (gRows.isNotEmpty()) {
                    val avg = gRows.mapNotNull { it.grade?.replace(',', '.')?.toDoubleOrNull() }.filter { it in 1.0..6.0 }.let { if (it.isEmpty()) null else it.average() }
                    item { SectionTitle("Noten", trailing = { avg?.let { Text("Ø " + "%.2f".format(it).replace('.', ','), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) } }) }
                    itemsIndexed(gRows) { i, r -> GroupedItem(i, gRows.size, r.title, supporting = r.body, leading = r.grade?.let { g -> ({ GradeBadge(g) }) }, trailing = { r.date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { detail = r }) }
                } else if (hip != null) item { SectionTitle("Noten"); Text("Noch keine Noten eingetragen.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                // Hausaufgaben
                val hw = hip?.of(HipClass.Hw).orEmpty().flatMap { t -> t.rows.filter { r -> r.any { SubjectMatch.mentions(it, subject.name, names, hm, ov.alias) } || SubjectMatch.mentions(t.title, subject.name, names, hm, ov.alias) }.map { HipRow.of(HipClass.Hw, t, it) } }.take(6)
                if (hw.isNotEmpty()) {
                    item { SectionTitle("Hausaufgaben") }
                    itemsIndexed(hw) { i, r -> GroupedItem(i, hw.size, r.title, supporting = r.body, leading = { TileIcon(Icons.Rounded.Assignment) }, trailing = { r.date?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, onClick = { detail = r }) }
                }

                // Fehlstunden
                val abs = (absL.state as? UiState.Ready)?.data.orEmpty()
                if (hip != null) {
                    item { SectionTitle("Fehlstunden", trailing = { if (absL.state is UiState.Loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else if (abs.isNotEmpty()) Text("${abs.sumOf { it.second }} gesamt", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }) }
                    if (abs.isEmpty() && absL.state !is UiState.Loading) item { Text(((absL.state as? UiState.Ready)?.data?.size ?: 0).let { "Keine Fehlstunden in diesem Fach gefunden (letzte 4 Monate; Zuordnung über den Stundenplan der jeweiligen Woche)." }, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(abs) { i, (d, n) -> GroupedItem(i, abs.size, Dates.dayLong(d), supporting = "$n ${if (n == 1) "Stunde" else "Stunden"} gefehlt", leading = { TileIcon(Icons.Rounded.EventBusy) }) }
                }

                // Dokumente aus Chats
                val docs = (docsL.state as? UiState.Ready)?.data.orEmpty()
                item { SectionTitle("Dokumente", trailing = { if (chatsL.state is UiState.Loading || docsL.state is UiState.Loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) }
                if (docs.isEmpty() && docsL.state !is UiState.Loading) item { Text("Keine Dateianhänge in den zugeordneten Chats gefunden.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(docs.take(30)) { i, d ->
                    GroupedItem(i, docs.take(30).size, d.name, supporting = listOfNotNull(d.chatName, d.author, Dates.parse(d.at)?.let { Dates.dayShort(it.toLocalDate()) }).joinToString(" · "), leading = { TileIcon(Icons.Rounded.AttachFile) },
                        onClick = { scope.launch { runCatching { Files.open(ctx, app.api.bytes(app.repos.messenger.downloadUrl(d.chatId, d.attachmentId)), d.name) } } })
                }

                // Zugeordnete Chats
                val chats = match?.chats.orEmpty()
                if (chats.isNotEmpty()) {
                    item { SectionTitle("Chats zu diesem Fach") }
                    itemsIndexed(chats) { i, c -> GroupedItem(i, chats.size, c.name, supporting = c.teaser, unread = c.unread > 0, leading = { PersonAvatar(c.name, key = c.id) }, onClick = { nav.navigate("chat/${c.id}") }) }
                }
                item { Box(Modifier.padding(16.dp)) { TextButton({ adjust = true }) { Icon(Icons.Rounded.Tune, null); Spacer(Modifier.width(8.dp)); Text("Kürzel und Chats anpassen") } } }
            }
        }
    }
    detail?.let { HipDetailSheet(it) { detail = null } }
    if (adjust && subject != null) AdjustSheet(subject, aliases = ov.alias.filterValues { it == subject.name }.keys.toList(), hipAliases = hm.filterValues { SubjectMatch.key(it) == SubjectMatch.key(subject.name) }.keys.toList(), match = match, onDismiss = { adjust = false })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun AdjustSheet(subject: Subject, aliases: List<String>, hipAliases: List<String>, match: ChatMatch?, onDismiss: () -> Unit) {
    val app = LocalApp.current; var newAlias by remember { mutableStateOf("") }; var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${subject.name} anpassen", style = MaterialTheme.typography.headlineSmall)
            Text("Kürzel", style = MaterialTheme.typography.titleMedium)
            Text("Kürzel aus Stundenplan und Terminen werden automatisch erkannt. Fehlt eines, füge es hier hinzu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hipAliases.forEach { AssistChip(onClick = {}, label = { Text(it.replaceFirstChar { c -> c.uppercase() } + " (erkannt)") }) }
                aliases.forEach { a -> InputChip(false, { app.prefs.setAlias(a, null) }, { Text(a.replaceFirstChar { c -> c.uppercase() }) }, trailingIcon = { Icon(Icons.Rounded.Close, "Entfernen", Modifier.size(18.dp)) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newAlias, { newAlias = it }, Modifier.weight(1f), singleLine = true, label = { Text("Neues Kürzel, z. B. Phy") })
                FilledTonalButton({ app.prefs.setAlias(newAlias, subject.name); newAlias = "" }, enabled = newAlias.isNotBlank(), shapes = ButtonDefaults.shapes()) { Text("Hinzufügen") }
            }
            HorizontalDivider()
            Text("Chats", style = MaterialTheme.typography.titleMedium)
            Text("Zugeordnet wird nach Chatname sowie nach den Mitgliedern (nur Lehrkräfte des Fachs und eine Gruppe). Hake Chats ab oder an, wenn die Erkennung falsch liegt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) }, label = { Text("Chats durchsuchen") })
            if (match == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            val chosen = match?.chats?.map { it.id }?.toSet().orEmpty()
            match?.all.orEmpty().filter { q.isBlank() || it.name.contains(q, true) }.sortedByDescending { it.id in chosen }.take(60).forEach { c ->
                val inc = c.id in chosen; val auto = c.id in match!!.auto
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (inc) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) { Text(c.name, style = MaterialTheme.typography.bodyLarge); if (inc) Text(if (auto) "Automatisch erkannt" else "Manuell hinzugefügt", style = MaterialTheme.typography.labelSmall) }
                    Checkbox(inc, { want -> app.prefs.setChat(subject.name, c.id, if (want) (if (auto) 0 else 1) else (if (auto) -1 else 0)) })
                }
            }
        }
    }
}

/** Liste aller Fächer des Schülers. */
@Composable
fun SubjectsScreen(nav: NavHostController) {
    val app = LocalApp.current; val school by app.session.school.collectAsState(); val gen by app.session.generation.collectAsState()
    val students = school?.students.orEmpty(); var sel by remember(students) { mutableStateOf(students.firstOrNull()?.id) }
    val sid = sel
    val subjects = rememberLoader(gen, sid) { if (sid == null) emptyList() else app.subjects.list(sid) }
    PageScaffold("Fächer", students.firstOrNull { it.id == sid }?.name, { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            if (students.size > 1) Row(Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { students.forEach { s -> FilterChip(s.id == sid, { sel = s.id }, { Text(s.name) }) } }
            StateHost(subjects, Modifier.weight(1f), isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.School, "Keine Fächer", "Für dieses Konto sind keine Fächer hinterlegt.") }) { list ->
                LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp)) {
                    itemsIndexed(list, key = { _, s -> s.name }) { i, s -> GroupedItem(i, list.size, s.name, modifier = Modifier.animateItem(), supporting = s.teachers.joinToString(", ") { it.name }.ifBlank { null }, leading = { TileIcon(Icons.Rounded.MenuBook) }, trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                        onClick = { nav.navigate("subject?n=${Uri.encode(s.name)}" + (sid?.let { "&s=${Uri.encode(it)}" } ?: "")) }) }
                }
            }
        }
    }
}
