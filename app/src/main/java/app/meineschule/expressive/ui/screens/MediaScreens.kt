package app.meineschule.expressive.ui.screens

import app.meineschule.expressive.core.Urls
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.core.Dates
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.data.MediaItem
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
fun MediaScreen(nav: NavHostController) {
    val app = LocalApp.current; val gen by app.session.generation.collectAsState()
    var category by remember { mutableStateOf<String?>(null) }; var q by remember { mutableStateOf("") }
    val cats = rememberLoader(gen) { app.repos.media.categories() }
    val items = rememberLoader(gen, category) { app.repos.media.index(buildMap { category?.let { put("category", it) } }) }
    PageScaffold(title = "Medien", subtitle = "Beiträge deiner Schule", onBack = { nav.popBackStack() }) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding())) {
            (cats.state as? UiState.Ready)?.data?.takeIf { it.isNotEmpty() }?.let { c -> Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(category == null, { category = null }, { Text("Alle") }); c.forEach { FilterChip(category == it.id, { category = it.id }, { Text(it.name) }) } } }
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp, 8.dp), placeholder = { Text("Beiträge durchsuchen") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(50))
            StateHost(items, Modifier.weight(1f), isEmpty = { it.isEmpty() }, empty = { EmptyState(Icons.Rounded.PermMedia, "Aktuell sind keine Medien vorhanden", "Neue Beiträge deiner Schule erscheinen hier.") }) { all ->
                val list = all.filter { q.isBlank() || it.title.contains(q, true) || it.text?.contains(q, true) == true } // clientseitige Suche (kein dokumentierter Such-Parameter)
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { items(list, key = { it.id }) { m -> MediaCard(m) { nav.navigate("media/${m.id}") } } }
            }
        }
    }
}

@Composable private fun MediaCard(m: MediaItem, onClick: () -> Unit) {
    val app = LocalApp.current; val cs = MaterialTheme.colorScheme; val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 10.dp)).background(cs.surfaceContainerLow).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().height(170.dp).background(cs.tertiaryContainer)) {
            SubcomposeAsyncImage(ImageRequest.Builder(ctx).data(app.repos.media.imageUrl(m.id)).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, loading = { SkeletonBox(Modifier.fillMaxSize()) }, error = { Icon(Icons.Rounded.Image, null, Modifier.align(Alignment.Center).size(40.dp), tint = cs.onTertiaryContainer) })
            m.category?.let { SuggestionChip(onClick = {}, label = { Text(it) }, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) }
        }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleLarge); Dates.parse(m.date)?.let { Text(Dates.dayShort(it.toLocalDate()), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant) }
            m.text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, color = cs.onSurfaceVariant) }
            if (m.confirmed == false) AssistChip(onClick = onClick, label = { Text("Bestätigung offen") })
        }
    }
}

@Composable
fun MediaDetailScreen(id: String, onBack: () -> Unit) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val ctx = LocalContext.current; val cs = MaterialTheme.colorScheme; val school by app.session.school.collectAsState()
    var lang by remember { mutableStateOf<String?>(null) }; val snack = remember { SnackbarHostState() }
    val loader = rememberLoader(id, lang) { app.repos.media.detail(id, lang) }
    fun act(msg: String, f: suspend () -> Unit) = scope.launch { try { f(); loader.reload(); snack.showSnackbar(msg) } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } }
    PageScaffold(title = (loader.state as? UiState.Ready)?.data?.title ?: "Beitrag", onBack = onBack, snackbarHost = { SnackbarHost(snack) }) { pad ->
        StateHost(loader, Modifier.padding(top = pad.calculateTopPadding())) { m ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SubcomposeAsyncImage(ImageRequest.Builder(ctx).data(app.repos.media.imageUrl(m.id, 1024)).build(), m.title, Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(28.dp)), contentScale = ContentScale.Crop, loading = { SkeletonBox(Modifier.fillMaxWidth().height(200.dp)) }, error = { }) }
                item { Text(listOfNotNull(Dates.parse(m.date)?.let { Dates.dayShort(it.toLocalDate()) }, m.category).joinToString(" · "), color = cs.onSurfaceVariant) }
                school?.languages?.takeIf { it.isNotEmpty() }?.let { l -> item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(lang == null, { lang = null }, { Text("Original") }); l.forEach { FilterChip(lang == it.key, { lang = it.key }, { Text(it.name) }) } } } }
                m.text?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge) } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { act(if (m.liked) "Gefällt mir entfernt" else "Gefällt mir") { app.repos.media.toggleLike(id) } }, shapes = ButtonDefaults.shapes()) { Icon(if (m.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (m.liked) "Gefällt dir" else "Gefällt mir") }
                    if (m.confirmed == false) Button(onClick = { act("Bestätigt") { app.repos.media.confirm(id); app.session.refreshState() } }, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Bestätigen") } } }
                if (m.attachments.isNotEmpty()) item { SectionTitle("Anhänge") }
                items(m.attachments) { a -> AssistChip(onClick = { scope.launch { try { Files.open(ctx, app.api.bytes(app.repos.media.downloadUrl(a.id)), a.name) } catch (e: Throwable) { snack.showSnackbar(e.asApi().userText) } } }, label = { Text(Urls.safeFileName(a.name)) }, leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) }) }
            }
        }
    }
}
