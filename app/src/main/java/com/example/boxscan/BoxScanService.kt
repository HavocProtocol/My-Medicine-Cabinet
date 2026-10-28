package com.example.boxscan

import android.content.Context
import com.example.boxscan.merge.MultiSideMerger
import com.example.boxscan.models.BoxScanResult
import com.example.boxscan.models.CapturedSide
import com.example.boxscan.models.FieldCandidate
import com.example.boxscan.models.OcrSideOutput
import com.example.boxscan.models.PackageSideType
import com.example.boxscan.ocr.ImagePreprocessor
import com.example.boxscan.ocr.LocalMultilingualOcrEngine
import com.example.boxscan.ocr.OcrEngine
import com.example.boxscan.parsing.FieldExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Orchestrates the complete on-device multi-side medicine box scan pipeline:
 * 1. Preprocesses each captured side (EXIF orientation, ~1600px resize, standard + high-contrast stamp variant)
 * 2. Runs on-device OCR (with 90°/180°/270° rotation retry when a side yields sparse text and coordinates mapped back)
 * 3. Extracts multilingual (Arabic + English) field candidates via [FieldExtractor]
 * 4. Merges candidates across sides via [MultiSideMerger]
 * 5. Cleans up temporary intermediate preprocessing files
 */
class BoxScanService(
    private val context: Context? = null,
    private val ocrEngine: OcrEngine = LocalMultilingualOcrEngine(context),
    private val referenceDate: LocalDate = LocalDate.now()
) {
    private val fieldExtractor = FieldExtractor(referenceDate)
    private val multiSideMerger = MultiSideMerger(referenceDate)

    /**
     * Processes 1..6 captured package sides sequentially and returns a [BoxScanResult].
     *
     * @param sides List of captured package sides (1 to 6)
     * @param onProgress Callback invoked with `(currentSide1Based, totalSides)` as each side is processed
     */
    suspend fun processCapturedSides(
        sides: List<CapturedSide>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BoxScanResult = withContext(Dispatchers.Default) {
        val boundedSides = sides.take(6)
        val total = boundedSides.size
        val sideOutputs = mutableListOf<OcrSideOutput>()
        val allCandidates = mutableListOf<FieldCandidate>()
        val tempFilesToCleanup = mutableListOf<File>()

        val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")

        try {
            for ((idx, side) in boundedSides.withIndex()) {
                onProgress(idx + 1, total)
                val sideIndex = idx

                val prep = ImagePreprocessor.preprocessImage(side.imagePath, cacheDir)
                val primaryPath = prep?.standardFile?.absolutePath ?: side.imagePath
                if (prep != null) {
                    tempFilesToCleanup.add(prep.standardFile)
                    tempFilesToCleanup.add(prep.highContrastFile)
                }

                // 1. Primary OCR pass on standard enhanced image
                var sideOutput = ocrEngine.recognizeSide(
                    imagePath = primaryPath,
                    sideType = side.sideType,
                    sideIndex = sideIndex
                )

                // 2. If side is a flap/side or primary text is sparse, also run on high-contrast stamp image
                if (prep != null && (side.sideType == PackageSideType.FLAP ||
                        side.sideType == PackageSideType.SIDE_1 ||
                        side.sideType == PackageSideType.SIDE_2 ||
                        sideOutput.fullText.length < 15)
                ) {
                    val hcOutput = ocrEngine.recognizeSide(
                        imagePath = prep.highContrastFile.absolutePath,
                        sideType = side.sideType,
                        sideIndex = sideIndex
                    )
                    sideOutput = combineSideOutputs(sideOutput, hcOutput)
                }

                // 3. Rotation retry (90°, 180°, 270°) if text is still very sparse (< 10 chars)
                if (sideOutput.fullText.trim().length < 10 && prep != null) {
                    for (deg in listOf(90, 180, 270)) {
                        val rotFile = File(cacheDir, "${prep.standardFile.nameWithoutExtension}_rot$deg.jpg")
                        tempFilesToCleanup.add(rotFile)
                        if (ImagePreprocessor.createRotatedVariant(prep.standardFile, deg, rotFile)) {
                            val rotOutput = ocrEngine.recognizeSide(
                                imagePath = rotFile.absolutePath,
                                sideType = side.sideType,
                                sideIndex = sideIndex
                            )
                            if (rotOutput.fullText.trim().length > sideOutput.fullText.trim().length) {
                                // Map bounding boxes back from rotated frame to upright frame
                                val mappedLines = rotOutput.lines.map { line ->
                                    line.copy(
                                        boundingBox = line.boundingBox?.let {
                                            ImagePreprocessor.mapBoundingBoxFromRotatedFrame(it, deg)
                                        }
                                    )
                                }
                                sideOutput = rotOutput.copy(lines = mappedLines)
                            }
                        }
                    }
                }

                sideOutputs.add(sideOutput)
                val extracted = fieldExtractor.extractFromSide(sideOutput)
                allCandidates.addAll(extracted)
            }
        } finally {
            // Always clean up temporary preprocessed/rotated files
            for (tempFile in tempFilesToCleanup) {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }

        multiSideMerger.merge(
            capturedSides = boundedSides,
            sideOutputs = sideOutputs,
            rawCandidates = allCandidates
        )
    }

    /**
     * Processes pre-extracted [OcrSideOutput]s directly (used in unit tests or when re-evaluating).
     */
    fun processOcrOutputs(
        capturedSides: List<CapturedSide>,
        sideOutputs: List<OcrSideOutput>
    ): BoxScanResult {
        val allCandidates = sideOutputs.flatMap { fieldExtractor.extractFromSide(it) }
        return multiSideMerger.merge(
            capturedSides = capturedSides,
            sideOutputs = sideOutputs,
            rawCandidates = allCandidates
        )
    }

    private fun combineSideOutputs(primary: OcrSideOutput, secondary: OcrSideOutput): OcrSideOutput {
        val existingTexts = primary.lines.map { it.text.trim() }.toSet()
        val additionalLines = secondary.lines.filter { it.text.trim().isNotEmpty() && it.text.trim() !in existingTexts }
        if (additionalLines.isEmpty()) return primary
        val mergedLines = primary.lines + additionalLines
        return primary.copy(
            lines = mergedLines,
            fullText = mergedLines.joinToString("\n") { it.text }
        )
    }

    companion object {
        /**
         * Deletes a single captured side image and its companion OCR sidecar file if present.
         */
        fun deleteSideFile(side: CapturedSide) {
            runCatching {
                val file = File(side.imagePath)
                val sidecar = ImagePreprocessor.getSidecarFile(file)
                if (sidecar.exists()) sidecar.delete()
                if (file.exists()) file.delete()
            }
        }

        /**
         * Deletes all temporary captured side images and sidecar files for a scan session.
         */
        fun cleanupSessionFiles(sides: List<CapturedSide>) {
            sides.forEach { deleteSideFile(it) }
        }
    }
}
