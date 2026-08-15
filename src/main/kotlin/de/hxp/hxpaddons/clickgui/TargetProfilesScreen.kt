package de.hxp.hxpaddons.clickgui

import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.features.impl.render.CustomESP
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
import de.hxp.hxpaddons.utils.ui.mouseX as odinMouseX
import de.hxp.hxpaddons.utils.ui.mouseY as odinMouseY

/**
 * [de.hxp.hxpaddons.features.impl.render.CustomESP]'s "Target Profiles" list manager - same window-chrome
 * family and layout skeleton as [FilterScreen] (title bar, search bar, scrollable row list, bottom bar), but
 * each row is one [de.hxp.hxpaddons.features.impl.render.TargetProfile] shown via its own
 * [de.hxp.hxpaddons.features.impl.render.TargetProfile.summary] instead of a plain string, and the bottom bar
 * is just a single "Add" button (on request - "rechts einen add button hat welche ein neues gui öffnet") that
 * opens [TargetProfileEditScreen] instead of a text-input-and-Add pair, since a profile needs several fields
 * filled in, not one string.
 *
 * Row interactions (2026-08-16, on request - "das man filter an und aus togglen kann ihnen namen geben kann
 * per rechts clicken bearbeiten kann"): a checkbox on the left of each row toggles
 * [de.hxp.hxpaddons.features.impl.render.TargetProfile.enabled] without deleting/retyping it; right-clicking
 * anywhere on a row opens [TargetProfileEditScreen] pre-filled with that profile (including its
 * [de.hxp.hxpaddons.features.impl.render.TargetProfile.label]) for editing in place; the × on the right still
 * deletes - narrowed from "anywhere on the row deletes it" (the original behavior) down to just that button's
 * own hitbox now that the rest of the row does other things. Same request also removed Escape as a way to
 * close this screen (and [TargetProfileEditScreen]) - only clicking the × now does, on request ("die nurnoch
 * wenn man rechts auf kreuz drückt schließt").
 */
object TargetProfilesScreen : Screen(Component.literal("Target Profiles")) {

    private val windowBg = Color(17, 13, 24)

    private const val W        = 640f
    private const val H        = 552f
    private const val TITLE_H  = 54f
    private const val SEARCH_H = 48f
    private const val BOTTOM_H = 64f
    private const val ROW_H    = 36f
    private const val PAD      = 10f
    private const val FS       = 15f
    private const val FS_TITLE = 20f
    private const val FS_HINT  = 11f
    private const val BTN_ADD  = 100f
    private const val CB_SIZE  = FS
    private const val LIST_H   = H - TITLE_H - SEARCH_H - BOTTOM_H

    private var scrollOffset = 0f

    private var searchQuery = ""
    private val searchInput = TextInputHandler(
        textProvider = { searchQuery },
        textSetter = { searchQuery = it }
    )

    private val displayedProfiles: List<Pair<Int, String>>
        get() = CustomESP.targetProfiles.mapIndexed { i, p -> i to p.summary() }
            .filter { (_, s) -> searchQuery.isBlank() || s.contains(searchQuery, ignoreCase = true) }

    override fun init() {
        scrollOffset = 0f
        searchQuery = ""
        super.init()
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

            val displayed = displayedProfiles
            val maxScroll = (displayed.size * ROW_H - LIST_H).coerceAtLeast(0f)
            scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

            val radius = ClickGUIModule.cornerRadius
            val bgAlpha = ClickGUIModule.backgroundOpacity / 100f
            val accent = ClickGUIModule.clickGUIColor

            NVGRenderer.rect(0f, 0f, sw, sh, Color(0, 0, 0, 0.55f).rgba, 0f)

            NVGRenderer.dropShadow(cx, cy, W, H, 24f, 4f, radius)
            NVGRenderer.rect(cx, cy, W, H, windowBg.withAlpha(bgAlpha).rgba, radius)
            NVGRenderer.hollowRect(cx, cy, W, H, 1.5f, accent.withAlpha(0.35f).rgba, radius)

            // ── Title bar ──────────────────────────────────────────
            NVGRenderer.circle(cx + PAD + 4f, cy + 20f, 4f, accent.rgba)
            NVGRenderer.text("Target Profiles", cx + PAD + 16f, cy + 20f - FS_TITLE / 2f, FS_TITLE, Colors.WHITE.rgba, NVGRenderer.defaultFont)
            NVGRenderer.text("Checkbox toggles • Right-click edits • × deletes", cx + PAD + 16f, cy + 20f + FS_TITLE / 2f, FS_HINT, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)

            val closeHovered = isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + 20f - FS_TITLE / 2f, FS_TITLE * 1.2f, FS_TITLE, true)
            NVGRenderer.text("×", cx + W - PAD - FS_TITLE, cy + 20f - FS_TITLE / 2f, FS_TITLE, if (closeHovered) Colors.MINECRAFT_RED.rgba else Colors.WHITE.rgba, NVGRenderer.defaultFont)

