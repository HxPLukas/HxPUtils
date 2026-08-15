package de.hxp.hxpaddons.features.impl.render

import de.hxp.hxpaddons.clickgui.ClickGUI
import de.hxp.hxpaddons.clickgui.HudManager
import de.hxp.hxpaddons.clickgui.settings.AlwaysActive
import de.hxp.hxpaddons.clickgui.settings.impl.*
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.ui.rendering.NVGRenderer
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.round

@AlwaysActive
object ClickGUIModule : Module(
    name = "Click GUI",
    description = "Allows you to customize the UI.",
    key = GLFW.GLFW_KEY_RIGHT_SHIFT,
    category = Category.GUI
) {
    val enableNotification by BooleanSetting("Chat notifications", true, desc = "Sends a message when you toggle a module with a keybind")
    val clickGUIColor by ColorSetting("Color", Color(153, 21, 199), desc = "The accent color of the Click GUI.")
    val cornerRadius by NumberSetting("Corner Radius", 18f, 0f, 34f, 1f, "How rounded the corners of the Click GUI window are.", "px")
    val backgroundOpacity by NumberSetting("Background Opacity", 88, 40, 100, 1, "How see-through the Click GUI window's background is.", "%")
    val windowScale by NumberSetting("Window Scale", 150, 70, 200, 5, "Overall size of the Click GUI window.", "%")
    val textScale by NumberSetting("Text Scale (Settings Open)", 125, 60, 200, 5, "Text size while a module's settings are open.", "%")
    val textScaleClosed by NumberSetting("Text Scale (Settings Closed)", 155, 60, 200, 5, "Text size while no module's settings are open.", "%")
    val toggleScale by NumberSetting("Toggle Switch Size", 130, 60, 200, 5, "Size of the on/off switch (and its keybind chip) in the module list.", "%")
    val keybindChipMinWidth by NumberSetting("Keybind Chip Min Width", 23f, 10f, 60f, 1f, "Minimum width of the keybind chip - keeps it from collapsing to nothing when unbound.", "px")
    val hypixelApiUrl by StringSetting("API URL", "https://api.hypixel.net/v2/", 128, "The Hypixel API server to connect to.").hide()
    val webSocketUrl by StringSetting("WebSocket URL", "wss://api.hypixel.net/ws/", 128, "The Websocket server to connect to.").hide()
    val debugWebhookUrl by StringSetting(
        "Debug Webhook URL",
        "https://discord.com/api/webhooks/1523269966798000249/7QCzRESslYlsSCPtCVi-FMNz1yI-3McbOKnuqs5qNCKU0q8_8t52S2KgTCT51vRhAwpQ",
        256,
        "Discord webhook that devMessage() output is mirrored to, so it can be read back outside of the in-game chat."
    ).hide()
    private val action by ActionSetting("Open HUD Editor", desc = "Opens the HUD editor when clicked.") { mc.setScreen(HudManager) }
    val devMessage by BooleanSetting("Developer Message", false, desc = "Sends development related messages to the chat.")
    val mapDebugMessages by BooleanSetting("Map Debug Messages", true, desc = "Whether MapColorScanner's dungeon map debug logging (cell/room state changes) is sent - separate from Developer Message, since it can be noisy.")

    override fun onKeybind() {
        toggle()
    }

    override fun onEnable() {
        mc.setScreen(ClickGUI)
        super.onEnable()
        toggle()
    }

    fun getStandardGuiScale(): Float {
        val verticalScale = (mc.window.screenHeight.toFloat() / 1080f) / NVGRenderer.devicePixelRatio()
        val horizontalScale = (mc.window.screenWidth.toFloat() / 1920f) / NVGRenderer.devicePixelRatio()
        return round(max(verticalScale, horizontalScale).coerceIn(1f, 3f) * 10f) / 10f
    }
}
