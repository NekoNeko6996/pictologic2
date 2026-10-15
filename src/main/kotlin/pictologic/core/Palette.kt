package pictologic.core

import arc.graphics.Pixmap
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Color Palette generator and quantizer using Perceptual Median-Cut + Farthest Point refinement.
 * Guarantees distinct, vibrant colors without duplicate swatches.
 */
class Palette(val colors: IntArray) {

    private val lookupTable = ShortArray(32 * 32 * 32) { -1 }

    val size: Int get() = colors.size

    fun getColor(index: Int): Int = colors[index]

    fun getRed(index: Int): Int = (colors[index] ushr 24) and 0xFF
    fun getGreen(index: Int): Int = (colors[index] ushr 16) and 0xFF
    fun getBlue(index: Int): Int = (colors[index] ushr 8) and 0xFF
    fun getAlpha(index: Int): Int = colors[index] and 0xFF

    fun findNearestIndex(rgba: Int): Int {
        val a = rgba and 0xFF
        if (a < 64) {
            return findNearestIndex(0x000000FF)
        }

        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF

        val tableKey = ((r ushr 3) shl 10) or ((g ushr 3) shl 5) or (b ushr 3)
        val cached = lookupTable[tableKey].toInt()
        if (cached >= 0) {
            return cached
        }

        var bestIndex = 0
        var bestDist = Long.MAX_VALUE

        for (i in colors.indices) {
            val pr = getRed(i)
            val pg = getGreen(i)
            val pb = getBlue(i)

            val dr = (r - pr).toLong()
            val dg = (g - pg).toLong()
            val db = (b - pb).toLong()

            // Redmean perceptual color distance
            val rMean = (r + pr) / 2
            val dist = (((512 + rMean) * dr * dr) shr 8) + 4 * dg * dg + (((767 - rMean) * db * db) shr 8)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }

        lookupTable[tableKey] = bestIndex.toShort()
        return bestIndex
    }

    fun quantize(rgba: Int): Int {
        return colors[findNearestIndex(rgba)]
    }

