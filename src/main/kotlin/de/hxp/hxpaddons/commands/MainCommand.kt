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
import de.hxp.hxpaddons.features.impl.skyblock.BazaarFlipper
import de.hxp.hxpaddons.features.impl.skyblock.FuseCheck
import de.hxp.hxpaddons.features.impl.skyblock.Fuser
import de.hxp.hxpaddons.utils.handlers.schedule
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.formatNumber
import de.hxp.hxpaddons.utils.formatTime
import de.hxp.hxpaddons.utils.skyblock.recipe.CraftFlipScanner
import de.hxp.hxpaddons.utils.skyblock.recipe.RecipeLookup
import de.hxp.hxpaddons.utils.skyblock.fusion.FuseIgnoreList
import de.hxp.hxpaddons.utils.skyblock.fusion.ShardFusionScanner
import kotlinx.coroutines.launch

/**
 * Every command lives under this one tree (`/hxp <category> <action> ...`) instead of as ~30 separate flat
 * commands - each category ends with its own `help` subcommand, [printHelp] lists the categories. All
 * "via NPC instead of `/bz`" variants (buy-collect, its continue, fuse-check, relist) use a trailing `npc`
 * word instead of a second command/suffix - see [splitTrailingNpc].
 */
val mainCommand = Commodore("hxpaddons", "hxp") {
    runs { schedule(0) { mc.setScreen(ClickGUI) } }

    literal("stop") {
        runs { stopAllFusingCommands() }
    }

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

    literal("bz") {
        runs { printBzHelp() }
        literal("help") { runs { printBzHelp() } }

        literal("flip") {
            runs { item: GreedyString -> BazaarFlipper.startManualFlip(item.string) }
        }

        literal("collect") {
            runs { spec: GreedyString ->
                val (itemSpec, viaNpc) = splitTrailingNpc(spec.string)
                BazaarFlipper.startBuyCollect(itemSpec, viaNpc)
            }
            literal("stop") {
                runs { BazaarFlipper.stopBuyCollect() }
            }
            literal("continue") {
                runs { spec: GreedyString ->
                    val (itemSpec, viaNpc) = splitTrailingNpc(spec.string)
                    BazaarFlipper.continueBuyCollect(itemSpec, viaNpc)
                }
            }
        }

        literal("hotkeys") {
            runs { spec: GreedyString -> BazaarFlipper.setHuntingBoxHotkeys(spec.string) }
        }

        literal("huntingbox") {
            runs { BazaarFlipper.startHuntingBoxCollect() }
        }

        literal("relist") {
            runs { BazaarFlipper.startRelistAll(viaNpc = false) }
            literal("npc") { runs { BazaarFlipper.startRelistAll(viaNpc = true) } }
        }

        literal("afk") {
            runs { arg: GreedyString? ->
                if (arg?.string?.trim()?.equals("stop", ignoreCase = true) == true) {
                    BazaarFlipper.stopFuseAfk()
                } else {
                    BazaarFlipper.toggleFuseAfk()
                }
            }
        }
    }

    literal("fuse") {
        runs { printFuseHelp() }
        literal("help") { runs { printFuseHelp() } }

        literal("run") {
            runs { args: GreedyString -> Fuser.start(args.string) }
        }
        literal("test") {
            runs { Fuser.startTest() }
        }
        literal("bz") {
            runs { BazaarFlipper.startShardFuse() }
        }

        literal("check") {
            runs { FuseCheck.start(viaNpc = false) }
            literal("npc") { runs { FuseCheck.start(viaNpc = true) } }
            literal("command") { runs { FuseCheck.start(viaNpc = false) } }
        }

        literal("best") {
            runs { runBestFuse() }
            literal("unique") { runs { count: Int? -> runBestFuseUnique(count) } }
            literal("parallel") { runs { count: Int? -> runBestFuseParallel(count) } }
            literal("budget") { runs { budgetMillions: Double, count: Int? -> runBestFuseBudget(budgetMillions, count) } }
        }

        literal("for") {
            runs { shard: GreedyString -> runFuseBestTargets(shard.string) }
        }

        literal("craft") {
            runs { spec: GreedyString -> runFuseCraft(spec.string) }
        }

        literal("ignore") {
            runs { name: GreedyString ->
                val shardName = name.string.trim()
                if (shardName.isEmpty()) {
                    modMessage("§cUsage: /hxp fuse ignore <shard name>")
                    return@runs
                }
                val alreadyIgnored = FuseIgnoreList.ignore(shardName)
                if (alreadyIgnored) modMessage("§a'$shardName' was already ignored - timer refreshed for another 10 minutes.")
                else modMessage("§a'$shardName' will be ignored in all /hxp fuse best* scans for the next 10 minutes.")
            }
            literal("clear") {
                runs {
                    val count = FuseIgnoreList.clear()
                    if (count == 0) modMessage("§7Fuse ignore list was already empty.")
                    else modMessage("§aCleared $count shard(s) from the fuse ignore list.")
                }
            }
        }
    }
}

