@file:Suppress("unused")

package de.hxp.hxpaddons.features

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.logger
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.HudManager
import de.hxp.hxpaddons.clickgui.settings.impl.HUDSetting
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.config.ModuleConfig
import de.hxp.hxpaddons.events.InputEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.ModuleManager.configs
import de.hxp.hxpaddons.features.impl.garden.GreenhouseTimer
import de.hxp.hxpaddons.features.impl.garden.PestESP
import de.hxp.hxpaddons.features.impl.garden.PhantomLeafSolver
import de.hxp.hxpaddons.features.impl.dungeon.AutoCloseChest
import de.hxp.hxpaddons.features.impl.dungeon.DungeonMap
import de.hxp.hxpaddons.features.impl.dungeon.PuzzleTriggerbot
import de.hxp.hxpaddons.features.impl.dungeon.SecretTriggerbot
import de.hxp.hxpaddons.features.impl.dungeon.StarMobESP
import de.hxp.hxpaddons.features.impl.dungeon.TerminalSolver
import de.hxp.hxpaddons.features.impl.dungeon.TerminalTriggerbot
import de.hxp.hxpaddons.features.impl.skyblock.AutoFish
import de.hxp.hxpaddons.features.impl.skyblock.AutoLoadout
import de.hxp.hxpaddons.features.impl.skyblock.BazaarFlipper
import de.hxp.hxpaddons.features.impl.skyblock.Combat
import de.hxp.hxpaddons.features.impl.skyblock.NoRotate
import de.hxp.hxpaddons.features.impl.skyblock.TermAutoClicker
import de.hxp.hxpaddons.features.impl.skyblock.Terminator
import de.hxp.hxpaddons.features.impl.general.AutoDialogue
import de.hxp.hxpaddons.features.impl.general.ChatFilter
import de.hxp.hxpaddons.features.impl.general.CompactChat
import de.hxp.hxpaddons.features.impl.general.CopyChat
import de.hxp.hxpaddons.features.impl.general.LobbyHopper
import de.hxp.hxpaddons.features.impl.general.ModIdHider
import de.hxp.hxpaddons.features.impl.general.SmoothWorldLoading
import de.hxp.hxpaddons.features.impl.mining.CrystalHollowsMap
import de.hxp.hxpaddons.features.impl.mining.CrystalHollowsStructureFinder
import de.hxp.hxpaddons.features.impl.mining.GoldenDragonFinder
import de.hxp.hxpaddons.features.impl.render.BorderlessFullscreen
import de.hxp.hxpaddons.features.impl.render.ClickGUIModule
import de.hxp.hxpaddons.features.impl.render.CustomESP
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.DeltaTracker
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.resources.Identifier.fromNamespaceAndPath
import java.io.File

/**
 * # Module Manager
 *
 * This object stores all [Modules][Module] and provides functionality to [HUDs][Module.HUD]
 */
object ModuleManager {

    /**
     * Map containing all modules,
     * where the key is the modules name in lowercase.
     */
    val modules: HashMap<String, Module> = linkedMapOf()

    /**
     * Map containing all modules under their category.
     */
    val modulesByCategory: HashMap<Category, ArrayList<Module>> = hashMapOf()

    /**
     * List of all configurations handled by this mod.
     */
    val configs: ArrayList<ModuleConfig> = arrayListOf()

    val keybindSettingsCache: ArrayList<KeybindSetting> = arrayListOf()
    val hudSettingsCache: ArrayList<HUDSetting> = arrayListOf()

    /**
     * Toggled via the `/hxp wip` command. While `false` (the default), modules annotated [de.hxp.hxpaddons.clickgui.settings.WipModule]
     * are skipped by [de.hxp.hxpaddons.clickgui.Panel] and stay invisible in the Click GUI, even though
     * they're still fully registered and functional. Deliberately not persisted - resets to hidden on
     * every restart.
     */
    var showWipModules = false

    private val HUD_LAYER: Identifier = fromNamespaceAndPath(HxPMod.MOD_ID, "hxpaddons_hud")

