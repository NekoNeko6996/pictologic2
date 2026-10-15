package pictologic.ui

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.CheckBox
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.Slider
import arc.scene.ui.TextButton
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Table
import arc.util.Log
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.FileChooser
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import pictologic.core.*
import pictologic.layout.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Main dialog for PicToLogic 2.
 * Supports Logic Display, Large Display, and Tiled Logic Display (seamless),
 * multi-display grids with visual borders, golden button highlights,
 * hover tooltips, and Switch reload buttons.
 */
class PicToLogicDialog : BaseDialog("PicToLogic 2") {

    // Current State
    private var selectedFile: Fi? = null
    private var discoveredFiles: List<Fi> = emptyList()
    private var rawFrames: List<Pixmap> = emptyList()
    private var encodedAnimation: EncodedAnimation? = null
    private var encodedFrames: List<EncodedFrame> = emptyList()
    private var activePalette: Palette? = null

    // Configurations
    private var displayType = DisplayType.LOGIC_DISPLAY
    private var gridScreensX = 1
    private var gridScreensY = 1
    private var processorChoice = ProcessorChoice.LOGIC_PROCESSOR
    private var aspectMode = AspectMode.FIT
    private var targetResolution = 80
    private var fps = 10
    private var paletteColors = 16
    private var loop = true
    private var maxFramesLimit = 40
    private var chunksDim = 1

    // Preview state
    private var previewPixmap: Pixmap? = null
    private var previewTexture: Texture? = null
    private var previewImage: Image? = null
    private var isPlaying = true
    private var currentPreviewFrame = 0
    private var previewTimer = 0f
    private val isEncoding = AtomicBoolean(false)

    // UI Widgets
    private val statusLabel = Label("No image selected")
    private val filesInfoLabel = Label("")
    private val statsLabel = Label("Select an image to start")
    private val previewFrameLabel = Label("Frame: 0/0")
    private val frameSlider = Slider(0f, 0f, 1f, false)
    private val paletteTable = Table(Tex.pane)

    // Option Button Groups for Golden Highlights
    private val displayButtons = ArrayList<Pair<DisplayType, TextButton>>()
    private val aspectButtons = ArrayList<Pair<AspectMode, TextButton>>()
    private val procButtons = ArrayList<Pair<ProcessorChoice, TextButton>>()
    private val chunksButtons = ArrayList<Pair<Int, TextButton>>()

    // Grid labels
    private val gridValX = Label("1")
    private val gridValY = Label("1")
    private val resVal = Label("80x80")
    private lateinit var resSlider: Slider

    init {
        addCloseButton()
        closeOnBack()
        buildUi()

        // Frame animation update loop
        update {
            if (isShown && isPlaying && encodedFrames.isNotEmpty()) {
                val delay = 1f / max(1, fps)
                previewTimer += Core.graphics.deltaTime
                if (previewTimer >= delay) {
                    previewTimer = 0f
                    val next = (currentPreviewFrame + 1) % encodedFrames.size
                    showPreviewFrame(next)
                }
            }
        }
    }

    private fun Element.addTip(text: String) {
        addListener(Tooltip(Cons { t: Table ->
            t.background(Tex.pane)
            t.margin(8f)
            val l = Label(text)
            l.setColor(Pal.accent)
            t.add(l)
        }))
    }

    private fun updateSelectableButton(btn: TextButton, isSelected: Boolean) {
        btn.isChecked = isSelected
        if (isSelected) {
            btn.setColor(Pal.accent)
            btn.getLabel().setColor(Pal.accent)
        } else {
            btn.setColor(Color.white)
            btn.getLabel().setColor(Color.lightGray)
        }
    }

    private fun refreshButtonHighlights() {
        for ((type, btn) in displayButtons) {
            updateSelectableButton(btn, type == displayType)
        }
        for ((mode, btn) in aspectButtons) {
            updateSelectableButton(btn, mode == aspectMode)
        }
        for ((proc, btn) in procButtons) {
            updateSelectableButton(btn, proc == processorChoice)
        }
        for ((c, btn) in chunksButtons) {
            updateSelectableButton(btn, c == chunksDim)
        }
    }

