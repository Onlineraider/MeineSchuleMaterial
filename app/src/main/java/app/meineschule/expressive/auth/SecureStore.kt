package app.meineschule.expressive.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Abstraktion, damit Tests ohne Android-Keystore laufen. */
interface SecureStore {
    fun put(name: String, plaintext: String)
    fun get(name: String): String?
    fun remove(name: String)
    /** Löscht alle Daten UND den Keystore-Schlüssel. */
    fun wipe()
}

/**
 * Android-Keystore-gestützter Speicher: AES-256-GCM, nicht exportierbarer Schlüssel (hardware-backed, wenn verfügbar).
 * Die Chiffrate liegen in einer privaten SharedPreferences-Datei (vom Backup ausgeschlossen). Nie Klartext.
 * Der Name (Alias) wird als AAD gebunden, damit Datensätze nicht vertauscht werden können.
 */
class KeystoreSecureStore(context: Context, private val alias: String = "msd.vault.key.v1") : SecureStore {
    private val prefs = context.applicationContext.getSharedPreferences("msd_vault", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build(),
        )
        return gen.generateKey()
    }

    @Synchronized override fun put(name: String, plaintext: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
        val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).commit() // commit(): atomar & synchron – Token-Paar wird als EIN Datensatz geschrieben
    }

    @Synchronized override fun get(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); updateAAD(name.toByteArray()) }
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) { // manipuliert, Schlüssel verloren (z. B. nach Biometrie-/Sperrbildschirm-Reset) → verwerfen
            prefs.edit().remove(name).commit(); null
        }
    }

    @Synchronized override fun remove(name: String) { prefs.edit().remove(name).commit() }

    @Synchronized override fun wipe() {
        prefs.edit().clear().commit()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
    }
}

/** Nur für Tests / Vorschau. */
class MemorySecureStore : SecureStore {
    val data = mutableMapOf<String, String>()
    override fun put(name: String, plaintext: String) { data[name] = plaintext }
    override fun get(name: String) = data[name]
    override fun remove(name: String) { data.remove(name) }
    override fun wipe() = data.clear()
}
