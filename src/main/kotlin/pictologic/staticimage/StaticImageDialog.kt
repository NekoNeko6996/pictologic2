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
import kotlin.math.min

class StaticImageDialog : BaseDialog("PicToLogic - Static Image") {

    private var selectedFile: Fi? = null
    private var encodedImage: EncodedStaticImage? = null
    private var activePalette: Palette? = null

    private var displayType = DisplayType.TILED_LOGIC_DISPLAY
    private var gridScreensX = 10
    private var gridScreensY = 10
    private var processorChoice = ProcessorChoice.MICRO_PROCESSOR
    private var aspectMode = AspectMode.FIT
    private var paletteColors = 16
    private var watermarkText = ""
    private var watermarkColorRgba = -0x1 // White (0xFFFFFFFF)
    private val isEncoding = AtomicBoolean(false)

    private var previewPixmap: Pixmap? = null
    private var previewTexture: Texture? = null
    private var previewImage: Image? = null

    private val statusLabel = Label("No image selected")
    private val fileDetailsLabel = Label("")
    private val statsLabel = Label("Select a static image to start")
    private val modeInfoLabel = Label("")
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
        // Preset RGBA8888 colors for watermark selection
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
            modeInfoLabel.setText("[accent]Chế độ Chunk (3x3 / 6x6):[] Mỗi màn hình là 1 chunk riêng, bắt buộc dùng [gold]Logic Processor (2x2)[] đồng bộ qua Controller chính.")
        } else {
            modeInfoLabel.setText("[sky]Chế độ Nối Chuỗi (1x1 Tiled, tối đa 16x16):[] Các vi xử lý liên kết theo chuỗi (Daisy-Chain) đến Controller.")
        }

        val cfg = config()
        resVal.setText("${cfg.effectiveDisplayWidth}x${cfg.effectiveDisplayHeight} px")
        refreshButtonHighlights()
        updatePreviewOverlay()
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

        // Expanded left settings panel width to 560f so all buttons and text fit comfortably!
        root.add(leftScroll).growY().width(560f).pad(8f)
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

        val bTiled = dispTypeTable.button("Tiled 1x1 (Max 16x16)", Styles.togglet) {
            displayType = DisplayType.TILED_LOGIC_DISPLAY
            applyDisplayTypeConstraints()
        }.height(36f).padRight(4f).growX().get()
        bTiled.addTip("Màn hình 1x1 ghép tối đa 16x16. Kết nối theo chuỗi (Daisy-Chain).")
        displayButtons.add(DisplayType.TILED_LOGIC_DISPLAY to bTiled)

        val bLogic = dispTypeTable.button("Logic 3x3 (Max 5x5)", Styles.togglet) {
            displayType = DisplayType.LOGIC_DISPLAY
            applyDisplayTypeConstraints()
        }.height(36f).padRight(4f).growX().get()
        bLogic.addTip("Màn hình 3x3 (80x80px) ghép tối đa 5x5. Mỗi màn hình là 1 chunk riêng biệt.")
        displayButtons.add(DisplayType.LOGIC_DISPLAY to bLogic)

        val bLarge = dispTypeTable.button("Large 6x6 (Max 4x4)", Styles.togglet) {
            displayType = DisplayType.LARGE_LOGIC_DISPLAY
            applyDisplayTypeConstraints()
        }.height(36f).growX().get()
        bLarge.addTip("Màn hình 6x6 (176x176px) ghép tối đa 4x4. Mỗi màn hình là 1 chunk riêng biệt.")
        displayButtons.add(DisplayType.LARGE_LOGIC_DISPLAY to bLarge)

        step2.add(dispTypeTable).growX().padBottom(6f).row()

        val gridTable = Table()
        gridTable.add(Label("Screen Grid: ")).left()
        gridValX.setColor(Pal.accent)
        gridTable.add(gridValX).width(22f)
        gridTable.add(Label("x")).padRight(4f)
        gridValY.setColor(Pal.accent)
        gridTable.add(gridValY).width(26f)

        sliderGridX = Slider(1f, 16f, 1f, false)
        sliderGridX.value = gridScreensX.toFloat()
        sliderGridX.moved { v ->
            gridScreensX = v.toInt()
            gridValX.setText("$gridScreensX")
            val cfg = config()
            resVal.setText("${cfg.effectiveDisplayWidth}x${cfg.effectiveDisplayHeight} px")
            updatePreviewOverlay()
        }
        gridTable.add(sliderGridX).growX().padRight(4f)

        sliderGridY = Slider(1f, 16f, 1f, false)
        sliderGridY.value = gridScreensY.toFloat()
        sliderGridY.moved { v ->
            gridScreensY = v.toInt()
            gridValY.setText("$gridScreensY")
            val cfg = config()
            resVal.setText("${cfg.effectiveDisplayWidth}x${cfg.effectiveDisplayHeight} px")
            updatePreviewOverlay()
        }
        gridTable.add(sliderGridY).growX()
        step2.add(gridTable).growX().padBottom(6f).row()

        val resTable = Table()
        resTable.add(Label("Total Canvas Resolution: ")).left()
        resVal.setColor(Pal.accent)
        resTable.add(resVal).left()
        step2.add(resTable).growX().padBottom(6f).row()

        val aspectTable = Table()
        aspectTable.add(Label("Aspect: ")).left().padRight(4f)
        aspectButtons.clear()
        for (mode in AspectMode.values()) {
            val bMode = aspectTable.button(mode.name.lowercase(), Styles.togglet) {
                aspectMode = mode
                refreshButtonHighlights()
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

            val bColor = wmColorTable.button("", Styles.togglet) {
                watermarkColorRgba = rgba
                refreshButtonHighlights()
                if (selectedFile != null) triggerEncoding()
            }.size(30f, 30f).padRight(4f).get()
            bColor.style.up = Tex.whiteui
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
        val previewTitle = Label("Static Image Preview")
        previewTitle.setFontScale(1.1f)
        previewTitle.setColor(Color.sky)
        pane.add(previewTitle).left().padBottom(8f).row()

        val canvasContainer = Table(Tex.pane)
        canvasContainer.margin(10f)

        previewPixmap = Pixmap(160, 160)
        previewPixmap!!.fill(0x000000FF)
        drawDisplayGridOverlay(previewPixmap!!, gridScreensX, gridScreensY, displayType.isTileable)

        previewTexture = Texture(previewPixmap)
        previewImage = Image(TextureRegion(previewTexture))

        canvasContainer.add(previewImage).size(380f, 380f).center()
        pane.add(canvasContainer).grow().center().padBottom(8f).row()

        val swatchContainer = Table()
        swatchContainer.add(Label("Active Palette Colors:")).color(Color.gray).left().row()
        paletteTable.margin(6f).left()
        swatchContainer.add(paletteTable).growX()
        pane.add(swatchContainer).growX().padBottom(4f).row()
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
            val pm = Pixmap(file)
            fileDetailsLabel.setText("Original Size: [accent]${pm.width}x${pm.height}[] px | ${file.length() / 1024} KB")
            pm.dispose()
        } catch (_: Exception) {
            fileDetailsLabel.setText("Size: ${file.length() / 1024} KB")
        }
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

                    val cw = result.canvasWidth
                    val ch = result.canvasHeight
                    if (previewPixmap == null || previewPixmap!!.width != cw || previewPixmap!!.height != ch) {
                        previewPixmap?.dispose()
                        previewTexture?.dispose()
                        previewPixmap = Pixmap(cw, ch)
                        previewTexture = Texture(previewPixmap)
                        previewImage?.setDrawable(TextureRegionDrawable(TextureRegion(previewTexture)))
                    }

                    previewPixmap!!.draw(result.quantizedPixmap, 0, 0, cw, ch, 0, 0, cw, ch, false)
                    drawDisplayGridOverlay(previewPixmap!!, cfg.clampedGridX, cfg.clampedGridY, displayType.isTileable)
                    previewTexture?.load(previewTexture!!.textureData)

                    val archDesc = if (cfg.isMultiScreenChunked) {
                        "${cfg.clampedGridX * cfg.clampedGridY} Screen Chunks (2x2 Logic)"
                    } else {
                        "Daisy-Chained (${cfg.effectiveProcessor.name.replace("_PROCESSOR", "")})"
                    }

                    statsLabel.setText(
                        "[green]Success![] 2D RLE Runs: [accent]${result.totalRuns}[] | Draw Cmds: [accent]${result.totalDrawInstructions}[]\n" +
                        "Architecture: [accent]$archDesc[] | Total Workers: [accent]${result.totalWorkers}[]"
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

    private fun updatePreviewOverlay() {
        val enc = encodedImage
        val cfg = config()
        if (enc != null && previewPixmap != null) {
            previewPixmap!!.draw(
                enc.quantizedPixmap,
                0, 0, enc.quantizedPixmap.width, enc.quantizedPixmap.height,
                0, 0, previewPixmap!!.width, previewPixmap!!.height, false
            )
            drawDisplayGridOverlay(previewPixmap!!, cfg.clampedGridX, cfg.clampedGridY, displayType.isTileable)
            previewTexture?.load(previewTexture!!.textureData)
        } else if (previewPixmap != null) {
            previewPixmap!!.fill(0x000000FF)
            drawDisplayGridOverlay(previewPixmap!!, cfg.clampedGridX, cfg.clampedGridY, displayType.isTileable)
            previewTexture?.load(previewTexture!!.textureData)
        }
    }

    private fun drawDisplayGridOverlay(pixmap: Pixmap, gridX: Int, gridY: Int, isTiled: Boolean) {
        val w = pixmap.width
        val h = pixmap.height
        val borderColor = 0x2C2C36FF
        val gridColor = if (isTiled) 0x3A3A46FF else 0x00E5FFFF

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
    }

    private fun exportSchematic() {
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