    private fun calculateRecommendedResolution(): Int {
        return when (displayType) {
            DisplayType.LOGIC_DISPLAY -> gridScreensX * 80
            DisplayType.LARGE_LOGIC_DISPLAY -> gridScreensX * 176
            DisplayType.TILED_LOGIC_DISPLAY -> kotlin.math.max(1, gridScreensX * 32 - 12)
        }
    }

    private fun buildUi() {
        cont.clear()
        val root = cont.table().grow().get()

        val leftPane = Table()
        val rightPane = Table()

        buildSettingsPane(leftPane)
        buildPreviewPane(rightPane)

        val leftScroll = Table()
        leftScroll.pane(leftPane).grow().scrollX(false)

        root.add(leftScroll).growY().width(650f).pad(8f)
        root.image().color(Color.darkGray).width(4f).growY()
        root.add(rightPane).grow().pad(8f)

        refreshButtonHighlights()
    }

    private fun buildSettingsPane(pane: Table) {
        pane.top().left()

        val header = Label("Configuration & Options")
        header.setFontScale(1.1f)
        header.setColor(Color.sky)
        pane.add(header).left().padBottom(8f).row()

        // --- STEP 1: SOURCE IMAGES ---
        val step1 = Table(Tex.button)
        step1.margin(10f).top().left()
        step1.add("[gold]1. Source Image Sequence / GIF[]").left().padBottom(6f).row()

        val btnSelect = step1.button("Select Start Image / GIF", Icon.fileImage) {
            showImageChooser()
        }.growX().height(42f).padBottom(6f).get()
        btnSelect.addTip("Chọn ảnh bắt đầu trong thư mục hoặc file .gif.\nMod sẽ tự động tìm và sắp xếp tất cả các khung hình tiếp theo theo thứ tự số.")
        step1.row()

        statusLabel.setColor(Color.lightGray)
        step1.add(statusLabel).left().padBottom(4f).row()

        filesInfoLabel.setColor(Color.lightGray)
        step1.add(filesInfoLabel).left().padBottom(6f).row()

        // Max frames slider
        val frameLimitTable = Table()
        frameLimitTable.add(Label("Max Frames: ")).left()
        val frameLimitVal = Label("$maxFramesLimit")
        frameLimitVal.setColor(Pal.accent)
        frameLimitTable.add(frameLimitVal).width(40f)

        val frameLimitSlider = Slider(2f, 120f, 1f, false)
        frameLimitSlider.value = maxFramesLimit.toFloat()
        frameLimitSlider.moved { v ->
            maxFramesLimit = v.toInt()
            frameLimitVal.setText("$maxFramesLimit")
        }
        frameLimitSlider.addTip("Giới hạn số lượng khung hình nạp vào để kiểm soát dung lượng bản vẽ.")
        frameLimitTable.add(frameLimitSlider).growX()
        step1.add(frameLimitTable).growX().padBottom(4f).row()

        pane.add(step1).growX().padBottom(10f).row()

        // --- STEP 2: DISPLAY SETTINGS ---
        val step2 = Table(Tex.button)
        step2.margin(10f).top().left()
        step2.add("[gold]2. Display & Scale[]").left().padBottom(6f).row()

        // 3 Display Type buttons
        val dispTypeTable = Table()
        displayButtons.clear()

        val bLogic = dispTypeTable.button("Logic (80)", Styles.togglet) {
            displayType = DisplayType.LOGIC_DISPLAY
            targetResolution = calculateRecommendedResolution()
            resVal.setText("${targetResolution}x$targetResolution")
            resSlider.value = targetResolution.toFloat()
            refreshButtonHighlights()
            updatePreviewOverlay()
        }.height(36f).padRight(4f).growX().get()
        bLogic.addTip("Màn hình logic 3x3 tiêu chuẩn (80x80 pixel).")
        displayButtons.add(DisplayType.LOGIC_DISPLAY to bLogic)

        val bLarge = dispTypeTable.button("Large (176)", Styles.togglet) {
            displayType = DisplayType.LARGE_LOGIC_DISPLAY
            targetResolution = calculateRecommendedResolution()
            resVal.setText("${targetResolution}x$targetResolution")
            resSlider.value = targetResolution.toFloat()
            refreshButtonHighlights()
            updatePreviewOverlay()
        }.height(36f).padRight(4f).growX().get()
        bLarge.addTip("Màn hình logic lớn 6x6 (176x176 pixel).")
        displayButtons.add(DisplayType.LARGE_LOGIC_DISPLAY to bLarge)

        val bTiled = dispTypeTable.button("Tiled (1x1)", Styles.togglet) {
            displayType = DisplayType.TILED_LOGIC_DISPLAY
            targetResolution = calculateRecommendedResolution()
            resVal.setText("${targetResolution}x$targetResolution")
            resSlider.value = targetResolution.toFloat()
            refreshButtonHighlights()
            updatePreviewOverlay()
        }.height(36f).growX().get()
        bTiled.addTip("Màn hình 1x1 không viền (Tiled Logic Display), tự ghép nối thành màn hình lớn tùy ý.")
        displayButtons.add(DisplayType.TILED_LOGIC_DISPLAY to bTiled)

        step2.add(dispTypeTable).growX().padBottom(6f).row()

        // Grid Screen Count (Width x Height screens)
        val gridTable = Table()
        gridTable.add(Label("Screen Grid: ")).left()
        gridValX.setColor(Pal.accent)
        gridTable.add(gridValX).width(16f)
        gridTable.add(Label("x")).padRight(4f)
        gridValY.setColor(Pal.accent)
        gridTable.add(gridValY).width(24f)

        val sliderGridX = Slider(1f, 8f, 1f, false)
        sliderGridX.value = gridScreensX.toFloat()
        sliderGridX.moved { v ->
            gridScreensX = v.toInt()
            gridValX.setText("$gridScreensX")
            targetResolution = calculateRecommendedResolution()
            resVal.setText("${targetResolution}x$targetResolution")
            resSlider.value = targetResolution.toFloat()
            updatePreviewOverlay()
        }
        sliderGridX.addTip("Số lượng màn hình ghép theo chiều ngang.")
        gridTable.add(sliderGridX).growX().padRight(4f)

        val sliderGridY = Slider(1f, 8f, 1f, false)
        sliderGridY.value = gridScreensY.toFloat()
        sliderGridY.moved { v ->
            gridScreensY = v.toInt()
            gridValY.setText("$gridScreensY")
            targetResolution = calculateRecommendedResolution()
            resVal.setText("${targetResolution}x$targetResolution")
            resSlider.value = targetResolution.toFloat()
            updatePreviewOverlay()
        }
        sliderGridY.addTip("Số lượng màn hình ghép theo chiều dọc.")
        gridTable.add(sliderGridY).growX()
        step2.add(gridTable).growX().padBottom(6f).row()

        // Resolution Slider
        val resTable = Table()
        resTable.add(Label("Pixel Grid: ")).left()
        resVal.setColor(Pal.accent)
        resTable.add(resVal).width(80f)

        resSlider = Slider(20f, 352f, 4f, false)
        resSlider.value = targetResolution.toFloat()
        resSlider.moved { v ->
            targetResolution = v.toInt()
            resVal.setText("${targetResolution}x$targetResolution")
        }
        resSlider.addTip("Độ phân giải pixel thực tế để lượng tử hóa và vẽ lên màn hình.")
        resTable.add(resSlider).growX()
        step2.add(resTable).growX().padBottom(6f).row()

        // Aspect ratio buttons
        val aspectTable = Table()
        aspectTable.add(Label("Aspect: ")).left().padRight(4f)
        aspectButtons.clear()
        for (mode in AspectMode.values()) {
            val bMode = aspectTable.button(mode.name.lowercase(), Styles.togglet) {
                aspectMode = mode
                refreshButtonHighlights()
            }.height(32f).padRight(4f).growX().get()
            bMode.addTip("Chế độ tỉ lệ: ${mode.name.lowercase()} (Fit: vừa vặn, Crop: phóng to cắt rìa, Stretch: kéo giãn).")
            aspectButtons.add(mode to bMode)
        }
        step2.add(aspectTable).growX().padBottom(4f).row()

        pane.add(step2).growX().padBottom(10f).row()

        // --- STEP 3: ANIMATION (FPS) ---
        val step3 = Table(Tex.button)
        step3.margin(10f).top().left()
        step3.add("[gold]3. Animation Settings[]").left().padBottom(6f).row()

        val fpsTable = Table()
        fpsTable.add(Label("Speed: ")).left()
        val fpsVal = Label("$fps FPS")
        fpsVal.setColor(Pal.accent)
        fpsTable.add(fpsVal).width(60f)

        val intervalVal = Label("(${config().frameIntervalTicks} ticks)")
        intervalVal.setColor(Color.gray)
        fpsTable.add(intervalVal).width(70f)

        val fpsSlider = Slider(1f, 60f, 1f, false)
        fpsSlider.value = fps.toFloat()
        fpsSlider.moved { v ->
            fps = v.toInt()
            fpsVal.setText("$fps FPS")
            intervalVal.setText("(${config().frameIntervalTicks} ticks)")
        }
        fpsSlider.addTip("Tốc độ khung hình mỗi giây. Khuyên dùng: 10 - 15 FPS.")
        fpsTable.add(fpsSlider).growX()
        step3.add(fpsTable).growX().padBottom(4f).row()

        val loopCheck = CheckBox("Loop Animation")
        loopCheck.isChecked = loop
        loopCheck.changed { loop = loopCheck.isChecked }
        loopCheck.addTip("Tự động lặp lại hiệu ứng chuyển động khi phát hết khung hình.")
        step3.add(loopCheck).left().padBottom(4f).row()

        pane.add(step3).growX().padBottom(10f).row()

        // --- STEP 4: LOGIC PROCESSORS & PALETTE ---
        val step4 = Table(Tex.button)
        step4.margin(10f).top().left()
        step4.add("[gold]4. Logic & Palette[]").left().padBottom(6f).row()

        // Palette colors slider
        val palTable = Table()
        palTable.add(Label("Colors: ")).left()
        val palVal = Label("$paletteColors")
        palVal.setColor(Pal.accent)
        palTable.add(palVal).width(35f)

        val palSlider = Slider(4f, 32f, 2f, false)
        palSlider.value = paletteColors.toFloat()
        palSlider.moved { v ->
            paletteColors = v.toInt()
            palVal.setText("$paletteColors")
        }
        palSlider.addTip("Số lượng màu trong bảng màu nén. Càng ít màu thì bản vẽ càng ít vi xử lý.")
        palTable.add(palSlider).growX()
        step4.add(palTable).growX().padBottom(6f).row()

        // Processor Choice
        val procTable = Table()
        procTable.add(Label("Processor: ")).left().padBottom(4f).row()
        val procBtnTable = Table()
        procButtons.clear()
        for (choice in ProcessorChoice.values()) {
            val shortName = choice.name.replace("_PROCESSOR", "").lowercase()
            val bProc = procBtnTable.button(shortName, Styles.togglet) {
                processorChoice = choice
                refreshButtonHighlights()
            }.height(32f).padRight(4f).growX().get()
            bProc.addTip("${choice.title}. Cung cấp ${choice.ipt} lệnh/tick.")
            procButtons.add(choice to bProc)
        }
        procTable.add(procBtnTable).growX()
        step4.add(procTable).growX().padBottom(6f).row()

        // Render Chunks (Spatial Partitioning)
        val chunkTable = Table()
        chunkTable.add(Label("Render Chunks: ")).left().padBottom(4f).row()
        val chunkBtnTable = Table()
        chunksButtons.clear()
        for (c in intArrayOf(1, 2, 3, 4)) {
            val title = "${c}x$c"
            val bChunk = chunkBtnTable.button(title, Styles.togglet) {
                chunksDim = c
                refreshButtonHighlights()
                updatePreviewOverlay()
            }.height(32f).padRight(4f).growX().get()
            val desc = if (c == 1) {
                "1x1: Không chia chunk (toàn màn hình trên 1 luồng xử lý)."
            } else {
                "${c}x$c: Chia màn hình thành ${c * c} vùng không gian xử lý song song, tăng mạnh FPS và giảm tải lệnh cho mỗi vi xử lý."
            }
            bChunk.addTip(desc)
            chunksButtons.add(c to bChunk)
        }
        chunkTable.add(chunkBtnTable).growX()
        step4.add(chunkTable).growX().padBottom(4f).row()

        pane.add(step4).growX().padBottom(10f).row()

        // --- STEP 5: ACTIONS & STATS ---
        val actionTable = Table(Tex.button)
        actionTable.margin(10f).top().left()

        val btnConvert = actionTable.button("Convert & Encode", Icon.refresh) {
            triggerEncoding()
        }.growX().height(46f).padBottom(8f).get()
        btnConvert.addTip("Lượng tử hóa bảng màu và mã hóa toàn bộ chuỗi ảnh thành các dải RLE Scanline.")
        actionTable.row()

        statsLabel.setColor(Color.lightGray)
        actionTable.add(statsLabel).left().padBottom(8f).row()

        val btnExport = actionTable.button("Export Schematic", Icon.export) {
            exportSchematic()
        }.growX().height(50f).disabled { encodedFrames.isEmpty() || isEncoding.get() }.get()
        btnExport.addTip("Tạo bản thiết kế hoàn chỉnh gồm màn hình, nút Switch reload và vi xử lý nối chuỗi bao quanh.")

        pane.add(actionTable).growX().padBottom(20f).row()
    }

