package com.financetracker.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/**
 * The person's own pictures used as company logos. A chosen picture is cut to a square from its
 * middle and shrunk to [SIZE] pixels, then kept as Base64 text with the logo choice in the app's
 * settings, so it's part of every full backup. A few KB each.
 */
object MerchantPhotos {
    const val SIZE = 128

    /** Decoded pictures, so a list of rows doesn't decode the same text again on every frame. */
    private val cache = LruCache<String, ImageBitmap>(64)

    /** Reads the picture at [uri], squares and shrinks it, and returns it as Base64; null if it
     * can't be read as an image. Call off the main thread. */
    fun encode(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        // Read only the size first, so a 12-megapixel photo isn't loaded in full.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val upright = rotateUpright(decoded, orientationOf(context, uri))
        val square = squareFromMiddle(upright, SIZE)
        val out = ByteArrayOutputStream()
        // PNG keeps see-through logos see-through; photos have no transparency and are smaller as JPEG.
        if (square.hasAlpha()) square.compress(Bitmap.CompressFormat.PNG, 100, out)
        else square.compress(Bitmap.CompressFormat.JPEG, 90, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    /** The picture for stored Base64 [photo], or null if it can't be decoded. */
    fun decode(photo: String): ImageBitmap? {
        cache.get(photo)?.let { return it }
        val bitmap = runCatching {
            val bytes = Base64.decode(photo, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return null
        return bitmap.asImageBitmap().also { cache.put(photo, it) }
    }

    private fun orientationOf(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Phone cameras often save photos sideways with a note saying which way is up. */
    private fun rotateUpright(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun squareFromMiddle(bitmap: Bitmap, size: Int): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val config = if (bitmap.hasAlpha()) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        return Bitmap.createBitmap(size, size, config).also { square ->
            square.setHasAlpha(bitmap.hasAlpha())
            Canvas(square).drawBitmap(bitmap, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
    }
}
