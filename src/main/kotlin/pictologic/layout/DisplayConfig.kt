package pictologic.layout

import mindustry.content.Blocks
import mindustry.world.Block
import mindustry.world.blocks.logic.LogicBlock

/**
 * Configuration options for the target Mindustry display.
 */
enum class DisplayType(
    val title: String,
    val block: Block,
    val nativeSize: Int, // e.g. 80 for logicDisplay, 176 for largeLogicDisplay, 32 for tileLogicDisplay
    val tileSpan: Int,   // 3 for 3x3, 6 for 6x6, 1 for 1x1
    val isTileable: Boolean = false
) {
    LOGIC_DISPLAY("Logic Display (80x80)", Blocks.logicDisplay, 80, 3, false),
    LARGE_LOGIC_DISPLAY("Large Logic Display (176x176)", Blocks.largeLogicDisplay, 176, 6, false),
    TILED_LOGIC_DISPLAY("Tiled Logic Display (Seamless)", Blocks.tileLogicDisplay, 32, 1, true)
}

enum class ProcessorChoice(
    val title: String,
    val block: LogicBlock,
    val ipt: Int, // instructions per tick
    val tileSpan: Int
) {
    LOGIC_PROCESSOR("Logic Processor (8 ipt - Recommended)", Blocks.logicProcessor as LogicBlock, 8, 2),
    MICRO_PROCESSOR("Micro Processor (2 ipt - Lightweight)", Blocks.microProcessor as LogicBlock, 2, 1),
    HYPER_PROCESSOR("Hyper Processor (25 ipt - Ultra)", Blocks.hyperProcessor as LogicBlock, 25, 3)
}

data class DisplayConfig(
    val displayType: DisplayType = DisplayType.LOGIC_DISPLAY,
    val gridX: Int = 1,
    val gridY: Int = 1,
    val pixelWidth: Int = 80,
    val pixelHeight: Int = 80,
    val fps: Int = 10,
    val loop: Boolean = true,
    val paletteSize: Int = 16,
    val processorChoice: ProcessorChoice = ProcessorChoice.LOGIC_PROCESSOR,
    val keyframeInterval: Int = 0,
    val chunksX: Int = 1,
    val chunksY: Int = 1
) {
    /** Total width of the display area in map tiles */
    val totalTilesX: Int get() = gridX * displayType.tileSpan

    /** Total height of the display area in map tiles */
    val totalTilesY: Int get() = gridY * displayType.tileSpan

    /**
     * Effective visible pixel width of the display area.
     * Note: Tiled Logic Display loses 12px (6px border on each side).
     */
    val effectiveDisplayWidth: Int
        get() = when (displayType) {
            DisplayType.TILED_LOGIC_DISPLAY -> kotlin.math.max(1, gridX * 32 - 12)
            DisplayType.LOGIC_DISPLAY -> gridX * 80
            DisplayType.LARGE_LOGIC_DISPLAY -> gridX * 176
        }

    /** Effective visible pixel height of the display area */
    val effectiveDisplayHeight: Int
        get() = when (displayType) {
            DisplayType.TILED_LOGIC_DISPLAY -> kotlin.math.max(1, gridY * 32 - 12)
            DisplayType.LOGIC_DISPLAY -> gridY * 80
            DisplayType.LARGE_LOGIC_DISPLAY -> gridY * 176
        }

    /** Base bezel border offset in pixels (6px for Tiled display, 0 for others) */
    val bezelOffsetX: Int get() = if (displayType == DisplayType.TILED_LOGIC_DISPLAY) 6 else 0
    val bezelOffsetY: Int get() = if (displayType == DisplayType.TILED_LOGIC_DISPLAY) 6 else 0

    /**
     * X offset in screen coordinates to center the image on the display.
     */
    val centerOffsetX: Int
        get() = bezelOffsetX + kotlin.math.max(0, (effectiveDisplayWidth - pixelWidth) / 2)

    /**
     * Y offset in screen coordinates to center the image on the display.
     */
    val centerOffsetY: Int
        get() = bezelOffsetY + kotlin.math.max(0, (effectiveDisplayHeight - pixelHeight) / 2)

    /** Frame interval in ticks (60 ticks/sec). E.g. 10 fps -> 6 ticks */
    val frameIntervalTicks: Int
        get() = kotlin.math.max(1, (60.0f / fps).toInt())

    /** Delay in seconds between frames */
    val frameDelaySeconds: Float
        get() = frameIntervalTicks / 60.0f
}
