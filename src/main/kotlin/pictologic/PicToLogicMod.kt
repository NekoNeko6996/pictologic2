package pictologic

import arc.Events
import arc.util.Log
import mindustry.Vars
import mindustry.game.EventType
import mindustry.gen.Icon
import mindustry.mod.Mod
import pictologic.ui.PicToLogicDialog

class PicToLogicMod : Mod() {

    private lateinit var dialog: PicToLogicDialog

    override fun init() {
        Log.info("[PicToLogic 2] Initializing RLE Scanline + Palette mod...")

        dialog = PicToLogicDialog()

        // Add button to Schematics Dialog
        Vars.ui.schematics.buttons.button("PicToLogic 2", Icon.fileImage) {
            dialog.show()
        }

        // Add button to Main Menu
        Vars.ui.menufrag.addButton("PicToLogic 2", Icon.fileImage) {
            dialog.show()
        }

        Events.on(EventType.ClientLoadEvent::class.java) {
            Log.info("[PicToLogic 2] Ready for high-speed animation encoding.")
        }
    }
}