    companion object {
        fun fromPixmaps(pixmaps: List<Pixmap>, maxColors: Int): Palette {
            require(maxColors in 2..64) { "maxColors must be between 2 and 64" }

            val colorCounts = HashMap<Int, Int>()
            val totalPixels = pixmaps.size.toLong() * pixmaps[0].width * pixmaps[0].height
            val sampleStep = max(1, (totalPixels / 150000L).toInt())

            var stepCounter = 0
            for (pm in pixmaps) {
                for (y in 0 until pm.height) {
                    for (x in 0 until pm.width) {
                        stepCounter++
                        if (stepCounter % sampleStep != 0) continue

                        val raw = pm.get(x, y)
                        val a = raw and 0xFF
                        val effectiveColor = if (a < 64) {
                            0x000000FF
                        } else {
                            // 5-bit quantization (32 levels per channel) to group JPEG compression noise
                            val r = (raw ushr 24) and 0xF8
                            val g = (raw ushr 16) and 0xF8
                            val b = (raw ushr 8) and 0xF8
                            (r shl 24) or (g shl 16) or (b shl 8) or 0xFF
                        }
                        colorCounts[effectiveColor] = (colorCounts[effectiveColor] ?: 0) + 1
                    }
                }
            }

            if (colorCounts.isEmpty()) {
                return Palette(intArrayOf(0x000000FF, -0x1))
            }

            // Compress dominant background counts using square-root weighting
            // so small vibrant details (hair, eyes, ribbons) are never drowned out by flat backgrounds!
            val weightedCounts = HashMap<Int, Long>(colorCounts.size)
            for ((color, rawCount) in colorCounts) {
                weightedCounts[color] = max(1L, (sqrt(rawCount.toDouble()) * 10.0).toLong())
            }

            val initialColors = weightedCounts.keys.toIntArray()
            if (initialColors.size <= maxColors) {
                return Palette(initialColors)
            }

            val rootBox = ColorBox(initialColors, weightedCounts)
            val pq = PriorityQueue<ColorBox>(compareByDescending { it.priorityScore })
            pq.add(rootBox)

            val lockedBoxes = ArrayList<ColorBox>()

            while (pq.size + lockedBoxes.size < maxColors && pq.isNotEmpty()) {
                val box = pq.poll()
                if (!box.canSplit) {
                    lockedBoxes.add(box)
                    continue
                }

                val (box1, box2) = box.split(weightedCounts)
                pq.add(box1)
                pq.add(box2)
            }

            val allBoxes = ArrayList<ColorBox>(pq.size + lockedBoxes.size)
            allBoxes.addAll(lockedBoxes)
            allBoxes.addAll(pq)

            // Extract representative average colors and deduplicate near-identical colors
            val rawPalette = ArrayList<Int>(allBoxes.size)
            for (b in allBoxes) {
                val c = b.averageColor(weightedCounts)
                if (rawPalette.none { colorDistanceSq(it, c) < 144 }) {
                    rawPalette.add(c)
                }
            }

            // If deduplication freed up slots, fill remaining slots with the most distant underrepresented colors
            while (rawPalette.size < maxColors && rawPalette.size < initialColors.size) {
                var bestCandidate = initialColors[0]
                var bestScore = -1L

                for (candidate in initialColors) {
                    var minDist = Long.MAX_VALUE
                    for (existing in rawPalette) {
                        val d = colorDistanceSq(candidate, existing)
                        if (d < minDist) minDist = d
                    }
                    val weight = weightedCounts[candidate] ?: 1L
                    val score = minDist * weight
                    if (score > bestScore) {
                        bestScore = score
                        bestCandidate = candidate
                    }
                }

                if (bestScore <= 0L) break
                rawPalette.add(bestCandidate)
            }

            return Palette(rawPalette.toIntArray())
        }

        private fun colorDistanceSq(c1: Int, c2: Int): Long {
            val dr = (((c1 ushr 24) and 0xFF) - ((c2 ushr 24) and 0xFF)).toLong()
            val dg = (((c1 ushr 16) and 0xFF) - ((c2 ushr 16) and 0xFF)).toLong()
            val db = (((c1 ushr 8) and 0xFF) - ((c2 ushr 8) and 0xFF)).toLong()
            return 2 * dr * dr + 4 * dg * dg + 3 * db * db
        }

        private class ColorBox(val colors: IntArray, counts: Map<Int, Long>) {
            var minR = 255; var maxR = 0
            var minG = 255; var maxG = 0
            var minB = 255; var maxB = 0
            var totalWeight: Long = 0

            init {
                for (c in colors) {
                    val w = counts[c] ?: 1L
                    totalWeight += w
                    val r = (c ushr 24) and 0xFF
                    val g = (c ushr 16) and 0xFF
                    val b = (c ushr 8) and 0xFF
                    minR = min(minR, r); maxR = max(maxR, r)
                    minG = min(minG, g); maxG = max(maxG, g)
                    minB = min(minB, b); maxB = max(maxB, b)
                }
            }

            val rRange: Int get() = maxR - minR
            val gRange: Int get() = maxG - minG
            val bRange: Int get() = maxB - minB

            val maxRange: Int get() = max(rRange, max(gRange, bRange))
            val canSplit: Boolean get() = colors.size > 1 && maxRange > 0

            // Balanced score between perceptual color span and damped population weight
            val priorityScore: Double
                get() {
                    if (!canSplit) return -1.0
                    val perceptualSpan = rRange * 2.0 + gRange * 4.0 + bRange * 3.0
                    return perceptualSpan * sqrt(totalWeight.toDouble())
                }

            fun split(counts: Map<Int, Long>): Pair<ColorBox, ColorBox> {
                val axis = when {
                    gRange >= rRange && gRange >= bRange -> 1
                    rRange >= bRange -> 0
                    else -> 2
                }

                val sorted = colors.sortedWith(Comparator { c1, c2 ->
                    val v1 = when (axis) {
                        0 -> (c1 ushr 24) and 0xFF
                        1 -> (c1 ushr 16) and 0xFF
                        else -> (c1 ushr 8) and 0xFF
                    }
                    val v2 = when (axis) {
                        0 -> (c2 ushr 24) and 0xFF
                        1 -> (c2 ushr 16) and 0xFF
                        else -> (c2 ushr 8) and 0xFF
                    }
                    v1.compareTo(v2)
                }).toIntArray()

                // Cut at midpoint of the dominant channel's value range (prevents tail-heavy background lockup!)
                val midVal = when (axis) {
                    0 -> (minR + maxR) / 2
                    1 -> (minG + maxG) / 2
                    else -> (minB + maxB) / 2
                }

                var splitIdx = -1
                for (i in 1 until sorted.size) {
                    val v = when (axis) {
                        0 -> (sorted[i] ushr 24) and 0xFF
                        1 -> (sorted[i] ushr 16) and 0xFF
                        else -> (sorted[i] ushr 8) and 0xFF
                    }
                    if (v > midVal) {
                        splitIdx = i
                        break
                    }
                }

                // Fallback to median index if midpoint falls on an edge
                if (splitIdx !in 1 until sorted.size) {
                    splitIdx = sorted.size / 2
                }

                val left = sorted.copyOfRange(0, splitIdx)
                val right = sorted.copyOfRange(splitIdx, sorted.size)
                return Pair(ColorBox(left, counts), ColorBox(right, counts))
            }

            fun averageColor(counts: Map<Int, Long>): Int {
                if (colors.isEmpty()) return 0x000000FF

                var sumR: Long = 0
                var sumG: Long = 0
                var sumB: Long = 0
                var sumWeight: Long = 0

                for (c in colors) {
                    val w = counts[c] ?: 1L
                    val r = (c ushr 24) and 0xFF
                    val g = (c ushr 16) and 0xFF
                    val b = (c ushr 8) and 0xFF
                    sumR += r * w
                    sumG += g * w
                    sumB += b * w
                    sumWeight += w
                }

                if (sumWeight == 0L) sumWeight = 1L
                val avgR = (sumR / sumWeight).toInt().coerceIn(0, 255)
                val avgG = (sumG / sumWeight).toInt().coerceIn(0, 255)
                val avgB = (sumB / sumWeight).toInt().coerceIn(0, 255)

                return (avgR shl 24) or (avgG shl 16) or (avgB shl 8) or 0xFF
            }
        }
    }
}