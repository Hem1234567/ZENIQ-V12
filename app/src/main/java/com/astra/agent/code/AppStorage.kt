package com.astra.agent.code

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** A generated app that was packaged as a ZIP. */
data class SavedApp(
    val name: String,
    val zipPath: String,          // private copy inside the app (always exists)
    val publicPath: String,       // where the user finds it on the phone, e.g. "Documents/ZENIQApps/todo-app.zip"
    val files: List<String>,
    val sizeKb: Int
)

/**
 * Packages generated apps and keeps every ZIP in ONE dedicated folder on the phone:
 *     Documents/ZENIQApps/<project>.zip
 * A private copy also lives in the app's own storage so Share / Download keep working.
 */
object AppStorage {
    const val FOLDER = "ZENIQApps"

    private fun privateDir(ctx: Context) = File(ctx.filesDir, "apps").apply { mkdirs() }

    private fun uniqueName(dir: File, base: String): String {
        var n = base; var i = 2
        while (File(dir, "$n.zip").exists()) { n = "$base-$i"; i++ }
        return n
    }

    /** Builds the ZIP, stores the private copy and copies it into Documents/ZENIQApps. */
    fun save(ctx: Context, project: GenProject): SavedApp {
        val dir = privateDir(ctx)
        val fileBase = uniqueName(dir, project.name)
        val bytes = FileExtractor.zip(project.copy(name = fileBase))
        val zip = File(dir, "$fileBase.zip")
        zip.writeBytes(bytes)
        val pub = exportToDevice(ctx, zip) ?: "App storage / apps / ${zip.name}"
        return SavedApp(fileBase, zip.absolutePath, pub, project.files.map { it.path }, (bytes.size / 1024).coerceAtLeast(1))
    }

    /**
     * Copies the ZIP into the public Documents/ZENIQApps folder.
     * Returns the human-readable location, or null if the phone refused (storage permission denied).
     */
    fun exportToDevice(ctx: Context, zip: File): String? {
        if (!zip.exists()) return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER)
                }
                val resolver = ctx.contentResolver
                val uri: Uri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
                resolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } } ?: return null
                Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/" + zip.name
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
                if (!dir.exists() && !dir.mkdirs()) return null
                zip.copyTo(File(dir, zip.name), overwrite = true)
                Environment.DIRECTORY_DOCUMENTS + "/" + FOLDER + "/" + zip.name
            }
        } catch (_: Throwable) {
            // Last resort: app-specific external folder (needs no permission, still visible in Files > Android > data)
            try {
                val dir = File(ctx.getExternalFilesDir(null), FOLDER).apply { mkdirs() }
                zip.copyTo(File(dir, zip.name), overwrite = true)
                "Android/data/" + ctx.packageName + "/files/" + FOLDER + "/" + zip.name
            } catch (_: Throwable) { null }
        }
    }

    /** Opens the Android share sheet for the ZIP (save to Drive, send on WhatsApp, etc.). */
    fun share(ctx: Context, zip: File): Boolean {
        if (!zip.exists()) return false
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", zip)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, zip.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share " + zip.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            true
        } catch (_: Throwable) { false }
    }
}
