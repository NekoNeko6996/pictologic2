package pictologic.core

import arc.files.Fi
import arc.graphics.Pixmap
import arc.struct.Seq
import arc.util.Log
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class AspectMode {
    STRETCH,
    FIT,
    CROP
}

/**
 * Manages loading image sequences from folders and GIF files,
 * resizing them to target display dimensions.
 */
class FrameSequence(
    val frames: List<Pixmap>,
    val sourceFiles: List<Fi> = emptyList(),
    val averageDelayMs: Int = 100
) {

    val count: Int get() = frames.size

    fun dispose() {
        for (f in frames) {
            try {
                if (!f.isDisposed) f.dispose()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif")

        /**
         * Scans the parent folder of [startFile] for all sequential images starting from [startFile].
         */
        fun discoverSequenceFiles(startFile: Fi): List<Fi> {
            val parent = startFile.parent() ?: return listOf(startFile)
            if (startFile.extension().equals("gif", ignoreCase = true)) {
                return listOf(startFile)
            }

            val matchingFiles = parent.list { file ->
                val ext = file.extension.lowercase()
                ext in IMAGE_EXTENSIONS && !ext.equals("gif", ignoreCase = true)
            }.toList()

            // Natural numerical sort: img_1, img_2, img_10
            val sorted = matchingFiles.sortedWith { f1, f2 ->
                NaturalSort.compare(f1.name(), f2.name())
            }

            // Find start file index
            val startIndex = sorted.indexOfFirst { it.path() == startFile.path() }
            return if (startIndex >= 0) {
                sorted.subList(startIndex, sorted.size)
            } else {
                sorted
            }
        }

        /**
         * Loads frames from files or GIF, scaling each frame to [targetW] x [targetH].
         */
        fun load(
            startFile: Fi,
            targetW: Int,
            targetH: Int,
            aspectMode: AspectMode = AspectMode.FIT,
            maxFrames: Int = 120
        ): FrameSequence {
            if (startFile.extension().equals("gif", ignoreCase = true)) {
                return loadGif(startFile, targetW, targetH, aspectMode, maxFrames)
            }

            val files = discoverSequenceFiles(startFile).take(maxFrames)
            val scaledFrames = ArrayList<Pixmap>(files.size)

            for (file in files) {
                val original = Pixmap(file)
                val scaled = scalePixmap(original, targetW, targetH, aspectMode)
                original.dispose()
                scaledFrames.add(scaled)
            }

            return FrameSequence(scaledFrames, files, 100)
        }

        private fun loadGif(
            gifFile: Fi,
            targetW: Int,
            targetH: Int,
            aspectMode: AspectMode,
            maxFrames: Int
        ): FrameSequence {
            val decoder = GifDecoder()
            val stream = FileInputStream(gifFile.file())
            decoder.read(stream)

            val rawFrames = decoder.frames.take(maxFrames)
            val scaledFrames = ArrayList<Pixmap>(rawFrames.size)
            var totalDelay = 0

            for (gf in rawFrames) {
                totalDelay += gf.delayMs
                val scaled = scalePixmap(gf.pixmap, targetW, targetH, aspectMode)
                gf.pixmap.dispose()
                scaledFrames.add(scaled)
            }

            val avgDelay = if (rawFrames.isNotEmpty()) totalDelay / rawFrames.size else 100
            return FrameSequence(scaledFrames, listOf(gifFile), avgDelay)
        }

        /**
         * Scales a Pixmap to target dimensions according to [AspectMode].
         */
        fun scalePixmap(
            src: Pixmap,
            targetW: Int,
            targetH: Int,
            aspectMode: AspectMode
        ): Pixmap {
            val dst = Pixmap(targetW, targetH)
            dst.fill(0x000000FF)

            when (aspectMode) {
                AspectMode.STRETCH -> {
                    dst.draw(src, 0, 0, src.width, src.height, 0, 0, targetW, targetH, false)
                }
                AspectMode.FIT -> {
                    val scale = min(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
                    val drawW = max(1, (src.width * scale).roundToInt())
                    val drawH = max(1, (src.height * scale).roundToInt())
                    val offsetX = (targetW - drawW) / 2
                    val offsetY = (targetH - drawH) / 2
                    dst.draw(src, 0, 0, src.width, src.height, offsetX, offsetY, drawW, drawH, false)
                }
                AspectMode.CROP -> {
                    val scale = max(targetW.toFloat() / src.width, targetH.toFloat() / src.height)
                    val drawW = max(1, (src.width * scale).roundToInt())
                    val drawH = max(1, (src.height * scale).roundToInt())
                    val offsetX = (targetW - drawW) / 2
                    val offsetY = (targetH - drawH) / 2
                    dst.draw(src, 0, 0, src.width, src.height, offsetX, offsetY, drawW, drawH, false)
                }
            }

            return dst
        }
    }
}
