package app.meineschule.expressive.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.SafeLink
import app.meineschule.expressive.core.Urls
import app.meineschule.expressive.ui.components.*

/** Weitere Links der Schule: nur validierte https-Ziele, Zielhost sichtbar, Bestätigung vor dem Öffnen, appMode „Disabled“ wird ausgeblendet. */
@Composable
fun LinksScreen(onBack: () -> Unit) {
    val app = LocalApp.current; val ctx = LocalContext.current; val school by app.session.school.collectAsState()
    var pending by remember { mutableStateOf<Pair<String, SafeLink>?>(null) }
    val raw = school?.links.orEmpty().filter { !it.appMode.equals("Disabled", true) }
    val links = raw.mapNotNull { l -> Urls.safeExternal(l.url)?.let { l.title to it } }
    val rejected = raw.size - links.size
    PageScaffold("Weitere Links", school?.name, onBack) { pad ->
        if (links.isEmpty()) EmptyState(Icons.Rounded.Link, "Keine Links", "Deine Schule hat keine (gültigen) Links hinterlegt.", Modifier.padding(top = pad.calculateTopPadding()))
        else LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding() + 8.dp, bottom = 24.dp)) {
            itemsIndexed(links) { i, (title, l) -> GroupedItem(i, links.size, title.ifBlank { l.host }, supporting = l.host, leading = { TileIcon(Icons.Rounded.Link) }, trailing = { Icon(Icons.Rounded.OpenInNew, "Extern öffnen") }, onClick = { pending = title to l }) }
            if (rejected > 0) item { Spacer(Modifier.height(12.dp)); InfoBanner("$rejected Link(s) wurden ausgeblendet, weil sie nicht sicher (https) oder ungültig sind.", Icons.Rounded.Shield, warn = true) }
        }
    }
    pending?.let { (title, l) ->
        AlertDialog(onDismissRequest = { pending = null }, icon = { Icon(Icons.Rounded.OpenInNew, null) }, title = { Text("Externe Seite öffnen?") },
            text = { Text("„$title“ öffnet ${l.host} im Browser. Diese Seite gehört nicht zur App.") },
            confirmButton = { Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); pending = null }, shapes = ButtonDefaults.shapes()) { Text("Öffnen") } },
            dismissButton = { TextButton({ pending = null }) { Text("Abbrechen") } })
    }
}