            NVGRenderer.line(cx, cy + TITLE_H, cx + W, cy + TITLE_H, 1f, Colors.gray38.rgba)

            // ── Search bar ─────────────────────────────────────────
            val searchY = cy + TITLE_H
            val srX = cx + PAD;  val srY = searchY + PAD
            val srW = W - PAD * 2;  val srH = SEARCH_H - PAD * 2
            NVGRenderer.hollowRect(srX, srY, srW, srH, 1.5f, accent.withAlpha(0.5f).rgba, 6f)

            val sInputY = srY + (srH - FS) / 2f
            if (searchQuery.isEmpty()) NVGRenderer.text("Search profiles...", srX + PAD, sInputY, FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            searchInput.x = srX + PAD - 2f;  searchInput.y = sInputY - 2f
            searchInput.width = srW - PAD * 2;  searchInput.height = FS + 2f
            searchInput.draw(smx, smy)

            // ── Profile list ───────────────────────────────────────
            val listStartY = cy + TITLE_H + SEARCH_H
            NVGRenderer.pushScissor(cx, listStartY, W, LIST_H)

            if (displayed.isEmpty()) {
                val msg = if (CustomESP.targetProfiles.isEmpty()) "No target profiles yet" else "No matches for \"$searchQuery\""
                val msgW = NVGRenderer.textWidth(msg, FS, NVGRenderer.defaultFont)
                NVGRenderer.text(msg, cx + (W - msgW) / 2f, listStartY + (LIST_H - FS) / 2f, FS, Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
            } else {
                displayed.forEachIndexed { rowIdx, (origIdx, summary) ->
                    val ry = listStartY + rowIdx * ROW_H - scrollOffset
                    if (ry + ROW_H < listStartY || ry > listStartY + LIST_H) return@forEachIndexed

                    val riY = ry + 2f;  val riH = ROW_H - 4f
                    val rowHovered = isAreaHovered(cx + PAD, riY, W - PAD * 2, riH, true)
                            && smy >= listStartY && smy <= listStartY + LIST_H
                    val profile = CustomESP.targetProfiles[origIdx]

                    NVGRenderer.rect(cx + PAD, riY, W - PAD * 2, riH, Colors.controlBg.rgba, 5f)
                    if (rowHovered) NVGRenderer.hollowRect(cx + PAD, riY, W - PAD * 2, riH, 1.5f, accent.withAlpha(0.45f).rgba, 5f)

                    val cbX = cx + PAD + 6f
                    val cbY = riY + (riH - CB_SIZE) / 2f
                    if (profile.enabled) {
                        NVGRenderer.rect(cbX, cbY, CB_SIZE, CB_SIZE, accent.rgba, 3f)
                    } else {
                        val cbHovered = isAreaHovered(cbX, cbY, CB_SIZE, CB_SIZE, true)
                        NVGRenderer.hollowRect(cbX, cbY, CB_SIZE, CB_SIZE, 1.5f, (if (cbHovered) accent else Colors.MINECRAFT_DARK_GRAY).withAlpha(0.7f).rgba, 3f)
                    }

                    val xBtnW = FS * 1.4f
                    val xBtnX = cx + W - PAD - xBtnW
                    val xHovered = isAreaHovered(xBtnX, riY, xBtnW, riH, true)
                    val tvCenter = riY + (riH - FS) / 2f

                    NVGRenderer.text("×", xBtnX + (xBtnW - NVGRenderer.textWidth("×", FS, NVGRenderer.defaultFont)) / 2f,
                        tvCenter, FS, if (xHovered) Colors.MINECRAFT_RED.rgba else Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)

                    val textX = cbX + CB_SIZE + 10f
                    val display = truncate(summary, xBtnX - PAD - textX)
                    NVGRenderer.text(display, textX, tvCenter, FS, if (profile.enabled) Colors.WHITE.rgba else Colors.MINECRAFT_DARK_GRAY.rgba, NVGRenderer.defaultFont)
                }
            }
            NVGRenderer.popScissor()

            // ── Bottom bar ─────────────────────────────────────────
            val bottomY = cy + H - BOTTOM_H
            NVGRenderer.line(cx, bottomY, cx + W, bottomY, 1f, Colors.gray38.rgba)

            val addBtnX = cx + W - PAD - BTN_ADD
            val barH = BOTTOM_H - PAD * 2;  val barY = bottomY + PAD

            val addHovered = isAreaHovered(addBtnX, barY, BTN_ADD, barH, true)
            NVGRenderer.rect(addBtnX, barY, BTN_ADD, barH, if (addHovered) accent.brighter(1.15f).rgba else accent.rgba, 5f)
            val addTw = NVGRenderer.textWidth("+ Add", FS, NVGRenderer.defaultFont)
            NVGRenderer.text("+ Add", addBtnX + (BTN_ADD - addTw) / 2f, barY + (barH - FS) / 2f, FS, Colors.WHITE.rgba, NVGRenderer.defaultFont)
        }

        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val cx = (mc.window.screenWidth / guiScale - W) / 2f
        val cy = (mc.window.screenHeight / guiScale - H) / 2f
        if (isAreaHovered(cx, cy + TITLE_H + SEARCH_H, W, LIST_H, true)) {
            val maxScroll = (displayedProfiles.size * ROW_H - LIST_H).coerceAtLeast(0f)
            scrollOffset = (scrollOffset - verticalAmount.toFloat() * ROW_H).coerceIn(0f, maxScroll)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun mouseClicked(mouseButtonEvent: MouseButtonEvent, bl: Boolean): Boolean {
        val guiScale = ClickGUIModule.getStandardGuiScale()
        val smx = odinMouseX / guiScale
        val smy = odinMouseY / guiScale
        val cx = (mc.window.screenWidth / guiScale - W) / 2f
        val cy = (mc.window.screenHeight / guiScale - H) / 2f

        if (mouseButtonEvent.button() == 0 && isAreaHovered(cx + W - PAD * 2 - FS_TITLE, cy + 20f - FS_TITLE / 2f, FS_TITLE * 1.2f, FS_TITLE, true)) {
            onClose(); return true
        }

        if (searchInput.mouseClicked(smx, smy, mouseButtonEvent)) return true

        val bottomY = cy + H - BOTTOM_H
        val addBtnX = cx + W - PAD - BTN_ADD
        val barH = BOTTOM_H - PAD * 2;  val barY = bottomY + PAD

        if (mouseButtonEvent.button() == 0 && isAreaHovered(addBtnX, barY, BTN_ADD, barH, true)) {
            TargetProfileEditScreen.openForNew()
            return true
        }

        val listStartY = cy + TITLE_H + SEARCH_H
        if (smy >= listStartY && smy <= listStartY + LIST_H) {
            displayedProfiles.forEachIndexed { rowIdx, (origIdx, _) ->
                val ry = listStartY + rowIdx * ROW_H - scrollOffset
                val riY = ry + 2f;  val riH = ROW_H - 4f
                if (!isAreaHovered(cx + PAD, riY, W - PAD * 2, riH, true)) return@forEachIndexed

                if (mouseButtonEvent.button() == 1) {
                    TargetProfileEditScreen.openForEdit(origIdx)
                    return true
                }
                if (mouseButtonEvent.button() == 0) {
                    val cbX = cx + PAD + 6f
                    val cbY = riY + (riH - CB_SIZE) / 2f
                    if (isAreaHovered(cbX, cbY, CB_SIZE, CB_SIZE, true)) {
                        val profile = CustomESP.targetProfiles[origIdx]
                        profile.enabled = !profile.enabled
                        ModuleManager.saveConfigurations()
                        return true
                    }
                    val xBtnW = FS * 1.4f
                    val xBtnX = cx + W - PAD - xBtnW
                    if (isAreaHovered(xBtnX, riY, xBtnW, riH, true)) {
                        CustomESP.targetProfiles.removeAt(origIdx)
                        ModuleManager.saveConfigurations()
                        scrollOffset = scrollOffset.coerceIn(0f, (CustomESP.targetProfiles.size * ROW_H - LIST_H).coerceAtLeast(0f))
                        return true
                    }
                }
            }
        }

        return super.mouseClicked(mouseButtonEvent, bl)
    }

    override fun mouseReleased(mouseButtonEvent: MouseButtonEvent): Boolean {
        searchInput.mouseReleased()
        return super.mouseReleased(mouseButtonEvent)
    }

    override fun charTyped(characterEvent: CharacterEvent): Boolean {
        if (searchInput.keyTyped(characterEvent)) return true
        return super.charTyped(characterEvent)
    }

    // Escape deliberately does NOT close this screen (2026-08-16, on request - "die nurnoch wenn man rechts
    // auf kreuz drückt schließt") - only the × button (see mouseClicked) does now.
    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        if (searchInput.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        ModuleManager.saveConfigurations()
        super.onClose()
    }

    override fun isPauseScreen() = false

    private fun truncate(text: String, maxW: Float): String {
        if (NVGRenderer.textWidth(text, FS, NVGRenderer.defaultFont) <= maxW) return text
        var t = text
        while (t.isNotEmpty() && NVGRenderer.textWidth("$t...", FS, NVGRenderer.defaultFont) > maxW) t = t.dropLast(1)
        return "$t..."
    }
}
