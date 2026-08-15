package de.hxp.hxpaddons.clickgui

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.features.impl.render.CustomESP
import de.hxp.hxpaddons.features.impl.render.TargetProfile
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Color.Companion.brighter
import de.hxp.hxpaddons.utils.Color.Companion.withAlpha
import de.hxp.hxpaddons.utils.Colors
import de.hxp.hxpaddons.utils.ui.TextInputHandler
import de.hxp.hxpaddons.utils.ui.isAreaHovered
import de.hxp.hxpaddons.utils.ui.rendering.NVGPIPRenderer
import de.hxp.hxpaddons.utils.ui.rendering.NVGRenderer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import de.hxp.hxpaddons.utils.ui.mouseX as odinMouseX
import de.hxp.hxpaddons.utils.ui.mouseY as odinMouseY

/**
 * The "add a new [TargetProfile]" screen opened by [TargetProfilesScreen]'s Add button (on request -
 * "welche ein neues gui öffnet wo man alle daten angeben kann entity und name hat er ja schon aber wo man
 * auch skin id item held armor und eben alles was man über high range detecten kann angeben kann"): five
 * independent text fields, one per [TargetProfile] property, all optional/wildcard when left blank (per
 * [TargetProfile]'s own matching rules). Save constructs one profile from whatever was filled in and returns
 * to [TargetProfilesScreen]; Cancel/Escape/× discards and returns without saving.
 */
object TargetProfileEditScreen : Screen(Component.literal("Add Target Profile")) {

    private val windowBg = Color(17, 13, 24)

    private const val W        = 480f
    private const val TITLE_H  = 42f
    private const val PAD      = 16f
    private const val FS       = 15f
    private const val FS_TITLE = 20f
    private const val LABEL_FS = 12f
    private const val FIELD_H  = 58f
    private const val FIELD_INPUT_H = 32f
    private const val BOTTOM_H = 64f
    private const val BTN_W    = 100f

    private data class Field(val label: String, val placeholder: String, val input: TextInputHandler)

    private val fieldValues = MutableList(5) { "" }

    private val fieldLabels = listOf(
        "Entity Type" to "e.g. Zombie",
        "Name" to "e.g. Crypt Ghoul",
        "Skin / Model ID" to "texture, CustomModelData or ItemModel id",
        "Item Held" to "e.g. Iron Sword",
        "Armor" to "e.g. Leather Boots"
    )

    private val fields: List<Field> = fieldLabels.mapIndexed { i, (label, placeholder) ->
        Field(label, placeholder, TextInputHandler(
            textProvider = { fieldValues[i] },
            textSetter = { fieldValues[i] = it }
        ))
    }

    private val H = TITLE_H + PAD + fields.size * FIELD_H + BOTTOM_H

