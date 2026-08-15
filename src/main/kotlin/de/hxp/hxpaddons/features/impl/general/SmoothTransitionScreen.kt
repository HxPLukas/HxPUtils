package de.hxp.hxpaddons.features.impl.general

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class SmoothTransitionScreen : Screen(Component.empty()) {
    override fun shouldCloseOnEsc(): Boolean = false

    override fun extractRenderState(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // intentionally blank — keeps screen non-null so handleKeybinds is skipped
        // during the config phase when player/level are null
    }

    override fun extractBackground(guiGraphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // no background
    }
}
