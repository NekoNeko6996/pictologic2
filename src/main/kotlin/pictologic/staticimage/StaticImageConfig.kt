package pictologic.staticimage

import pictologic.core.AspectMode
import pictologic.layout.DisplayType
import pictologic.layout.ProcessorChoice
import kotlin.math.max

/**
 * Configuration options for Static Image rendering:
 * - Tiled 1x1 Display: max 16x16 grid, daisy-chained 1x1 Micro Processors.
 * - Logic Display 3x3: max 5x5 grid, per-screen chunking with 16px bezel gap compensation, forced 2x2 Logic Processors.
 * - Large Display 6x6: max 4x4 grid, per-screen chunking with 16px bezel gap compensation, forced 2x2 Logic Processors.
 */
data class StaticImageConfig(
    val displayType: DisplayType = DisplayType.TILED_LOGIC_DISPLAY,
    val gridX: Int = 10,
    val gridY: Int = 10,
    val aspectMode: AspectMode = AspectMode.FIT,
    val paletteSize: Int = 16,
    val processorChoice: ProcessorChoice = ProcessorChoice.MICRO_PROCESSOR,
    val maxCommandsPerWorker: Int = 180
) {
    val maxGridLimit: Int
        get() = maxGridForType(displayType)

    val clampedGridX: Int get() = gridX.coerceIn(1, maxGridLimit)
    val clampedGridY: Int get() = gridY.coerceIn(1, maxGridLimit)

    val effectiveProcessor: ProcessorChoice
        get() = if (displayType == DisplayType.TILED_LOGIC_DISPLAY) {
            processorChoice
        } else {
            ProcessorChoice.LOGIC_PROCESSOR
        }

    val isMultiScreenChunked: Boolean
        get() = displayType != DisplayType.TILED_LOGIC_DISPLAY

    val totalTilesX: Int get() = clampedGridX * displayType.tileSpan
    val totalTilesY: Int get() = clampedGridY * displayType.tileSpan

    /**
     * Physical bezel gap in pixels between two adjacent non-tiled displays (3x3 or 6x6).
     * Each 3x3 display is 96x96 world units with an 80x80 screen (8px border per side -> 16px gap between screens).
     * Each 6x6 display is 192x192 world units with a 176x176 screen (8px border per side -> 16px gap between screens).
     */
    val screenBezelGapPx: Int
        get() = if (isMultiScreenChunked) 16 else 0

    /**
     * Base bezel offset for Tiled Logic Display (6px border on outer edges).
     */
    val tiledBezelOffsetPx: Int
        get() = if (displayType == DisplayType.TILED_LOGIC_DISPLAY) 6 else 0

    /**
     * Effective pixel width of the entire display grid (including bezel gaps for 3x3 and 6x6 so geometry aligns!).
     */
    val effectiveDisplayWidth: Int
        get() = when (displayType) {
            DisplayType.TILED_LOGIC_DISPLAY -> max(1, clampedGridX * 32 - 12)
            DisplayType.LOGIC_DISPLAY -> clampedGridX * 80 + (clampedGridX - 1) * screenBezelGapPx
            DisplayType.LARGE_LOGIC_DISPLAY -> clampedGridX * 176 + (clampedGridX - 1) * screenBezelGapPx
        }

    /**
     * Effective pixel height of the entire display grid (including bezel gaps for 3x3 and 6x6).
     */
    val effectiveDisplayHeight: Int
        get() = when (displayType) {
            DisplayType.TILED_LOGIC_DISPLAY -> max(1, clampedGridY * 32 - 12)
            DisplayType.LOGIC_DISPLAY -> clampedGridY * 80 + (clampedGridY - 1) * screenBezelGapPx
            DisplayType.LARGE_LOGIC_DISPLAY -> clampedGridY * 176 + (clampedGridY - 1) * screenBezelGapPx
        }

    companion object {
        fun maxGridForType(type: DisplayType): Int {
            return when (type) {
                DisplayType.TILED_LOGIC_DISPLAY -> 16
                DisplayType.LOGIC_DISPLAY -> 5
                DisplayType.LARGE_LOGIC_DISPLAY -> 4
            }
        }
    }
}