    fun openForNew() {
        for (i in fieldValues.indices) fieldValues[i] = ""
        mc.setScreen(this)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale

        NVGPIPRenderer.draw(context, 0, 0, context.guiWidth(), context.guiHeight()) {
            NVGRenderer.scale(guiScale, guiScale)

            val sw = mc.window.screenWidth / guiScale
            val sh = mc.window.screenHeight / guiScale
            val cx = (sw - W) / 2f
            val cy = (sh - H) / 2f

            val radius = ClickGUIModule.cornerRadius
            val bgAlpha = ClickGUIModule.backgroundOpacity / 100f
            val accent = ClickGUIModule.clickGUIColor

            NVGRenderer.rect(0f, 0f, sw, sh, Color(0, 0, 0, 0.55f).rgba, 0f)

            NVGRenderer.dropShadow(cx, cy, W, H, 24f, 4f, radius)
            NVGRenderer.rect(cx, cy, W, H, windowBg.withAlpha(bgAlpha).rgba, radius)
            NVGRenderer.hollowRect(cx, cy, W, H, 1.5f, accent.withAlpha(0.35f).rgba, radius)

            // ── Title bar ──────────────────────────────────────────
            NVGRenderer.circle(cx + PAD + 4f, cy + TITLE_H / 2f, 4f, accent.rgba)
            NVGRenderer.text("Add Target Profile", cx + PAD + 16f, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, Colors.WHITE.rgba, NVGRenderer.defaultFont)

            val closeHovered = isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE * 1.2f, FS_TITLE, true)
            NVGRenderer.text("×", cx + W - PAD - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, if (closeHovered) Colors.MINECRAFT_RED.rgba else Colors.WHITE.rgba, NVGRenderer.defaultFont)

            NVGRenderer.line(cx, cy + TITLE_H, cx + W, cy + TITLE_H, 1f, Colors.gray38.rgba)

            // ── Fields ─────────────────────────────────────────────
            var fy = cy + TITLE_H + PAD / 2f
            val fieldX = cx + PAD
            val fieldW = W - PAD * 2

            fields.forEachIndexed { i, field ->
                NVGRenderer.text(field.label, fieldX, fy, LABEL_FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)

                val inputY = fy + LABEL_FS + 6f
                NVGRenderer.rect(fieldX, inputY, fieldW, FIELD_INPUT_H, Colors.controlBg.rgba, 5f)
                NVGRenderer.hollowRect(fieldX, inputY, fieldW, FIELD_INPUT_H, 1.5f, accent.withAlpha(0.45f).rgba, 5f)

                val textY = inputY + (FIELD_INPUT_H - FS) / 2f
                if (fieldValues[i].isEmpty()) NVGRenderer.text(field.placeholder, fieldX + PAD * 0.6f, textY, FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
                field.input.x = fieldX + PAD * 0.6f - 2f;  field.input.y = textY - 2f
                field.input.width = fieldW - PAD * 1.2f;  field.input.height = FS + 2f
                field.input.draw(smx, smy)

                fy += FIELD_H
            }

            // ── Bottom bar ─────────────────────────────────────────
            val bottomY = cy + H - BOTTOM_H
            NVGRenderer.line(cx, bottomY, cx + W, bottomY, 1f, Colors.gray38.rgba)

            val barH = BOTTOM_H - PAD * 2;  val barY = bottomY + PAD
            val saveBtnX = cx + W - PAD - BTN_W
            val cancelBtnX = saveBtnX - PAD - BTN_W

            val cancelHovered = isAreaHovered(cancelBtnX, barY, BTN_W, barH, true)
            NVGRenderer.rect(cancelBtnX, barY, BTN_W, barH, Colors.controlBg.rgba, 5f)
            if (cancelHovered) NVGRenderer.hollowRect(cancelBtnX, barY, BTN_W, barH, 1.5f, accent.withAlpha(0.45f).rgba, 5f)
            val cancelTw = NVGRenderer.textWidth("Cancel", FS, NVGRenderer.defaultFont)
            NVGRenderer.text("Cancel", cancelBtnX + (BTN_W - cancelTw) / 2f, barY + (barH - FS) / 2f, FS, Colors.WHITE.rgba, NVGRenderer.defaultFont)

            val saveHovered = isAreaHovered(saveBtnX, barY, BTN_W, barH, true)
            NVGRenderer.rect(saveBtnX, barY, BTN_W, barH, if (saveHovered) accent.brighter(1.15f).rgba else accent.rgba, 5f)
            val saveTw = NVGRenderer.textWidth("Save", FS, NVGRenderer.defaultFont)
            NVGRenderer.text("Save", saveBtnX + (BTN_W - saveTw) / 2f, barY + (barH - FS) / 2f, FS, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }

        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale
        val cx = (mc.window.screenWidth / guiScale - W) / 2f
        val cy = (mc.window.screenHeight / guiScale - H) / 2f

        if (mouseButtonEvent.button() == 0 && isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE * 1.2f, FS_TITLE, true)) {
            cancel(); return true
        }

        for (field in fields) if (field.input.mouseClicked(smx, smy, mouseButtonEvent)) return true

        val bottomY = cy + H - BOTTOM_H
        val barH = BOTTOM_H - PAD * 2;  val barY = bottomY + PAD
        val saveBtnX = cx + W - PAD - BTN_W
        val cancelBtnX = saveBtnX - PAD - BTN_W

        if (mouseButtonEvent.button() == 0 && isAreaHovered(cancelBtnX, barY, BTN_W, barH, true)) { cancel(); return true }
        if (mouseButtonEvent.button() == 0 && isAreaHovered(saveBtnX, barY, BTN_W, barH, true)) { save(); return true }

        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        for (field in fields) field.input.mouseReleased()
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        for (field in fields) if (field.input.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (keyEvent.key == GLFW.GLFW_KEY_ESCAPE) { cancel(); return true }
        for (field in fields) if (field.input.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        super.onClose()
    }

    override fun isPauseScreen() = false

    private fun save() {
        val profile = TargetProfile(
            entityType = fieldValues[0].trim(),
            name = fieldValues[1].trim(),
            skinId = fieldValues[2].trim(),
            heldItem = fieldValues[3].trim(),
            armor = fieldValues[4].trim()
        )
        if (!profile.isBlank) {
            CustomESP.targetProfiles.add(profile)
            ModuleManager.saveConfigurations()
        }
        mc.setScreen(TargetProfilesScreen)
    }

    private fun cancel() {
        mc.setScreen(TargetProfilesScreen)
    }
}
