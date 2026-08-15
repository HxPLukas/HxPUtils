package de.hxp.hxpaddons.features.impl.garden

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.AlwaysActive
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.events.GuiEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.WorldEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.render.text
import de.hxp.hxpaddons.utils.skyblock.Island
import de.hxp.hxpaddons.utils.skyblock.LocationUtils
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import java.io.File
import java.util.regex.Pattern

@AlwaysActive
object GreenhouseTimer : Module(
    name = "Greenhouse Timer",
    description = "Shows a timer for greenhouse growth stages and tracks time away from Garden.(default time is maxed)",
    category = Category.GARDEN
) {
    // Settings — default is 1h 44m 20s
    val stageDurationHours by NumberSetting("Stage Duration Hours", default = 1, min = 0, max = 23, desc = "Hours part of one growth stage duration.")
    val stageDurationMinutes by NumberSetting("Stage Duration Minutes", default = 44, min = 0, max = 59, desc = "Minutes part of one growth stage duration.")
    val stageDurationSeconds by NumberSetting("Stage Duration Seconds", default = 20, min = 0, max = 59, desc = "Seconds part of one growth stage duration.")
    val showLastInGarden by BooleanSetting("Show Last in Garden", true, "Shows how many growth stages ago you were last in the Garden.")

    private val stageDurationMs: Long
        get() = (stageDurationHours * 3600L + stageDurationMinutes * 60L + stageDurationSeconds) * 1000L

    private var nextStageAt: Long = -1L
    private var hasNotified = true
    private var tickCounter = 0
    private var lastInGardenAt: Long = -1L
    private var gardenSaveCounter = 0

    private val gson = GsonBuilder().create()
    private val dataFile = File(HxPMod.configFile, "greenhouse-timer.json")

    private val nextStagePattern = Pattern.compile(
        "Next Stage: (?:(\\d+)h )?(?:(\\d+)m )?(?:(\\d+)s)?"
    )

    val hud = +HUD(
        name = "Greenhouse Timer",
        desc = "Shows next growth stage timer and how long you've been away from the Garden.",
        toggleable = true
    ) { example ->
        val lines = if (example) {
            listOf(
                "§6Next Growth Stage: §a23m 45s",
                "§7Garden Status: §aCurrently in Garden"
            )
        } else {
            listOfNotNull(buildTimerText(), if (showLastInGarden) buildGardenText() else null)
        }

        if (lines.isEmpty()) return@HUD 0 to 0

        val lineSpacing = mc.font.lineHeight + 2
        var y = 0
        var maxWidth = 0
        for (line in lines) {
            text(line, 0, y)
            maxWidth = maxOf(maxWidth, mc.font.width(line))
            y += lineSpacing
        }
        maxWidth to y
    }

    init {
        loadData()

        on<GuiEvent.SlotUpdate> {
            val containerScreen = screen as? AbstractContainerScreen<*> ?: return@on
            if (!containerScreen.title.string.contains("Crop Diagnostics")) return@on
            if (menu.slots.size <= 20) return@on
            val slot = menu.getSlot(20)
            if (!slot.hasItem()) return@on
            parseLore(slot.item)
        }

        on<TickEvent.End> {
            if (LocationUtils.isCurrentArea(Island.Garden)) {
                lastInGardenAt = System.currentTimeMillis()
                if (++gardenSaveCounter >= 1200) {
                    gardenSaveCounter = 0
                    saveData()
                }
            }

            if (++tickCounter < 20) return@on
            tickCounter = 0
            checkAndNotify()
        }

        on<WorldEvent.Unload> {
            saveData()
        }
    }

    private fun parseLore(stack: ItemStack) {
        val lore = stack.get(DataComponents.LORE) ?: return
        for (line in lore.lines()) {
            val matcher = nextStagePattern.matcher(line.string)
            if (!matcher.find()) continue

            val hours = matcher.group(1)?.toLongOrNull() ?: 0L
            val minutes = matcher.group(2)?.toLongOrNull() ?: 0L
            val seconds = matcher.group(3)?.toLongOrNull() ?: 0L
            val durationMs = (hours * 3600 + minutes * 60 + seconds) * 1000L

            if (durationMs > 0) {
                nextStageAt = System.currentTimeMillis() + durationMs
                hasNotified = false
                saveData()
            }
            return
        }
    }

    private fun checkAndNotify() {
        if (nextStageAt < 0) return
        val now = System.currentTimeMillis()
        if (now < nextStageAt) return

        if (!hasNotified) {
            modMessage("§aGreenhouse Growth Stage is ready!")
            hasNotified = true
        }

        while (nextStageAt <= now) {
            nextStageAt += stageDurationMs
        }
        hasNotified = false
        saveData()
    }

    private fun saveData() {
        try {
            dataFile.parentFile?.mkdirs()
            dataFile.createNewFile()
            val obj = JsonObject().apply {
                addProperty("nextStageAt", nextStageAt)
                addProperty("lastInGardenAt", lastInGardenAt)
            }
            dataFile.writeText(gson.toJson(obj))
        } catch (e: Exception) {
            HxPMod.logger.error("GreenhouseTimer: failed to save data", e)
        }
    }

    private fun loadData() {
        try {
            if (!dataFile.exists() || dataFile.readText().isBlank()) return
            val obj = JsonParser.parseString(dataFile.readText()).asJsonObject
            nextStageAt = obj.get("nextStageAt")?.asLong ?: -1L
            lastInGardenAt = obj.get("lastInGardenAt")?.asLong ?: -1L
            if (nextStageAt > 0) {
                val now = System.currentTimeMillis()
                while (nextStageAt <= now) {
                    nextStageAt += stageDurationMs
                }
            }
        } catch (e: Exception) {
            HxPMod.logger.error("GreenhouseTimer: failed to load data", e)
        }
    }

    private fun buildTimerText(): String {
        if (nextStageAt < 0) return "§6Next Growth Stage: §7Pls use Plant Diagnostic Tool"
        val remaining = nextStageAt - System.currentTimeMillis()
        if (remaining <= 0) return "§6Next Growth Stage: §cOVERDUE"
        val color = when {
            remaining > 10 * 60 * 1000L -> "§a"
            remaining > 5 * 60 * 1000L -> "§e"
            else -> "§c"
        }
        return "§6Next Growth Stage: $color${formatTime(remaining)}"
    }

    private fun buildGardenText(): String? {
        if (lastInGardenAt < 0) return null
        if (LocationUtils.isCurrentArea(Island.Garden)) return "§7Garden Status: §aCurrently in Garden"
        val stages = if (nextStageAt > 0) {
            ((nextStageAt - lastInGardenAt) / stageDurationMs).toInt().coerceAtLeast(0)
        } else {
            val elapsed = System.currentTimeMillis() - lastInGardenAt
            (elapsed / stageDurationMs).toInt()
        }
        return if (stages == 0) {
            "§7Last in Garden: §aLess than 1 stage ago"
        } else {
            val plural = if (stages == 1) "stage" else "stages"
            "§7Last in Garden: §e$stages §7growth $plural ago"
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }
    }
}
