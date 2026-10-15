package pictologic

import arc.Events
import arc.util.Log
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Icon
import mindustry.mod.Mod
import pictologic.staticimage.StaticImageDialog
import pictologic.ui.PicToLogicDialog
import pictologic.ui.PicToLogicLauncherDialog

class PicToLogicMod : Mod() {

    private lateinit var gifDialog: PicToLogicDialog
    private lateinit var staticDialog: StaticImageDialog
    private lateinit var launcherDialog: PicToLogicLauncherDialog

    override fun init() {
        Log.info("[PicToLogic 2] Initializing RLE Scanline + Palette mod...")

        gifDialog = PicToLogicDialog()
        staticDialog = StaticImageDialog()
        launcherDialog = PicToLogicLauncherDialog(gifDialog, staticDialog)

        // Add button to Schematics Dialog
        Vars.ui.schematics.buttons.button("PicToLogic 2", Icon.fileImage) {
            launcherDialog.show()
        }

        // Add button to Main Menu
        Vars.ui.menufrag.addButton("PicToLogic 2", Icon.fileImage) {
            launcherDialog.show()
        }

        Events.on(EventType.ClientLoadEvent::class.java) {
            Log.info("[PicToLogic 2] Ready for high-speed animation & static image encoding.")
        }
    }
}
