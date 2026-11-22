package app.meineschule.expressive.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.core.ApiException
import app.meineschule.expressive.core.ErrorKind
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.theme.MsdShapes
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------- Zustände ----------------
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Ready<T>(val data: T) : UiState<T>
    data class Failed(val error: ApiException) : UiState<Nothing>
}

/** Lade-Holder: zeigt vorhandene Daten weiter an, während neu geladen wird (kein Flackern, Offline-freundlich). */
class Loader<T>(private val scope: CoroutineScope, private val load: suspend () -> T) {
    var state by mutableStateOf<UiState<T>>(UiState.Loading); private set
    var refreshing by mutableStateOf(false); private set
    private var job: Job? = null
    init { reload() }
    fun reload() {
        job?.cancel(); refreshing = true
        job = scope.launch {
            try { state = UiState.Ready(load()) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { if (state !is UiState.Ready) state = UiState.Failed(e.asApi()) }
            finally { refreshing = false }
        }
    }
    /** Lokale Änderung ohne Netzwerk (z. B. nach Aktionen). */
    fun update(f: (T) -> T) { (state as? UiState.Ready<T>)?.let { state = UiState.Ready(f(it.data)) } }
}

@Composable fun <T> rememberLoader(vararg keys: Any?, load: suspend () -> T): Loader<T> {
    val scope = rememberCoroutineScope()
    return remember(*keys) { Loader(scope, load) }
}

/** Einheitliche Darstellung: Skeleton → Inhalt / Leerzustand / Fehler, mit Pull-to-Refresh. */
@Composable fun <T> StateHost(
    loader: Loader<T>, modifier: Modifier = Modifier, isEmpty: (T) -> Boolean = { false }, empty: @Composable () -> Unit = {},
    skeleton: @Composable () -> Unit = { SkeletonList() }, content: @Composable (T) -> Unit,
) {
    PullToRefreshBox(isRefreshing = loader.refreshing && loader.state is UiState.Ready, onRefresh = { loader.reload() }, modifier = modifier) {
        when (val s = loader.state) {
            UiState.Loading -> skeleton()
            is UiState.Failed -> ErrorState(s.error, onRetry = { loader.reload() })
            is UiState.Ready -> if (isEmpty(s.data)) empty() else content(s.data)
        }
    }
}

// ---------------- Skeleton ----------------
@Composable fun shimmerBrush(): Brush {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-300f, 900f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh; val hi = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .9f)
    return Brush.linearGradient(listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 300f, 120f))
}
@Composable fun SkeletonBox(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) { Box(modifier.clip(shape).background(shimmerBrush())) }
@Composable fun SkeletonList(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp).semantics { contentDescription = "Lädt" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(count) { SkeletonBox(Modifier.fillMaxWidth().height(72.dp), MsdShapes.listShape(it, count)) }
    }
}

// ---------------- Empty / Error ----------------
@Composable fun EmptyState(icon: ImageVector, title: String, text: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, error: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(136.dp, 116.dp), contentAlignment = Alignment.Center) { // alles um die Mitte zentriert // dekorative Form-Komposition (je Zustand anders gefärbt)
            Box(Modifier.align(Alignment.Center).size(104.dp, 92.dp).clip(RoundedCornerShape(46.dp, 54.dp, 40.dp, 60.dp)).background(if (error) cs.errorContainer else cs.primaryContainer))
            Box(Modifier.align(Alignment.TopEnd).size(44.dp).rotate(14f).clip(RoundedCornerShape(14.dp)).background(cs.tertiaryContainer))
            Box(Modifier.align(Alignment.BottomStart).size(24.dp).clip(CircleShape).background(cs.secondaryContainer))
            Icon(icon, null, Modifier.size(44.dp), tint = if (error) cs.onErrorContainer else cs.onPrimaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
        if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(8.dp)); action() }
    }
}
@Composable fun ErrorState(e: ApiException, onRetry: (() -> Unit)? = null) {
    val icon = when (e.kind) { ErrorKind.Network -> Icons.Rounded.CloudOff; ErrorKind.Forbidden -> Icons.Rounded.Lock; ErrorKind.NotFound, ErrorKind.Unsupported -> Icons.Rounded.Info; ErrorKind.Auth, ErrorKind.Session -> Icons.Rounded.Key; else -> Icons.Rounded.Warning }
    EmptyState(icon, e.title, e.userText, error = true, action = if (e.retryable && onRetry != null) ({ FilledTonalButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Erneut versuchen") } }) else null)
}

// ---------------- Seiten-Gerüst ----------------
/** Seite mit großer, kollabierender Überschrift (Display-Typografie). */
@Composable fun PageScaffold(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {}, snackbarHost: @Composable () -> Unit = {}, onTitleClick: (() -> Unit)? = null, content: @Composable (PaddingValues) -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection), containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Column(if (onTitleClick != null) Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onTitleClick) else Modifier) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } },
                actions = actions, scrollBehavior = behavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        floatingActionButton = floatingActionButton, snackbarHost = snackbarHost, content = content,
    )
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)); trailing?.invoke()
    }
}