    init {
        registerModules(config = ModuleConfig(file = File(HxPMod.configFile, "hxpaddons-config.json")),
            ClickGUIModule,
            BorderlessFullscreen,
            AutoDialogue,
            ChatFilter,
            CompactChat,
            CopyChat,
            ModIdHider,
            SmoothWorldLoading,
            LobbyHopper,
            GreenhouseTimer,
            PhantomLeafSolver,
            PestESP,
            Terminator,
            TermAutoClicker,
            AutoFish,
            Combat,
            NoRotate,
            AutoLoadout,
            BazaarFlipper,
            SecretTriggerbot,
            AutoCloseChest,
            PuzzleTriggerbot,
            StarMobESP,
            CustomESP,
            TerminalSolver,
            TerminalTriggerbot,
            CrystalHollowsStructureFinder,
            GoldenDragonFinder,
            CrystalHollowsMap
        )

        // Its own config file (config/hxpaddons/addons/dungeon-map.json) instead of the shared
        // hxpaddons-config.json, so the map layout/color settings can be copied to another install (or
        // shared with someone else) on their own, without dragging every other module's settings along.
        // One-time carry-over of the "Dungeon Map" entry already sitting in the old shared file, so
        // existing users don't lose their configured layout/colors just from this split.
        migrateDungeonMapConfig()
        registerModules(config = ModuleConfig("dungeon-map.json"), DungeonMap)

        // hashmap, but would need to keep track when setting values change
        on<InputEvent> {
            for (setting in keybindSettingsCache) {
                if (setting.value.value == key.value) setting.onPress?.invoke()
            }
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, HUD_LAYER, ModuleManager::render)
    }

    /**
     * Copies the "Dungeon Map" entry out of the old shared `hxpaddons-config.json` into the new, separate
     * `addons/dungeon-map.json` the first time it runs (i.e. only while the new file doesn't exist yet) -
     * afterward the old entry is simply left stale in the shared file until that file's own next save,
     * which naturally drops it since [DungeonMap] is no longer part of that [ModuleConfig]'s modules.
     */
    private fun migrateDungeonMapConfig() {
        val newFile = File(HxPMod.configFile, "addons/dungeon-map.json")
        if (newFile.exists()) return
        val oldFile = File(HxPMod.configFile, "hxpaddons-config.json")
        if (!oldFile.exists()) return

        try {
            val root = JsonParser.parseString(oldFile.readText()).takeIf { it.isJsonArray }?.asJsonArray ?: return
            val entry = root.firstOrNull { it.isJsonObject && it.asJsonObject.get("name")?.asString.equals("Dungeon Map", ignoreCase = true) } ?: return

            newFile.parentFile.mkdirs()
            val gson = GsonBuilder().setPrettyPrinting().create()
            newFile.writeText(gson.toJson(JsonArray().apply { add(entry) }))
        } catch (e: Exception) {
            logger.error("Error migrating Dungeon Map config", e)
        }
    }

    /**
     * Registers modules to the [ModuleManager] and initializes them.
     *
     * @param config the config the [Module] is saved to,
     * it is recommended that each unique mod that uses this has its own config
     */
    fun registerModules(config: ModuleConfig, vararg modules: Module) {
        for (module in modules) {
            if (module.isDevModule && !FabricLoader.getInstance().isDevelopmentEnvironment) continue

            val lowercase = module.name.lowercase()
            config.modules[lowercase] = module
            this.modules[lowercase] = module
            this.modulesByCategory.getOrPut(module.category) { arrayListOf() }.add(module)

            module.key?.let { keybind ->
                val setting = KeybindSetting("Keybind", keybind, "Toggles this module.")
                setting.onPress = module::onKeybind
                module.registerSetting(setting)
            }

            for ((_, setting) in module.settings) {
                when (setting) {
                    is KeybindSetting -> keybindSettingsCache.add(setting)
                    is HUDSetting -> hudSettingsCache.add(setting)
                }
            }
        }
        configs.add(config)
        config.load()
    }

    /**
     * Loads all [configs] from disk, into the respective modules.
     */
    fun loadConfigurations() {
        for (config in configs) {
            config.load()
        }
    }

    /**
     * Saves all [configs] to disk, from the respective modules.
     */
    fun saveConfigurations() {
        for (config in configs) {
            config.save()
        }
    }

    fun render(guiGraphics: GuiGraphicsExtractor, tickCounter: DeltaTracker) {
        if (mc.level == null || mc.player == null || mc.screen == HudManager || mc.options.hideGui) return

        guiGraphics.pose().pushMatrix()
        val sf = mc.window.guiScale
        guiGraphics.pose().scale(1f / sf, 1f / sf)
        for (hudSettings in hudSettingsCache) {
            if (hudSettings.isEnabled) hudSettings.value.draw(guiGraphics, false)
        }
        guiGraphics.pose().popMatrix()
    }
}