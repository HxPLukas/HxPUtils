package de.hxp.hxpaddons.clickgui.settings.impl

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import de.hxp.hxpaddons.clickgui.settings.RenderableSetting
import de.hxp.hxpaddons.clickgui.settings.Saving
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.ui.animations.LinearAnimation
import de.hxp.hxpaddons.utils.ui.isAreaHovered
import de.hxp.hxpaddons.utils.ui.rendering.NVGRenderer
import net.minecraft.client.input.MouseButtonEvent

class BooleanSetting(
    name: String,
    override val default: Boolean = false,
    desc: String,
) : RenderableSetting<Boolean>(name, desc), Saving {

    override var value: Boolean = default
    var enabled: Boolean by this::value

    private val toggleAnimation = LinearAnimation<Float>(200)

    override fun render(x: Float, y: Float, mouseX: Float, mouseY: Float): Float {
        super.render(x, y, mouseX, mouseY)
        val height = getHeight()

        NVGRenderer.text(name, x + 6f, y + height / 2f - 8f, 16f, Colors.WHITE.rgba, NVGRenderer.defaultFont)

        NVGRenderer.toggleSwitch(x + width - 40f, y + height / 2f - 10f, enabled, isHovered, ClickGUIModule.clickGUIColor, toggleAnimation)

        return height
    }

    override fun mouseClicked(mouseX: Float, mouseY: Float, click: MouseButtonEvent): Boolean {
        return if (click.button() != 0 || !isHovered) false
        else {
            toggleAnimation.start()
            enabled = !enabled
            true
        }
    }

    override val isHovered: Boolean get() = isAreaHovered(lastX + width - 43f, lastY + getHeight() / 2f - 10f, 34f, 20f, true)

    override fun write(gson: Gson): JsonElement = JsonPrimitive(enabled)

    override fun read(element: JsonElement, gson: Gson) {
        enabled = element.asBoolean
    }
}