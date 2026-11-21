package app.meineschule.expressive.ui.screens

import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActionScope
import app.meineschule.expressive.LocalApp
import app.meineschule.expressive.auth.LoginResult
import app.meineschule.expressive.core.ApiException
import app.meineschule.expressive.core.ErrorKind
import app.meineschule.expressive.core.asApi
import app.meineschule.expressive.ui.components.*
import app.meineschule.expressive.ui.theme.MsdShapes
import kotlinx.coroutines.launch

/** Zweistufiger Login: Zugangsdaten → (nur bei `twoFactorNeeded`) 2FA-Code. „Angemeldet bleiben“ ist ausdrücklich, nie vorausgewählt. */
@Composable
fun LoginScreen(sessionExpired: Boolean) {
    val app = LocalApp.current; val scope = rememberCoroutineScope(); val cs = MaterialTheme.colorScheme
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }; var stayLoggedIn by remember { mutableStateOf(false) } // NIE vorausgewählt
    var step2 by remember { mutableStateOf(false) }; var loading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<ApiException?>(null) }
    val uri = LocalUriHandler.current

    fun submit() {
        if (loading || user.isBlank() || pass.isEmpty() || (step2 && code.length < 4)) return
        loading = true; error = null
        scope.launch {
            try { when (app.auth.login(user.trim(), pass, if (step2) code.trim() else null, stayLoggedIn)) { LoginResult.TwoFactorNeeded -> step2 = true; LoginResult.Success -> pass = "" } }
            catch (e: Throwable) { error = e.asApi() } finally { loading = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // dekorative Formen im Hintergrund (Expressive-Charakter, keine Verläufe)
        Box(Modifier.align(Alignment.TopEnd).offset(x = 60.dp, y = (-40).dp).size(220.dp).clip(RoundedCornerShape(46, 54, 40, 60)).background(cs.primaryContainer))
        Box(Modifier.align(Alignment.TopStart).offset(x = (-30).dp, y = 130.dp).size(72.dp).clip(RoundedCornerShape(24.dp)).background(cs.tertiaryContainer))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().systemBarsPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
            Spacer(Modifier.height(72.dp))
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 10.dp)).background(cs.primary), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.School, null, Modifier.size(40.dp), tint = cs.onPrimary) }
            Spacer(Modifier.height(24.dp))
            Text(if (step2) "Fast geschafft." else "Willkommen zurück.", style = MaterialTheme.typography.displaySmall)
            Text(if (step2) "Gib den Code aus deiner Authenticator-App ein." else "Ein Login. Dein ganzer Schulalltag.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))

            if (sessionExpired && error == null) InfoBanner("Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.", Icons.Rounded.Key, modifier = Modifier.padding(bottom = 12.dp).offset(x = (-16).dp).width(IntrinsicSize.Max))
            AnimatedContent(step2, label = "step") { second ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!second) {
                        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Benutzername") }, leadingIcon = { Icon(Icons.Rounded.Person, null) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                            supportingText = { Text("Dein MeineSchule.digital-Konto") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), isError = error?.kind == ErrorKind.Auth)
                        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Passwort") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPass) "Passwort verbergen" else "Passwort anzeigen") } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }), isError = error?.kind == ErrorKind.Auth)
                    } else {
                        OutlinedTextField(code, { code = it.filter(Char::isLetterOrDigit).take(10) }, Modifier.fillMaxWidth(), label = { Text("Bestätigungscode") }, leadingIcon = { Icon(Icons.Rounded.Shield, null) }, singleLine = true, shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { submit() }), isError = error?.kind == ErrorKind.Auth)
                    }
                }
            }
            error?.let { e -> Text(if (e.kind == ErrorKind.Auth) (e.message ?: e.userText) else e.userText, color = cs.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }

            if (!step2) {
                // Ausdrückliche, verständliche Einwilligung – standardmäßig AUS
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Checkbox) { stayLoggedIn = !stayLoggedIn }.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Checkbox(stayLoggedIn, { stayLoggedIn = it })
                    Column(Modifier.padding(top = 12.dp)) {
                        Text("Angemeldet bleiben", style = MaterialTheme.typography.titleSmall)
                        Text("Die App speichert dafür eine geschützte Sitzung (kein Passwort) verschlüsselt im Android Keystore dieses Geräts. Freiwillig · jederzeit in den Einstellungen widerrufbar.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            LoadingButton(if (step2) "Bestätigen" else "Sicher anmelden", loading, ::submit, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Login, enabled = user.isNotBlank() && pass.isNotEmpty() && (!step2 || code.length >= 4))
            if (step2) TextButton(onClick = { step2 = false; code = ""; error = null }, Modifier.align(Alignment.CenterHorizontally)) { Text("Zurück") }
            else TextButton(onClick = { uri.openUri("${app.origin}/account/forgotpassword") }, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.AutoMirrored.Rounded.HelpOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Passwort vergessen") }
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = cs.secondaryContainer, contentColor = cs.onSecondaryContainer)) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, null)
                    Column { Text("Dein Konto bleibt geschützt", style = MaterialTheme.typography.titleSmall); Text("Zugangsdaten werden niemals unverschlüsselt gespeichert. Die automatische Token-Erneuerung hält dich angemeldet, solange die Sitzung gültig ist.", style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
