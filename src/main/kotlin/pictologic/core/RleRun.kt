package pictologic.core

/**
 * Represents a rectangular run-length encoded segment (supports 1D scanline or 2D merged rect).
 * On a Mindustry display, this corresponds to:
 * `draw rect x y width height 0 0`
 */
data class RleRun(
    val x: Int,
    val y: Int,
    val width: Int,
    val colorIndex: Int,
    val rgba: Int,
    val height: Int = 1
) {
    val r: Int get() = (rgba ushr 24) and 0xFF
    val g: Int get() = (rgba ushr 16) and 0xFF
    val b: Int get() = (rgba ushr 8) and 0xFF
    val a: Int get() = rgba and 0xFF

    /**
     * Emits the draw rect mlog instruction with optional coordinate offsets.
     */
    fun toRectInstruction(offsetX: Int = 0, offsetY: Int = 0): String {
        val drawX = x + offsetX
        val drawY = y + offsetY
        return "draw rect " + drawX + " " + drawY + " " + width + " " + height + " 0 0"
    }

    /**
     * Emits the draw color mlog instruction.
     */
    fun toColorInstruction(): String {
        return "draw color " + r + " " + g + " " + b + " " + a + " 0 0"
    }
}