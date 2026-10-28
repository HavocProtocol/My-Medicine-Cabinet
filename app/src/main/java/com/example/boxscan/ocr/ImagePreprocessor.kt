package com.example.boxscan.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import com.example.boxscan.models.NormalizedRect
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Result of preprocessing a captured package side image.
 */
data class PreprocessedImageResult(
    val standardFile: File,
    val highContrastFile: File,
    val width: Int,
    val height: Int
)

/**
 * Preprocesses captured medication box images before OCR:
 * - Normalizes EXIF orientation
 * - Resizes longest edge to ~1600px
 * - Produces a standard grayscale + contrast-enhanced image
 * - Produces a second high-contrast thresholded variant for embossed / dot-matrix LOT & EXP stamps
 * - Provides 90°/180°/270° rotated variants and maps bounding boxes back to the upright frame
 */
object ImagePreprocessor {
    private const val TARGET_MAX_EDGE = 1600

    fun preprocessImage(sourcePath: String, cacheDir: File): PreprocessedImageResult? {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null

        val uprightBitmap = decodeAndOrientBitmap(sourceFile, TARGET_MAX_EDGE) ?: return null
        val width = uprightBitmap.width
        val height = uprightBitmap.height

        val standardBitmap = createStandardEnhancedBitmap(uprightBitmap)
        val highContrastBitmap = createHighContrastStampBitmap(uprightBitmap)

        val baseName = sourceFile.nameWithoutExtension
        val standardFile = File(cacheDir, "${baseName}_prep_std.jpg")
        val highContrastFile = File(cacheDir, "${baseName}_prep_hc.jpg")

        saveJpeg(standardBitmap, standardFile, quality = 90)
        saveJpeg(highContrastBitmap, highContrastFile, quality = 90)

        if (standardBitmap !== uprightBitmap) standardBitmap.recycle()
        if (highContrastBitmap !== uprightBitmap) highContrastBitmap.recycle()
        uprightBitmap.recycle()

        return PreprocessedImageResult(
            standardFile = standardFile,
            highContrastFile = highContrastFile,
            width = width,
            height = height
        )
    }

    /**
     * Rotates an image file by [degrees] (90, 180, or 270) and writes it to [outputFile].
     */
    fun createRotatedVariant(sourceFile: File, degrees: Int, outputFile: File): Boolean {
        if (!sourceFile.exists()) return false
        val bitmap = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: return false
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val saved = saveJpeg(rotated, outputFile, quality = 90)
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        return saved
    }

    /**
     * Maps a normalized bounding box from a rotated image frame ([rotationDegrees] clockwise)
     * back into the upright normalized coordinate system (0f..1f).
     */
    fun mapBoundingBoxFromRotatedFrame(
        rect: NormalizedRect,
        rotationDegrees: Int
    ): NormalizedRect {
        return when ((rotationDegrees % 360 + 360) % 360) {
            90 -> NormalizedRect(
                left = rect.top.coerceIn(0f, 1f),
                top = (1f - rect.right).coerceIn(0f, 1f),
                right = rect.bottom.coerceIn(0f, 1f),
                bottom = (1f - rect.left).coerceIn(0f, 1f)
            )
            180 -> NormalizedRect(
                left = (1f - rect.right).coerceIn(0f, 1f),
                top = (1f - rect.bottom).coerceIn(0f, 1f),
                right = (1f - rect.left).coerceIn(0f, 1f),
                bottom = (1f - rect.top).coerceIn(0f, 1f)
            )
            270 -> NormalizedRect(
                left = (1f - rect.bottom).coerceIn(0f, 1f),
                top = rect.left.coerceIn(0f, 1f),
                right = (1f - rect.top).coerceIn(0f, 1f),
                bottom = rect.right.coerceIn(0f, 1f)
            )
            else -> rect
        }
    }

    private fun decodeAndOrientBitmap(file: File, maxEdge: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (max(rawW / sampleSize, rawH / sampleSize) > maxEdge * 2) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

        val exifRotation = readExifRotationDegrees(file)
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > maxEdge) maxEdge.toFloat() / longest.toFloat() else 1f

        if (exifRotation == 0 && scale == 1f) {
            return decoded
        }

        val matrix = Matrix().apply {
            if (scale != 1f) {
                postScale(scale, scale)
            }
            if (exifRotation != 0) {
                postRotate(exifRotation.toFloat())
            }
        }
        val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    private fun readExifRotationDegrees(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Standard pass: grayscale + moderate contrast stretch for general printed text.
     */
    private fun createStandardEnhancedBitmap(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.25f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayMatrix.postConcat(contrastMatrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * High-contrast stamp pass: aggressive local contrast / adaptive threshold approximation
     * to bring out low-contrast embossed or dot-matrix LOT / MFG / EXP text on flaps.
     */
    private fun createHighContrastStampBitmap(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 2.1f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val highContrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        grayMatrix.postConcat(highContrastMatrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun saveJpeg(bitmap: Bitmap, dest: File, quality: Int): Boolean {
        return try {
            FileOutputStream(dest).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(50, 100), out)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a synthetic realistic medication package side image on disk with embedded OCR sidecar
     * metadata so users on emulators or devices can test multi-side scanning offline immediately,
     * and also supports rendering real camera captures.
     */
    fun renderSyntheticPackageSideImage(
        cacheDir: File,
        fileName: String,
        titleText: String,
        lines: List<String>,
        accentColorHex: Int = Color.parseColor("#0D9488")
    ): File {
        val width = 900
        val height = 620
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Box background
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F8FAFC") }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Accent band
        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColorHex }
        canvas.drawRect(0f, 0f, width.toFloat(), 96f, bandPaint)

        // Border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRect(12f, 12f, width - 12f, height - 12f, borderPaint)

        // Header text
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            isFakeBoldText = true
        }
        canvas.drawText(titleText, 36f, 62f, headerPaint)

        // Body lines
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 30f
            isFakeBoldText = false
        }
        var y = 165f
        for ((idx, line) in lines.withIndex()) {
            linePaint.textSize = if (idx == 0) 38f else 28f
            linePaint.isFakeBoldText = idx == 0
            canvas.drawText(line, 44f, y, linePaint)
            y += if (idx == 0) 64f else 52f
        }

        val imageFile = File(cacheDir, fileName)
        saveJpeg(bitmap, imageFile, quality = 92)
        bitmap.recycle()

        // Write companion OCR metadata sidecar for deterministic offline extraction on synthetic presets
        val sidecarFile = getSidecarFile(imageFile)
        sidecarFile.writeText(lines.joinToString("\n"))

        return imageFile
    }

    fun getSidecarFile(imageFile: File): File {
        return File(imageFile.parentFile, "${imageFile.nameWithoutExtension}.ocr.txt")
    }
}
