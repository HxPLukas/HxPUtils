package de.hxp.hxpaddons

import de.hxp.hxpaddons.commands.*
import de.hxp.hxpaddons.events.EventDispatcher
import de.hxp.hxpaddons.events.core.EventBus
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.utils.IrisCompatability
import de.hxp.hxpaddons.utils.ServerUtils
import de.hxp.hxpaddons.utils.handlers.TickTasks
import de.hxp.hxpaddons.utils.render.ItemStateRenderer
import de.hxp.hxpaddons.utils.render.RenderBatchManager
import de.hxp.hxpaddons.utils.render.RoundRectPIPRenderer
import de.hxp.hxpaddons.utils.skyblock.*
import de.hxp.hxpaddons.utils.skyblock.dungeon.DoorScanUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonListener
import de.hxp.hxpaddons.utils.skyblock.dungeon.DungeonUtils
import de.hxp.hxpaddons.utils.skyblock.dungeon.MapColorScanner
import de.hxp.hxpaddons.utils.skyblock.dungeon.NearbyRoomScanner
import de.hxp.hxpaddons.utils.skyblock.dungeon.ScanUtils
import de.hxp.hxpaddons.utils.ui.rendering.NVGPIPRenderer
import de.hxp.hxpaddons.utils.ui.widget.CustomGUIImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.Version
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext

object HxPMod : ClientModInitializer {

    val logger: Logger = LogManager.getLogger("HxPAddons")

    @JvmStatic
    val mc: Minecraft = Minecraft.getInstance()

    /**
     * Main config file location.
     * @see de.hxp.hxpaddons.config.ModuleConfig
     * @see de.hxp.hxpaddons.config.DungeonWaypointConfig
     */
    val configFile: File = File(mc.gameDirectory, "config/hxpaddons/").apply {
        try {
            if (isFile) delete() // Delete old bugged files that prevent creating the directory
            if (!exists()) mkdirs()
        } catch (e: Exception) {
            println("Error initializing module config\n${e.message}")
            logger.error("Error initializing module config", e)
        }
    }

    const val MOD_ID = "hxpaddons"

    val version: Version by lazy { FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().metadata.version }
    val scope = CoroutineScope(SupervisorJob() + EmptyCoroutineContext)

    override fun onInitializeClient() {
        System.setProperty("java.awt.headless", "false")

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            mainCommand.register(dispatcher)
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register { ModuleManager.saveConfigurations() }

        listOf(
            this, LocationUtils, TickTasks, KuudraUtils,
            SkyblockPlayer, ServerUtils, EventDispatcher,
            DungeonListener, PartyUtils,
            ScanUtils, DungeonUtils, SplitsManager,
            IrisCompatability, RenderBatchManager,
            ModuleManager, CustomGUIImpl,
            NearbyRoomScanner, DoorScanUtils, MapColorScanner
        ).forEach { EventBus.subscribe(it) }

        PictureInPictureRendererRegistry.register { context ->
            NVGPIPRenderer(context.bufferSource())
        }

        PictureInPictureRendererRegistry.register { context ->
            RoundRectPIPRenderer(context.bufferSource())
        }

        PictureInPictureRendererRegistry.register { context ->
            ItemStateRenderer(context.bufferSource())
        }

    }
}