    private fun buildPreviewPane(pane: Table) {
        pane.top()
        val previewTitle = Label("Live Animation Preview")
        previewTitle.setFontScale(1.1f)
        previewTitle.setColor(Color.sky)
        pane.add(previewTitle).left().padBottom(8f).row()

        // Canvas container
        val canvasContainer = Table(Tex.pane)
        canvasContainer.margin(10f)

        previewPixmap = Pixmap(160, 160)
        previewPixmap!!.fill(0x000000FF)
        drawDisplayGridOverlay(previewPixmap!!, gridScreensX, gridScreensY, displayType.isTileable)

        previewTexture = Texture(previewPixmap)
        previewImage = Image(TextureRegion(previewTexture))

        canvasContainer.add(previewImage).size(360f, 360f).center()
        pane.add(canvasContainer).grow().center().padBottom(8f).row()

        // Preview playback controls
        val controlsTable = Table(Tex.button)
        controlsTable.margin(8f)

        val bPlay = controlsTable.button(Icon.play) {
            isPlaying = !isPlaying
        }.size(44f).padRight(6f).get()
        bPlay.addTip("Phát / Tạm dừng xem trước ảnh động.")

        val bPrev = controlsTable.button(Icon.left) {
            isPlaying = false
            stepPreviewFrame(-1)
        }.size(44f).padRight(6f).get()
        bPrev.addTip("Lùi lại 1 khung hình.")

        val bNext = controlsTable.button(Icon.right) {
            isPlaying = false
            stepPreviewFrame(1)
        }.size(44f).padRight(10f).get()
        bNext.addTip("Tiến lên 1 khung hình.")

        frameSlider.moved { v ->
            if (!isPlaying && encodedFrames.isNotEmpty()) {
                showPreviewFrame(v.toInt())
            }
        }
        frameSlider.addTip("Thanh tua khung hình xem trước.")
        controlsTable.add(frameSlider).growX().padRight(10f)

        previewFrameLabel.setColor(Pal.accent)
        controlsTable.add(previewFrameLabel).width(110f)
        pane.add(controlsTable).growX().padBottom(8f).row()

        // Palette swatch bar
        val swatchContainer = Table()
        swatchContainer.add(Label("Active Palette Colors:")).color(Color.gray).left().row()
        paletteTable.margin(6f).left()
        swatchContainer.add(paletteTable).growX()
        pane.add(swatchContainer).growX().padBottom(4f).row()
    }

