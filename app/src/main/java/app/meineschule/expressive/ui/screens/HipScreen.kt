package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
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

private val tabs = listOf("Noten", "Fehlzeiten", "Bemerkungen", "Hausaufgaben")

/** Home.InfoPoint: serverseitiges HTML wird bereinigt (Jsoup-Safelist) und nativ dargestellt – nie in einer WebView. */
@Composable
fun HipScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    val students = school?.students.orEmpty(); var idx by remember { mutableIntStateOf(0) }; var tab by remember { mutableIntStateOf(0) }
    val student = students.getOrNull(idx)
    val loader = rememberLoader(gen, student?.id, school != null) { if (student == null) HipData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false) else app.repos.hip.student(student.id) }
    // Optionale, clientseitige Fristableitung aus dem Stundenplan – wird in der UI ausdrücklich als „ermittelt“ gekennzeichnet.
    val sched = rememberLoader(gen, student?.id) { runCatching { app.repos.schedule(LocalDate.now(), student?.id) }.getOrNull() }
    PageScaffold(title = "Home.InfoPoint", subtitle = student?.name, onBack = onBack) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            if (students.size > 1) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { students.forEachIndexed { i, s -> FilterChip(i == idx, { idx = i }, { Text(s.name) }) } }
            PrimaryScrollableTabRow(tab, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.isEmpty }, empty = { EmptyState(Icons.Rounded.Grade, "Keine Informationen verfügbar", "Für diese Person liegen im Home.InfoPoint aktuell keine Daten vor.") }) { d ->
                LazyColumn(contentPadding = PaddingValues(vertical = 12.dp, horizontal = 0.dp)) {
                    when (tab) {
                        0 -> if (d.grades.isEmpty()) item { EmptyState(Icons.Rounded.Grade, "Keine Noten verfügbar", null) } else d.grades.groupBy { it.subject }.forEach { (subj, gs) ->
                            item { SectionTitle(subj) }
                            itemsIndexed(gs) { i, g -> GroupedItem(i, gs.size, g.remark.ifBlank { "Bewertung" }, supporting = listOf(g.date, g.information).filter { it.isNotBlank() }.joinToString(" · "), trailing = { GradeBadge(g.value) }) }
                        }
                        1 -> if (d.absences.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Keine Fehlzeiten", null) } else itemsIndexed(d.absences) { i, a -> GroupedItem(i, d.absences.size, a.date, supporting = a.remark.ifBlank { "Keine Begründung" }, leading = { TileIcon(Icons.Rounded.EventBusy) }) }
                        2 -> if (d.remarks.isEmpty()) item { EmptyState(Icons.Rounded.Notes, "Keine Bemerkungen", null) } else itemsIndexed(d.remarks) { i, r -> GroupedItem(i, d.remarks.size, r.text, supporting = listOf(r.date, r.subject, r.type, r.author).filter { it.isNotBlank() }.joinToString(" · "), leading = { TileIcon(Icons.Rounded.Notes) }) }
                        else -> {
                            val days = (sched.state as? UiState.Ready)?.data?.days.orEmpty()
                            if (d.lessons.isEmpty()) item { EmptyState(Icons.Rounded.Assignment, "Keine Hausaufgaben", null) } else itemsIndexed(d.lessons) { i, l ->
                                val due = l.due ?: HipParser.inferDue(l, days)
                                GroupedItem(i, d.lessons.size, "${l.subject} · ${l.date}", supporting = buildString { append(l.homework.ifBlank { "Keine Aufgabe eingetragen" }); due?.let { append("\nFällig: ${Dates.dayShort(it.date)}${if (it.source == DueSource.Inferred) " (ermittelt aus Stundenplan)" else ""}") } }, leading = { TileIcon(Icons.Rounded.Assignment) })
                            }
                        }
                    }
                    if (d.otherText.isNotEmpty()) { item { SectionTitle("Weitere Informationen") }; itemsIndexed(d.otherText) { i, t -> GroupedItem(i, d.otherText.size, t) } }
                }
            }
        }
    }
}

@Composable private fun GradeBadge(value: String) { val cs = MaterialTheme.colorScheme; Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer) } }
