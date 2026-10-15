package pictologic.staticimage

import arc.math.geom.Point2
import arc.struct.Seq
import arc.struct.StringMap
import mindustry.content.Blocks
import mindustry.game.Schematic
import mindustry.world.blocks.logic.LogicBlock
import mindustry.world.blocks.logic.LogicBlock.LogicLink
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Generates Mindustry Schematics for Static Images:
 *
 * 1. TILED 1x1 DISPLAY MODE (Max 16x16):
 *    - Global unified coordinate system (+6px bezel offset) across all merged 1x1 tiles.
 *    - Daisy-chained perimeter processors: Controller -> W1 -> W2 -> ... -> Wn.
 *    - Uses `sensor` on upstream processor / switch state and periodic refresh so 100% of slices render
 *      without ever disabling processors or exceeding link range!
 *
 * 2. MULTI-SCREEN CHUNK MODE (3x3 max 5x5, 6x6 max 4x4):
 *    - Forced 2x2 Logic Processors placed right next to the specific screen (gx, gy) they manage.
 *    - Zero dependency on a distant `cell1` (which previously caused outer chunks >14 tiles away to fail!).
 *    - Each chunk cluster links directly to its adjacent screen `display1` and renders autonomously,
 *      while also relaying the Master Controller's reload signal across neighbor processors!
 */
