package pictologic.staticimage

import arc.Core
import arc.files.Fi
import arc.func.Cons
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.graphics.Texture
import arc.graphics.g2d.TextureRegion
import arc.scene.Element
import arc.scene.style.TextureRegionDrawable
import arc.scene.ui.Image
import arc.scene.ui.Label
import arc.scene.ui.Slider
import arc.scene.ui.TextButton
import arc.scene.ui.TextField
import arc.scene.ui.Tooltip
import arc.scene.ui.layout.Table
import arc.util.Log
import arc.util.Scaling
import mindustry.Vars
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.FileChooser
import mindustry.ui.Styles
import mindustry.ui.dialogs.BaseDialog
import pictologic.core.AspectMode
import pictologic.core.Palette
import pictologic.layout.DisplayType
import pictologic.layout.ProcessorChoice
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class StaticImageDialog : BaseDialog("PicToLogic - Static Image") {

    private var selectedFile: Fi? = null
    private var cachedSourcePixmap: Pixmap? = null
    private var encodedImage: EncodedStaticImage? = null
    private var activePalette: Palette? = null
    // True when quantized preview is active; false when user tweaks grid sliders for instant live preview
    private var isQuantizedPreviewValid = false

    private var displayType = DisplayType.TILED_LOGIC_DISPLAY
    private var gridScreensX = 10
    private var gridScreensY = 10
    private var processorChoice = ProcessorChoice.MICRO_PROCESSOR
    private var aspectMode = AspectMode.FIT
    private var paletteColors = 16
    private var watermarkText = ""
    private var watermarkColorRgba = -0x1
    private val isEncoding = AtomicBoolean(false)

    // Fixed high-res square viewport for distortion-free rectangular grid preview
    private val viewportSize = 480
    private var previewPixmap: Pixmap? = null
    private var previewTexture: Texture? = null
    private var previewImage: Image? = null

    private val statusLabel = Label("No image selected")
    private val fileDetailsLabel = Label("")
    private val statsLabel = Label("Select a static image to start")
    private val modeInfoLabel = Label("")
    private val coverageInfoLabel = Label("")
    private val paletteTable = Table(Tex.pane)

    private val displayButtons = ArrayList<Pair<DisplayType, TextButton>>()
    private val aspectButtons = ArrayList<Pair<AspectMode, TextButton>>()
    private val procButtons = ArrayList<Pair<ProcessorChoice, TextButton>>()
    private val wmColorButtons = ArrayList<Pair<Int, TextButton>>()

    private val gridValX = Label("10")
    private val gridValY = Label("10")
    private val resVal = Label("308x308 px")
    private lateinit var sliderGridX: Slider
    private lateinit var sliderGridY: Slider

    companion object {
        private val WATERMARK_COLORS = listOf(
            "White" to (0xFFFFFFFF.toInt()),
            "Gold" to (0xFFD700FF.toInt()),
            "Cyan" to (0x00E5FFFF.toInt()),
            "Red" to (0xFF3B30FF.toInt()),
            "Green" to (0x34C759FF.toInt()),
            "Pink" to (0xFF69B4FF.toInt()),
            "Orange" to (0xFF9500FF.toInt()),
            "Purple" to (0xAF52DEFF.toInt()),
            "Black" to (0x101014FF.toInt())
        )
    }

    init {
        addCloseButton()
        closeOnBack()
        buildUi()
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

    private fun updateSelectableButton(btn: TextButton, isSelected: Boolean, isDisabled: Boolean = false) {
        btn.isChecked = isSelected
        btn.isDisabled = isDisabled
        if (isDisabled) {
            btn.setColor(Color.darkGray)
            btn.getLabel().setColor(Color.gray)
        } else if (isSelected) {
            btn.setColor(Pal.accent)
            btn.getLabel().setColor(Pal.accent)
        } else {
            btn.setColor(Color.white)
            btn.getLabel().setColor(Color.lightGray)
        }
    }

    private fun applyDisplayTypeConstraints() {
        val maxGrid = StaticImageConfig.maxGridForType(displayType)
        gridScreensX = min(gridScreensX, maxGrid)
        gridScreensY = min(gridScreensY, maxGrid)

        sliderGridX.setRange(1f, maxGrid.toFloat())
        sliderGridX.value = gridScreensX.toFloat()
        sliderGridY.setRange(1f, maxGrid.toFloat())
        sliderGridY.value = gridScreensY.toFloat()

        gridValX.setText("$gridScreensX")
        gridValY.setText("$gridScreensY")

        if (displayType != DisplayType.TILED_LOGIC_DISPLAY) {
            processorChoice = ProcessorChoice.LOGIC_PROCESSOR
        }
        
		isQuantizedPreviewValid = false
        updateResolutionAndLivePreview()
        refreshButtonHighlights()
    }

    private fun updateResolutionAndLivePreview() {
        val cfg = config()
        val cw = cfg.effectiveDisplayWidth
        val ch = cfg.effectiveDisplayHeight
        resVal.setText("${cw}x${ch} px")

        val src = cachedSourcePixmap
        if (src != null) {
            val (dw, dh) = StaticImageEncoder.calculateDrawnDimensions(src.width, src.height, cw, ch, aspectMode)
            val pctW = (dw * 100f / cw).roundToInt()
            val pctH = (dh * 100f / ch).roundToInt()
        }

        renderDistortionFreePreview()
    }

    private fun refreshButtonHighlights() {
        for ((type, btn) in displayButtons) {
            updateSelectableButton(btn, type == displayType)
        }
        for ((mode, btn) in aspectButtons) {
            updateSelectableButton(btn, mode == aspectMode)
        }
        val lockTo2x2 = displayType != DisplayType.TILED_LOGIC_DISPLAY
        for ((proc, btn) in procButtons) {
            val disabled = lockTo2x2 && proc != ProcessorChoice.LOGIC_PROCESSOR
            updateSelectableButton(btn, proc == processorChoice, disabled)
        }
        for ((rgba, btn) in wmColorButtons) {
            btn.isChecked = (rgba == watermarkColorRgba)
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

        root.add(leftScroll).growY().width(750f).pad(8f)
        root.image().color(Color.darkGray).width(4f).growY()
        root.add(rightPane).grow().pad(8f)

        applyDisplayTypeConstraints()
    }

    private fun buildSettingsPane(pane: Table) {
        pane.top().left()

        val header = Label("Static Image Configuration")
        header.setFontScale(1.1f)
        header.setColor(Color.sky)
        pane.add(header).left().padBottom(8f).row()

        // --- STEP 1: SOURCE IMAGE ---
        val step1 = Table(Tex.button)
        step1.margin(10f).top().left()
        step1.add("[gold]1. Source Image (.png, .jpg, .bmp)[]").left().padBottom(6f).row()

        val btnSelect = step1.button("Select Static Image", Icon.fileImage) {
            showImageChooser()
        }.growX().height(42f).padBottom(6f).get()
        btnSelect.addTip("Chọn file ảnh tĩnh (PNG, JPG, BMP) để chuyển đổi.")
        step1.row()

        statusLabel.setColor(Color.lightGray)
        step1.add(statusLabel).left().padBottom(4f).row()

        fileDetailsLabel.setColor(Color.lightGray)
        step1.add(fileDetailsLabel).left().padBottom(4f).row()

        pane.add(step1).growX().padBottom(10f).row()

        // --- STEP 2: DISPLAY & GRID SETUP ---
        val step2 = Table(Tex.button)
        step2.margin(10f).top().left()
        step2.add("[gold]2. Display Type & Screen Grid[]").left().padBottom(6f).row()

        val dispTypeTable = Table()
        displayButtons.clear()

        val bTiled = dispTypeTable.button("Tiled 1x1", Styles.togglet) {
            displayType = DisplayType.TILED_LOGIC_DISPLAY
            applyDisplayTypeConstraints()
        }.height(36f).padRight(4f).growX().get()
        bTiled.addTip("Màn hình 1x1 ghép tối đa 16x16. Kết nối theo chuỗi (Daisy-Chain).")
        displayButtons.add(DisplayType.TILED_LOGIC_DISPLAY to bTiled)

        val bLogic = dispTypeTable.button("Logic 3x3", Styles.togglet) {
            displayType = DisplayType.LOGIC_DISPLAY
            applyDisplayTypeConstraints()
        }.height(36f).padRight(4f).growX().get()
        bLogic.addTip("Màn hình 3x3 (80x80px) ghép tối đa 5x5. Mỗi màn hình là 1 chunk riêng biệt.")
        displayButtons.add(DisplayType.LOGIC_DISPLAY to bLogic)

        val bLarge = dispTypeTable.button("Large 6x6", Styles.togglet) {
            displayType = DisplayType.LARGE_LOGIC_DISPLAY
            applyDisplayTypeConstraints()
        }.height(36f).growX().get()
        bLarge.addTip("Màn hình 6x6 (176x176px) ghép tối đa 4x4. Mỗi màn hình là 1 chunk riêng biệt.")
        displayButtons.add(DisplayType.LARGE_LOGIC_DISPLAY to bLarge)

        step2.add(dispTypeTable).growX().padBottom(6f).row()

        val gridTable = Table()
        gridTable.add(Label("Screen Grid (W x H): ")).left()
        gridValX.setColor(Pal.accent)
        gridTable.add(gridValX).width(22f)
        gridTable.add(Label("x")).padRight(4f)
        gridValY.setColor(Pal.accent)
        gridTable.add(gridValY).width(26f)

        sliderGridX = Slider(1f, 16f, 1f, false)
        sliderGridX.value = gridScreensX.toFloat()
        sliderGridX.moved { v ->
            val newX = v.toInt()
            if (newX != gridScreensX) {
                gridScreensX = newX
                gridValX.setText("$gridScreensX")
                isQuantizedPreviewValid = false
                updateResolutionAndLivePreview()
            }
        }
        gridTable.add(sliderGridX).growX().padRight(4f)

        sliderGridY = Slider(1f, 16f, 1f, false)
        sliderGridY.value = gridScreensY.toFloat()
        sliderGridY.moved { v ->
            val newY = v.toInt()
            if (newY != gridScreensY) {
                gridScreensY = newY
                gridValY.setText("$gridScreensY")
                isQuantizedPreviewValid = false
                updateResolutionAndLivePreview()
            }
        }
        gridTable.add(sliderGridY).growX()
        step2.add(gridTable).growX().padBottom(6f).row()

        val resTable = Table()
        resTable.add(Label("Grid Canvas: ")).left()
        resVal.setColor(Pal.accent)
        resTable.add(resVal).left().padRight(12f)
		
        step2.add(resTable).growX().padBottom(4f).row()

        coverageInfoLabel.setWrap(true)
        step2.add(coverageInfoLabel).growX().padBottom(6f).row()

        val aspectTable = Table()
        aspectTable.add(Label("Aspect: ")).left().padRight(4f)
        aspectButtons.clear()
        for (mode in AspectMode.values()) {
            val bMode = aspectTable.button(mode.name.lowercase(), Styles.togglet) {
                aspectMode = mode
                isQuantizedPreviewValid = false
                refreshButtonHighlights()
                updateResolutionAndLivePreview()
            }.height(32f).padRight(4f).growX().get()
            aspectButtons.add(mode to bMode)
        }
        step2.add(aspectTable).growX().padBottom(4f).row()

        pane.add(step2).growX().padBottom(10f).row()

        // --- STEP 3: PALETTE & PROCESSOR ---
        val step3 = Table(Tex.button)
        step3.margin(10f).top().left()
        step3.add("[gold]3. Palette & Architecture[]").left().padBottom(6f).row()

        val palTable = Table()
        palTable.add(Label("Palette Colors: ")).left()
        val palVal = Label("$paletteColors")
        palVal.setColor(Pal.accent)
        palTable.add(palVal).width(35f)

        val palSlider = Slider(4f, 48f, 2f, false)
        palSlider.value = paletteColors.toFloat()
        palSlider.moved { v ->
            paletteColors = v.toInt()
            palVal.setText("$paletteColors")
        }
        palTable.add(palSlider).growX()
        step3.add(palTable).growX().padBottom(6f).row()

        val procTable = Table()
        procTable.add(Label("Processor: ")).left().padBottom(4f).row()
        val procBtnTable = Table()
        procButtons.clear()
        for (choice in ProcessorChoice.values()) {
            val shortName = choice.name.replace("_PROCESSOR", "").lowercase()
            val bProc = procBtnTable.button(shortName, Styles.togglet) {
                if (displayType == DisplayType.TILED_LOGIC_DISPLAY) {
                    processorChoice = choice
                    refreshButtonHighlights()
                }
            }.height(32f).padRight(4f).growX().get()
            procButtons.add(choice to bProc)
        }
        procTable.add(procBtnTable).growX()
        step3.add(procTable).growX().padBottom(6f).row()

        modeInfoLabel.setWrap(true)
        step3.add(modeInfoLabel).growX().padBottom(4f).row()

        pane.add(step3).growX().padBottom(10f).row()

        // --- STEP 4: WATERMARK (TOP-RIGHT) ---
        val step4 = Table(Tex.button)
        step4.margin(10f).top().left()
        step4.add("[gold]4. Watermark (Góc trên bên phải - Tối đa 15 ký tự)[]").left().padBottom(6f).row()

        val wmInputTable = Table()
        wmInputTable.add(Label("Text: ")).left().padRight(6f)
        val wmField = TextField(watermarkText)
        wmField.maxLength = 15
        wmField.messageText = "Nhập chữ (tối đa 15 ký tự)..."
        wmField.changed {
            watermarkText = wmField.text.take(15)
        }
        wmInputTable.add(wmField).growX().height(34f)
        step4.add(wmInputTable).growX().padBottom(8f).row()

        val wmColorTable = Table()
        wmColorTable.add(Label("Color: ")).left().padRight(6f)
        wmColorButtons.clear()
        for ((cName, rgba) in WATERMARK_COLORS) {
            val r = ((rgba ushr 24) and 0xFF) / 255f
            val g = ((rgba ushr 16) and 0xFF) / 255f
            val b = ((rgba ushr 8) and 0xFF) / 255f

            // Clone style so we NEVER mutate Mindustry's global Styles.togglet!
            val customStyle = TextButton.TextButtonStyle(Styles.togglet)
            customStyle.up = Tex.whiteui

            val bColor = wmColorTable.button("", customStyle) {
                watermarkColorRgba = rgba
                refreshButtonHighlights()
                if (selectedFile != null) triggerEncoding()
            }.size(30f, 30f).padRight(4f).get()
            bColor.color.set(r, g, b, 1f)
            bColor.addTip("Màu Watermark: $cName")
            wmColorButtons.add(rgba to bColor)
        }
        step4.add(wmColorTable).left().padBottom(4f).row()

        pane.add(step4).growX().padBottom(10f).row()

        // --- STEP 5: ACTIONS & STATS ---
        val actionTable = Table(Tex.button)
        actionTable.margin(10f).top().left()

        actionTable.button("Convert & Encode", Icon.refresh) {
            triggerEncoding()
        }.growX().height(46f).padBottom(8f).row()

        statsLabel.setColor(Color.lightGray)
        actionTable.add(statsLabel).left().padBottom(8f).row()

        actionTable.button("Export Schematic", Icon.export) {
            exportSchematic()
        }.growX().height(50f).disabled { encodedImage == null || isEncoding.get() }

        pane.add(actionTable).growX().padBottom(20f).row()
    }

    private fun buildPreviewPane(pane: Table) {
        pane.top()
        val previewTitle = Label("Live Grid & Alignment Preview")
        previewTitle.setFontScale(1.1f)
        previewTitle.setColor(Color.sky)
        pane.add(previewTitle).left().padBottom(8f).row()

        val canvasContainer = Table(Tex.pane)
        canvasContainer.margin(10f)

        previewPixmap = Pixmap(viewportSize, viewportSize)
        previewTexture = Texture(previewPixmap)
        previewImage = Image(TextureRegion(previewTexture))
        previewImage!!.setScaling(Scaling.fit)

        canvasContainer.add(previewImage).size(420f, 420f).center()
        pane.add(canvasContainer).grow().center().padBottom(8f).row()

        val swatchContainer = Table()
        swatchContainer.add(Label("Active Palette Colors:")).color(Color.gray).left().row()
        paletteTable.margin(6f).left()
        swatchContainer.add(paletteTable).growX()
        pane.add(swatchContainer).growX().padBottom(4f).row()
    }

    /**
     * Automatically sets gridScreensX and gridScreensY to best match the loaded image's aspect ratio
     * within the current displayType's maxGridLimit.
     */
    private fun autoFitGridToImageAspect() {
        val src = cachedSourcePixmap ?: return
        val maxGrid = StaticImageConfig.maxGridForType(displayType)
        val imgAspect = src.width.toFloat() / max(1, src.height)

        if (imgAspect >= 1f) {
            gridScreensX = maxGrid
            gridScreensY = (maxGrid / imgAspect).roundToInt().coerceIn(1, maxGrid)
        } else {
            gridScreensY = maxGrid
            gridScreensX = (maxGrid * imgAspect).roundToInt().coerceIn(1, maxGrid)
        }

        sliderGridX.value = gridScreensX.toFloat()
        sliderGridY.value = gridScreensY.toFloat()
        gridValX.setText("$gridScreensX")
        gridValY.setText("$gridScreensY")
        isQuantizedPreviewValid = false
        updateResolutionAndLivePreview()
    }

    private fun config(): StaticImageConfig {
        return StaticImageConfig(
            displayType = displayType,
            gridX = gridScreensX,
            gridY = gridScreensY,
            aspectMode = aspectMode,
            paletteSize = paletteColors,
            processorChoice = processorChoice,
            watermarkText = watermarkText,
            watermarkColorRgba = watermarkColorRgba
        )
    }

    private fun showImageChooser() {
        val extensions = arrayOf("png", "jpg", "jpeg", "bmp")
        FileChooser.open(*extensions).submit { fi ->
            selectedFile = fi
            onFileSelected(fi)
        }
    }

    private fun onFileSelected(file: Fi) {
        statusLabel.setText("Selected: [gold]${file.name()}[]")
        try {
            cachedSourcePixmap?.dispose()
            val pm = Pixmap(file)
            cachedSourcePixmap = pm
            fileDetailsLabel.setText("Original Size: [accent]${pm.width}x${pm.height}[] px | ${file.length() / 1024} KB")
        } catch (_: Exception) {
            fileDetailsLabel.setText("Size: ${file.length() / 1024} KB")
        }
        isQuantizedPreviewValid = false
        updateResolutionAndLivePreview()
        triggerEncoding()
    }

    private fun triggerEncoding() {
        val file = selectedFile
        if (file == null) {
            Vars.ui.showInfo("Please select a static image file first!")
            return
        }
        if (isEncoding.getAndSet(true)) return

        statsLabel.setText("[yellow]Loading and encoding image...[]")

        thread(isDaemon = true, name = "StaticImage-Encoder") {
            try {
                val cfg = config()
                val encoder = StaticImageEncoder(cfg)
                val result = encoder.encode(file)

                Core.app.post {
                    encodedImage?.quantizedPixmap?.dispose()
                    encodedImage = result
                    activePalette = result.palette
                    isQuantizedPreviewValid = true

                    updateResolutionAndLivePreview()

                    val archDesc = if (cfg.isMultiScreenChunked) {
                        "${cfg.clampedGridX * cfg.clampedGridY} Screen Chunks (2x2 Logic)"
                    } else {
                        "Daisy-Chained (${cfg.effectiveProcessor.name.replace("_PROCESSOR", "")})"
                    }

                    statsLabel.setText(
                        "[green]Success![] 2D RLE Runs: [accent]${result.totalRuns}[] | Draw Cmds: [accent]${result.totalDrawInstructions}[]\n" +
                        "Drawn Area: [accent]${result.drawnWidth}x${result.drawnHeight}px[] / ${result.canvasWidth}x${result.canvasHeight}px | Workers: [accent]${result.totalWorkers}[] ($archDesc)"
                    )

                    updatePaletteSwatches(result.palette)
                    isEncoding.set(false)
                }
            } catch (e: Exception) {
                Log.err("[StaticImage] Encoding failed", e)
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

    /**
     * Renders the true rectangular screen grid (e.g. 16x10) inside the square [previewPixmap] viewport
     * without ANY aspect ratio distortion, aligning the image to the bottom-left corner of the grid!
     */
    private fun renderDistortionFreePreview() {
        val vp = previewPixmap ?: return
        val cfg = config()
        val gx = cfg.clampedGridX
        val gy = cfg.clampedGridY

        // 1. Fill outside viewport with dark UI backdrop
        vp.fill(0x14141AFF)

        // 2. Compute true rectangular screen grid box inside viewport so every screen cell is a PERFECT SQUARE!
        val margin = 12
        val avail = viewportSize - margin * 2
        val maxScreensDim = max(gx, gy)
        val cellPx = max(4, avail / maxScreensDim)
        val gridBoxW = gx * cellPx
        val gridBoxH = gy * cellPx

        val gridOriginX = (viewportSize - gridBoxW) / 2
        val gridOriginY = (viewportSize - gridBoxH) / 2

        // 3. Fill the screen grid area with Mindustry's unpainted display canvas gray (0x565666FF)
        val unpaintedDisplayColor = 0x565666FF
        for (y in gridOriginY until (gridOriginY + gridBoxH)) {
            for (x in gridOriginX until (gridOriginX + gridBoxW)) {
                vp.set(x, y, unpaintedDisplayColor)
            }
        }

        // 4. Draw the image (either encoded quantizedPixmap or instant live cachedSourcePixmap) aligned to BOTTOM-LEFT!
        val enc = encodedImage
        val src = cachedSourcePixmap
        if (isQuantizedPreviewValid && enc != null &&
            enc.canvasWidth == cfg.effectiveDisplayWidth &&
            enc.canvasHeight == cfg.effectiveDisplayHeight
        ) {
            // quantizedPixmap already has the image aligned bottom-left and unpainted gray in empty areas
            vp.draw(
                enc.quantizedPixmap,
                0, 0, enc.quantizedPixmap.width, enc.quantizedPixmap.height,
                gridOriginX, gridOriginY, gridBoxW, gridBoxH,
                false
            )
        } else if (src != null) {
            // Instant Live Preview: calculate bottom-left drawn region inside gridBoxW x gridBoxH
            val (drawBoxW, drawBoxH) = StaticImageEncoder.calculateDrawnDimensions(
                srcW = src.width,
                srcH = src.height,
                canvasW = gridBoxW,
                canvasH = gridBoxH,
                aspectMode = aspectMode
            )
            // In Pixmap top-left coordinates, bottom-left of the grid box is:
            // X = gridOriginX, Y = gridOriginY + (gridBoxH - drawBoxH)
            val dstX = gridOriginX
            val dstY = gridOriginY + (gridBoxH - drawBoxH)
            vp.draw(src, 0, 0, src.width, src.height, dstX, dstY, drawBoxW, drawBoxH, false)
        }

        // 5. Draw square screen grid lines and outer bezel over the rectangular grid box
        drawRectangularGridOverlay(vp, gridOriginX, gridOriginY, gridBoxW, gridBoxH, gx, gy, displayType.isTileable)

        previewTexture?.load(previewTexture!!.textureData)
    }

    private fun drawRectangularGridOverlay(
        pixmap: Pixmap,
        startX: Int,
        startY: Int,
        boxW: Int,
        boxH: Int,
        gridX: Int,
        gridY: Int,
        isTiled: Boolean
    ) {
        val borderColor = 0x8E8EA0FF.toInt()
        val gridColor = if (isTiled) 0x282834FF else 0x00E5FFFF

        // Outer metallic bezel around the active screen grid
        for (t in 0 until 2) {
            for (x in startX until (startX + boxW)) {
                pixmap.set(x, startY + t, borderColor)
                pixmap.set(x, startY + boxH - 1 - t, borderColor)
            }
            for (y in startY until (startY + boxH)) {
                pixmap.set(startX + t, y, borderColor)
                pixmap.set(startX + boxW - 1 - t, y, borderColor)
            }
        }

        // Perfectly square screen cell dividers
        if (gridX > 1) {
            val stepX = boxW.toFloat() / gridX
            for (gx in 1 until gridX) {
                val lx = startX + (gx * stepX).toInt()
                for (y in startY until (startY + boxH)) {
                    pixmap.set(lx, y, gridColor)
                    if (!isTiled && lx + 1 < startX + boxW) pixmap.set(lx + 1, y, gridColor)
                }
            }
        }
        if (gridY > 1) {
            val stepY = boxH.toFloat() / gridY
            for (gy in 1 until gridY) {
                val ly = startY + (gy * stepY).toInt()
                for (x in startX until (startX + boxW)) {
                    pixmap.set(x, ly, gridColor)
                    if (!isTiled && ly + 1 < startY + boxH) pixmap.set(x, ly + 1, gridColor)
                }
            }
        }
    }

    private fun exportSchematic() {
        // If user adjusted grid sliders without clicking Convert & Encode yet, encode automatically or warn
        if (!isQuantizedPreviewValid || encodedImage == null) {
            Vars.ui.showInfo("Đang tự động mã hóa lại theo kích thước lưới màn hình mới...")
            triggerEncoding()
            return
        }
        val enc = encodedImage ?: return
        try {
            val name = selectedFile?.nameWithoutExtension() ?: "Static Image"
            val generator = StaticSchematicGenerator(config(), enc)
            val schem = generator.buildSchematic(name)

            Vars.schematics.add(schem)
            Vars.ui.showInfoFade("Schematic [accent]\"$name\"[] added to your Schematics!")
            Vars.ui.schematics.show()
        } catch (e: Exception) {
            Log.err("[StaticImage] Schematic export failed", e)
            Vars.ui.showErrorMessage("Failed to export schematic: ${e.message}")
        }
    }
}