// ---------------- Listen ----------------
/** Gruppierte Listenzeile: äußere Ecken groß, innere klein (Material-3-Expressive-„Segmented List“). */
@Composable fun GroupedItem(
    index: Int, count: Int, title: String, modifier: Modifier = Modifier, supporting: String? = null, meta: String? = null, leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null, unread: Boolean = false, strike: Boolean = false, container: Color = MaterialTheme.colorScheme.surfaceContainerLow, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
) {
    val shape = MsdShapes.listShape(index, count); val cs = MaterialTheme.colorScheme
    val base = modifier.padding(horizontal = 16.dp).padding(bottom = 2.dp).fillMaxWidth().clip(shape).background(container)
    Row(
        (if (onClick != null) base.combinedClickable(onClick = onClick, onLongClick = onLongClick) else base).heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = if (unread) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (strike) androidx.compose.ui.text.style.TextDecoration.LineThrough else null, color = if (strike) cs.onSurfaceVariant else cs.onSurface)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (meta != null) Text(meta, style = MaterialTheme.typography.labelMedium, color = if (unread) cs.primary else cs.onSurfaceVariant)
        trailing?.invoke()
    }
}
fun LazyListScope.groupedItems(count: Int, item: @Composable (Int) -> Unit) { items(count) { item(it) } }

@Composable fun TileIcon(icon: ImageVector, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer, shape: Shape = MsdShapes.Tile) {
    Box(modifier.size(48.dp).clip(shape).background(container), contentAlignment = Alignment.Center) { Icon(icon, null, tint = content) }
}

/** Avatare haben je Person eine stabile, unterschiedliche Form und Tönung. Bild über den authentifizierten Coil-Loader. */
@Composable fun PersonAvatar(name: String, imageUrl: String? = null, size: androidx.compose.ui.unit.Dp = 48.dp, key: String = name) {
    val cs = MaterialTheme.colorScheme; val h = key.hashCode().let { if (it < 0) -it else it }
    val shape: Shape = listOf<Shape>(CircleShape, RoundedCornerShape(16.dp), MsdShapes.Cookie, RoundedCornerShape(topStartPercent = 38, topEndPercent = 62, bottomEndPercent = 45, bottomStartPercent = 55))[(h / 3) % 4]
    val (bg, fg) = listOf(cs.primaryContainer to cs.onPrimaryContainer, cs.tertiaryContainer to cs.onTertiaryContainer, cs.secondaryContainer to cs.onSecondaryContainer)[h % 3]
    Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Text(name.trim().split(Regex("\\s+")).take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }, color = fg, style = MaterialTheme.typography.titleMedium)
        val show by app.meineschule.expressive.LocalApp.current.prefs.avatars.collectAsState()
        if (imageUrl != null && show) AsyncImage(ImageRequest.Builder(LocalContext.current).data(imageUrl).build(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

@Composable fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false) {
    AlertDialog(
        onDismissRequest = onDismiss, shape = RoundedCornerShape(28.dp),
        icon = if (destructive) ({ Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) }) else null,
        title = { Text(title) }, text = { Text(text) },
        confirmButton = { Button(onClick = { onDismiss(); onConfirm() }, shapes = ButtonDefaults.shapes(), colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable fun LoadingButton(text: String, loading: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary) else if (icon != null) Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp)); Text(text)
    }
}

@Composable fun InfoBanner(text: String, icon: ImageVector = Icons.Rounded.Info, warn: Boolean = false, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (warn) cs.errorContainer else cs.tertiaryContainer).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (warn) cs.onErrorContainer else cs.onTertiaryContainer)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (warn) cs.onErrorContainer else cs.onTertiaryContainer)
    }
}

/** Profilbild groß ansehen (Doppeltipp/Zwei-Finger-Zoom, Tippen außerhalb schließt). */
@Composable fun ImageZoomDialog(url: String, name: String, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }; var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .92f)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss), contentAlignment = Alignment.Center) {
            AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).build(), name, Modifier.fillMaxWidth().aspectRatio(1f)
                .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero } }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 3f }, onTap = { }) }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            Text(name, Modifier.align(Alignment.BottomCenter).padding(24.dp), color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium)
            IconButton(onDismiss, Modifier.align(Alignment.TopEnd).padding(16.dp)) { Icon(Icons.Rounded.Close, "Schließen", tint = androidx.compose.ui.graphics.Color.White) }
        }
    }
}
