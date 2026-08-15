package de.hxp.hxpaddons.utils.render

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.world.entity.player.PlayerSkin

private const val SKIN_SHEET_SIZE = 64
private const val FACE_REGION = 8
private const val NO_TINT = -1 // full-white ARGB multiplier, i.e. render the texture untinted

/**
 * Draws a flat player-head icon (face + hat overlay layer) from a skin texture, for use as a
 * small map marker. Falls back to the default Steve/Alex skin when [skin] is null. To rotate it (and
 * anything drawn alongside it, like a border), wrap the call in the caller's own pose transform instead
 * of rotating just the icon in isolation.
 */
fun GuiGraphicsExtractor.playerHeadIcon(skin: PlayerSkin?, x: Int, y: Int, size: Int) {
    val texture = skin?.body()?.texturePath() ?: DefaultPlayerSkin.getDefaultTexture()
    blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 8f, 8f, size, size, FACE_REGION, FACE_REGION, SKIN_SHEET_SIZE, SKIN_SHEET_SIZE, NO_TINT)
    blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 40f, 8f, size, size, FACE_REGION, FACE_REGION, SKIN_SHEET_SIZE, SKIN_SHEET_SIZE, NO_TINT)
}
