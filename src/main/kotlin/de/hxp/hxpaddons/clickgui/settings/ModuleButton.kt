package de.hxp.hxpaddons.clickgui.settings

import com.mojang.blaze3d.platform.InputConstants
import de.hxp.hxpaddons.clickgui.ClickGUI
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.withAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.ui.HoverHandler
import de.hxp.hxpaddons.utils.ui.animations.LinearAnimation
import de.hxp.hxpaddons.utils.ui.isAreaHovered
import de.hxp.hxpaddons.utils.ui.rendering.NVGRenderer
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * A single row in the module list: name + short description, a keybind chip and an on/off switch at the
 * right edge. Left-click anywhere on the row (outside those two controls) toggles the module. Right-click
 * doesn't expand anything in place anymore - it just reports itself as clicked so
 * [de.hxp.hxpaddons.clickgui.ClickGUI] can mark it as the module whose settings show in the settings
 * column.
 *
 * [ClickGUIModule] itself gets no switch here - toggling "the GUI you're currently looking at" from
 * inside itself doesn't mean anything (its `enabled` flag is just a momentary open-trigger, see its
 * `onEnable`), but its row is still selectable by right-click since that's the only way to reach its own
 * settings (accent color, corner radius, scale, ...), and it still gets a keybind chip like every module.
 *
 * @author Stivais, Aton
 *
 * see [RenderableSetting]
 */
class ModuleButton(val module: Module) {

    // Excludes KeybindSetting - it's already covered by the compact chip below, so it doesn't need to
    // also take up a whole row in the settings column.
    val representableSettings = module.settings.values.filterIsInstance<RenderableSetting<*>>().filterNot { it is KeybindSetting }

    /** Column width this row draws at - set by the container every frame. */
    var width = 0f

    /** Whether this is the module currently shown in the settings column - set by the container. */
    var selected = false

    private val toggleAnim = LinearAnimation<Float>(200)
    private val hover = HoverHandler(200)

    // No chip for ClickGUIModule either - editing the key that opens this very GUI from inside it is the
    // same kind of confusing self-reference as its (already removed) on/off switch.
    private val keybindSetting = (module.settings["Keybind"] as? KeybindSetting).takeIf { module !== ClickGUIModule }
    private var keybindListening = false
    private var kbX = 0f
    private var kbY = 0f
    private var kbW = 0f

    private val toggleScale: Float get() = ClickGUIModule.toggleScale / 100f

    private val rowHeight: Float
        // At least tall enough for the (possibly scaled-up) switch to have breathing room, on top of
        // whatever text scale already asks for.
        get() = (BASE_ROW_H * ClickGUI.textScale).coerceAtLeast(20f * toggleScale + 14f).coerceAtLeast(34f)

