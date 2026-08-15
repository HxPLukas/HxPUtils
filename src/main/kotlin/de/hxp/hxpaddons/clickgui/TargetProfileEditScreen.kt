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
import de.hxp.hxpaddons.utils.ui.mouseX as odinMouseX
import de.hxp.hxpaddons.utils.ui.mouseY as odinMouseY

/**
 * The add/edit screen for one [TargetProfile], opened either from [TargetProfilesScreen]'s Add button (on
 * request - "welche ein neues gui öffnet wo man alle daten angeben kann entity und name hat er ja schon aber wo
 * man auch skin id item held armor und eben alles was man über high range detecten kann angeben kann") via
 * [openForNew], or by right-clicking an existing row (2026-08-16, on request - "per rechts clicken bearbeiten
 * kann") via [openForEdit] - both share the same form, [editingIndex] just tracks which mode [save] should act
 * in. Independent text fields, one per [TargetProfile] property (plus [TargetProfile.label], on the same
 * request - "ihnen namen geben kann"), all optional/wildcard when left blank except Label (per [TargetProfile]'s
 * own matching rules). Save constructs/updates one profile from whatever was filled in and returns to
 * [TargetProfilesScreen]; Cancel/× discards and returns without saving - Escape deliberately does NOT (same
 * request as [TargetProfilesScreen]'s own row rework - "die nurnoch wenn man rechts auf kreuz drückt schließt").
 */
object TargetProfileEditScreen : Screen(Component.literal("Target Profile")) {

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

    // Index 0 is TargetProfile.label (cosmetic, not a matching field - see the class doc) - kept first since
    // it's the row's own title, everything after it is a matching field in the exact order TargetProfile's
    // constructor/`/hxp esp mob` use.
    private val fieldValues = MutableList(9) { "" }
    private const val IDX_LABEL = 0
    private const val IDX_ENTITY_TYPE = 1
    private const val IDX_NAME = 2
    private const val IDX_SKIN_ID = 3
    private const val IDX_HELD_ITEM = 4
    private const val IDX_HELMET = 5
    private const val IDX_CHESTPLATE = 6
    private const val IDX_LEGGINGS = 7
    private const val IDX_BOOTS = 8

    /** null while adding a brand new profile ([openForNew]); the index into [CustomESP.targetProfiles] being edited otherwise ([openForEdit]) - decides whether [save] appends or updates in place. */
    private var editingIndex: Int? = null

    private val fieldLabels = listOf(
        "Label (optional)" to "your own name for this profile, e.g. \"Iron Golem farmers\"",
        "Entity Type" to "e.g. Zombie",
        "Name" to "e.g. Crypt Ghoul",
        "Skin / Model ID" to "texture, CustomModelData or ItemModel id",
        "Item Held" to "e.g. Iron Sword",
        "Helmet" to "e.g. Leather Cap",
        "Chestplate" to "e.g. Leather Tunic",
        "Leggings" to "e.g. Leather Pants",
        "Boots" to "e.g. Leather Boots"
    )

    private val fields: List<Field> = fieldLabels.mapIndexed { i, (label, placeholder) ->
        Field(label, placeholder, TextInputHandler(
            textProvider = { fieldValues[i] },
            textSetter = { fieldValues[i] = it }
        ))
    }

    private val H = TITLE_H + PAD + fields.size * FIELD_H + BOTTOM_H

    fun openForNew() {
        editingIndex = null
        for (i in fieldValues.indices) fieldValues[i] = ""
        mc.setScreen(this)
    }

    /** Pre-fills every field from `CustomESP.targetProfiles[index]` and switches [save] into update-in-place mode. */
    fun openForEdit(index: Int) {
        val profile = CustomESP.targetProfiles.getOrNull(index) ?: return
        editingIndex = index
        fieldValues[IDX_LABEL] = profile.label
        fieldValues[IDX_ENTITY_TYPE] = profile.entityType
        fieldValues[IDX_NAME] = profile.name
        fieldValues[IDX_SKIN_ID] = profile.skinId
        fieldValues[IDX_HELD_ITEM] = profile.heldItem
        fieldValues[IDX_HELMET] = profile.helmet
        fieldValues[IDX_CHESTPLATE] = profile.chestplate
        fieldValues[IDX_LEGGINGS] = profile.leggings
        fieldValues[IDX_BOOTS] = profile.boots
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
            val titleText = if (editingIndex != null) "Edit Target Profile" else "Add Target Profile"
            NVGRenderer.text(titleText, cx + PAD + 16f, cy + (TITLE_H - FS_TITLE) / 2f, FS_TITLE, Colors.WHITE.rgba, NVGRenderer.defaultFont)

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

    // Escape deliberately does NOT close this screen (see the class doc) - only Cancel/× (see mouseClicked) do.
    override fun keyPressed(keyEvent: KeyEvent): Boolean {
        for (field in fields) if (field.input.keyPressed(keyEvent)) return true
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        super.onClose()
    }

    override fun isPauseScreen() = false

    private fun save() {
        val editIdx = editingIndex
        // Preserve the original's `enabled` state when editing (2026-08-16, on request - this form has no
        // enabled toggle of its own, that's TargetProfilesScreen's row checkbox; a save here shouldn't
        // silently re-enable a profile the user had deliberately turned off) - a brand new profile always
        // starts enabled, same as TargetProfile's own default.
        val wasEnabled = editIdx?.let { CustomESP.targetProfiles.getOrNull(it)?.enabled } ?: true
        val profile = TargetProfile(
            label = fieldValues[IDX_LABEL].trim(),
            enabled = wasEnabled,
            entityType = fieldValues[IDX_ENTITY_TYPE].trim(),
            name = fieldValues[IDX_NAME].trim(),
            skinId = fieldValues[IDX_SKIN_ID].trim(),
            heldItem = fieldValues[IDX_HELD_ITEM].trim(),
            helmet = fieldValues[IDX_HELMET].trim(),
            chestplate = fieldValues[IDX_CHESTPLATE].trim(),
            leggings = fieldValues[IDX_LEGGINGS].trim(),
            boots = fieldValues[IDX_BOOTS].trim()
        )
        if (!profile.isBlank) {
            if (editIdx != null && editIdx < CustomESP.targetProfiles.size) CustomESP.targetProfiles[editIdx] = profile
            else CustomESP.targetProfiles.add(profile)
            ModuleManager.saveConfigurations()
        }
        mc.setScreen(TargetProfilesScreen)
    }

    private fun cancel() {
        mc.setScreen(TargetProfilesScreen)
    }
}
