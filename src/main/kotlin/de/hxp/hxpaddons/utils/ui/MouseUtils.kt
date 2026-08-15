package de.hxp.hxpaddons.utils.ui

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule

inline val mouseX: Float
    get() =
        mc.mouseHandler.xpos().toFloat()

inline val mouseY: Float
    get() =
        mc.mouseHandler.ypos().toFloat()

fun isAreaHovered(x: Float, y: Float, w: Float, h: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / ClickGUIModule.getStandardGuiScale() in x..(x + w) && mouseY / ClickGUIModule.getStandardGuiScale() in y..(y + h)
    else mouseX in x..(x + w) && mouseY in y..(y + h)

fun isAreaHovered(x: Float, y: Float, w: Float, scaled: Boolean = false): Boolean =
    if (scaled) mouseX / ClickGUIModule.getStandardGuiScale() in x..(x + w) && mouseY / ClickGUIModule.getStandardGuiScale() >= y
    else mouseX in x..(x + w) && mouseY >= y