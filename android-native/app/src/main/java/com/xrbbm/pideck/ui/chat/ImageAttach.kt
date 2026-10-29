package com.xrbbm.pideck.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image attach pipeline:
 * ≤2MB or gif/webp goes raw; larger stills decode → long side 1600 →
 * JPEG 82. Over-2MB-after-compress items are dropped with a count.
 */
object ImageAttach {
    const val MAX_COUNT = 4
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_SIDE = 1600

    fun guessMime(name: String?): String {
        val lower = (name ?: "").lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    fun mimeOfDataUrl(src: String): String =
        Regex("^data:([^;]+);").find(src)?.groupValues?.get(1) ?: "image/jpeg"

    suspend fun encodeAll(
        context: Context,
        uris: List<Uri>,
    ): Pair<List<String>, Int> = withContext(Dispatchers.IO) {
        val encoded = mutableListOf<String>()
        var dropped = 0
        for (uri in uris) {
            val mime = context.contentResolver.getType(uri)
                ?: guessMime(uri.lastPathSegment)
            val raw = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            } ?: continue
            val url = compressDataUrl(raw, mime)
            // base64 size ≈ raw bytes * 4/3: cap at 2MB.
            if ((url.length * 3) / 4 > MAX_BYTES) {
                dropped++
                continue
            }
            encoded.add(url)
        }
        encoded.take(MAX_COUNT) to dropped
    }

    suspend fun compressDataUrl(raw: ByteArray, mime: String): String =
        withContext(Dispatchers.Default) {
            if (raw.size <= MAX_BYTES) {
                return@withContext "data:$mime;base64,${Base64.encodeToString(raw, Base64.NO_WRAP)}"
            }
            val lower = mime.lowercase()
            if (lower.contains("gif") || lower.contains("webp")) {
                return@withContext "data:$mime;base64,${Base64.encodeToString(raw, Base64.NO_WRAP)}"
            }
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                var sample = 1
                val longest = maxOf(opts.outWidth, opts.outHeight)
                while (longest / sample > MAX_SIDE) sample *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
                var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts)
                    ?: return@withContext "data:$mime;base64,${
                        Base64.encodeToString(raw, Base64.NO_WRAP)
                    }"
                val w = bmp.width
                val h = bmp.height
                val longSide = maxOf(w, h)
                if (longSide > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / longSide
                    val nw = (w * scale).toInt().coerceAtLeast(1)
                    val nh = (h * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                    if (scaled != bmp) bmp = scaled
                }
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
                "data:image/jpeg;base64,${Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)}"
            } catch (_: Exception) {
                "data:$mime;base64,${Base64.encodeToString(raw, Base64.NO_WRAP)}"
            }
        }
}
