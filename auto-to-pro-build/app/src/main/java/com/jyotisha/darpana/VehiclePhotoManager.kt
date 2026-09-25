package com.jyotisha.darpana

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class VehiclePhotoManager(
    private val context: Context,
    private val emit: (String, JSONObject) -> Unit
) {
    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val PREFIX = "/local/vehicle/"
        private const val COVER_NAME = "cover.jpg"
        private const val DRAFT_NAME = "draft.jpg"
        private const val MAX_SOURCE_BYTES = 30L * 1024L * 1024L
        private const val MAX_DECODE_DIM = 2400
        private const val MAX_OUTPUT_W = 1280
        private const val MAX_OUTPUT_H = 960
        private const val JPEG_QUALITY = 82
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val dir: File
        get() = File(context.filesDir, "vehicle").apply { mkdirs() }
    private val coverFile: File get() = File(dir, COVER_NAME)
    private val draftFile: File get() = File(dir, DRAFT_NAME)

    fun importFromUri(uri: Uri) {
        executor.execute {
            if (closed.get()) return@execute
            try {
                val mime = context.contentResolver.getType(uri).orEmpty()
                if (mime.isNotBlank() && !mime.startsWith("image/", true)) {
                    throw IllegalArgumentException("Выбранный файл не является изображением")
                }

                val sourceSize = querySize(uri)
                if (sourceSize > MAX_SOURCE_BYTES) {
                    throw IllegalArgumentException("Фото слишком большое. Максимум 30 МБ")
                }

                val orientation = readOrientation(uri)
                val bitmap = decodeSampled(uri)
                    ?: throw IllegalStateException("Не удалось прочитать изображение")
                val normalized = orient(bitmap, orientation)
                if (normalized !== bitmap) bitmap.recycle()
                val scaled = scaleDown(normalized, MAX_OUTPUT_W, MAX_OUTPUT_H)
                if (scaled !== normalized) normalized.recycle()

                writeJpegAtomically(scaled, draftFile)
                val width = scaled.width
                val height = scaled.height
                scaled.recycle()

                emit(
                    "picked",
                    JSONObject()
                        .put("ok", true)
                        .put("uri", uriFor(DRAFT_NAME))
                        .put("width", width)
                        .put("height", height)
                        .put("bytes", draftFile.length())
                )
            } catch (t: Throwable) {
                try { draftFile.delete() } catch (_: Throwable) { }
                emit(
                    "error",
                    JSONObject()
                        .put("ok", false)
                        .put("message", safeMessage(t))
                )
            }
        }
    }

    fun migrateBase64(dataUrl: String) {
        executor.execute {
            if (closed.get()) return@execute
            try {
                if (!dataUrl.startsWith("data:image/", true)) {
                    throw IllegalArgumentException("Старое фото имеет неподдерживаемый формат")
                }
                val comma = dataUrl.indexOf(',')
                if (comma < 0) throw IllegalArgumentException("Повреждённые данные фотографии")
                val encoded = dataUrl.substring(comma + 1)
                if (encoded.length > 16_000_000) {
                    throw IllegalArgumentException("Старое фото слишком большое для миграции")
                }
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.size > MAX_SOURCE_BYTES) {
                    throw IllegalArgumentException("Старое фото превышает 30 МБ")
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Не удалось декодировать старое фото")
                val scaled = scaleDown(bitmap, MAX_OUTPUT_W, MAX_OUTPUT_H)
                if (scaled !== bitmap) bitmap.recycle()
                writeJpegAtomically(scaled, coverFile)
                scaled.recycle()
                emit(
                    "migrated",
                    JSONObject()
                        .put("ok", true)
                        .put("uri", uriFor(COVER_NAME))
                )
            } catch (t: Throwable) {
                emit(
                    "migration_error",
                    JSONObject()
                        .put("ok", false)
                        .put("message", safeMessage(t))
                )
            }
        }
    }

    @Synchronized
    fun commitDraft(): String {
        if (!draftFile.exists()) {
            return if (coverFile.exists()) uriFor(COVER_NAME) else ""
        }
        val tmp = File(dir, "cover.commit")
        if (tmp.exists()) tmp.delete()
        draftFile.copyTo(tmp, overwrite = true)
        FileOutputStream(tmp, true).use { it.fd.sync() }
        if (coverFile.exists() && !coverFile.delete()) {
            tmp.delete()
            throw IllegalStateException("Не удалось заменить старое фото")
        }
        if (!tmp.renameTo(coverFile)) {
            tmp.copyTo(coverFile, overwrite = true)
            tmp.delete()
        }
        draftFile.delete()
        return uriFor(COVER_NAME)
    }

    @Synchronized
    fun discardDraft() {
        try { draftFile.delete() } catch (_: Throwable) { }
    }

    @Synchronized
    fun deletePhoto() {
        discardDraft()
        try { coverFile.delete() } catch (_: Throwable) { }
    }

    fun currentUri(): String = if (coverFile.exists()) uriFor(COVER_NAME) else ""

    fun hasPhoto(): Boolean = coverFile.exists() && coverFile.length() > 0L

    fun intercept(uri: Uri?): WebResourceResponse? {
        if (uri == null || !uri.scheme.equals("https", true) || uri.host != HOST) return null
        val path = uri.path ?: return null
        if (!path.startsWith(PREFIX)) return null
        val name = path.removePrefix(PREFIX)
        val file = when (name) {
            COVER_NAME -> coverFile
            DRAFT_NAME -> draftFile
            else -> return null
        }
        if (!file.exists() || !file.isFile) return null
        return try {
            WebResourceResponse(
                "image/jpeg",
                null,
                200,
                "OK",
                mapOf(
                    "Cache-Control" to "no-store, max-age=0",
                    "Pragma" to "no-cache"
                ),
                FileInputStream(file)
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun close() {
        closed.set(true)
        executor.shutdownNow()
    }

    private fun uriFor(name: String): String =
        "https://$HOST/local/vehicle/$name?v=${System.currentTimeMillis()}"

    private fun querySize(uri: Uri): Long {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    return cursor.getLong(idx)
                }
            }
        } catch (_: Throwable) { }
        return -1L
    }

    private fun readOrientation(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_DIM ||
            bounds.outHeight / sample > MAX_DECODE_DIM
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = max(1, sample)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scaleDown(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val scale = minOf(1f, maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height)
        if (scale >= 0.999f) return source
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun writeJpegAtomically(bitmap: Bitmap, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        if (tmp.exists()) tmp.delete()
        FileOutputStream(tmp).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw IllegalStateException("Не удалось сохранить фото")
            }
            out.flush()
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) {
            tmp.delete()
            throw IllegalStateException("Не удалось заменить фото")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun safeMessage(t: Throwable): String =
        (t.message ?: "Не удалось обработать фотографию")
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(220)
}
