package app.meineschule.expressive.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.meineschule.expressive.core.Urls
import java.io.File

/** Lädt Anhänge in den app-privaten Cache (bereinigter Dateiname) und öffnet sie über FileProvider. */
object Files {
    fun open(ctx: Context, bytes: ByteArray, name: String) {
        val safe = Urls.safeFileName(name)
        val dir = File(ctx.cacheDir, "downloads").apply { mkdirs() }
        val f = File(dir, safe).also { it.writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), safe).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun clearCache(ctx: Context) { File(ctx.cacheDir, "downloads").deleteRecursively() }

    /** Liest eine per Picker gewählte Datei (Größe/Typ werden vor dem Upload geprüft). */
    fun read(ctx: Context, uri: Uri): Triple<ByteArray, String?, String> {
        val mime = ctx.contentResolver.getType(uri)
        val name = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "upload"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        return Triple(bytes, mime, name)
    }
}
