package de.hxp.hxpaddons.commands

import com.github.stivais.commodore.Commodore
import com.github.stivais.commodore.utils.GreedyString
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.ClickGUI
import de.hxp.hxpaddons.features.ModuleManager
import de.hxp.hxpaddons.features.impl.garden.PhantomLeafSolver
import de.hxp.hxpaddons.features.impl.skyblock.AutoFish
import de.hxp.hxpaddons.features.impl.skyblock.AutoLoadout
import de.hxp.hxpaddons.features.impl.skyblock.Fuser
import de.hxp.hxpaddons.features.impl.render.CustomESP
import de.hxp.hxpaddons.utils.handlers.schedule
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.formatNumber
import de.hxp.hxpaddons.utils.formatTime
import de.hxp.hxpaddons.utils.skyblock.recipe.CraftFlipScanner
import de.hxp.hxpaddons.utils.skyblock.recipe.RecipeLookup
import kotlinx.coroutines.launch

/** Every command lives under this one tree (`/hxp <category> <action> ...`) - [printHelp] lists them. */
val mainCommand = Commodore("hxpaddons", "hxp") {
    runs { schedule(0) { mc.setScreen(ClickGUI) } }

    literal("help") {
        runs { printHelp() }
    }

    literal("wip") {
        runs {
            ModuleManager.showWipModules = !ModuleManager.showWipModules
            if (ModuleManager.showWipModules) modMessage("§aWIP modules §2shown§a in the Click GUI.")
            else modMessage("§cWIP modules §4hidden§c in the Click GUI.")
        }
    }

    literal("fish") {
        runs { arg: GreedyString? ->
            if (arg?.string?.trim()?.equals("stop", ignoreCase = true) == true) {
                if (AutoFish.enabled) {
                    AutoFish.toggle()
                    modMessage("§cAuto Fish §4stopped§c.")
                } else {
                    modMessage("§eAuto Fish is not running.")
                }
            } else {
                AutoFish.toggle()
                if (AutoFish.enabled) modMessage("§aAuto Fish §2started§a.") else modMessage("§cAuto Fish §4stopped§c.")
            }
        }
    }

    literal("garden") {
        literal("reset") {
            runs {
                PhantomLeafSolver.softReset()
                modMessage("§aPhantom Leaf Solver reset.")
            }
        }
    }

    literal("loadout") {
        runs { n: Int -> AutoLoadout.equip(n) }
    }

    literal("fuse") {
        literal("run") {
            runs { args: GreedyString -> Fuser.start(args.string) }
        }
    }

    literal("esp") {
        literal("dump") {
            runs { CustomESP.dumpLookedAtEntity() }
        }
        literal("mob") {
            runs { CustomESP.dumpClosestMatchedMob() }
        }
    }

    literal("recipe") {
        runs { item: GreedyString ->
            val query = item.string
            if (query.isBlank()) {
                modMessage("§cUsage: /hxp recipe <item name>.")
                return@runs
            }
            HxPMod.scope.launch { RecipeLookup.lookupAndPrint(query) }
        }
    }

    literal("craftflip") {
        runs { runCraftFlip() }
    }
}

private fun printHelp() {
    modMessage("§6HxPUtils commands:")
    modMessage("§f/hxp §7- opens the Click GUI.")
    modMessage("§f/hxp recipe <item> §7- looks up an item's crafting recipe.")
    modMessage("§f/hxp craftflip §7- scans the Bazaar for profitable craft flips.")
    modMessage("§f/hxp fish [stop] §7- toggles Auto Fish.")
    modMessage("§f/hxp garden reset §7- resets the Phantom Leaf Solver.")
    modMessage("§f/hxp loadout <n> §7- equips loadout n via /loadout.")
    modMessage("§f/hxp fuse run <shard 1> [qty] <shard 2> [qty] <result> §7- runs a fuse via the Fusion NPC (qty defaults to 5 if omitted).")
    modMessage("§f/hxp esp dump §7- dumps every readable field for whatever entity you're looking at.")
    modMessage("§f/hxp esp mob §7- dumps the closest ESP'd entity's data in Target Profile field order (Entity Type, Name, Skin ID, Item Held, Helmet, Chestplate, Leggings, Boots).")
    modMessage("§f/hxp wip §7- toggles WIP modules in the Click GUI.")
}

private fun runCraftFlip() {
    if (CraftFlipScanner.scanning) {
        modMessage("§eA craft-flip scan is already running, please wait for it to finish.")
        return
    }
    modMessage("§7Scanning the Bazaar for craft flips - the first run can take a while (uncached recipes get fetched one by one), later runs are fast.")
    HxPMod.scope.launch {
        val lastUpdateMs = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        CraftFlipScanner.scan(onProgress = { done, total ->
            val now = System.currentTimeMillis()
            val prev = lastUpdateMs.get()
            if (now - prev >= 5000 && lastUpdateMs.compareAndSet(prev, now)) {
                modMessage("§8Craft-flip scan: $done/$total Bazaar products checked...")
            }
        }).fold(
            onSuccess = { flips ->
                if (flips.isEmpty()) {
                    modMessage("§eNo craft flips currently fit the budget/liquidity constraints.")
                } else {
                    modMessage("§aFound ${flips.size} viable craft flip(s), best first:")
                    flips.take(10).forEachIndexed { i, flip ->
                        val subCraftNote = if (flip.usesSubCrafting) " §7[uses sub-crafting]" else ""
                        modMessage(
                            "§6${i + 1}. §f${flip.name} §7- §a+${formatNumber(flip.totalProfit.toLong().toString())} coins " +
                                "§7(${flip.repeats}x, ${flip.itemsPerCraft * flip.repeats} items, spend ${formatNumber(flip.totalSpend.toLong().toString())}, ${flip.recipeType})$subCraftNote"
                        )
                        val invColor = if (flip.fitsInventory) "§a" else "§c"
                        modMessage(
                            "§8    $invColor${flip.neededSlots}/${flip.freeSlots} slots free §7- buy §f~${formatTime((flip.buyPhaseHours * 3_600_000).toLong())} " +
                                "§7+ sell §f~${formatTime((flip.sellPhaseHours * 3_600_000).toLong())} §7(§a${formatNumber(flip.profitPerHour.toLong().toString())}/h§7)"
                        )
                    }
                }
            },
            onFailure = { modMessage("§cCraft-flip scan failed: ${it.message}") }
        )
    }
}
