package app.meineschule.expressive.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.components.*
import kotlinx.coroutines.launch

/** Ziel des Android-Teilen-Menüs: Text/Dateien an einen oder mehrere Chats senden (Dateien erst hochladen, dann Nachricht mit tempId). */
@Composable
fun ShareScreen(text: String?, uris: List<Uri>, nav: NavHostController) {
    val app = LocalApp.current; val ctx = LocalContext.current; val scope = rememberCoroutineScope(); val gen by app.session.generation.collectAsState()
    val chats = rememberLoader(gen) { app.repos.messenger.index().filter { it.canPost } }
    var picked by remember { mutableStateOf(setOf<String>()) }; var caption by remember { mutableStateOf(text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }; val snack = remember { SnackbarHostState() }
    fun temp() = buildString { repeat(40) { append(('A'..'Z').toList().plus('0'..'9').random()) } }
    PageScaffold("Teilen an …", "${uris.size} Datei(en)", { nav.popBackStack() }, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), label = { Text("Nachricht") }, minLines = 1, maxLines = 4)
            StateHost(chats, Modifier.weight(1f)) { list ->
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    itemsIndexed(list) { i, c ->
                        GroupedItem(i, list.size, c.name, leading = { Checkbox(c.id in picked, null) }, onClick = { picked = if (c.id in picked) picked - c.id else picked + c.id })
                    }
                }
            }
            LoadingButton("Senden (${picked.size})", busy, {
                busy = true
                scope.launch {
                    try {
                        val files = uris.map { ChatFiles.read(ctx, it) }
                        for (id in picked) {
                            if (files.isEmpty()) app.repos.messenger.send(id, caption.trim())
                            else files.forEachIndexed { i, (bytes, mime, name) -> val t = temp(); app.repos.messenger.upload(id, t, bytes, mime, name); app.repos.messenger.send(id, if (i == 0) caption.trim() else "", t) }
                        }
                        nav.popBackStack(); if (picked.size == 1) nav.navigate("chat/${picked.first()}")
                    } catch (e: Throwable) { busy = false; snack.showSnackbar(e.asApi().userText) }
                }
            }, Modifier.fillMaxWidth().padding(16.dp), enabled = picked.isNotEmpty() && (caption.isNotBlank() || uris.isNotEmpty()), icon = Icons.Rounded.Send)
        }
    }
}

private object ChatFiles { fun read(c: android.content.Context, u: Uri): Triple<ByteArray, String, String> = Files.read(c, u).let { Triple(it.first, it.second ?: "application/octet-stream", it.third) } }
