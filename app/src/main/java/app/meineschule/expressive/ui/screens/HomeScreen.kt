package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.data.*
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Dashboard: ein klarer Hero-Moment („Dein Tag“), danach ruhige, reale Daten. Keine erfundenen Inhalte – leere Bereiche zeigen Empty States. */
@Composable
fun HomeScreen(nav: NavHostController) {
    val app = LocalApp.current; val cs = MaterialTheme.colorScheme
    val profile by app.session.profile.collectAsState(); val school by app.session.school.collectAsState(); val state by app.session.state.collectAsState(); val gen by app.session.generation.collectAsState()
    val student = school?.students?.firstOrNull()
    val today = remember { LocalDate.now() }

    val schedule = rememberLoader(gen, student?.id, school != null) { if (school == null) null else app.repos.schedule(today, student?.id) }
    val events = rememberLoader(gen) { app.repos.calendar.index() }
    val chats = rememberLoader(gen, state?.unreadChatMessageCount) { if (school?.canUseMessenger == false) emptyList() else app.repos.messenger.index() }

    PageScaffold(
        title = "${Dates.greeting()}${profile?.displayName?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}!", subtitle = Dates.dayLong(today),
        actions = {
            IconButton(onClick = { nav.navigate("notifications") }) { BadgedBox(badge = { val n = (state?.unreadChatMessageCount ?: 0) + (state?.notConfirmedCalendar?.size ?: 0) + (state?.notConfirmedMedia?.size ?: 0); if (n > 0) Badge { Text(n.toString()) } }) { Icon(Icons.Rounded.Notifications, "Benachrichtigungen") } }
            IconButton(onClick = { nav.navigate("profile") }) { PersonAvatar(profile?.displayName ?: "?", profile?.userId?.let { app.repos.school.avatarUrl(it) }, 32.dp, profile?.userId ?: "") }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 24.dp)) {
            // Schulauswahl (nur bei mehreren Schulen)
            if ((profile?.schools?.size ?: 0) > 1) item { SchoolChips() }

            // ---- Hero: Heute ----
            item {
                val st = schedule.state
                val day = (st as? UiState.Ready)?.data?.days?.firstOrNull { it.date == today }
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(MsdShapes.Hero).background(cs.primaryContainer).clickable { nav.navigate("plan") }.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoStories, null, tint = cs.onPrimaryContainer); Spacer(Modifier.width(8.dp))
                        Text("Dein Tag", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(12.dp))
                    when {
                        st is UiState.Loading -> { SkeletonBox(Modifier.fillMaxWidth(.6f).height(36.dp)); Spacer(Modifier.height(8.dp)); SkeletonBox(Modifier.fillMaxWidth(.4f).height(18.dp)) }
                        st is UiState.Failed -> Text("Stundenplan gerade nicht verfügbar", style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                        day == null || day.activeSlots.isEmpty() -> Text("Heute ist kein Unterricht eingetragen", style = MaterialTheme.typography.headlineSmall, color = cs.onPrimaryContainer)
                        else -> {
                            // Lehrplan-Uhrzeiten/Räume sind im Portal-JSON nicht belegt → keine „In 12 Minuten“-Behauptungen.
                            val first = day.activeSlots.firstNotNullOfOrNull { s -> s.entries.firstOrNull { !it.cancelled } }
                            Text(first?.subject ?: "Alle Stunden entfallen", style = MaterialTheme.typography.displaySmall, color = cs.onPrimaryContainer)
                            Text("${day.lessonCount} Unterrichtsstunden heute", style = MaterialTheme.typography.bodyLarge, color = cs.onPrimaryContainer)
                            val cancelled = day.slots.sumOf { s -> s.entries.count { it.cancelled } }
                            if (cancelled > 0) { Spacer(Modifier.height(12.dp)); AssistChip(onClick = {}, label = { Text(if (cancelled == 1) "1 Stunde entfällt" else "$cancelled Stunden entfallen") }, leadingIcon = { Icon(Icons.Rounded.EventBusy, null, Modifier.size(18.dp)) }) }
                            Spacer(Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(day.activeSlots.flatMap { it.entries }) { e -> SuggestionChip(onClick = {}, label = { Text(e.subject) }, enabled = !e.cancelled) } }
                        }
                    }
                }
            }

            // ---- Schnellzugriffe ----
            item { SectionTitle("Schnellzugriff") }
            item {
                val quick = buildList {
                    add(Triple("Stundenplan", Icons.Rounded.CalendarViewWeek, "plan")); if (school?.canUseMessenger != false) add(Triple("Nachrichten", Icons.Rounded.ChatBubble, "chats"))
                    if (school?.userHasHIPAccess != false) { add(Triple("Hausaufgaben", Icons.Rounded.Assignment, "hip")); add(Triple("Noten", Icons.Rounded.Grade, "hip")) }
                    add(Triple("Kalender", Icons.Rounded.CalendarMonth, "calendar")); add(Triple("Abwesenheit", Icons.Rounded.EventBusy, "absence"))
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(quick.size) { i ->
                        val (label, icon, route) = quick[i]
                        val shape = listOf(RoundedCornerShape(28.dp), RoundedCornerShape(28.dp, 12.dp, 28.dp, 12.dp), RoundedCornerShape(50), RoundedCornerShape(12.dp, 28.dp, 12.dp, 28.dp))[i % 4] // wechselnde Formen
                        val (bg, fg) = listOf(cs.secondaryContainer to cs.onSecondaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer, cs.surfaceContainerHigh to cs.onSurface)[i % 3]
                        Column(Modifier.size(104.dp).clip(shape).background(bg).clickable { nav.navigate(route) }.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Icon(icon, null, tint = fg); Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                        }
                    }
                }
            }

            // ---- Nächste Termine ----
            item { SectionTitle("Nächste Termine", trailing = { TextButton(onClick = { nav.navigate("calendar") }) { Text("Alle") } }) }
            item {
                when (val s = events.state) {
                    UiState.Loading -> SkeletonList(2)
                    is UiState.Failed -> ErrorState(s.error) { events.reload() }
                    is UiState.Ready -> {
                        val next = s.data.filter { (Dates.date(it.start) ?: today) >= today }.sortedBy { Dates.parse(it.start) }.take(3)
                        if (next.isEmpty()) EmptyState(Icons.Rounded.EventAvailable, "Keine Termine", "Aktuell stehen keine Termine an.", Modifier.padding(vertical = 0.dp))
                        else Column { next.forEachIndexed { i, e -> GroupedItem(i, next.size, e.title, supporting = listOfNotNull(Dates.parse(e.start)?.let { d -> if (e.allDay) Dates.dayShort(d.toLocalDate()) else "${Dates.dayShort(d.toLocalDate())} · ${Dates.time(d)}" }, e.location).joinToString(" · "), leading = { TileIcon(Icons.Rounded.Event) }, onClick = { nav.navigate("calendar") }) } }
                    }
                }
            }

            // ---- Aktuelle Nachrichten ----
            if (school?.canUseMessenger != false) {
                item { SectionTitle("Aktuelle Nachrichten", trailing = { TextButton(onClick = { nav.navigate("chats") }) { Text("Alle") } }) }
                item {
                    when (val s = chats.state) {
                        UiState.Loading -> SkeletonList(2)
                        is UiState.Failed -> ErrorState(s.error) { chats.reload() }
                        is UiState.Ready -> { val top = s.data.take(3)
                            if (top.isEmpty()) EmptyState(Icons.Rounded.ChatBubbleOutline, "Noch keine Nachrichten", "Sobald dir jemand schreibt, siehst du es hier.")
                            else Column { top.forEachIndexed { i, c -> GroupedItem(i, top.size, c.name, supporting = c.teaser, unread = c.unread > 0, leading = { PersonAvatar(c.name, key = c.id) }, onClick = { nav.navigate("chat/${c.id}") }) } } }
                    }
                }
            }

            // ---- Hinweise (aus School/State) ----
            val pending = (state?.notConfirmedCalendar?.size ?: 0) to (state?.notConfirmedMedia?.size ?: 0)
            if (pending.first + pending.second > 0) {
                item { SectionTitle("Wichtige Hinweise") }
                if (pending.first > 0) item { InfoBanner("${pending.first} Termin(e) warten auf deine Bestätigung.", Icons.Rounded.EventRepeat) }
                if (pending.second > 0) item { InfoBanner("${pending.second} Medienbeitrag/-beiträge warten auf deine Bestätigung.", Icons.Rounded.PermMedia) }
            }
            if (school?.userHasHIPAccess == true) item { SectionTitle("Home.InfoPoint"); GroupedItem(0, 1, "Noten, Hausaufgaben & Fehlzeiten", supporting = "Informationen rund um den Schulalltag", leading = { TileIcon(Icons.Rounded.HomeWork) }, trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }, onClick = { nav.navigate("hip") }) }
        }
    }
}

@Composable private fun SchoolChips() {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val profile by app.session.profile.collectAsState(); val tag by app.session.tag.collectAsState()
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(profile?.schools ?: emptyList()) { s -> FilterChip(selected = s.tag == tag, onClick = { scope.launch { app.session.switchSchool(s.tag) } }, label = { Text(s.name) }, leadingIcon = { Icon(Icons.Rounded.School, null, Modifier.size(18.dp)) }) }
    }
}
