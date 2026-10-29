package com.astra.agent.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Decodes a picked/captured image, fixes rotation, shrinks it and returns (base64 JPEG, thumbnail). */
object ImageUtil {
    fun encode(ctx: Context, uri: Uri, maxSide: Int = 1024): Pair<String, Bitmap> {
        val cr = ctx.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Cannot open the image.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("This file is not a readable image.")

        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Cannot decode the image.")

        // Camera photos are often stored rotated; apply the EXIF rotation
        try {
            val orientation = cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val m = Matrix().apply { postRotate(degrees) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
        } catch (_: Exception) { /* no EXIF, ignore */ }

        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1), true)
        } else bmp

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        val ts = 160f / maxOf(scaled.width, scaled.height)
        val thumb = if (ts < 1f) {
            Bitmap.createScaledBitmap(scaled, (scaled.width * ts).toInt().coerceAtLeast(1),
                (scaled.height * ts).toInt().coerceAtLeast(1), true)
        } else scaled
        return Pair(b64, thumb)
    }
}