/**
 * `/hxp stop` - stops every fusing-related thing that can be started via a command in this file: [Fuser]
 * (`/hxp fuse run`, `/hxp fuse test`), [FuseCheck] (`/hxp fuse check`), and everything
 * [BazaarFlipper.stopAllFusingActions] covers (`/hxp fuse bz`, `/hxp bz collect`, `/hxp bz huntingbox`,
 * `/hxp bz relist`, `/hxp bz afk`). Doesn't touch anything started another way - BazaarFlipper's always-on book
 * undercut watching, for example, keeps running - this is "stop what I told it to start", not a kill switch
 * for the whole mod.
 */
private fun stopAllFusingCommands() {
    val stopped = mutableListOf<String>()

    if (Fuser.enabled) {
        Fuser.toggle()
        stopped += "Fuser (/hxp fuse run)"
    }
    if (FuseCheck.enabled) {
        FuseCheck.toggle()
        stopped += "Fuse Check (/hxp fuse check)"
    }
    stopped += BazaarFlipper.stopAllFusingActions()

    if (stopped.isEmpty()) {
        modMessage("§eNothing fusing-related is currently running.")
    } else {
        modMessage("§cStopped: §f${stopped.joinToString(", ")}§c.")
    }
}

private fun printHelp() {
    modMessage("§6HxPAddons commands:")
    modMessage("§f/hxp §7- opens the Click GUI.")
    modMessage("§f/hxp stop §7- stops everything fusing-related that was started via a command.")
    modMessage("§f/hxp bz help §7- Bazaar Flipper commands (manual flips, buy-collect, hunting box, relisting, AFK loop).")
    modMessage("§f/hxp fuse help §7- shard fusion commands (running fuses, fuse-check, best-fusion scans, ignore list).")
    modMessage("§f/hxp recipe <item> §7- looks up an item's crafting recipe.")
    modMessage("§f/hxp craftflip §7- scans the Bazaar for profitable craft flips.")
    modMessage("§f/hxp fish [stop] §7- toggles Auto Fish.")
    modMessage("§f/hxp garden reset §7- resets the Phantom Leaf Solver.")
    modMessage("§f/hxp loadout <n> §7- equips loadout n via /loadout.")
    modMessage("§f/hxp wip §7- toggles WIP modules in the Click GUI.")
}

private fun printBzHelp() {
    modMessage("§6HxPAddons Bazaar Flipper commands:")
    modMessage("§f/hxp bz flip <item> §7- starts a manual flip for the given item.")
    modMessage("§f/hxp bz collect <item> <amount>, ... [npc] §7- buys and collects the given spec, optionally via the Bazaar NPC.")
    modMessage("§f/hxp bz collect stop §7- stops the current buy-collect.")
    modMessage("§f/hxp bz collect continue <item>, ... [npc] §7- resumes an already-listed buy-collect.")
    modMessage("§f/hxp bz hotkeys <keys> §7- sets the Hunting Box hotbar hotkeys.")
    modMessage("§f/hxp bz huntingbox §7- moves every shard in the inventory into the Hunting Box.")
    modMessage("§f/hxp bz relist [npc] §7- cancels and relists every open Bazaar order.")
    modMessage("§f/hxp bz afk [stop] §7- toggles the 5-minute claim/collect/relist AFK loop.")
}

