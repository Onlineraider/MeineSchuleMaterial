package app.meineschule.expressive.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.auth.AuthState
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.nav.MainShell
import app.meineschule.expressive.ui.screens.LoginScreen
import app.meineschule.expressive.work.Notifier
import kotlinx.coroutines.launch

@Composable
fun AppRoot(locked: Boolean, onUnlock: () -> Unit) {
    val app = LocalApp.current
    val auth by app.auth.state.collectAsState()
    LaunchedEffect(Unit) { if (app.auth.state.value == AuthState.Unknown) app.auth.restore() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(targetState = auth::class, transitionSpec = { (fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec(), .94f)) togetherWith fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) }, label = "auth") { kind ->
            when (kind) {
                AuthState.Unknown::class -> Splash()
                AuthState.SignedIn::class -> if (locked) LockScreen(onUnlock) else SignedInHost()
                else -> LoginScreen(sessionExpired = auth is AuthState.SessionExpired)
            }
        }
    }
}

@Composable private fun Splash() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }

@Composable private fun LockScreen(onUnlock: () -> Unit) = EmptyState(Icons.Rounded.Lock, "MeineSchule ist gesperrt", "Entsperre die App mit Fingerabdruck, Gesicht oder Geräte-PIN.", Modifier.fillMaxSize().wrapContentHeight(), action = { Button(onClick = onUnlock, shapes = ButtonDefaults.shapes()) { Text("Entsperren") } })

/** Lädt Profil/Schule nach Login/Neustart, startet SignalR und lokale Benachrichtigungen. */
@Composable private fun SignedInHost() {
    val app = LocalApp.current; val scope = rememberCoroutineScope()
    val profile by app.session.profile.collectAsState()
    var error by remember { mutableStateOf<app.meineschule.expressive.core.ApiException?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) { error = null; try { app.session.bootstrap() } catch (e: Throwable) { error = e.asApi() } }
    LaunchedEffect(Unit) {
        app.hub.start()
        app.hub.events.collect {
            app.session.refreshState()
            if (!app.inForeground && app.prefs.notifications.value) Notifier.notifyUnread(app.context, app.session.state.value?.unreadChatMessageCount ?: 1)
        }
    }
    val notify by app.prefs.notifications.collectAsState()
    LaunchedEffect(notify) { Notifier.schedule(app.context, notify && app.auth.remember) }
    DisposableEffect(Unit) { onDispose { app.hub.stop() } }

    when {
        profile != null -> MainShell()
        error != null -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            ErrorState(error!!, onRetry = { attempt++ })
            TextButton(onClick = { app.auth.logout(); app.session.clear() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Abmelden") }
        }
        else -> Splash()
    }
}
