package pictologic.layout

import arc.math.geom.Point2
import arc.struct.Seq
import arc.struct.StringMap
import mindustry.content.Blocks
import mindustry.game.Schematic
import mindustry.world.blocks.logic.LogicBlock
import mindustry.world.blocks.logic.LogicBlock.LogicLink
import pictologic.core.EncodedAnimation
import pictologic.core.EncodedFrame
import pictologic.core.RleEncoder
import pictologic.core.RleRun
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class SchematicGenerator(
    val config: DisplayConfig,
    val animation: EncodedAnimation
) {
    constructor(config: DisplayConfig, frames: List<EncodedFrame>) : this(
        config,
        EncodedAnimation(
            initKeyframe = frames.firstOrNull() ?: EncodedFrame(0, true, emptyList(), emptyMap()),
            loopFrames = frames
        )
    )

    // Keep well below Mindustry's 256 display command buffer limit
    private val maxGraphicsBuffer = 200

    class FrameSlice(
        val frameIndex: Int,
        val chunkCoord: Pair<Int, Int>,
        val runs: List<RleRun>,
        val groupedByColor: Map<Int, List<RleRun>>,
        val isInit: Boolean = false,
        val subFrameOrder: Int = 0
    ) {
        val drawInstructionCount: Int
            get() = groupedByColor.size + runs.size
    }

    class WorkerPlan(val chunkCoord: Pair<Int, Int>) {
        var workerId: Int = 0
        val initSlices = ArrayList<FrameSlice>()
        val loopSlices = ArrayList<FrameSlice>()
        var totalInstructions: Int = 40

        fun canAccept(slice: FrameSlice, totalFrames: Int, intervalTicks: Int, ipt: Int): Boolean {
            val added = slice.drawInstructionCount + 18
            if (totalInstructions + added > 900) return false

            if (slice.isInit) {
                return initSlices.isEmpty()
            }

            // Calculate how many frames of gap this worker needs to finish executing all `draw rect` commands
            val ticksNeeded = ceil((slice.drawInstructionCount + 22).toFloat() / ipt).toInt() + 6
            val minGap = max(1, ceil(ticksNeeded.toFloat() / intervalTicks).toInt())

            if (loopSlices.isEmpty()) {
                // CRITICAL FIX: If this worker also has an initSlice, it must wait until Init finishes (k = 0)
                // before it can start buffering its first loopSlice!
                // Therefore, its first loopSlice MUST NOT be at frame 0..minGap, otherwise it will miss frame 0!
                if (initSlices.isNotEmpty() && slice.frameIndex < minGap) {
                    return false
                }
                return true
            }

            val lastSlice = loopSlices.last()
            if (slice.frameIndex <= lastSlice.frameIndex) return false

            val forwardGap = slice.frameIndex - lastSlice.frameIndex
            if (forwardGap < minGap) return false

            if (totalFrames > 1) {
                val firstSlice = loopSlices.first()
                val firstTicksNeeded = ceil((firstSlice.drawInstructionCount + 22).toFloat() / ipt).toInt() + 6
                val minWrapGap = max(1, ceil(firstTicksNeeded.toFloat() / intervalTicks).toInt())
                val wrapGap = (totalFrames - slice.frameIndex) + firstSlice.frameIndex
                if (wrapGap < minWrapGap) return false
            }

            return true
        }

        fun addSlice(slice: FrameSlice) {
            if (slice.isInit) {
                initSlices.add(slice)
            } else {
                loopSlices.add(slice)
            }
            totalInstructions += slice.drawInstructionCount + 18
        }
    }

    fun planWorkers(): Pair<List<WorkerPlan>, Int> {
        val workers = ArrayList<WorkerPlan>()
        val totalFrames = animation.totalFrames
        val ipt = config.processorChoice.ipt
        val intervalTicks = config.frameIntervalTicks

        // 1. Collect all init slices across all chunks
        val allInitSlices = ArrayList<FrameSlice>()
        for (cy in 0 until config.chunksY) {
            for (cx in 0 until config.chunksX) {
                val chunkCoord = cx to cy
                val chunkRuns = if (config.chunksX > 1 || config.chunksY > 1) {
                    RleEncoder.splitRunsIntoChunks(
                        animation.initKeyframe.runs,
                        config.pixelWidth,
                        config.pixelHeight,
                        config.chunksX,
                        config.chunksY
                    )[chunkCoord] ?: emptyList()
                } else {
                    animation.initKeyframe.runs
                }
                allInitSlices.addAll(splitRunsIntoBufferSlices(0, chunkCoord, chunkRuns, isInit = true))
            }
        }

        val totalInitSlices = allInitSlices.size
        var initCounter = -totalInitSlices
        val numberedInitByChunk = HashMap<Pair<Int, Int>, ArrayList<FrameSlice>>()
        for (slice in allInitSlices) {
            val numbered = FrameSlice(
                frameIndex = initCounter++,
                chunkCoord = slice.chunkCoord,
                runs = slice.runs,
                groupedByColor = slice.groupedByColor,
                isInit = true,
                subFrameOrder = 0
            )
            numberedInitByChunk.getOrPut(slice.chunkCoord) { ArrayList() }.add(numbered)
        }

        val frameCommandLoad = IntArray(max(1, totalFrames))
        val frameSubOrderCounter = IntArray(max(1, totalFrames))

        // 2. Schedule workers per spatial chunk
        for (cy in 0 until config.chunksY) {
            for (cx in 0 until config.chunksX) {
                val chunkCoord = cx to cy
                val chunkWorkers = ArrayList<WorkerPlan>()

                val inits = numberedInitByChunk[chunkCoord] ?: emptyList()
                for (initSlice in inits) {
                    val w = WorkerPlan(chunkCoord)
                    w.addSlice(initSlice)
                    chunkWorkers.add(w)
                }

                val chunkLoopSlices = ArrayList<FrameSlice>()
                for (frame in animation.loopFrames) {
                    val chunkRuns = if (config.chunksX > 1 || config.chunksY > 1) {
                        RleEncoder.splitRunsIntoChunks(
                            frame.runs,
                            config.pixelWidth,
                            config.pixelHeight,
                            config.chunksX,
                            config.chunksY
                        )[chunkCoord] ?: emptyList()
                    } else {
                        frame.runs
                    }

                    if (chunkRuns.isEmpty()) continue
                    val rawSlices = splitRunsIntoBufferSlices(frame.frameIndex, chunkCoord, chunkRuns, isInit = false)

                    for (s in rawSlices) {
                        val fIdx = s.frameIndex.coerceIn(0, max(0, totalFrames - 1))
                        val currentLoad = frameCommandLoad[fIdx]
                        if (currentLoad + s.drawInstructionCount > maxGraphicsBuffer) {
                            frameSubOrderCounter[fIdx]++
                            frameCommandLoad[fIdx] = s.drawInstructionCount
                        } else {
                            frameCommandLoad[fIdx] += s.drawInstructionCount
                        }
                        val staggerOrder = frameSubOrderCounter[fIdx]
                        chunkLoopSlices.add(
                            FrameSlice(
                                frameIndex = s.frameIndex,
                                chunkCoord = s.chunkCoord,
                                runs = s.runs,
                                groupedByColor = s.groupedByColor,
                                isInit = false,
                                subFrameOrder = staggerOrder
                            )
                        )
                    }
                }

                if (chunkWorkers.isEmpty() && chunkLoopSlices.isNotEmpty()) {
                    chunkWorkers.add(WorkerPlan(chunkCoord))
                }

                for (slice in chunkLoopSlices) {
                    var found: WorkerPlan? = null
                    for (w in chunkWorkers) {
                        if (w.canAccept(slice, totalFrames, intervalTicks, ipt)) {
                            found = w
                            break
                        }
                    }

                    if (found != null) {
                        found.addSlice(slice)
                    } else {
                        val newWorker = WorkerPlan(chunkCoord)
                        newWorker.addSlice(slice)
                        chunkWorkers.add(newWorker)
                    }
                }

                workers.addAll(chunkWorkers)
            }
        }

        // Prioritize workers with the most loop slices so W0..W23 on the debug screen are active loop workers!
        workers.sortWith(
            compareByDescending<WorkerPlan> { it.loopSlices.size }
                .thenByDescending { it.initSlices.isNotEmpty() }
                .thenByDescending { it.totalInstructions }
        )

        for (i in workers.indices) {
            workers[i].workerId = i
        }

        return workers to totalInitSlices
    }

    private fun splitRunsIntoBufferSlices(
        frameIndex: Int,
        chunkCoord: Pair<Int, Int>,
        chunkRuns: List<RleRun>,
        isInit: Boolean
    ): ArrayList<FrameSlice> {
        val list = ArrayList<FrameSlice>()
        if (chunkRuns.isEmpty()) return list

        val grouped = chunkRuns.groupBy { it.colorIndex }
        val estInstr = grouped.size + chunkRuns.size
        if (estInstr <= maxGraphicsBuffer) {
            list.add(FrameSlice(frameIndex, chunkCoord, chunkRuns, grouped, isInit = isInit))
            return list
        }

        val colorSortedRuns = grouped.values.flatten()
        var curRuns = ArrayList<RleRun>()
        var curColors = HashSet<Int>()

        for (run in colorSortedRuns) {
            val colorsNeeded = if (curColors.contains(run.colorIndex)) 0 else 1
            if (curRuns.size + curColors.size + colorsNeeded + 1 > maxGraphicsBuffer) {
                val g = curRuns.groupBy { it.colorIndex }
                list.add(FrameSlice(frameIndex, chunkCoord, curRuns, g, isInit = isInit))
                curRuns = ArrayList()
                curColors = HashSet()
            }
            curRuns.add(run)
            curColors.add(run.colorIndex)
        }
        if (curRuns.isNotEmpty()) {
            val g = curRuns.groupBy { it.colorIndex }
            list.add(FrameSlice(frameIndex, chunkCoord, curRuns, g, isInit = isInit))
        }

        return list
    }

    private fun generateSurroundingSpiral(
        dispW: Int,
        dispH: Int,
        step: Int,
        count: Int
    ): List<Point2> {
        val candidates = ArrayList<Point2>()
        val maxLayer = max(12, ceil(kotlin.math.sqrt(count.toDouble())).toInt() + 4)

        for (layer in 0 until maxLayer) {
            val minX = -(layer + 1) * step
            val maxX = dispW + layer * step
            val minY = -(layer + 1) * step
            val maxY = dispH + layer * step

            var x = minX
            while (x <= maxX) {
                candidates.add(Point2(x, minY))
                x += step
            }

            var y = minY + step
            while (y <= maxY) {
                candidates.add(Point2(maxX, y))
                y += step
            }

            x = maxX - step
            while (x >= minX) {
                candidates.add(Point2(x, maxY))
                x -= step
            }

            y = maxY - step
            while (y >= minY + step) {
                candidates.add(Point2(minX, y))
                y -= step
            }
        }

        val centerX = dispW / 2.0
        val centerY = dispH / 2.0

        val reservedCount = min(4, candidates.size)
        val firstSlots = candidates.take(reservedCount)
        val cellRefX = firstSlots.firstOrNull()?.x?.toDouble() ?: 0.0
        val cellRefY = firstSlots.firstOrNull()?.y?.toDouble() ?: -step.toDouble()

        val remaining = candidates.drop(reservedCount).sortedBy { pt ->
            val px = pt.x + step / 2.0
            val py = pt.y + step / 2.0
            val distDisp = hypot(px - centerX, py - centerY)
            val distCell = hypot(px - cellRefX, py - cellRefY)
            max(distDisp, distCell)
        }

        val result = ArrayList<Point2>(count)
        result.addAll(firstSlots)
        result.addAll(remaining.take(max(0, count - firstSlots.size)))
        return result
    }

    fun buildSchematic(name: String = "PicToLogic Animation"): Schematic {
        val (workers, totalInitSlices) = planWorkers()
        val totalFrames = animation.totalFrames
        val displayBlock = config.displayType.block
        val procBlock = config.processorChoice.block
        val dispSpan = config.displayType.tileSpan
        val procSpan = config.processorChoice.tileSpan

        val dispTotalW = config.totalTilesX
        val dispTotalH = config.totalTilesY

        val totalSlots = if (procSpan == 1) workers.size + 4 else workers.size + 2
        val rawSpiral = generateSurroundingSpiral(dispTotalW, dispTotalH, procSpan, totalSlots)

        var minX = 0
        var minY = 0
        for (p in rawSpiral) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
        }
        val offsetX = -minX
        val offsetY = -minY

        val tiles = Seq<Schematic.Stile>()

        val dispBaseX = offsetX
        val dispBaseY = offsetY

        for (gx in 0 until config.gridX) {
            for (gy in 0 until config.gridY) {
                val dx = dispBaseX + gx * dispSpan
                val dy = dispBaseY + gy * dispSpan
                tiles.add(Schematic.Stile(displayBlock, dx, dy, null, 0.toByte()))
            }
        }

        val primaryDispX = dispBaseX
        val primaryDispY = dispBaseY

        val switchX: Int
        val switchY: Int
        val cellX: Int
        val cellY: Int
        val msgX: Int
        val msgY: Int
        val ctrlX: Int
        val ctrlY: Int
        val workerStartIdx: Int

        if (procSpan == 1) {
            switchX = rawSpiral[0].x + offsetX
            switchY = rawSpiral[0].y + offsetY
            tiles.add(Schematic.Stile(Blocks.switchBlock, switchX, switchY, null, 0.toByte()))

            cellX = rawSpiral[1].x + offsetX
            cellY = rawSpiral[1].y + offsetY
            tiles.add(Schematic.Stile(Blocks.memoryCell, cellX, cellY, null, 0.toByte()))

            msgX = rawSpiral[2].x + offsetX
            msgY = rawSpiral[2].y + offsetY
            tiles.add(Schematic.Stile(Blocks.message, msgX, msgY, "PicToLogic 2 Debug Initializing...", 0.toByte()))

            ctrlX = rawSpiral[3].x + offsetX
            ctrlY = rawSpiral[3].y + offsetY

            workerStartIdx = 4
        } else {
            val slot0 = rawSpiral[0]
            switchX = slot0.x + offsetX
            switchY = slot0.y + offsetY
            tiles.add(Schematic.Stile(Blocks.switchBlock, switchX, switchY, null, 0.toByte()))

            cellX = switchX + 1
            cellY = switchY
            tiles.add(Schematic.Stile(Blocks.memoryCell, cellX, cellY, null, 0.toByte()))

            msgX = switchX
            msgY = switchY + 1
            tiles.add(Schematic.Stile(Blocks.message, msgX, msgY, "PicToLogic 2 Debug Initializing...", 0.toByte()))

            val ctrlPt = rawSpiral[1]
            ctrlX = ctrlPt.x + offsetX
            ctrlY = ctrlPt.y + offsetY

            workerStartIdx = 2
        }

        val ctrlCode = buildControllerCode(totalInitSlices, totalFrames, workers.size)
        val ctrlLinks = Seq<LogicLink>()
        ctrlLinks.add(LogicLink(primaryDispX - ctrlX, primaryDispY - ctrlY, "display1", true))
        ctrlLinks.add(LogicLink(switchX - ctrlX, switchY - ctrlY, "switch1", true))
        ctrlLinks.add(LogicLink(cellX - ctrlX, cellY - ctrlY, "cell1", true))
        ctrlLinks.add(LogicLink(msgX - ctrlX, msgY - ctrlY, "message1", true))
        val ctrlConfig = LogicBlock.compress(ctrlCode, ctrlLinks)
        tiles.add(Schematic.Stile(procBlock, ctrlX, ctrlY, ctrlConfig, 0.toByte()))

        for (i in workers.indices) {
            val worker = workers[i]
            val pt = rawSpiral[i + workerStartIdx]
            val wx = pt.x + offsetX
            val wy = pt.y + offsetY

            val workerCode = buildWorkerCode(worker)
            val workerLinks = Seq<LogicLink>()
            workerLinks.add(LogicLink(primaryDispX - wx, primaryDispY - wy, "display1", true))
            workerLinks.add(LogicLink(cellX - wx, cellY - wy, "cell1", true))
            val workerConfig = LogicBlock.compress(workerCode, workerLinks)

            tiles.add(Schematic.Stile(procBlock, wx, wy, workerConfig, 0.toByte()))
        }

        var maxXBound = dispBaseX + dispTotalW
        var maxYBound = dispBaseY + dispTotalH

        for (t in tiles) {
            val bSpan = t.block.size
            if (t.x + bSpan > maxXBound) maxXBound = t.x.toInt() + bSpan
            if (t.y + bSpan > maxYBound) maxYBound = t.y.toInt() + bSpan
        }

        val tags = StringMap()
        tags.put("name", name)
        tags.put(
            "description",
            "Converted with PicToLogic 2 (2D Greedy RLE + Live Green-Flash Debug). $totalFrames frames, ${config.fps} FPS,${workers.size} workers."
        )

        return Schematic(tiles, tags, maxXBound, maxYBound)
    }

    /**
     * Fast, zero-loop Controller code with real-time Green-Flash Worker Debug Telemetry.
     *
     * Memory Cell Layout:
     * - cell1[0]: Current clock frame (k < 0 for Init, 0..N-1 for Loop, -999999 for Reset)
     * - cell1[1]: Total drawflush counter across all workers
     * - cell1[2]: Last Worker ID that flushed to display
     * - cell1[3]: Connected & Initialized Workers count
     * - cell1[4..27]: Real-time status code of Worker 0..23:
     *     0 = OFFLINE
     *     1 = EXEC_CMDS (Executing `draw rect` instructions to fill buffer)
     *     2 = WAIT_SYNC (Buffer full, waiting for target frame clock)
     *     3 = CYCLE_DONE / FLUSHED (Flashes GREEN when worker completes a cycle!)
     *     4 = IDLE (Init-only worker finished job)
     */
    /**
     * Helper that joins Mlog tokens with a guaranteed single space so copy-pasting never strips spaces!
     */
    private fun cmd(vararg parts: Any): String = parts.joinToString(" ")

    fun buildControllerCode(totalInitSlices: Int, totalFrames: Int, totalWorkers: Int): String {
        val ipt = config.processorChoice.ipt
        val displayStatusCount = min(20, totalWorkers)
        val initStartupWait = max(1.8f, (maxGraphicsBuffer + 50f) / (ipt * 60f) + 0.8f)
        val stepInitWait = max(0.12f, 16f / (ipt * 60f))
        val postInitWait = max(1.2f, (maxGraphicsBuffer + 50f) / (ipt * 60f) + 0.5f)
        val waitStr = String.format(java.util.Locale.US, "%.1f", initStartupWait)
        val nl = "\\" + "n"

        val sb = StringBuilder()
        sb.appendLine("wait_links:")
        sb.appendLine(cmd("set", "switch", "switch1"))
        sb.appendLine(cmd("set", "display", "display1"))
        sb.appendLine(cmd("set", "cell", "cell1"))
        sb.appendLine(cmd("set", "msg", "message1"))
        sb.appendLine(cmd("jump", "wait_links", "equal", "display", "null"))
        sb.appendLine(cmd("jump", "wait_links", "equal", "cell", "null"))
        sb.appendLine(cmd("control", "enabled", "switch", 1, 0, 0, 0))
        sb.appendLine(cmd("set", "loops", 0))

        sb.appendLine("reload:")
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("control", "enabled", "switch", 1, 0, 0, 0))
        sb.appendLine(cmd("draw", "reset", 0, 0, 0, 0, 0, 0))
        sb.appendLine(cmd("drawflush", "display"))
        sb.appendLine(cmd("write", -999999, "cell", 0))
        sb.appendLine(cmd("write", 0, "cell", 1))
        sb.appendLine(cmd("write", -1, "cell", 2))
        sb.appendLine(cmd("write", 0, "cell", 3))
        for (w in 0 until displayStatusCount) {
            sb.appendLine(cmd("write", 0, "cell", w + 4))
        }

        sb.appendLine("print \"[gold]=== PICTOLOGIC 2 DEBUG ===[]$nl\"")
        sb.appendLine("print \"State: [yellow]RESET & PRE-BUFFERING[]$nl\"")
        sb.appendLine("print \"Total Workers: $totalWorkers | Init Slices: $totalInitSlices$nl\"")
        sb.appendLine("print \"Pre-buffering (${waitStr}s)...\"")
        sb.appendLine(cmd("printflush", "msg"))
        sb.appendLine(cmd("wait", initStartupWait))

        sb.appendLine("init_begin:")
        if (totalInitSlices > 0) {
            sb.appendLine(cmd("set", "k", -totalInitSlices))
            sb.appendLine("init_loop:")
            sb.appendLine(cmd("write", "k", "cell", 0))
            sb.appendLine(cmd("read", "flushes", "cell", 1))
            sb.appendLine(cmd("read", "last_w", "cell", 2))
            sb.appendLine(cmd("read", "conn", "cell", 3))
            sb.appendLine("print \"[gold]=== PICTOLOGIC 2 DEBUG ===[]$nl\"")
            sb.appendLine("print \"State: [orange]RENDERING FRAME 0 (INIT)[]$nl\"")
            sb.appendLine("print \"Connected: [cyan]\"")
            sb.appendLine(cmd("print", "conn"))
            sb.appendLine("print \"/$totalWorkers[] | Slice: [accent]\"")
            sb.appendLine(cmd("print", "k"))
            sb.appendLine("print \"/0[]$nl\"")
            sb.appendLine("print \"Flushes: [green]\"")
            sb.appendLine(cmd("print", "flushes"))
            sb.appendLine("print \"[] | Last Draw: [green]W#\"")
            sb.appendLine(cmd("print", "last_w"))
            sb.appendLine("print \"[]\"")
            sb.appendLine(cmd("printflush", "msg"))
            sb.appendLine(cmd("wait", stepInitWait))
            sb.appendLine(cmd("op", "add", "k", "k", 1))
            sb.appendLine(cmd("jump", "init_loop", "lessThan", "k", 0))
        }
        sb.appendLine(cmd("write", -100000, "cell", 0))
        sb.appendLine(cmd("wait", postInitWait))
        sb.appendLine(cmd("set", "last_tick", "@tick"))

        sb.appendLine("first:")
        sb.appendLine(cmd("set", "i", 0))
        sb.appendLine("loop:")
        sb.appendLine(cmd("jump", "reload", "lessThan", "@tick", "last_tick"))
        sb.appendLine(cmd("set", "last_tick", "@tick"))
        sb.appendLine(cmd("sensor", "enabled", "switch", "@enabled"))
        sb.appendLine(cmd("jump", "reload", "equal", "enabled", 0))

        sb.appendLine(cmd("write", "i", "cell", 0))
        sb.appendLine(cmd("wait", config.frameDelaySeconds))

        sb.appendLine(cmd("read", "flushes", "cell", 1))
        sb.appendLine(cmd("read", "last_w", "cell", 2))
        sb.appendLine(cmd("read", "conn", "cell", 3))

        sb.appendLine("print \"[gold]=== PICTOLOGIC 2 DEBUG ===[]$nl\"")
        sb.appendLine("print \"State: [green]RUNNING[] | Frame: [accent]\"")
        sb.appendLine(cmd("print", "i"))
        sb.appendLine("print \"/$totalFrames[] (${config.fps}fps)$nl\"")
        sb.appendLine("print \"Linked: [cyan]\"")
        sb.appendLine(cmd("print", "conn"))
        sb.appendLine("print \"/$totalWorkers[] | Flushes: [stat]\"")
        sb.appendLine(cmd("print", "flushes"))
        sb.appendLine("print \"[] | Loop: \"")
        sb.appendLine(cmd("print", "loops"))
        sb.appendLine("print \"${nl}Last Completed: [green]W#\"")
        sb.appendLine(cmd("print", "last_w"))
        sb.appendLine("print \"[]$nl[lightgray]1=RunCmd 2=Wait [green]3=Done![] 4=Idle:[]$nl\"")

        for (w in 0 until displayStatusCount) {
            val slot = w + 4
            val lblDone = "p_done_$w"
            val lblNext = "p_next_$w"
            sb.appendLine(cmd("read", "ws", "cell", slot))
            sb.appendLine(cmd("jump", lblDone, "equal", "ws", 3))
            sb.appendLine("print \"W$w:\"")
            sb.appendLine(cmd("print", "ws"))
            sb.appendLine(cmd("jump", lblNext, "always"))
            sb.appendLine("$lblDone:")
            sb.appendLine("print \"[green]W$w:OK[]\"")
            sb.appendLine(cmd("write", 1, "cell", slot))
            sb.appendLine("$lblNext:")
            if (w % 5 == 4 || w == displayStatusCount - 1) {
                sb.appendLine("print \"$nl\"")
            } else {
                sb.appendLine("print \" \"")
            }
        }
        sb.appendLine(cmd("printflush", "msg"))

        sb.appendLine(cmd("op", "add", "i", "i", 1))
        if (totalFrames > 1) {
            sb.appendLine(cmd("jump", "loop", "lessThan", "i", totalFrames))
            sb.appendLine(cmd("op", "add", "loops", "loops", 1))
            sb.appendLine(cmd("jump", "first", "always"))
        } else {
            sb.appendLine("static_hold:")
            sb.appendLine(cmd("jump", "reload", "lessThan", "@tick", "last_tick"))
            sb.appendLine(cmd("set", "last_tick", "@tick"))
            sb.appendLine(cmd("sensor", "enabled", "switch", "@enabled"))
            sb.appendLine(cmd("jump", "reload", "equal", "enabled", 0))
            sb.appendLine(cmd("wait", 0.5f))
            sb.appendLine(cmd("jump", "static_hold", "always"))
        }

        return sb.toString()
    }

    fun buildWorkerCode(worker: WorkerPlan): String {
        val sb = StringBuilder()
        val wId = worker.workerId
        val statusSlot = if (wId < 20) wId + 4 else -1

        sb.appendLine("start:")
        sb.appendLine(cmd("draw", "reset", 0, 0, 0, 0, 0, 0))
        sb.appendLine(cmd("set", "display", "display1"))
        sb.appendLine(cmd("set", "cell", "cell1"))
        sb.appendLine(cmd("jump", "start", "equal", "display", "null"))
        sb.appendLine(cmd("jump", "start", "equal", "cell", "null"))

        // Wait until Controller finishes resetting cell1[3] before incrementing connected count
        sb.appendLine("wait_reset_clear:")
        sb.appendLine(cmd("read", "i", "cell", 0))
        sb.appendLine(cmd("jump", " register_conn".trim(), "notEqual", "i", -999999))
        sb.appendLine("register_conn:")
        sb.appendLine(cmd("read", "conn", "cell", 3))
        sb.appendLine(cmd("op", "add", "conn", "conn", 1))
        sb.appendLine(cmd("write", "conn", "cell", 3))

        val ox = config.centerOffsetX
        val oy = config.centerOffsetY
        val cx = worker.chunkCoord.first
        val cy = worker.chunkCoord.second

        // Phase 1: Init slice (1 per worker)
        for ((idx, slice) in worker.initSlices.withIndex()) {
            if (statusSlot >= 4) {
                sb.appendLine(cmd("write", 1, "cell", statusSlot))
            }
            for ((_, runs) in slice.groupedByColor) {
                if (runs.isEmpty()) continue
                sb.appendLine(runs[0].toColorInstruction())
                for (run in runs) {
                    sb.appendLine(run.toRectInstruction(ox, oy))
                }
            }
            if (statusSlot >= 4) {
                sb.appendLine(cmd("write", 2, "cell", statusSlot))
            }
            val waitLabel = "wait_init_${cx}_${cy}_s$idx"
            val skipLabel = "skip_init_${cx}_${cy}_s$idx"
            sb.appendLine("$waitLabel:")
            sb.appendLine(cmd("read", "i", "cell", 0))
            sb.appendLine(cmd("jump", waitLabel, "equal", "i", -999999))
            sb.appendLine(cmd("jump", skipLabel, "greaterThanEq", "i", 0))
            sb.appendLine(cmd("jump", waitLabel, "lessThan", "i", slice.frameIndex))

            sb.appendLine(cmd("drawflush", "display"))
            if (statusSlot >= 4) {
                sb.appendLine(cmd("write", 3, "cell", statusSlot))
            }
            sb.appendLine(cmd("read", "fc", "cell", 1))
            sb.appendLine(cmd("op", "add", "fc", "fc", 1))
            sb.appendLine(cmd("write", "fc", "cell", 1))
            sb.appendLine(cmd("write", wId, "cell", 2))
            sb.appendLine("$skipLabel:")
        }

        if (worker.initSlices.isNotEmpty()) {
            sb.appendLine("wait_init_done:")
            sb.appendLine(cmd("read", "i", "cell", 0))
            sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
            sb.appendLine(cmd("jump", "wait_init_done", "lessThan", "i", -100000))
        }

        // Phase 2: Animation loop slices
        if (worker.loopSlices.isNotEmpty()) {
            sb.appendLine("loop_start:")
            for ((idx, slice) in worker.loopSlices.withIndex()) {
                if (statusSlot >= 4) {
                    sb.appendLine(cmd("write", 1, "cell", statusSlot))
                }
                for ((_, runs) in slice.groupedByColor) {
                    if (runs.isEmpty()) continue
                    sb.appendLine(runs[0].toColorInstruction())
                    for (run in runs) {
                        sb.appendLine(run.toRectInstruction(ox, oy))
                    }
                }

                if (statusSlot >= 4) {
                    sb.appendLine(cmd("write", 2, "cell", statusSlot))
                }

                val waitInitEnd = "wait_pre_${slice.frameIndex}_$idx"
                sb.appendLine("$waitInitEnd:")
                sb.appendLine(cmd("read", "i", "cell", 0))
                sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
                sb.appendLine(cmd("jump", waitInitEnd, "lessThan", "i", 0))

                if (idx == 0 && slice.frameIndex < animation.totalFrames - 3) {
                    val waitWrap = "wait_wrap_${slice.frameIndex}_$idx"
                    sb.appendLine("$waitWrap:")
                    sb.appendLine(cmd("read", "i", "cell", 0))
                    sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
                    sb.appendLine(cmd("jump", waitWrap, "greaterThan", "i", slice.frameIndex + 2))
                }

                val waitLabel = "wait_f${slice.frameIndex}_s${idx}_c${cx}_${cy}"
                sb.appendLine("$waitLabel:")
                sb.appendLine(cmd("read", "i", "cell", 0))
                sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
                sb.appendLine(cmd("jump", waitLabel, "lessThan", "i", slice.frameIndex))

                if (slice.subFrameOrder > 0) {
                    val staggerSec = String.format(java.util.Locale.US, "%.4f", slice.subFrameOrder / 60.0f)
                    sb.appendLine(cmd("wait", staggerSec))
                }

                sb.appendLine(cmd("drawflush", "display"))
                if (statusSlot >= 4) {
                    sb.appendLine(cmd("write", 3, "cell", statusSlot))
                }
                sb.appendLine(cmd("read", "fc", "cell", 1))
                sb.appendLine(cmd("op", "add", "fc", "fc", 1))
                sb.appendLine(cmd("write", "fc", "cell", 1))
                sb.appendLine(cmd("write", wId, "cell", 2))

                val advLabel = "adv_f${slice.frameIndex}_s${idx}_c${cx}_${cy}"
                sb.appendLine("$advLabel:")
                sb.appendLine(cmd("read", "i", "cell", 0))
                sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
                sb.appendLine(cmd("jump", advLabel, "equal", "i", slice.frameIndex))
            }

            val lastFrameIdx = worker.loopSlices.last().frameIndex
            sb.appendLine("wait_cycle_end:")
            sb.appendLine(cmd("read", "i", "cell", 0))
            sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
            sb.appendLine(cmd("jump", "wait_cycle_end", "greaterThanEq", "i", lastFrameIdx))

            sb.appendLine(cmd("jump", "loop_start", "always"))
        } else {
            if (statusSlot >= 4) {
                sb.appendLine(cmd("write", 4, "cell", statusSlot))
            }
            sb.appendLine("idle:")
            sb.appendLine(cmd("read", "i", "cell", 0))
            sb.appendLine(cmd("jump", "start", "equal", "i", -999999))
            sb.appendLine(cmd("wait", 0.5f))
            sb.appendLine(cmd("jump", "idle", "always"))
        }

        return sb.toString()
    }
}