private fun printFuseHelp() {
    modMessage("§6HxPAddons Fuse commands:")
    modMessage("§f/hxp fuse run <shard 1> <shard 2> <result> §7- runs a fuse via the Fusion NPC.")
    modMessage("§f/hxp fuse test §7- sanity-checks the fuse NPC menu detection.")
    modMessage("§f/hxp fuse bz §7- auto-buys and fuses the current best shard fusion.")
    modMessage("§f/hxp fuse check [npc|command] §7- checks live fuse recipe prices via the Bazaar.")
    modMessage("§f/hxp fuse best §7- scans for the best shard fusions overall.")
    modMessage("§f/hxp fuse best unique [count] §7- best fusion per unique output shard.")
    modMessage("§f/hxp fuse best parallel [count] §7- best fusions with no repeated input/output shards.")
    modMessage("§f/hxp fuse best budget <millions> [count] §7- best fusions sized to a coin budget.")
    modMessage("§f/hxp fuse for <shard> §7- best fusions using a given shard as an input.")
    modMessage("§f/hxp fuse craft <shard> [count] §7- best fusions that produce a given shard.")
    modMessage("§f/hxp fuse ignore <shard> §7- temporarily blacklists a shard from fusion scans.")
    modMessage("§f/hxp fuse ignore clear §7- clears the fusion ignore list.")
}

/**
 * Splits a trailing `npc` word off a spec argument - `/hxp bz collect 5 diamond npc` runs via the Bazaar NPC,
 * `/hxp bz collect 5 diamond` runs via `/bz` - same trailing-token approach [runFuseCraft] already uses for its
 * optional count, since Commodore's `GreedyString` eats the rest of the command line and has no separate
 * "flag" syntax.
 */
