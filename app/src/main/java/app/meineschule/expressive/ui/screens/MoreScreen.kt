package app.meineschule.expressive.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.ui.components.*

private data class Entry(val title: String, val sub: String, val icon: ImageVector, val route: String)

/** „Mehr“: nur Module anzeigen, die School/Index tatsächlich freigibt. Module ohne eigenes Flag erscheinen und behandeln 403/404 als „nicht bereitgestellt“. */
@Composable
fun MoreScreen(nav: NavHostController) {
    val app = LocalApp.current; val school by app.session.school.collectAsState(); val profile by app.session.profile.collectAsState(); val s = school
    val tag by app.prefs.school.collectAsState(); val gen by app.session.generation.collectAsState()
    val mods = listOf("media", "care", "lost", "protocols")
    val avail = remember(tag) { mutableStateMapOf<String, Boolean>().apply { mods.forEach { m -> tag?.let { t -> app.prefs.avail(t, m)?.let { v -> put(m, v) } } } } }
    // Ungenutzte Dienste ausblenden: Liste leer und kein Recht zum Anlegen, oder vom Server nicht bereitgestellt (403/404). Netzwerkfehler ändern nichts.
    LaunchedEffect(tag, gen, s) {
        val t = tag ?: return@LaunchedEffect; val sc = s ?: return@LaunchedEffect
        suspend fun probe(m: String, f: suspend () -> Boolean) {
            try { val v = f(); avail[m] = v; app.prefs.setAvail(t, m, v) }
            catch (e: Throwable) { val k = (e as? app.meineschule.expressive.core.ApiException)?.kind; if (k == app.meineschule.expressive.core.ErrorKind.Forbidden || k == app.meineschule.expressive.core.ErrorKind.NotFound || k == app.meineschule.expressive.core.ErrorKind.Unsupported) { avail[m] = false; app.prefs.setAvail(t, m, false) } }
        }
        probe("media") { sc.canCreateMediaItems || app.repos.media.index().isNotEmpty() }
        probe("lost") { sc.canCreateLostAndFound || app.repos.lost.index().isNotEmpty() }
        probe("protocols") { sc.canCreateProtocol || app.repos.protocols.index().isNotEmpty() }
        probe("care") { app.repos.care.index().let { it.options.isNotEmpty() || it.students.isNotEmpty() } }
    }
    val entries = buildList {
        if (avail["media"] != false) add(Entry("Medien & Dateien", "Beiträge, Bilder und Anlagen", Icons.Rounded.PermMedia, "media"))
        if (s == null || s.userHasHIPAccess) add(Entry("Home.InfoPoint", "Noten, Fehlzeiten, Hausaufgaben", Icons.Rounded.HomeWork, "hip"))
        if (avail["care"] != false) add(Entry("Betreuung", "Abholzeiten und Betreuungsoptionen", Icons.Rounded.Schedule, "care"))
        if (avail["lost"] != false) add(Entry("Fundsachen", "Gefundenes ansehen oder melden", Icons.Rounded.Inventory2, "lost"))
        if (avail["protocols"] != false) add(Entry("Protokolle", "Sitzungen, Tagesordnungen und Dateien", Icons.Rounded.Description, "protocols"))
        if (s != null && s.links.isNotEmpty()) add(Entry("Weitere Links", "Von deiner Schule bereitgestellt", Icons.Rounded.Link, "links"))
        add(Entry("Profil", profile?.displayName ?: "Konto und Schulen", Icons.Rounded.Person, "profile"))
        add(Entry("Einstellungen", "Darstellung, Sicherheit, Datenschutz", Icons.Rounded.Tune, "settings"))
    }
    PageScaffold(title = "Mehr", subtitle = s?.name) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = 24.dp)) {
            itemsIndexed(entries, key = { _, e -> e.route }) { i, e -> GroupedItem(i, entries.size, e.title, modifier = Modifier.animateItem(), supporting = e.sub, leading = { TileIcon(e.icon) }, trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }, onClick = { nav.navigate(e.route) }) }
        }
    }
}
