package app.meineschule.expressive.ui.screens

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

/** Home.InfoPoint: serverseitiges HTML wird bereinigt (Jsoup-Safelist) und nativ dargestellt – nie in einer WebView. */
@Composable
fun HipScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val gen by app.session.generation.collectAsState(); val school by app.session.school.collectAsState()
    val students = school?.students.orEmpty(); var idx by remember { mutableIntStateOf(0) }; var tab by remember { mutableStateOf(HipClass.Abs) }
    val student = students.getOrNull(idx)
    val loader = rememberLoader(gen, student?.id, school != null) { if (student == null) HipData(emptyList(), emptyList()) else app.repos.hip.student(student.id) }
    PageScaffold(title = "Home.InfoPoint", subtitle = student?.name, onBack = onBack) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            if (students.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { students.forEachIndexed { i, s -> FilterChip(i == idx, { idx = i }, { Text(s.name) }) } }
            StateHost(loader, Modifier.weight(1f), isEmpty = { it.isEmpty }, empty = { EmptyState(Icons.Rounded.Grade, "Keine Informationen verfügbar", "Für diese Person liegen im Home.InfoPoint aktuell keine Daten vor.") }) { d ->
                val kinds = (listOf(HipClass.Abs, HipClass.Rem) + HipClass.entries.drop(2).filter { c -> d.of(c).isNotEmpty() }).let { if (d.text.isNotEmpty()) it + HipClass.Oth else it }.distinct()
                val cur = if (tab in kinds) tab else kinds.first()
                Column {
                    PrimaryScrollableTabRow(kinds.indexOf(cur), edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface) { kinds.forEach { k -> Tab(cur == k, { tab = k }, text = { Text(k.label) }) } }
                    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (cur == HipClass.Abs) {
                            val a = HipParser.absSummary(d.of(HipClass.Abs))
                            item { Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Stat("${a.daysExcused}", "Fehltage entschuldigt", Modifier.weight(1f)); Stat("${a.daysUnexcused}", "Fehltage unentschuldigt", Modifier.weight(1f)) } }
                            item { Spacer(Modifier.height(8.dp)); Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Stat("${a.hoursExcused}", "Fehlstunden entschuldigt", Modifier.weight(1f)); Stat("${a.hoursUnexcused}", "Fehlstunden unentschuldigt", Modifier.weight(1f)) } }
                            item { SectionTitle("Fehlzeiten") }
                            if (a.entries.isEmpty()) item { EmptyState(Icons.Rounded.EventAvailable, "Keine Fehlzeiten eingetragen", null) } else itemsIndexed(a.entries) { i, e -> GroupedItem(i, a.entries.size, e.day, supporting = e.text.ifBlank { null }, leading = { TileIcon(Icons.Rounded.EventBusy) }) }
                        } else {
                            val ts = d.of(cur)
                            if (ts.isEmpty() && d.text.isEmpty()) item { EmptyState(Icons.Rounded.Notes, "Keine Einträge", null) }
                            ts.forEach { t ->
                                if (t.title.isNotBlank()) item { SectionTitle(t.title) }
                                itemsIndexed(t.rows) { i, r ->
                                    val gi = if (cur == HipClass.Grd) t.head.indexOfFirst { Regex("note|zensur|bewertung", RegexOption.IGNORE_CASE).containsMatchIn(it) } else -1
                                    val cells = r.indices.filter { it != gi && r[it].isNotBlank() }
                                    GroupedItem(i, t.rows.size, cells.firstOrNull()?.let { r[it] } ?: "–", supporting = cells.drop(1).joinToString("\n") { c -> t.head.getOrNull(c)?.takeIf { it.isNotBlank() }?.let { h -> "$h: ${r[c]}" } ?: r[c] }.ifBlank { null },
                                        trailing = if (gi >= 0 && r.getOrNull(gi)?.isNotBlank() == true) ({ GradeBadge(r[gi]) }) else null)
                                }
                            }
                            if (cur == HipClass.Oth && d.text.isNotEmpty()) { item { SectionTitle("Weitere Informationen") }; itemsIndexed(d.text) { i, t -> GroupedItem(i, d.text.size, t) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Stat(n: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(cs.secondaryContainer).padding(16.dp)) { Text(n, style = MaterialTheme.typography.headlineMedium, color = cs.onSecondaryContainer); Text(label, style = MaterialTheme.typography.labelMedium, color = cs.onSecondaryContainer) }
}

@Composable private fun GradeBadge(value: String) { val cs = MaterialTheme.colorScheme; Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) { Text(value, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer) } }
