package app.meineschule.expressive.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.meineschule.expressive.AppContainer
import app.meineschule.expressive.BuildConfig
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.Prefs
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.work.Notifier
import app.meineschule.expressive.net.DebugLog
import kotlinx.coroutines.launch

/** Vollständiges Abmelden: Tokens, Sitzung, Hub, Hintergrundjobs und heruntergeladene Dateien werden entfernt. */
fun fullLogout(app: AppContainer) {
    app.hub.stop()
    Notifier.schedule(app.context, false)
    Files.clearCache(app.context)
    app.session.clear()
    app.auth.logout()            // setzt den Zustand auf „abgemeldet“ → Login-Bildschirm; löscht gespeicherte Sitzung
    app.prefs.setSchool(null)
    app.store.wipe()
}

@Composable
fun SettingsScreen(onBack: () -> Unit, nav: androidx.navigation.NavHostController) {
    val app = LocalApp.current; val ctx = LocalContext.current
    val mode by app.prefs.mode.collectAsState(); val dynamic by app.prefs.dynamicColor.collectAsState(); val seed by app.prefs.seed.collectAsState()
    val notify by app.prefs.notifications.collectAsState(); val bio by app.prefs.biometricLock.collectAsState()
    var remembered by remember { mutableStateOf(app.auth.remember) }
    var dlg by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> app.prefs.setNotifications(ok) }
    val canBio = remember { BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS }

    val debug by app.prefs.debug.collectAsState(); var taps by remember { mutableIntStateOf(0) }; var lastTap by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    PageScaffold("Einstellungen", null, onBack, snackbarHost = { SnackbarHost(snack) }, onTitleClick = {
        val now = System.currentTimeMillis(); taps = if (now - lastTap < 1500) taps + 1 else 1; lastTap = now
        if (taps >= 7) { taps = 0; app.prefs.setDebug(!debug); scope.launch { snack.showSnackbar(if (!debug) "Debug-Modus aktiviert" else "Debug-Modus beendet") } }
    }) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 32.dp)) {
            if (debug) {
                item { SectionTitle("Debug") }
                item { SwitchRow("Debug-Modus aktiv", "Server-Antworten werden nur im Arbeitsspeicher protokolliert (ohne Tokens/Passwörter). Zum Beenden 7× auf „Einstellungen“ tippen.", true, true, Icons.Rounded.BugReport) { app.prefs.setDebug(it) } }
                item { Box(Modifier.padding(horizontal = 16.dp)) { FilledTonalButton({ nav.navigate("debuglog") }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.DataObject, null); Spacer(Modifier.width(8.dp)); Text("Antwort-Log ansehen (${DebugLog.entries.collectAsState().value.size})") } } }
            }
            item { SectionTitle("Darstellung") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val opts = listOf(Prefs.Mode.System to "System", Prefs.Mode.Light to "Hell", Prefs.Mode.Dark to "Dunkel")
                        opts.forEachIndexed { i, (m, l) -> SegmentedButton(mode == m, { app.prefs.setMode(m) }, SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(l) } }
                    }
                }
            }
            item { SwitchRow("Dynamische Farben", if (Build.VERSION.SDK_INT >= 31) "Farben aus deinem Hintergrundbild (Material You)" else "Ab Android 12 verfügbar – es wird die Ausweichpalette genutzt", dynamic && Build.VERSION.SDK_INT >= 31, Build.VERSION.SDK_INT >= 31, Icons.Rounded.Palette) { app.prefs.setDynamic(it) } }
            item {
                val usingFallback = !dynamic || Build.VERSION.SDK_INT < 31
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text("Ausweichfarbe", style = MaterialTheme.typography.titleSmall, color = if (usingFallback) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(Prefs.Seed.entries) { s ->
                            val sel = s == seed
                            FilterChip(sel, { app.prefs.setSeed(s) }, { Text(s.label) }, enabled = usingFallback, leadingIcon = { if (sel) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) })
                        }
                    }
                }
            }

            item { SectionTitle("Benachrichtigungen") }
            item {
                SwitchRow("Hinweis bei neuen Nachrichten", "Lokal, ohne Inhalt. Echtes Push (FCM) ist nicht eingerichtet; im Hintergrund wird etwa alle 15 Minuten geprüft – das setzt „Angemeldet bleiben“ voraus.", notify, true, Icons.Rounded.Notifications) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else app.prefs.setNotifications(on)
                }
            }

            item { SectionTitle("Sicherheit & Datenschutz") }
            item {
                SwitchRow("Angemeldet bleiben", if (remembered) "Die Sitzung liegt verschlüsselt im Android-Keystore. Es wird nie ein Passwort gespeichert." else "Aus: Nach dem Beenden der App musst du dich neu anmelden.", remembered, true, Icons.Rounded.Key) { on ->
                    if (on) dlg = "remember" else { app.auth.forgetStored(); remembered = false; Notifier.schedule(ctx, false) }
                }
            }
            item { SwitchRow("App-Sperre", if (canBio) "Entsperren per Fingerabdruck, Gesicht oder Geräte-PIN" else "Auf diesem Gerät ist keine Bildschirmsperre/Biometrie eingerichtet", bio && canBio, canBio, Icons.Rounded.Fingerprint) { app.prefs.setBiometric(it) } }
            item { ActionRow("Gespeicherte Anmeldung löschen", "Entfernt die Sitzung aus dem Keystore; du bleibst bis zum Beenden angemeldet.", Icons.Rounded.NoEncryption, enabled = remembered) { dlg = "forget" } }
            item { ActionRow("Lokale Daten bereinigen", "Löscht heruntergeladene Anhänge und den Bildcache.", Icons.Rounded.CleaningServices) { Files.clearCache(ctx); app.context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } } }
            item { ActionRow("Abmelden", "Beendet die Sitzung und löscht alle lokal gespeicherten Anmeldedaten.", Icons.Rounded.Logout, danger = true) { dlg = "logout" } }

            item { SectionTitle("Über die App") }
            item {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inoffizieller Client für MeineSchule.digital. Version ${BuildConfig.VERSION_NAME}. Server: ${app.originHost}", style = MaterialTheme.typography.bodyMedium)
                    Text("Datenschutz: Die App spricht ausschließlich per HTTPS mit deinem Schulserver. Tokens und Passwörter werden nicht protokolliert; Passwörter werden nie gespeichert. Berechtigungen entscheidet immer der Server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("API-Grundlage: reverse-engineerte Dokumentation (MSD-API-SPEC). Nicht dokumentierte Funktionen sind in der App nicht enthalten. Open-Source-Bibliotheken: Jetpack Compose, OkHttp, Coil, Jsoup, SignalR-Java-Client, kotlinx.serialization, WorkManager (alle Apache-2.0).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    when (dlg) {
        "remember" -> ConfirmDialog("Angemeldet bleiben?", "Deine Sitzung wird verschlüsselt auf diesem Gerät gespeichert, bis du sie löschst oder dich abmeldest. Nur auf eigenen Geräten verwenden.", "Speichern", { app.auth.enableRemember(); remembered = true }, { dlg = null })
        "forget" -> ConfirmDialog("Gespeicherte Anmeldung löschen?", "Beim nächsten App-Start musst du dich neu anmelden.", "Löschen", { app.auth.forgetStored(); remembered = false; Notifier.schedule(ctx, false) }, { dlg = null }, destructive = true)
        "logout" -> ConfirmDialog("Abmelden?", "Die Sitzung wird beendet und lokale Anmeldedaten werden gelöscht.", "Abmelden", { fullLogout(app) }, { dlg = null }, destructive = true)
    }
}

@Composable private fun SwitchRow(title: String, text: String, checked: Boolean, enabled: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onChange: (Boolean) -> Unit) {
    GroupedItem(0, 1, title, supporting = text, leading = { TileIcon(icon) }, trailing = { Switch(checked, onChange, enabled = enabled) }, onClick = if (enabled) ({ onChange(!checked) }) else null, modifier = Modifier.padding(vertical = 2.dp))
}
@Composable private fun ActionRow(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    GroupedItem(0, 1, title, supporting = text, leading = { TileIcon(icon, container = if (danger) cs.errorContainer else cs.secondaryContainer, content = if (danger) cs.onErrorContainer else cs.onSecondaryContainer) }, onClick = if (enabled) onClick else null, modifier = Modifier.padding(vertical = 2.dp))
}
