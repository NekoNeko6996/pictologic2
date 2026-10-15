package pictologic.staticimage

import arc.files.Fi
import arc.graphics.Pixmap
import pictologic.core.FrameSequence
import pictologic.core.Palette
import pictologic.core.RleRun

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

        // 1. Scale image to exact total display pixel dimensions (including bezel gaps for multi-screen!)
        val scaledPixmap = FrameSequence.scalePixmap(
            src = originalPixmap,
            targetW = canvasW,
            targetH = canvasH,
            aspectMode = config.aspectMode
        )
        originalPixmap.dispose()

        // 2. Generate optimal palette
        val palette = Palette.fromPixmaps(listOf(scaledPixmap), config.paletteSize)

        // 3. Quantize pixels (invert Y so 0 is bottom-left as in Mindustry displays)
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
            // MODE B: 3x3 (80x80) or 6x6 (176x176) Displays
            // Each display block is an independent chunk separated by `screenBezelGapPx` (16px physical frame border)
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
            // MODE A: 1x1 Tiled Logic Display (up to 16x16)
            // Global seamless coordinate system across the entire canvas (offset by +6px bezel when drawing)
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
}