private fun splitTrailingNpc(raw: String): Pair<String, Boolean> {
    val trimmed = raw.trim()
    val tokens = trimmed.split(Regex("\\s+"))
    return if (tokens.size > 1 && tokens.last().equals("npc", ignoreCase = true)) {
        tokens.dropLast(1).joinToString(" ") to true
    } else {
        trimmed to false
    }
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

/** Shared by every `/hxp fuse best*`/`for`/`craft` result line - identical per-result chat/dev-log formatting either way. */
private fun printFusionResults(commandTag: String, fusions: List<ShardFusionScanner.ShardFusion>) {
    fusions.forEachIndexed { i, f ->
        modMessage(
            "§6${i + 1}. §f${f.input1Name} §7+ §f${f.input2Name} §7-> §f${f.outputQty}x ${f.outputName} " +
                "§7(§d${f.outputRarity}§7) §a+${formatNumber(f.profitPerHour.toLong().toString())}/h"
        )
        modMessage(
            "§8    ${f.input1Qty}x ${f.input1Name} + ${f.input2Qty}x ${f.input2Name} " +
                "§7- cost ${formatNumber(f.costPerFuse.toLong().toString())}, profit ${formatNumber(f.profitPerFuse.toLong().toString())}/fuse " +
                "(${String.format("%.1f", f.roiPercent)}%), ~${String.format("%.1f", f.fusesPerHour)} fuses/h"
        )
        modMessage(getVolumeLine(f))
        devMessage(
            "[$commandTag] #${i + 1} price sources - " +
                "${f.input1Name}: ${formatNumber(f.input1Price.toLong().toString())} (${f.input1Source}), " +
                "${f.input2Name}: ${formatNumber(f.input2Price.toLong().toString())} (${f.input2Source}), " +
                "${f.outputName}: ${formatNumber(f.outputPrice.toLong().toString())} (${f.outputSource})"
        )
    }
}

/**
 * "§8    Market: ~N/h X bought, ~N/h Y bought, ~N/h Z sold" - each leg's OWN raw hourly Bazaar volume
 * individually (2026-08-13, on request - "wie viele von allen gekauft/verkauft werden ... für jeden
 * individuell"), as opposed to [ShardFusionScanner.ShardFusion.fusesPerHour] which is already the min across
 * all three legs (divided by each leg's own per-fuse quantity) and therefore hides which specific leg is
 * actually the bottleneck. Shared by every `/hxp fuse best*` result line via [printFusionResults] and by
 * `/hxp fuse best budget` via [printBudgetSizedResult].
 */
private fun getVolumeLine(f: ShardFusionScanner.ShardFusion): String {
    return "§8    Market: ~${formatNumber(f.input1RatePerHour.toLong().toString())}/h ${f.input1Name} bought, " +
        "~${formatNumber(f.input2RatePerHour.toLong().toString())}/h ${f.input2Name} bought, " +
        "~${formatNumber(f.outputRatePerHour.toLong().toString())}/h ${f.outputName} sold"
}

private fun runBestFuse() {
    if (ShardFusionScanner.scanning) {
        modMessage("§eA shard-fusion scan is already running, please wait for it to finish.")
        return
    }
    modMessage("§7Scanning all known shard fusions for the best coins/hour...")
    HxPMod.scope.launch {
        ShardFusionScanner.scan().fold(
            onSuccess = { fusions ->
                if (fusions.isEmpty()) {
                    modMessage("§eNo profitable shard fusions currently fit the liquidity/ROI constraints.")
                } else {
                    modMessage("§aFound ${fusions.size} viable shard fusion(s), best first:")
                    printFusionResults("hxp fuse best", fusions.take(10))
                    modMessage("§7Run one via §f/hxp fuse run ${fusions.first().input1Name} | ${fusions.first().input2Name} | ${fusions.first().outputName}")
                }
            },
            onFailure = { modMessage("§cShard-fusion scan failed: ${it.message}") }
        )
    }
}

/**
 * `/hxp fuse best unique [count]` - same ranking as [runBestFuse] but collapsed to each output shard's single
 * best recipe first (see [ShardFusionScanner.scanBestPerOutput]'s own doc) - on request, so the top N show N
 * different fusion *targets* instead of e.g. "2x Newt" repeated with 8 different cheap second ingredients.
 * `count` defaults to 5 when omitted and is clamped to [1, 25].
 */
private fun runBestFuseUnique(count: Int?) {
    if (ShardFusionScanner.scanning) {
        modMessage("§eA shard-fusion scan is already running, please wait for it to finish.")
        return
    }
    val limit = (count ?: 5).coerceIn(1, 25)
    modMessage("§7Scanning all known shard fusions for the best coins/hour (unique outputs only, top $limit)...")
    HxPMod.scope.launch {
        ShardFusionScanner.scanBestPerOutput(limit).fold(
            onSuccess = { fusions ->
                if (fusions.isEmpty()) {
                    modMessage("§eNo profitable shard fusions currently fit the liquidity/ROI constraints.")
                } else {
                    modMessage("§aFound ${fusions.size} viable unique-output shard fusion(s), best first:")
                    printFusionResults("hxp fuse best unique", fusions)
                    modMessage("§7Run one via §f/hxp fuse run ${fusions.first().input1Name} | ${fusions.first().input2Name} | ${fusions.first().outputName}")
                }
            },
            onFailure = { modMessage("§cShard-fusion scan failed: ${it.message}") }
        )
    }
}

/**
 * `/hxp fuse best parallel [count]` - like [runBestFuseUnique] but additionally guarantees no input shard
 * repeats across the whole returned list either (see [ShardFusionScanner.scanUniqueInputsAndOutputs]'s own
 * doc) - on request, so the resulting set can be buy-ordered/fused in parallel without two picks competing for
 * the same shard's liquidity/budget. `count` defaults to 3 (the number asked for) when omitted, clamped to
 * [1, 25].
 */
private fun runBestFuseParallel(count: Int?) {
    if (ShardFusionScanner.scanning) {
        modMessage("§eA shard-fusion scan is already running, please wait for it to finish.")
        return
    }
    val limit = (count ?: 3).coerceIn(1, 25)
    modMessage("§7Scanning all known shard fusions for the best coins/hour (unique outputs AND inputs, top $limit)...")
    HxPMod.scope.launch {
        ShardFusionScanner.scanUniqueInputsAndOutputs(limit).fold(
            onSuccess = { fusions ->
                if (fusions.isEmpty()) {
                    modMessage("§eNo profitable shard fusions currently fit the liquidity/ROI constraints.")
                } else {
                    modMessage("§aFound ${fusions.size} shard-independent fusion(s) safe to run in parallel, best first:")
                    printFusionResults("hxp fuse best parallel", fusions)
                }
            },
            onFailure = { modMessage("§cShard-fusion scan failed: ${it.message}") }
        )
    }
}

/**
 * `/hxp fuse best budget <budgetMillions> [count]` - ranks fusions by the actual total profit achievable
 * within a real budget + fill-time constraint, not by ROI% or raw coins/hour (2026-08-13, on request - "nicht
 * der beste return on invest ist sondern der beste profit für das geld in 7 std order"). See
 * [ShardFusionScanner.scanBestByBudgetProfit]'s own doc for why ROI% alone is misleading here and the exact
 * sizing formula.
 *
 * `budgetMillions`: max total coins to spend on ONE result's pair of Buy Orders combined, given in millions
 * (`5` = 5,000,000 - "die coins sollen in m angegeben werden"). Fill-time cap is fixed at [MAX_FILL_HOURS].
 */
private fun runBestFuseBudget(budgetMillions: Double, count: Int?) {
    if (ShardFusionScanner.scanning) {
        modMessage("§eA shard-fusion scan is already running, please wait for it to finish.")
        return
    }
    if (budgetMillions <= 0.0) {
        modMessage("§cBudget must be a positive number of millions, e.g. /hxp fuse best budget 5 for 5m.")
        return
    }
    val budget = budgetMillions * 1_000_000.0
    val limit = (count ?: 5).coerceIn(1, 25)
    modMessage("§7Scanning all known shard fusions for the best profit within budget ${formatNumber(budget.toLong().toString())} (avg fill ≤${MAX_FILL_HOURS.toInt()}h, top $limit)...")
    HxPMod.scope.launch {
        ShardFusionScanner.scanBestByBudgetProfit(budget, MAX_FILL_HOURS, limit).fold(
            onSuccess = { sized ->
                if (sized.isEmpty()) {
                    modMessage("§eNo shard fusion fits both the budget and the ${MAX_FILL_HOURS.toInt()}h fill-time cap for even 1 fuse-set.")
                } else {
                    modMessage("§aFound ${sized.size} shard fusion(s) sized to budget, best profit first:")
                    sized.forEachIndexed { i, s -> printBudgetSizedResult(i, s) }
                }
            },
            onFailure = { modMessage("§cShard-fusion scan failed: ${it.message}") }
        )
    }
}

/** Max average hours a sized order in [runBestFuseBudget] is allowed to take to fill, per input shard's own historical rate. */
private const val MAX_FILL_HOURS = 7.0

/** Prints one already-sized `/hxp fuse best budget` result. */
private fun printBudgetSizedResult(index: Int, s: ShardFusionScanner.SizedFusion) {
    val f = s.fusion
    modMessage(
        "§6${index + 1}. §f${f.input1Name} §7+ §f${f.input2Name} §7-> §f${f.outputQty}x ${f.outputName} " +
            "§7(§d${f.outputRarity}§7) §a+${formatNumber(s.expectedProfit.toLong().toString())} §7profit (${String.format("%.1f", f.roiPercent)}% ROI)"
    )
    modMessage(
        "§8    Buy Order ${s.amount1}x ${f.input1Name} + ${s.amount2}x ${f.input2Name} " +
            "§7- spend ${formatNumber(s.spend.toLong().toString())} §7(bound by ${s.boundBy})"
    )
    modMessage(getVolumeLine(f))
}

/**
 * `/hxp fuse for <shard name>` - top 10 fusions that use the given shard as one of the two inputs, ranked by
 * coins/hour (see [ShardFusionScanner.scanBestTargetsFor]'s own doc) - on request, "die mir die top 10 besten
 * shards anzeigt in die ich den shard fusen kann" (answers "I have/can get shard X, what's the best thing to
 * fuse it into?" rather than the other `/hxp fuse best*` commands' "what's the best fusion overall?").
 */
private fun runFuseBestTargets(rawShard: String) {
    val shardName = rawShard.trim()
    if (shardName.isEmpty()) {
        modMessage("§cUsage: /hxp fuse for <shard name>")
        return
    }
    if (ShardFusionScanner.scanning) {
        modMessage("§eA shard-fusion scan is already running, please wait for it to finish.")
        return
    }
    modMessage("§7Scanning all known shard fusions using '$shardName' as an ingredient...")
    HxPMod.scope.launch {
        ShardFusionScanner.scanBestTargetsFor(shardName).fold(
            onSuccess = { fusions ->
                if (fusions.isEmpty()) {
                    modMessage("§eNo viable fusion uses '$shardName' as an ingredient right now (check the spelling, or it just isn't profitable/liquid currently).")
                } else {
                    modMessage("§aTop ${fusions.size} fusion target(s) using '$shardName', best first:")
                    printFusionResults("hxp fuse for", fusions)
                }
            },
            onFailure = { modMessage("§cShard-fusion scan failed: ${it.message}") }
        )
    }
}

/**
 * `/hxp fuse craft <shard name> [count]` - top fusion recipes that PRODUCE the given shard as their OUTPUT,
 * ranked by coins/hour (see [ShardFusionScanner.scanBestRecipesFor]'s own doc) - the reverse of
 * [runFuseBestTargets]. Added on request alongside a clickable "[Fuse options?]" link on every `BazaarFlipper`
 * "Find Best Flip" result line ([de.hxp.hxpaddons.features.impl.skyblock.BazaarFlipper.bestFlipCandidateLine])
 * that runs this exact command - since you already know a "Find Best Flip" item sells well, this answers
 * whether buy-ordering two input shards and fusing them beats buying/Buy-Ordering the item itself outright.
 *
 * Commodore has no "GreedyString then optional trailing arg" form (a `GreedyString` eats the rest of the
 * command line), so the optional trailing count is parsed out of the raw string here instead: if the last
 * whitespace-separated token parses as an `Int`, it's the count and everything before it is the shard name;
 * otherwise the whole string is the shard name and count defaults to 5 (same default/clamp as
 * [runBestFuseUnique]). `/hxp fuse craft Stoneworm` -> top 5. `/hxp fuse craft Stoneworm 10` -> top 10.
 */
private fun runFuseCraft(rawSpec: String) {
    val raw = rawSpec.trim()
    if (raw.isEmpty()) {
        modMessage("§cUsage: /hxp fuse craft <shard name> [count]")
        return
    }
    val tokens = raw.split(Regex("\\s+"))
    val trailingCount = tokens.last().toIntOrNull()
    val shardName = if (trailingCount != null && tokens.size > 1) tokens.dropLast(1).joinToString(" ") else raw
    if (shardName.isBlank()) {
        modMessage("§cUsage: /hxp fuse craft <shard name> [count]")
        return
    }
    if (ShardFusionScanner.scanning) {
        modMessage("§eA shard-fusion scan is already running, please wait for it to finish.")
        return
    }
    val limit = (trailingCount ?: 5).coerceIn(1, 25)
    modMessage("§7Scanning all known shard fusions that produce '$shardName'...")
    HxPMod.scope.launch {
        ShardFusionScanner.scanBestRecipesFor(shardName, limit).fold(
            onSuccess = { fusions ->
                if (fusions.isEmpty()) {
                    modMessage("§eNo viable fusion currently produces '$shardName' (check the spelling, or it just isn't profitable/liquid right now).")
                } else {
                    modMessage("§aTop ${fusions.size} way(s) to fuse '$shardName', best first:")
                    printFusionResults("hxp fuse craft", fusions)
                }
            },
            onFailure = { modMessage("§cShard-fusion scan failed: ${it.message}") }
        )
    }
}
