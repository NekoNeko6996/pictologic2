package pictologic.ui

import arc.graphics.Color
import arc.scene.ui.Label
import arc.scene.ui.layout.Table
import mindustry.gen.Icon
import mindustry.gen.Tex
import mindustry.graphics.Pal
import mindustry.ui.dialogs.BaseDialog
import pictologic.staticimage.StaticImageDialog

/**
 * Launcher / Mode Selection Dialog:
 * Allows user to choose between:
 * 1. GIF & Animation Converter (Original GIF/Sequence mod)
 * 2. Static Image Converter (New 1x1 tiled displays up to 20x20 with chained micro-processors)
 */
class PicToLogicLauncherDialog(
    val gifDialog: PicToLogicDialog,
    val staticDialog: StaticImageDialog
) : BaseDialog("PicToLogic 2") {

    init {
        addCloseButton()
        closeOnBack()
        buildUi()
    }

    private fun buildUi() {
        cont.clear()

        val mainTable = cont.table().pad(20f).get()

        val title = Label("Chọn Chế Độ Chuyển Đổi")
        title.setFontScale(1.3f)
        title.setColor(Pal.accent)
        mainTable.add(title).padBottom(16f).row()

        val subtitle = Label("Vui lòng chọn loại phương tiện bạn muốn chuyển đổi thành mạch logic Mindustry:")
        subtitle.setColor(Color.lightGray)
        mainTable.add(subtitle).padBottom(24f).row()

        val optionsTable = Table()

        // Card 1: GIF & Animation
        val cardGif = Table(Tex.button)
        cardGif.margin(16f).top()

        cardGif.image(Icon.play).size(48f).color(Pal.accent).padBottom(12f)
        cardGif.row()

        val gifTitle = Label("[gold]Tạo GIF / Animation[]")
        gifTitle.setFontScale(1.15f)
        cardGif.add(gifTitle).padBottom(8f).row()

        val gifDesc = Label(
            "Chuyển đổi ảnh chuỗi ảnh thành schematic chuyển động nhiều khung hình.\n"
        )
        gifDesc.setWrap(true)
        gifDesc.setColor(Color.lightGray)
        cardGif.add(gifDesc).width(280f).padBottom(16f).growY().row()

        cardGif.button("Mở Tool Animation", Icon.play) {
            hide()
            gifDialog.show()
        }.growX().height(46f)

        optionsTable.add(cardGif).width(310f).height(300f).padRight(16f)

        // Card 2: Static Image
        val cardStatic = Table(Tex.button)
        cardStatic.margin(16f).top()

        cardStatic.image(Icon.fileImage).size(48f).color(Color.sky).padBottom(12f)
        cardStatic.row()

        val staticTitle = Label("[sky]Tạo Ảnh Tĩnh (Static)[]")
        staticTitle.setFontScale(1.15f)
        cardStatic.add(staticTitle).padBottom(8f).row()

        val staticDesc = Label(
            "Chuyển đổi 1 ảnh tĩnh thành schematic màn hình."
        )
        staticDesc.setWrap(true)
        staticDesc.setColor(Color.lightGray)
        cardStatic.add(staticDesc).width(280f).padBottom(16f).growY().row()

        cardStatic.button("Mở Tool Ảnh Tĩnh", Icon.image) {
            hide()
            staticDialog.show()
        }.growX().height(46f)

        optionsTable.add(cardStatic).width(310f).height(300f)

        mainTable.add(optionsTable).row()
    }
}