    fun draw(x: Float, y: Float): Float {
        val h = rowHeight
        hover.handle(x, y, width, h, true)

        val accent = ClickGUIModule.clickGUIColor
        when {
            selected -> {
                NVGRenderer.rect(x, y, width, h, accent.withAlpha(0.12f).rgba, 6f)
                NVGRenderer.hollowRect(x, y, width, h, 1.3f, accent.withAlpha(0.55f).rgba, 6f)
            }
            hover.isHovered -> NVGRenderer.rect(x, y, width, h, Colors.WHITE.withAlpha(0.045f).rgba, 6f)
        }

        val hasToggle = module !== ClickGUIModule
        val ts = ClickGUI.textScale
        val nameFs = 15f * ts
        val descFs = 11f * ts
        val nameY = y + 7f * ts
        // Gap between the two lines grows with text scale too, so bigger text doesn't start crowding
        // (or overlapping) the description underneath it.
        val descY = nameY + nameFs + 3f * ts

        NVGRenderer.text(module.name, x + 12f, nameY, nameFs, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        val toggleW = 34f * toggleScale
        val toggleX = x + width - toggleW - 12f
        val chipH = 20f * toggleScale

        val kb = keybindSetting
        if (kb != null) {
            val unbound = !keybindListening && kb.value == InputConstants.UNKNOWN
            val label = if (keybindListening) "..." else if (unbound) "" else kb.value.displayName.string
            val labelFs = 11f * toggleScale
            kbW = (NVGRenderer.textWidth(label, labelFs, NVGRenderer.defaultFont) + 14f * toggleScale).coerceAtLeast(ClickGUIModule.keybindChipMinWidth)
            kbX = (if (hasToggle) toggleX else x + width) - 8f - kbW
            kbY = y + h / 2f - chipH / 2f
            drawKeybindChip(kbX, kbY, kbW, chipH, label, labelFs, accent)
        }

        val controlsLeft = if (kb != null) kbX else if (hasToggle) toggleX else x + width
        val descMaxW = controlsLeft - 12f - (x + 12f)
        val descMaxH = (h - (descY - y) - 4f).coerceAtLeast(0f)
        val desc = truncateWrapped(module.description, descMaxW, descMaxH, descFs)
        NVGRenderer.drawWrappedString(desc, x + 12f, descY, descMaxW, descFs, Colors.MINECRAFT_GRAY.rgba, NVGRenderer.defaultFont)

        if (hasToggle) NVGRenderer.toggleSwitch(toggleX, y + h / 2f - chipH / 2f, module.enabled, hover.isHovered, accent, toggleAnim, toggleScale)

        return h
    }

    private fun drawKeybindChip(x: Float, y: Float, w: Float, h: Float, label: String, labelFs: Float, accent: Color) {
        val hovered = isAreaHovered(x, y, w, h, true)
        val radius = h / 2f * 0.55f
        NVGRenderer.rect(x, y, w, h, (if (hovered || keybindListening) Colors.controlBgHover else Colors.controlBg).rgba, radius)
        NVGRenderer.hollowRect(x, y, w, h, 1.3f * toggleScale, accent.withAlpha(if (keybindListening) 1f else 0.4f).rgba, radius)
        val labelW = NVGRenderer.textWidth(label, labelFs, NVGRenderer.defaultFont)
        val color = if (keybindListening) Colors.MINECRAFT_YELLOW else Colors.WHITE
        NVGRenderer.text(label, x + (w - labelW) / 2f, y + (h - labelFs) / 2f, labelFs, color.rgba, NVGRenderer.defaultFont)
    }

    fun mouseClicked(click: MouseButtonEvent): Boolean {
        val kb = keybindSetting
        if (kb != null) {
            if (keybindListening) {
                kb.key = InputConstants.Type.MOUSE.getOrCreate(click.button())
                keybindListening = false
                return true
            }
            if (isAreaHovered(kbX, kbY, kbW, 20f * toggleScale, true)) {
                if (click.button() == 0) keybindListening = true
                return true
            }
        }

        if (!hover.isHovered) return false
        if (click.button() == 0) {
            if (module === ClickGUIModule) return true
            toggleAnim.start()
            module.toggle()
            return true
        }
        // Nothing to show for modules with no settings beyond the keybind (already its own chip) - right
        // click just does nothing instead of opening an empty settings column.
        return click.button() == 1 && representableSettings.isNotEmpty()
    }

    fun keyPressed(input: KeyEvent): Boolean {
        val kb = keybindSetting ?: return false
        if (!keybindListening) return false

        when (input.key) {
            GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_BACKSPACE -> kb.key = InputConstants.UNKNOWN
            GLFW.GLFW_KEY_ENTER -> {}
            else -> kb.key = InputConstants.getKey(input)
        }
        keybindListening = false
        return true
    }

    /** Like [truncate] but wraps within [maxW] first and only cuts once the wrapped block exceeds [maxH]. */
    private fun truncateWrapped(text: String, maxW: Float, maxH: Float, fs: Float): String {
        if (maxW <= 0f || maxH <= 0f) return ""

        fun wrappedHeight(s: String): Float {
            val bounds = NVGRenderer.wrappedTextBounds(s, maxW, fs, NVGRenderer.defaultFont)
            return bounds[3] - bounds[1]
        }

        if (wrappedHeight(text) <= maxH) return text
        var t = text
        while (t.isNotEmpty() && wrappedHeight("$t...") > maxH) t = t.dropLast(1)
        return "$t..."
    }

    companion object {
        private const val BASE_ROW_H = 46f
    }
}
