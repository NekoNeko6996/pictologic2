package pictologic.staticimage

import arc.files.Fi
import arc.graphics.Pixmap
import pictologic.core.FrameSequence
import pictologic.core.Palette
import pictologic.core.RleRun
import kotlin.math.max

data class StaticWorkerSlice(
    val workerIndex: Int,
    val screenX: Int,
    val screenY: Int,
    val runs: List<RleRun>,
    val groupedByColor: Map<Int, List<RleRun>>
) {
    val drawInstructionCount: Int
        get() = groupedByColor.size + runs.size
}

data class EncodedStaticImage(
    val originalFile: Fi,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val palette: Palette,
    val runs: List<RleRun>,
    val workerSlices: List<StaticWorkerSlice>,
    val quantizedPixmap: Pixmap
) {
    val totalRuns: Int get() = runs.size
    val totalWorkers: Int get() = workerSlices.size
    val totalDrawInstructions: Int
        get() = workerSlices.sumOf { it.drawInstructionCount }
}

class StaticImageEncoder(
    val config: StaticImageConfig
) {

    fun encode(imageFile: Fi): EncodedStaticImage {
        val originalPixmap = Pixmap(imageFile)
        val canvasW = config.effectiveDisplayWidth
        val canvasH = config.effectiveDisplayHeight

        // 1. Scale image to exact total display pixel dimensions
        val scaledPixmap = FrameSequence.scalePixmap(
            src = originalPixmap,
            targetW = canvasW,
            targetH = canvasH,
            aspectMode = config.aspectMode
        )
        originalPixmap.dispose()

        val wmText = config.watermarkText.trim().take(15)
        val hasWatermark = wmText.isNotEmpty()

        // 2. Generate optimal palette (reserve 1 slot for exact watermark color if watermark is active)
        val baseColorCount = if (hasWatermark) max(2, config.paletteSize - 1) else config.paletteSize
        val basePalette = Palette.fromPixmaps(listOf(scaledPixmap), baseColorCount)

        val finalColors = if (hasWatermark && !basePalette.colors.contains(config.watermarkColorRgba)) {
            basePalette.colors + intArrayOf(config.watermarkColorRgba)
        } else {
            basePalette.colors
        }
        val palette = Palette(finalColors)

        // 3. Draw watermark onto scaledPixmap (top-right corner) before quantization
        if (hasWatermark) {
            drawWatermarkTopRight(scaledPixmap, wmText, config.watermarkColorRgba)
        }

        // 4. Quantize pixels (invert Y so 0 is bottom-left as in Mindustry displays)
        val indices = IntArray(canvasW * canvasH)
        val quantizedPixmap = Pixmap(canvasW, canvasH)

        for (y in 0 until canvasH) {
            val rowOffset = y * canvasW
            val srcY = canvasH - 1 - y
            for (x in 0 until canvasW) {
                val rgba = scaledPixmap.get(x, srcY)
                val colorIdx = palette.findNearestIndex(rgba)
                indices[rowOffset + x] = colorIdx
                quantizedPixmap.set(x, srcY, palette.getColor(colorIdx))
            }
        }
        scaledPixmap.dispose()

        val allMergedRuns = ArrayList<RleRun>()
        val workerSlices = ArrayList<StaticWorkerSlice>()
        var workerCounter = 0

        if (config.isMultiScreenChunked) {
            val screenPx = config.displayType.nativeSize
            val stepPx = screenPx + config.screenBezelGapPx

            for (sy in 0 until config.clampedGridY) {
                for (sx in 0 until config.clampedGridX) {
                    val startX = sx * stepPx
                    val startY = sy * stepPx

                    val rawChunkRuns = ArrayList<RleRun>()
                    for (localY in 0 until screenPx) {
                        val globalY = startY + localY
                        if (globalY >= canvasH) break
                        val rowOffset = globalY * canvasW

                        var localX = 0
                        while (localX < screenPx) {
                            val globalX = startX + localX
                            if (globalX >= canvasW) break

                            val colorIdx = indices[rowOffset + globalX]
                            var runEnd = localX + 1
                            while (runEnd < screenPx && (startX + runEnd) < canvasW && indices[rowOffset + startX + runEnd] == colorIdx) {
                                runEnd++
                            }
                            val runWidth = runEnd - localX
                            rawChunkRuns.add(RleRun(localX, localY, runWidth, colorIdx, palette.getColor(colorIdx), 1))
                            localX = runEnd
                        }
                    }

                    val mergedChunkRuns = mergeRunsVertically(rawChunkRuns)
                    allMergedRuns.addAll(mergedChunkRuns)

                    val slicesForScreen = partitionIntoSlices(
                        runs = mergedChunkRuns,
                        screenX = sx,
                        screenY = sy,
                        maxBuffer = config.maxCommandsPerWorker,
                        startWorkerIdx = workerCounter
                    )
                    workerCounter += slicesForScreen.size
                    workerSlices.addAll(slicesForScreen)
                }
            }
        } else {
            val rawRuns = ArrayList<RleRun>()
            for (y in 0 until canvasH) {
                val rowOffset = y * canvasW
                var x = 0
                while (x < canvasW) {
                    val colorIdx = indices[rowOffset + x]
                    var runEnd = x + 1
                    while (runEnd < canvasW && indices[rowOffset + runEnd] == colorIdx) {
                        runEnd++
                    }
                    val runWidth = runEnd - x
                    rawRuns.add(RleRun(x, y, runWidth, colorIdx, palette.getColor(colorIdx), 1))
                    x = runEnd
                }
            }

            val mergedRuns = mergeRunsVertically(rawRuns)
            allMergedRuns.addAll(mergedRuns)

            val slices = partitionIntoSlices(
                runs = mergedRuns,
                screenX = 0,
                screenY = 0,
                maxBuffer = config.maxCommandsPerWorker,
                startWorkerIdx = 0
            )
            workerSlices.addAll(slices)
        }

        return EncodedStaticImage(
            originalFile = imageFile,
            canvasWidth = canvasW,
            canvasHeight = canvasH,
            palette = palette,
            runs = allMergedRuns,
            workerSlices = workerSlices,
            quantizedPixmap = quantizedPixmap
        )
    }

    /**
     * Renders a crisp 5x7 pixel-art watermark at the top-right corner of [pixmap].
     * Automatically scales font size according to canvas resolution.
     */
    private fun drawWatermarkTopRight(pixmap: Pixmap, text: String, colorRgba: Int) {
        val w = pixmap.width
        val h = pixmap.height
        val scale = max(1, w / 180)
        val charW = 5 * scale
        val charH = 7 * scale
        val spacing = 1 * scale
        val margin = max(3, 4 * scale)

        val totalTextW = text.length * charW + max(0, text.length - 1) * spacing
        val startX = max(2, w - totalTextW - margin)
        val startY = margin

        for (i in text.indices) {
            val ch = text[i].uppercaseChar()
            val glyph = FONT_5X7[ch] ?: FONT_5X7['?']!!
            val cx = startX + i * (charW + spacing)

            for (row in 0 until 7) {
                val rowBits = glyph[row]
                for (col in 0 until 5) {
                    if (((rowBits shr (4 - col)) and 1) != 0) {
                        for (dy in 0 until scale) {
                            for (dx in 0 until scale) {
                                val px = cx + col * scale + dx
                                val py = startY + row * scale + dy
                                if (px in 0 until w && py in 0 until h) {
                                    pixmap.set(px, py, colorRgba)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun mergeRunsVertically(runs: List<RleRun>): List<RleRun> {
        if (runs.size <= 1) return runs

        val merged = ArrayList<RleRun>(runs.size)
        val activeRowRuns = HashMap<Long, RleRun>()
        var currentY = -1

        for (run in runs) {
            if (run.y != currentY) {
                if (run.y != currentY + 1) {
                    merged.addAll(activeRowRuns.values)
                    activeRowRuns.clear()
                }
                currentY = run.y
            }

            val key = (run.x.toLong() shl 32) or (run.width.toLong() shl 16) or (run.colorIndex.toLong() and 0xFFFF)
            val prev = activeRowRuns[key]

            if (prev != null && prev.y + prev.height == run.y) {
                activeRowRuns[key] = prev.copy(height = prev.height + run.height)
            } else {
                if (prev != null) {
                    merged.add(prev)
                }
                activeRowRuns[key] = run
            }
        }

        merged.addAll(activeRowRuns.values)
        return merged
    }

    private fun partitionIntoSlices(
        runs: List<RleRun>,
        screenX: Int,
        screenY: Int,
        maxBuffer: Int,
        startWorkerIdx: Int
    ): List<StaticWorkerSlice> {
        val slices = ArrayList<StaticWorkerSlice>()
        if (runs.isEmpty()) return slices

        val groupedAll = runs.groupBy { it.colorIndex }
        val colorSortedRuns = groupedAll.values.flatten()

        var currentRuns = ArrayList<RleRun>()
        var currentColors = HashSet<Int>()
        var workerIdx = startWorkerIdx

        for (run in colorSortedRuns) {
            val colorExtra = if (currentColors.contains(run.colorIndex)) 0 else 1
            if (currentRuns.size + currentColors.size + colorExtra > maxBuffer && currentRuns.isNotEmpty()) {
                val g = currentRuns.groupBy { it.colorIndex }
                slices.add(StaticWorkerSlice(workerIdx++, screenX, screenY, currentRuns, g))
                currentRuns = ArrayList()
                currentColors = HashSet()
            }
            currentRuns.add(run)
            currentColors.add(run.colorIndex)
        }

        if (currentRuns.isNotEmpty()) {
            val g = currentRuns.groupBy { it.colorIndex }
            slices.add(StaticWorkerSlice(workerIdx++, screenX, screenY, currentRuns, g))
        }

        return slices
    }

    companion object {
        // Compact 5x7 pixel font table (5 bits per row, 7 rows per character)
        private val FONT_5X7: Map<Char, IntArray> = mapOf(
            ' ' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            'A' to intArrayOf(0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
            'B' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
            'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
            'D' to intArrayOf(0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C),
            'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
            'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
            'G' to intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F),
            'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
            'I' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
            'J' to intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C),
            'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
            'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
            'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
            'N' to intArrayOf(0x11, 0x19, 0x15, 0x13, 0x11, 0x11, 0x11),
            'O' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
            'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
            'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
            'R' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
            'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
            'T' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
            'U' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
            'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
            'W' to intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x1B, 0x11),
            'X' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
            'Y' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04),
            'Z' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F),
            '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
            '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
            '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
            '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
            '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
            '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
            '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
            '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
            '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
            '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
            '-' to intArrayOf(0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00),
            '_' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F),
            '.' to intArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C),
            ':' to intArrayOf(0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00),
            '!' to intArrayOf(0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04),
            '?' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x00, 0x04),
            '@' to intArrayOf(0x0E, 0x11, 0x17, 0x15, 0x17, 0x10, 0x0E),
            '#' to intArrayOf(0x0A, 0x0A, 0x1F, 0x0A, 0x1F, 0x0A, 0x0A),
            '/' to intArrayOf(0x01, 0x02, 0x02, 0x04, 0x08, 0x08, 0x10)
        )
    }
}