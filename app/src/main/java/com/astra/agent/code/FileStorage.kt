package com.astra.agent.code

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** A document ZENIQ created (PDF, Word, Excel, PowerPoint, CSV ...). */
data class SavedFile(
    val name: String,          // e.g. "sales-report.pdf"
    val path: String,          // private copy inside the app (always exists)
    val publicPath: String,    // where the user finds it, e.g. "Documents/ZENIQFiles/sales-report.pdf"
    val mime: String,
    val sizeKb: Int
)

/**
 * Generated documents live in two places:
 *  - a private copy in the app (so Open / Share always work), and
 *  - a public copy in Documents/ZENIQFiles (visible in the Files app).
 * "Download" puts another copy in the phone's Downloads/ZENIQ folder.
 */
object FileStorage {
    const val FOLDER = "ZENIQFiles"
    const val DOWNLOAD_FOLDER = "ZENIQ"

    private fun privateDir(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }

    private fun uniqueName(dir: File, base: String, ext: String): String {
        var n = base; var i = 2
        while (File(dir, "$n.$ext").exists()) { n = "$base-$i"; i++ }
        return "$n.$ext"
    }

    fun save(ctx: Context, base: String, ext: String, mime: String, bytes: ByteArray): SavedFile {
        val dir = privateDir(ctx)
        val name = uniqueName(dir, base, ext)
        val f = File(dir, name)
        f.writeBytes(bytes)
        val pub = export(ctx, f, mime, toDownloads = false) ?: "App storage / docs / $name"
        return SavedFile(name, f.absolutePath, pub, mime, (bytes.size / 1024).coerceAtLeast(1))
    }

    /**
     * Copies [file] into Documents/ZENIQFiles (or Downloads/ZENIQ when [toDownloads]).
     * Returns the human-readable location, or null when the phone refused.
     */
    fun export(ctx: Context, file: File, mime: String, toDownloads: Boolean): String? {
        if (!file.exists()) return null
        val top = if (toDownloads) Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_DOCUMENTS
        val folder = if (toDownloads) DOWNLOAD_FOLDER else FOLDER
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$top/$folder")
                }
                val collection = if (toDownloads) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Files.getContentUri("external")
                val resolver = ctx.contentResolver
                val uri: Uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return null
                "$top/$folder/${file.name}"
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(top), folder)
                if (!dir.exists() && !dir.mkdirs()) return null
                file.copyTo(File(dir, file.name), overwrite = true)
                "$top/$folder/${file.name}"
            }
        } catch (_: Throwable) {
            try {
                val dir = File(ctx.getExternalFilesDir(null), folder).apply { mkdirs() }
                file.copyTo(File(dir, file.name), overwrite = true)
                "Android/data/${ctx.packageName}/files/$folder/${file.name}"
            } catch (_: Throwable) { null }
        }
    }

    private fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)

    /** Opens the file in an installed viewer (PDF reader, Word, Excel, Sheets ...). Returns false if none exists. */
    fun open(ctx: Context, file: File, mime: String): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = uriFor(ctx, file)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(view)
            true
        } catch (_: ActivityNotFoundException) { false } catch (_: Throwable) { false }
    }

    /** Opens the Android share sheet (Drive, WhatsApp, email ...). */
    fun share(ctx: Context, file: File, mime: String): Boolean {
        if (!file.exists()) return false
        return try {
            val uri = uriFor(ctx, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share " + file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Throwable) { false }
    }
}