    private fun config(): DisplayConfig {
        return DisplayConfig(
            displayType = displayType,
            gridX = gridScreensX,
            gridY = gridScreensY,
            pixelWidth = targetResolution,
            pixelHeight = targetResolution,
            fps = fps,
            loop = loop,
            paletteSize = paletteColors,
            processorChoice = processorChoice,
            chunksX = chunksDim,
            chunksY = chunksDim
        )
    }

    private fun showImageChooser() {
        val extensions = arrayOf("png", "jpg", "jpeg", "bmp", "gif")
        FileChooser.open(*extensions).submit { fi ->
            selectedFile = fi
            onFileSelected(fi)
        }
    }

    private fun onFileSelected(file: Fi) {
        statusLabel.setText("Selected: [gold]${file.name()}[]")
        discoveredFiles = FrameSequence.discoverSequenceFiles(file)
        if (file.extension().equals("gif", ignoreCase = true)) {
            filesInfoLabel.setText("Type: Animated GIF file")
        } else {
            filesInfoLabel.setText("Found [accent]${discoveredFiles.size}[] sequential frames in folder")
        }

        triggerEncoding()
    }

    private fun triggerEncoding() {
        val file = selectedFile
        if (file == null) {
            Vars.ui.showInfo("Please select a starting image or GIF first!")
            return
        }

        if (isEncoding.getAndSet(true)) return

        statsLabel.setText("[yellow]Loading and quantizing frames...[]")

        thread(isDaemon = true, name = "PicToLogic-Encoder") {
            try {
                val seq = FrameSequence.load(
                    startFile = file,
                    targetW = targetResolution,
                    targetH = targetResolution,
                    aspectMode = aspectMode,
                    maxFrames = maxFramesLimit
                )

                if (seq.frames.isEmpty()) {
                    Core.app.post {
                        statsLabel.setText("[scarlet]Failed to load any frames![]")
                        isEncoding.set(false)
                    }
                    return@thread
                }

                Core.app.post { statsLabel.setText("[yellow]Generating optimal ${paletteColors}-color palette...[]") }
                val palette = Palette.fromPixmaps(seq.frames, paletteColors)

                Core.app.post { statsLabel.setText("[yellow]RLE encoding ${seq.frames.size} frames...[]") }
                val encoder = RleEncoder(targetResolution, targetResolution, palette)
                val anim = encoder.encodeAnimation(seq.frames)

                Core.app.post {
                    rawFrames = seq.frames
                    encodedAnimation = anim
                    encodedFrames = anim.loopFrames
                    activePalette = palette

                    if (previewPixmap == null || previewPixmap!!.width != targetResolution || previewPixmap!!.height != targetResolution) {
                        previewPixmap?.dispose()
                        previewTexture?.dispose()
                        previewPixmap = Pixmap(targetResolution, targetResolution)
                        previewTexture = Texture(previewPixmap)
                        previewImage?.setDrawable(TextureRegionDrawable(TextureRegion(previewTexture)))
                    }

                    val totalDisplayFrames = if (anim.totalFrames > 0) anim.totalFrames else 1
                    frameSlider.setRange(0f, (totalDisplayFrames - 1).toFloat())
                    frameSlider.value = 0f
                    showPreviewFrame(0)

                    val totalRuns = anim.totalRuns
                    val avgRuns = if (anim.totalFrames > 0) anim.loopFrames.sumOf { it.totalRuns } / anim.totalFrames else 0
                    val planner = SchematicGenerator(config(), anim)
                    val (workers, totalInitSlices) = planner.planWorkers()

                    statsLabel.setText(
                        "[green]Success![] Frames: [accent]${anim.totalFrames}[] | Loop Delta Runs: [accent]${anim.loopFrames.sumOf { it.totalRuns }}[] (avg [accent]$avgRuns[]/frame)\n" +
                        "Chunks: [accent]${chunksDim}x${chunksDim}[] | Processors: [accent]${workers.size}[] workers + 1 controller + 1 switch + 1 cell (Init: [accent]$totalInitSlices[] slices)"
                    )

                    updatePaletteSwatches(palette)
                    isEncoding.set(false)
                }
            } catch (e: Exception) {
                Log.err("[PicToLogic2] Encoding failed", e)
                Core.app.post {
                    statsLabel.setText("[scarlet]Error: ${e.message}[]")
                    isEncoding.set(false)
                }
            }
        }
    }

