package pictologic.core

import arc.graphics.Pixmap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class EncodedFrame(
    val frameIndex: Int,
    val isKeyframe: Boolean,
    val runs: List<RleRun>,
    val groupedByColor: Map<Int, List<RleRun>>
) {
    val totalRuns: Int get() = runs.size
    val estimatedInstructions: Int
        get() = groupedByColor.size + totalRuns
}

data class EncodedAnimation(
    val initKeyframe: EncodedFrame,
    val loopFrames: List<EncodedFrame>
) {
    val totalRuns: Int get() = initKeyframe.totalRuns + loopFrames.sumOf { it.totalRuns }
    val totalFrames: Int get() = loopFrames.size
}

class RleEncoder(
    val width: Int,
    val height: Int,
    val palette: Palette
) {

    fun encodeAnimation(pixmaps: List<Pixmap>): EncodedAnimation {
        if (pixmaps.isEmpty()) {
            val emptyFrame = EncodedFrame(0, true, emptyList(), emptyMap())
            return EncodedAnimation(emptyFrame, emptyList())
        }

        val frameIndices = Array(pixmaps.size) { i ->
            quantizePixmap(pixmaps[i])
        }

        val initRuns = encodeKeyframe(frameIndices[0])
        val initKeyframe = EncodedFrame(0, true, initRuns, initRuns.groupBy { it.colorIndex })

        val loopFrames = ArrayList<EncodedFrame>(pixmaps.size)
        for (i in pixmaps.indices) {
            val runs = if (pixmaps.size == 1) {
                emptyList()
            } else if (i == 0) {
                encodeDeltaFrame(frameIndices[0], frameIndices[pixmaps.size - 1])
            } else {
                encodeDeltaFrame(frameIndices[i], frameIndices[i - 1])
            }

            val grouped = runs.groupBy { it.colorIndex }
            loopFrames.add(EncodedFrame(i, false, runs, grouped))
        }

        return EncodedAnimation(initKeyframe, loopFrames)
    }

    fun encodeSequence(pixmaps: List<Pixmap>, keyframeInterval: Int = 0): List<EncodedFrame> {
        return encodeAnimation(pixmaps).loopFrames
    }

    private fun quantizePixmap(pixmap: Pixmap): IntArray {
        val indices = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            val srcY = height - 1 - y
            for (x in 0 until width) {
                val rgba = pixmap.get(x, srcY)
                indices[rowOffset + x] = palette.findNearestIndex(rgba)
            }
        }
        return indices
    }

    private fun encodeKeyframe(indices: IntArray): List<RleRun> {
        val rawRuns = ArrayList<RleRun>()

        for (y in 0 until height) {
            val rowOffset = y * width
            var x = 0
            while (x < width) {
                val colorIdx = indices[rowOffset + x]
                var runEnd = x + 1
                while (runEnd < width && indices[rowOffset + runEnd] == colorIdx) {
                    runEnd++
                }
                val runWidth = runEnd - x
                rawRuns.add(RleRun(x, y, runWidth, colorIdx, palette.getColor(colorIdx), 1))
                x = runEnd
            }
        }

        return mergeRunsVertically(rawRuns)
    }

    private fun encodeDeltaFrame(current: IntArray, previous: IntArray): List<RleRun> {
        val rawRuns = ArrayList<RleRun>()

        for (y in 0 until height) {
            val rowOffset = y * width
            var x = 0
            while (x < width) {
                val idx = rowOffset + x
                if (current[idx] == previous[idx]) {
                    x++
                    continue
                }

                val colorIdx = current[idx]
                var runEnd = x + 1
                while (runEnd < width && current[rowOffset + runEnd] == colorIdx) {
                    runEnd++
                }

                var trimmedEnd = runEnd
                while (trimmedEnd > x + 1 && current[rowOffset + trimmedEnd - 1] == previous[rowOffset + trimmedEnd - 1]) {
                    trimmedEnd--
                }

                val runWidth = trimmedEnd - x
                rawRuns.add(RleRun(x, y, runWidth, colorIdx, palette.getColor(colorIdx), 1))
                x = runEnd
            }
        }

        return mergeRunsVertically(rawRuns)
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

    fun decodeOnto(runs: List<RleRun>, target: Pixmap) {
        for (run in runs) {
            for (dy in 0 until run.height) {
                val targetY = height - 1 - (run.y + dy)
                if (targetY !in 0 until target.height) continue
                for (dx in 0 until run.width) {
                    val targetX = run.x + dx
                    if (targetX in 0 until target.width) {
                        target.set(targetX, targetY, run.rgba)
                    }
                }
            }
        }
    }

    companion object {
        fun splitRunsIntoChunks(
            runs: List<RleRun>,
            imageWidth: Int,
            imageHeight: Int,
            chunksX: Int,
            chunksY: Int
        ): Map<Pair<Int, Int>, List<RleRun>> {
            if (chunksX <= 1 && chunksY <= 1) {
                return mapOf((0 to 0) to runs)
            }

            val chunkW = max(1, ceil(imageWidth.toFloat() / chunksX).toInt())
            val chunkH = max(1, ceil(imageHeight.toFloat() / chunksY).toInt())

            val result = HashMap<Pair<Int, Int>, ArrayList<RleRun>>()

            for (run in runs) {
                var currentY = run.y
                var remainingHeight = run.height

                while (remainingHeight > 0) {
                    val cy = (currentY / chunkH).coerceIn(0, chunksY - 1)
                    val chunkEndY = (cy + 1) * chunkH
                    val sliceHeight = min(remainingHeight, chunkEndY - currentY)

                    var currentX = run.x
                    var remainingWidth = run.width

                    while (remainingWidth > 0) {
                        val cx = (currentX / chunkW).coerceIn(0, chunksX - 1)
                        val chunkEndX = (cx + 1) * chunkW
                        val sliceWidth = min(remainingWidth, chunkEndX - currentX)

                        val key = cx to cy
                        val list = result.getOrPut(key) { ArrayList() }
                        list.add(
                            RleRun(
                                x = currentX,
                                y = currentY,
                                width = sliceWidth,
                                colorIndex = run.colorIndex,
                                rgba = run.rgba,
                                height = sliceHeight
                            )
                        )

                        currentX += sliceWidth
                        remainingWidth -= sliceWidth
                    }

                    currentY += sliceHeight
                    remainingHeight -= sliceHeight
                }
            }

            return result
        }
    }
}