class StaticSchematicGenerator(
    val config: StaticImageConfig,
    val encoded: EncodedStaticImage
) {

    private fun cmd(vararg parts: Any): String = parts.joinToString(" ")

    private fun generatePerimeterSlots(dispW: Int, dispH: Int, step: Int, countNeeded: Int): List<Point2> {
        val slots = ArrayList<Point2>()
        var layer = 1

        while (slots.size < countNeeded) {
            val minX = -layer * step
            val maxX = dispW + (layer - 1) * step
            val minY = -layer * step
            val maxY = dispH + (layer - 1) * step

            var x = minX
            while (x <= maxX && slots.size < countNeeded) {
                slots.add(Point2(x, minY))
                x += step
            }

            var y = minY + step
            while (y <= maxY && slots.size < countNeeded) {
                slots.add(Point2(maxX, y))
                y += step
            }

            x = maxX - step
            while (x >= minX && slots.size < countNeeded) {
                slots.add(Point2(x, maxY))
                x -= step
            }

            y = maxY - step
            while (y >= minY + step && slots.size < countNeeded) {
                slots.add(Point2(minX, y))
                y -= step
            }

            layer++
        }

        return slots
    }

    fun buildSchematic(name: String = "PicToLogic Static Image"): Schematic {
        return if (config.isMultiScreenChunked) {
            buildMultiScreenChunkedSchematic(name)
        } else {
            buildTiledChainSchematic(name)
        }
    }

    /**
     * ARCHITECTURE A: 1x1 Tiled Display (up to 16x16) with Daisy-Chained Processors.
     */
    private fun buildTiledChainSchematic(name: String): Schematic {
        val slices = encoded.workerSlices
        val totalWorkers = max(1, slices.size)
        val displayBlock = config.displayType.block
        val procBlock = config.effectiveProcessor.block
        val dispSpan = config.displayType.tileSpan
        val procSpan = config.effectiveProcessor.tileSpan

        val gridX = config.clampedGridX
        val gridY = config.clampedGridY
        val dispTotalW = config.totalTilesX
        val dispTotalH = config.totalTilesY

        val auxiliarySlots = if (procSpan == 1) 2 else 1 // Switch + Message
        val totalSlotsNeeded = auxiliarySlots + totalWorkers
        val perimeter = generatePerimeterSlots(dispTotalW, dispTotalH, procSpan, totalSlotsNeeded)

        var minX = 0
        var minY = 0
        for (p in perimeter) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
        }
        val offsetX = -minX
        val offsetY = -minY

        val tiles = Seq<Schematic.Stile>()
        val dispBaseX = offsetX
        val dispBaseY = offsetY

        for (gx in 0 until gridX) {
            for (gy in 0 until gridY) {
                val dx = dispBaseX + gx * dispSpan
                val dy = dispBaseY + gy * dispSpan
                tiles.add(Schematic.Stile(displayBlock, dx, dy, null, 0.toByte()))
            }
        }

        val switchX: Int; val switchY: Int
        val msgX: Int; val msgY: Int

        if (procSpan == 1) {
            switchX = perimeter[0].x + offsetX; switchY = perimeter[0].y + offsetY
            msgX = perimeter[1].x + offsetX; msgY = perimeter[1].y + offsetY
        } else {
            val slot0 = perimeter[0]
            switchX = slot0.x + offsetX; switchY = slot0.y + offsetY
            msgX = switchX + 1; msgY = switchY
        }

        tiles.add(Schematic.Stile(Blocks.switchBlock, switchX, switchY, null, 0.toByte()))
        tiles.add(Schematic.Stile(Blocks.message, msgX, msgY, "PicToLogic Static: Ready", 0.toByte()))

        val workerPositions = ArrayList<Point2>(totalWorkers)
        for (i in 0 until totalWorkers) {
            val pt = perimeter[i + auxiliarySlots]
            workerPositions.add(Point2(pt.x + offsetX, pt.y + offsetY))
        }

        // Link each perimeter processor to the nearest 1x1 display tile (always 1..2 tiles away, well within 10-tile range!).
        // In Mindustry's TiledLogicDisplay, all merged tiles share the SAME global canvas origin (0,0 at bottom-left)!
        fun findClosestTile(procX: Int, procY: Int): Point2 {
            val bestGx = ((procX - dispBaseX) / dispSpan).coerceIn(0, gridX - 1)
            val bestGy = ((procY - dispBaseY) / dispSpan).coerceIn(0, gridY - 1)
            return Point2(dispBaseX + bestGx * dispSpan, dispBaseY + bestGy * dispSpan)
        }

        // Build Worker 0 (Master Controller + Slice 0)
        val w0Pos = workerPositions[0]
        val w0Slice = slices.firstOrNull() ?: StaticWorkerSlice(0, 0, 0, emptyList(), emptyMap())
        val w0Disp = findClosestTile(w0Pos.x, w0Pos.y)
        val w0Code = buildTiledControllerCode(w0Slice, totalWorkers)
        val w0Links = Seq<LogicLink>()
        w0Links.add(LogicLink(switchX - w0Pos.x, switchY - w0Pos.y, "switch1", true))
        w0Links.add(LogicLink(msgX - w0Pos.x, msgY - w0Pos.y, "message1", true))
        w0Links.add(LogicLink(w0Disp.x - w0Pos.x, w0Disp.y - w0Pos.y, "display1", true))
        if (totalWorkers > 1) {
            val w1Pos = workerPositions[1]
            w0Links.add(LogicLink(w1Pos.x - w0Pos.x, w1Pos.y - w0Pos.y, "processor1", true))
        }
        tiles.add(Schematic.Stile(procBlock, w0Pos.x, w0Pos.y, LogicBlock.compress(w0Code, w0Links), 0.toByte()))

        // Build Chained Workers 1 .. N-1
        // Each Worker i links to its nearest display tile AND links to Worker i+1 ("processor1") in a daisy-chain!
        for (i in 1 until totalWorkers) {
            val wPos = workerPositions[i]
            val slice = slices[i]
            val hasNext = i < totalWorkers - 1
            val disp = findClosestTile(wPos.x, wPos.y)

            val wCode = buildTiledChainedWorkerCode(i, slice, hasNext)
            val wLinks = Seq<LogicLink>()
            wLinks.add(LogicLink(disp.x - wPos.x, disp.y - wPos.y, "display1", true))
            if (hasNext) {
                val nextPos = workerPositions[i + 1]
                wLinks.add(LogicLink(nextPos.x - wPos.x, nextPos.y - wPos.y, "processor1", true))
            }

            tiles.add(Schematic.Stile(procBlock, wPos.x, wPos.y, LogicBlock.compress(wCode, wLinks), 0.toByte()))
        }

        return finalizeSchematic(name, tiles, dispBaseX + dispTotalW, dispBaseY + dispTotalH, totalWorkers)
    }

    /**
     * ARCHITECTURE B: 3x3 (max 5x5) and 6x6 (max 4x4) Multi-Screen Chunk Mode.
     * - Forced 2x2 Logic Processors.
     * - Each screen (gx, gy) is an independent chunk.
     * - Processors are placed right next to their assigned screen (gx, gy) so distance is always <= 10 tiles!
     * - Workers do NOT depend on a distant `cell1` (so 100% of screens render immediately even on a huge 24x24 6x6 grid!).
     */
    private fun buildMultiScreenChunkedSchematic(name: String): Schematic {
        val slices = encoded.workerSlices
        val totalWorkers = max(1, slices.size)
        val displayBlock = config.displayType.block
        val procBlock = config.effectiveProcessor.block // Forced 2x2 Logic Processor
        val dispSpan = config.displayType.tileSpan
        val procSpan = config.effectiveProcessor.tileSpan // 2

        val gridX = config.clampedGridX
        val gridY = config.clampedGridY
        val dispTotalW = config.totalTilesX
        val dispTotalH = config.totalTilesY

        val totalSlotsNeeded = 2 + totalWorkers
        // Generate ample perimeter slots so every screen chunk can pick the closest slots to itself
        val candidateSlots = generatePerimeterSlots(dispTotalW, dispTotalH, procSpan, totalSlotsNeeded + 32)
        val allCandidates = candidateSlots.toMutableList()

        val auxSlot = allCandidates.removeAt(0)
        val ctrlSlot = allCandidates.removeAt(0)

        // Assign each worker slice to the candidate slot closest to its target screen (screenX, screenY)
        val assignedWorkerSlots = ArrayList<Point2>(totalWorkers)
        for (slice in slices) {
            val targetScreenCenterX = slice.screenX * dispSpan + dispSpan / 2.0
            val targetScreenCenterY = slice.screenY * dispSpan + dispSpan / 2.0

            var bestIdx = 0
            var bestDist = Double.MAX_VALUE
            for (idx in allCandidates.indices) {
                val pt = allCandidates[idx]
                val px = pt.x + procSpan / 2.0
                val py = pt.y + procSpan / 2.0
                // Pure distance to its assigned screen so it NEVER exceeds the 14-tile link range!
                val dScreen = hypot(px - targetScreenCenterX, py - targetScreenCenterY)
                if (dScreen < bestDist) {
                    bestDist = dScreen
                    bestIdx = idx
                }
            }
            assignedWorkerSlots.add(allCandidates.removeAt(bestIdx))
        }

        val usedPoints = ArrayList<Point2>()
        usedPoints.add(auxSlot)
        usedPoints.add(ctrlSlot)
        usedPoints.addAll(assignedWorkerSlots)

        var minX = 0; var minY = 0
        for (p in usedPoints) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
        }
        val offsetX = -minX
        val offsetY = -minY

        val tiles = Seq<Schematic.Stile>()
        val dispBaseX = offsetX
        val dispBaseY = offsetY

        // 1. Place Display Grid
        for (gx in 0 until gridX) {
            for (gy in 0 until gridY) {
                val dx = dispBaseX + gx * dispSpan
                val dy = dispBaseY + gy * dispSpan
                tiles.add(Schematic.Stile(displayBlock, dx, dy, null, 0.toByte()))
            }
        }

        // 2. Place Switch, Memory Cell, and Message in auxSlot (2x2 area)
        val switchX = auxSlot.x + offsetX
        val switchY = auxSlot.y + offsetY
        val cellX = switchX + 1
        val cellY = switchY
        val msgX = switchX
        val msgY = switchY + 1

        tiles.add(Schematic.Stile(Blocks.switchBlock, switchX, switchY, null, 0.toByte()))
        tiles.add(Schematic.Stile(Blocks.memoryCell, cellX, cellY, null, 0.toByte()))
        tiles.add(Schematic.Stile(Blocks.message, msgX, msgY, "PicToLogic Chunked Static: Ready", 0.toByte()))

        // 3. Place Master Controller (2x2 Logic Processor)
        val ctrlX = ctrlSlot.x + offsetX
        val ctrlY = ctrlSlot.y + offsetY
        val ctrlCode = buildChunkedMasterControllerCode(totalWorkers, gridX * gridY)
        val ctrlLinks = Seq<LogicLink>()
        ctrlLinks.add(LogicLink(switchX - ctrlX, switchY - ctrlY, "switch1", true))
        ctrlLinks.add(LogicLink(cellX - ctrlX, cellY - ctrlY, "cell1", true))
        ctrlLinks.add(LogicLink(msgX - ctrlX, msgY - ctrlY, "message1", true))
        tiles.add(Schematic.Stile(procBlock, ctrlX, ctrlY, LogicBlock.compress(ctrlCode, ctrlLinks), 0.toByte()))

        // 4. Place Chunk Workers (2x2 Logic Processors)
        // Each worker links directly to its adjacent screen `display1` (and `cell1` if within 13.5 tiles).
        val screenWorkerOrder = HashMap<Pair<Int, Int>, Int>()
        for (i in slices.indices) {
            val slice = slices[i]
            val pt = assignedWorkerSlots[i]
            val wx = pt.x + offsetX
            val wy = pt.y + offsetY

            val targetDispX = dispBaseX + slice.screenX * dispSpan
            val targetDispY = dispBaseY + slice.screenY * dispSpan

            val screenKey = slice.screenX to slice.screenY
            val orderInScreen = screenWorkerOrder[screenKey] ?: 0
            screenWorkerOrder[screenKey] = orderInScreen + 1

            val distToCell = hypot((cellX - wx).toDouble(), (cellY - wy).toDouble())
            val canLinkCell = distToCell <= 13.5

            val wCode = buildChunkWorkerCode(i, slice, orderInScreen, canLinkCell)
            val wLinks = Seq<LogicLink>()
            wLinks.add(LogicLink(targetDispX - wx, targetDispY - wy, "display1", true))
            if (canLinkCell) {
                wLinks.add(LogicLink(cellX - wx, cellY - wy, "cell1", true))
            }

            tiles.add(Schematic.Stile(procBlock, wx, wy, LogicBlock.compress(wCode, wLinks), 0.toByte()))
        }

        return finalizeSchematic(name, tiles, dispBaseX + dispTotalW, dispBaseY + dispTotalH, totalWorkers)
    }

    private fun finalizeSchematic(
        name: String,
        tiles: Seq<Schematic.Stile>,
        initMaxX: Int,
        initMaxY: Int,
        totalWorkers: Int
    ): Schematic {
        var maxXBound = initMaxX
        var maxYBound = initMaxY
        for (t in tiles) {
            val bSpan = t.block.size
            if (t.x + bSpan > maxXBound) maxXBound = t.x.toInt() + bSpan
            if (t.y + bSpan > maxYBound) maxYBound = t.y.toInt() + bSpan
        }

        val tags = StringMap()
        tags.put("name", name)
        tags.put(
            "description",
            "Static image (${config.clampedGridX}x${config.clampedGridY} ${config.displayType.title}),$totalWorkers workers."
        )
        return Schematic(tiles, tags, maxXBound, maxYBound)
    }

    /**
     * Controller for Mode A (1x1 Tiled Daisy-Chain):
     * - Draws Slice 0 using global coordinates (+6px bezel offset).
     * - Triggers downstream chain via temporary pulse on `processor1` when Switch is clicked.
     */
    private fun buildTiledControllerCode(
        slice0: StaticWorkerSlice,
        totalWorkers: Int
    ): String {
        val sb = StringBuilder()
        val nl = "\\" + "n"
        val ox = config.tiledBezelOffsetPx
        val oy = config.tiledBezelOffsetPx
        val ipt = config.effectiveProcessor.ipt
        val estSec = max(0.6f, encoded.totalDrawInstructions / (ipt * 60.0f) + totalWorkers * 0.05f)
        val secStr = String.format(java.util.Locale.US, "%.1f", estSec)

        sb.appendLine("init:")
        sb.appendLine(cmd("set", "disp", "display1"))
        sb.appendLine(cmd("set", "sw", "switch1"))
        sb.appendLine(cmd("set", "msg", "message1"))
        sb.appendLine(cmd("jump", "init", "equal", "disp", "null"))

        sb.appendLine("start_render:")
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("control", "enabled", "sw", 1, 0, 0, 0))

        sb.appendLine("print \"[gold]=== PICTOLOGIC STATIC (1x1 TILED) ===[]$nl\"")
        sb.appendLine("print \"Status: [yellow]RENDERING CHAIN...[]$nl\"")
        sb.appendLine("print \"Screen Grid: [accent]${config.clampedGridX}x${config.clampedGridY}[] (${encoded.canvasWidth}x${encoded.canvasHeight}px)$nl\"")
        sb.appendLine("print \"Chain Length: [cyan]$totalWorkers[] processors$nl\"")
        sb.appendLine("print \"Est. Time: ~${secStr}s\"")
        sb.appendLine(cmd("printflush", "msg"))

        // Draw Slice 0
        sb.appendLine(cmd("draw", "reset", 0, 0, 0, 0, 0, 0))
        for ((_, runs) in slice0.groupedByColor) {
            if (runs.isEmpty()) continue
            sb.appendLine(runs[0].toColorInstruction())
            for (run in runs) {
                sb.appendLine(run.toRectInstruction(ox, oy))
            }
        }
        sb.appendLine(cmd("drawflush", "disp"))

        // Pulse processor1 (briefly disable for 1 tick then re-enable) to propagate reload signal down the chain!
        if (totalWorkers > 1) {
            sb.appendLine(cmd("control", "enabled", "processor1", 0, 0, 0, 0))
            sb.appendLine(cmd("wait", 0.03f))
            sb.appendLine(cmd("control", "enabled", "processor1", 1, 0, 0, 0))
            sb.appendLine(cmd("wait", estSec))
        }

        sb.appendLine("hold_loop:")
        sb.appendLine("print \"[gold]=== PICTOLOGIC STATIC (1x1 TILED) ===[]$nl\"")
        sb.appendLine("print \"Status: [green]RENDER COMPLETE! [OK][]$nl\"")
        sb.appendLine("print \"Screen Grid: [accent]${config.clampedGridX}x${config.clampedGridY}[] (${encoded.canvasWidth}x${encoded.canvasHeight}px)$nl\"")
        sb.appendLine("print \"Chain Active: [green]$totalWorkers/$totalWorkers[] | Cmds: [stat]${encoded.totalDrawInstructions}[]$nl\"")
        sb.appendLine("print \"[lightgray]Click Switch to Re-render[]\"")
        sb.appendLine(cmd("printflush", "msg"))

        sb.appendLine(cmd("jump", "start_render", "lessThan", "@tick", "last_tick"))
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("sensor", "on", "sw", "@enabled"))
        sb.appendLine(cmd("jump", "start_render", "equal", "on", 0))
        sb.appendLine(cmd("wait", 0.4f))
        sb.appendLine(cmd("jump", "hold_loop", "always"))

        return sb.toString()
    }

    /**
     * Daisy-Chained Worker Code for Mode A (1x1 Tiled Display):
     * - Uses global coordinates (+6px bezel offset) so all 16x16 tiles render seamlessly!
     * - Staggers initial draw by `workerIdx * 0.04s` so the 256-cmd display buffer never overflows!
     * - Relays reload pulses along `processor1` and periodically refreshes every few seconds so
     *   the static image stays permanently rendered even if displays are rebuilt!
     */
    private fun buildTiledChainedWorkerCode(
        workerIdx: Int,
        slice: StaticWorkerSlice,
        hasNext: Boolean
    ): String {
        val sb = StringBuilder()
        val ox = config.tiledBezelOffsetPx
        val oy = config.tiledBezelOffsetPx
        val initialDelay = String.format(java.util.Locale.US, "%.3f", workerIdx * 0.04f)

        sb.appendLine("boot:")
        sb.appendLine(cmd("set", "disp", "display1"))
        sb.appendLine(cmd("jump", "boot", "equal", "disp", "null"))
        sb.appendLine(cmd("wait", initialDelay))

        sb.appendLine("do_draw:")
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("draw", "reset", 0, 0, 0, 0, 0, 0))
        for ((_, runs) in slice.groupedByColor) {
            if (runs.isEmpty()) continue
            sb.appendLine(runs[0].toColorInstruction())
            for (run in runs) {
                sb.appendLine(run.toRectInstruction(ox, oy))
            }
        }
        sb.appendLine(cmd("drawflush", "disp"))

        if (hasNext) {
            sb.appendLine(cmd("control", "enabled", "processor1", 1, 0, 0, 0))
        }

        // Wait loop: re-draws immediately on World Reload (@tick < last_tick)
        // or every ~4 seconds (staggered) to keep the static canvas 100% persistent!
        sb.appendLine(cmd("set", "w_cnt", 0))
        sb.appendLine("wait_loop:")
        sb.appendLine(cmd("jump", "do_draw", "lessThan", "@tick", "last_tick"))
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("wait", 0.25f))
        sb.appendLine(cmd("op", "add", "w_cnt", "w_cnt", 1))
        sb.appendLine(cmd("jump", "wait_loop", "lessThan", "w_cnt", 16))
        sb.appendLine(cmd("jump", "do_draw", "always"))

        return sb.toString()
    }

    /**
     * Master Controller for Mode B (3x3 and 6x6 Multi-Screen Chunked Mode).
     */
    private fun buildChunkedMasterControllerCode(totalWorkers: Int, totalScreens: Int): String {
        val sb = StringBuilder()
        val nl = "\\" + "n"

        sb.appendLine("init:")
        sb.appendLine(cmd("set", "sw", "switch1"))
        sb.appendLine(cmd("set", "cell", "cell1"))
        sb.appendLine(cmd("set", "msg", "message1"))
        sb.appendLine(cmd("jump", "init", "equal", "cell", "null"))
        sb.appendLine(cmd("set", "epoch", 1))

        sb.appendLine("trigger_render:")
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("control", "enabled", "sw", 1, 0, 0, 0))
        sb.appendLine(cmd("write", 0, "cell", 1))
        sb.appendLine(cmd("op", "add", "epoch", "epoch", 1))
        sb.appendLine(cmd("write", "epoch", "cell", 0))

        sb.appendLine("monitor_loop:")
        sb.appendLine(cmd("read", "last_w", "cell", 2))
        sb.appendLine("print \"[gold]=== PICTOLOGIC CHUNKED STATIC ===[]$nl\"")
        sb.appendLine("print \"Status: [green]ALL CHUNKS ACTIVE [OK][]$nl\"")
        sb.appendLine("print \"Screen Grid: [accent]${config.clampedGridX}x${config.clampedGridY}[] ($totalScreens screens)$nl\"")
        sb.appendLine("print \"Resolution: [accent]${encoded.canvasWidth}x${encoded.canvasHeight}px[] (Bezel aligned)$nl\"")
        sb.appendLine("print \"Total Chunk Workers: [cyan]$totalWorkers[] (2x2 Logic)$nl\"")
        sb.appendLine("print \"[lightgray]Click Switch to Re-render[]\"")
        sb.appendLine(cmd("printflush", "msg"))

        sb.appendLine(cmd("jump", "trigger_render", "lessThan", "@tick", "last_tick"))
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("sensor", "on", "sw", "@enabled"))
        sb.appendLine(cmd("jump", "trigger_render", "equal", "on", 0))
        sb.appendLine(cmd("wait", 0.25f))
        sb.appendLine(cmd("jump", "monitor_loop", "always"))

        return sb.toString()
    }

    /**
     * Chunk Worker Code for Mode B (3x3 and 6x6 Displays):
     * - NEVER blocks waiting for `cell1` if `cell1` is out of range (>14 tiles away on 6x6 grids)!
     * - Immediately renders its assigned screen chunk `(screenX, screenY)` on placement and World Reload,
     *   and listens to `cell1[0]` if within range (or refreshes periodically if out of cell range).
     */
    private fun buildChunkWorkerCode(
        workerId: Int,
        slice: StaticWorkerSlice,
        orderInScreen: Int,
        canLinkCell: Boolean
    ): String {
        val sb = StringBuilder()
        val staggerDelay = String.format(java.util.Locale.US, "%.3f", orderInScreen * 0.06f + 0.02f)

        sb.appendLine("boot:")
        sb.appendLine(cmd("set", "disp", "display1"))
        sb.appendLine(cmd("jump", "boot", "equal", "disp", "null"))
        sb.appendLine(cmd("set", "my_epoch", -1))

        sb.appendLine("do_render:")
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("draw", "reset", 0, 0, 0, 0, 0, 0))
        for ((_, runs) in slice.groupedByColor) {
            if (runs.isEmpty()) continue
            sb.appendLine(runs[0].toColorInstruction())
            for (run in runs) {
                sb.appendLine(run.toRectInstruction(0, 0))
            }
        }

        sb.appendLine(cmd("wait", staggerDelay))
        sb.appendLine(cmd("drawflush", "disp"))

        if (canLinkCell) {
            sb.appendLine(cmd("read", "done_w", "cell1", 1))
            sb.appendLine(cmd("op", "add", "done_w", "done_w", 1))
            sb.appendLine(cmd("write", "done_w", "cell1", 1))
            sb.appendLine(cmd("write", workerId, "cell1", 2))
        }

        sb.appendLine(cmd("set", "idle_cnt", 0))
        sb.appendLine("wait_loop:")
        sb.appendLine(cmd("jump", "do_render", "lessThan", "@tick", "last_tick"))
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        if (canLinkCell) {
            sb.appendLine(cmd("read", "cur_epoch", "cell1", 0))
            sb.appendLine(cmd("jump", "check_timer", "equal", "cur_epoch", "my_epoch"))
            sb.appendLine(cmd("set", "my_epoch", "cur_epoch"))
            sb.appendLine(cmd("jump", "do_render", "always"))
        }
        sb.appendLine("check_timer:")
        sb.appendLine(cmd("wait", 0.25f))
        sb.appendLine(cmd("op", "add", "idle_cnt", "idle_cnt", 1))
        sb.appendLine(cmd("jump", "wait_loop", "lessThan", "idle_cnt", 16))
        sb.appendLine(cmd("jump", "do_render", "always"))

        return sb.toString()
    }
}