    private fun updatePaletteSwatches(palette: Palette) {
        paletteTable.clear()
        for (i in 0 until palette.size) {
            val c = palette.getColor(i)
            val r = ((c ushr 24) and 0xFF) / 255f
            val g = ((c ushr 16) and 0xFF) / 255f
            val b = ((c ushr 8) and 0xFF) / 255f
            val swatch = Table()
            swatch.background = Tex.whiteui
            swatch.color.set(r, g, b, 1f)
            paletteTable.add(swatch).size(16f, 16f).pad(2f)
        }
    }

    private fun updatePreviewOverlay() {
        if (encodedFrames.isNotEmpty()) {
            showPreviewFrame(currentPreviewFrame)
        } else if (previewPixmap != null) {
            previewPixmap!!.fill(0x000000FF)
            drawDisplayGridOverlay(previewPixmap!!, gridScreensX, gridScreensY, displayType.isTileable)
            previewTexture?.load(previewTexture!!.textureData)
        }
    }

    private fun drawDisplayGridOverlay(pixmap: Pixmap, gridX: Int, gridY: Int, isTiled: Boolean) {
        val w = pixmap.width
        val h = pixmap.height
        val borderColor = 0x2C2C36FF // Metallic bezel border
        val gridColor = if (isTiled) 0x3A3A46FF else 0x181820FF

        // Outer border
        for (i in 0 until 2) {
            for (x in 0 until w) {
                pixmap.set(x, i, borderColor)
                pixmap.set(x, h - 1 - i, borderColor)
            }
            for (y in 0 until h) {
                pixmap.set(i, y, borderColor)
                pixmap.set(w - 1 - i, y, borderColor)
            }
        }

        // Inner display seams
        if (gridX > 1) {
            val stepX = w.toFloat() / gridX
            for (gx in 1 until gridX) {
                val lineX = (gx * stepX).toInt()
                for (y in 0 until h) {
                    pixmap.set(lineX, y, gridColor)
                    if (!isTiled && lineX + 1 < w) pixmap.set(lineX + 1, y, gridColor)
                }
            }
        }
        if (gridY > 1) {
            val stepY = h.toFloat() / gridY
            for (gy in 1 until gridY) {
                val lineY = (gy * stepY).toInt()
                for (x in 0 until w) {
                    pixmap.set(x, lineY, gridColor)
                    if (!isTiled && lineY + 1 < h) pixmap.set(x, lineY + 1, gridColor)
                }
            }
        }

        // Render chunk boundaries (dotted cyan lines for multi-chunk layouts)
        if (chunksDim > 1) {
            val chunkColor = 0x00E5FFFF // Vibrant cyan
            val stepX = w.toFloat() / chunksDim
            for (cx in 1 until chunksDim) {
                val lineX = (cx * stepX).toInt()
                for (y in 0 until h step 4) {
                    pixmap.set(lineX, y, chunkColor)
                    if (y + 1 < h) pixmap.set(lineX, y + 1, chunkColor)
                }
            }
            val stepY = h.toFloat() / chunksDim
            for (cy in 1 until chunksDim) {
                val lineY = (cy * stepY).toInt()
                for (x in 0 until w step 4) {
                    pixmap.set(x, lineY, chunkColor)
                    if (x + 1 < w) pixmap.set(x + 1, lineY, chunkColor)
                }
            }
        }
    }

