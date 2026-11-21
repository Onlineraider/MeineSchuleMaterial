package app.meineschule.expressive

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.meineschule.expressive.ui.AppRoot
import app.meineschule.expressive.ui.theme.MsdTheme

class MainActivity : FragmentActivity() {
    private val locked = mutableStateOf(false)
    private val container get() = (application as MsdApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (container.prefs.biometricLock.value) locked.value = true
        setContent {
            val app = container
            val mode by app.prefs.mode.collectAsState(); val dyn by app.prefs.dynamicColor.collectAsState(); val seed by app.prefs.seed.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(LocalApp provides app) {
                MsdTheme(mode, dyn, seed) { AppRoot(locked = locked.value, onUnlock = ::authenticate) }
            }
        }
    }

    override fun onStart() { super.onStart(); container.inForeground = true; if (locked.value) authenticate() }
    override fun onStop() { super.onStop(); container.inForeground = false; if (container.prefs.biometricLock.value) locked.value = true }

    /** App-Sperre: Biometrie oder Geräte-PIN. Schützt nur die UI-Sitzung; Tokens liegen ohnehin im Keystore. */
    private fun authenticate() {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) { locked.value = false; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked.value = false }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("MeineSchule entsperren").setAllowedAuthenticators(allowed).build())
    }
}