    private fun showPreviewFrame(index: Int) {
        if (encodedFrames.isEmpty() || previewPixmap == null) return
        val safeIdx = index.coerceIn(0, encodedFrames.size - 1)
        currentPreviewFrame = safeIdx
        frameSlider.value = safeIdx.toFloat()
        previewFrameLabel.setText("Frame: ${safeIdx + 1}/${encodedFrames.size}")

        if (safeIdx in rawFrames.indices) {
            val framePixmap = rawFrames[safeIdx]
            previewPixmap!!.draw(framePixmap, 0, 0, framePixmap.width, framePixmap.height, 0, 0, previewPixmap!!.width, previewPixmap!!.height, false)
            drawDisplayGridOverlay(previewPixmap!!, gridScreensX, gridScreensY, displayType.isTileable)
            previewTexture?.load(previewTexture!!.textureData)
        }
    }

    private fun stepPreviewFrame(delta: Int) {
        if (encodedFrames.isEmpty()) return
        var next = currentPreviewFrame + delta
        if (next < 0) next = encodedFrames.size - 1
        if (next >= encodedFrames.size) next = 0
        showPreviewFrame(next)
    }

    private fun exportSchematic() {
        val anim = encodedAnimation ?: return
        try {
            val name = selectedFile?.nameWithoutExtension() ?: "PicToLogic Animation"
            val generator = SchematicGenerator(config(), anim)
            val schem = generator.buildSchematic(name)

            Vars.schematics.add(schem)
            Vars.ui.showInfoFade("Schematic [accent]\"$name\"[] added to your Schematics!")
            Vars.ui.schematics.show()
        } catch (e: Exception) {
            Log.err("[PicToLogic2] Schematic export failed", e)
            Vars.ui.showErrorMessage("Failed to export schematic: ${e.message}")
        }
    }
}
