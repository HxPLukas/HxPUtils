package de.hxp.hxpaddons.features.impl.skyblock

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.clickgui.settings.Setting.Companion.withDependency
import de.hxp.hxpaddons.clickgui.settings.impl.BooleanSetting
import de.hxp.hxpaddons.clickgui.settings.impl.ColorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.KeybindSetting
import de.hxp.hxpaddons.clickgui.settings.impl.NumberSetting
import de.hxp.hxpaddons.clickgui.settings.impl.SelectorSetting
import de.hxp.hxpaddons.clickgui.settings.impl.StringSetting
import de.hxp.hxpaddons.events.ChatPacketEvent
import de.hxp.hxpaddons.events.RenderItemDecorationsEvent
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.mixin.accessors.AbstractSignEditScreenAccessor
import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.clickSlot
import de.hxp.hxpaddons.utils.devMessage
import de.hxp.hxpaddons.utils.loreString
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.playSoundAtPlayer
import de.hxp.hxpaddons.utils.readPurseBalance
import de.hxp.hxpaddons.utils.romanToInt
import de.hxp.hxpaddons.utils.setTitle
import de.hxp.hxpaddons.utils.simulateKeyPressChar
import de.hxp.hxpaddons.utils.simulateRightClickWithShiftHeld
import de.hxp.hxpaddons.utils.simulateScroll
import de.hxp.hxpaddons.utils.network.hypixelapi.BazaarApiData
import de.hxp.hxpaddons.utils.network.hypixelapi.RequestUtils
import de.hxp.hxpaddons.utils.render.text
import de.hxp.hxpaddons.utils.sendChatMessage
import de.hxp.hxpaddons.utils.sendCommand
import de.hxp.hxpaddons.utils.skyblock.fusion.ShardFusionScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.game.ServerboundInteractPacket
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * Auto Bazaar Flipper.
 *
 * Hypixel announces a filled order in chat the moment it happens - e.g. "[Bazaar] Your Buy Order for
 * 64x Wild Rose was filled!" - regardless of what screen (if any) is currently open, so that's the
 * sole trigger for detection (matched fills are queued, then processed once the player isn't in any
 * GUI). To actually flip it: open Manage Orders, right-click the filled order (which claims it and
 * leads into Hypixel's own "Flip Order" follow-up showing the current best orders for that item),
 * read the lowest price shown there, and place the new opposite order 1 coin past it.
 *
 * Every screen this touches is dumped (item names + lore) via [devMessage], which - per the existing
 * [de.hxp.hxpaddons.utils.network.DiscordLogger] wiring - also mirrors to the configured debug webhook
 * whenever one is set. This is reverse engineered against the live Hypixel Bazaar GUI rather than a
 * documented API, so those dumps are the fastest way to correct any wrong keyword/step: turn on Dev
 * Messages (`/hxpdev`) and a debug webhook, run one flip, and send back what got logged.
 *
 * "Books Combined" (opt-in, see [combineBooks]) is a special case for filled Buy Orders on a level 1
 * enchant book (item name ending in "I"/"1"): instead of flipping, the filled order is claimed with a
 * plain left-click (not the right-click used for [flipOrder]'s claim, which would open "Flip Order"),
 * then the books are combined pairwise in the anvil (`/av`) up to level 5 - exactly like combining two
 * matching enchant books/tools on a real anvil: two level N books consume into one level N+1 book. Any
 * book left unpaired by an odd count at some level is sold at that level instead of being discarded, and
 * the final (up to) level 5 stack is listed as a new Sell Offer via the same search-based flow
 * [manualCreateOrder] already uses (see [createBookSellOrder]/[placeOrderViaSearch]). Confirmed live: the
 * `/av` anvil is a custom Hypixel GUI (not a true vanilla anvil), with "Item to Upgrade"/"Item to
 * Sacrifice" labelled slots instead of vanilla's fixed 0/1/2 - see [combinePairsInAnvil] for the
 * label-search-based click sequence and how it detects (and reverts) an unrecognized combine outcome
 * rather than guessing further. Every combine step is dumped via [devMessage] same as the rest of this
 * module.
 */
object BazaarFlipper : Module(
    name = "Auto Bazaar Flipper",
    description = "Claims filled Bazaar orders and automatically re-lists them as the opposite order.",
    category = Category.SKYBLOCK
) {
    // Net +100ms across the board vs. the original baseline (was bumped +800, backed off -400, now -300 more)
    // - lowered again on request but never below the 400ms floor every click in this module is guaranteed to
    // respect regardless (see [randomDelay]'s own doc on why that floor exists).
    private const val GUI_APPEAR_DELAY = 500L

    /** [waitForGuiUpdate]'s settle time after detecting a genuine content change - same value/reasoning as [Fuser]'s `GUI_UPDATE_SETTLE_MS`, per explicit request. */
    private const val GUI_UPDATE_SETTLE_MS = 200L

    /** [depositHotbarIntoHuntingBox]'s pause between a simulated hotbar-slot key press and its shift-right-click, and again before moving to the next slot - no GUI transition to wait for here, just enough for the client to register the slot switch/click. */
    private const val HOTBAR_DEPOSIT_STEP_DELAY = 250L

    /** [depositHotbarIntoHuntingBox]'s bound on how long to wait for a screen to appear after a shift-right-click before giving up and moving to the next slot (the click may or may not open anything). */
    private const val HOTBAR_DEPOSIT_GUI_WAIT_MS = 2000L

    /** `/hxp bz huntingbox`'s pause between a hotbar-slot key press and its shift-right-click, and again before moving to the next slot - separate from [HOTBAR_DEPOSIT_STEP_DELAY] since this was asked for as its own explicit ~400ms ("delay von 400 oder so"), not tied to that other command's own tuning history. */
    private const val HXPHB_STEP_DELAY = 400L

    /** `/hxp bz huntingbox`'s wait for [huntingBoxDepositRegex] to confirm one shift-right-click before retrying the same slot - on request ("wenn nicht soll er nach 200 immer wieder retryen"). */
    private const val HXPHB_RETRY_DELAY = 200L

    /** `/hxp bz huntingbox`'s cap on retries for a single hotbar slot before giving up on it - 10 * [HXPHB_RETRY_DELAY] = 2s total per slot, on request ("dann alle 200 bis zu 2 sekunden lang"). An empty slot never produces a confirmation, so this bounds how long that's tolerated before moving on. */
    private const val HXPHB_MAX_RETRIES_PER_SLOT = 10

    /** `/hxp bz huntingbox`'s pause between each SWAP click while moving shard stacks into the hotbar - on request ("zwischen jeden switch in die hotbar 300ms wartet"), separate from [HXPHB_STEP_DELAY] (that one paces the deposit step's key-press-then-click, this one paces the move-into-hotbar step itself). */
    private const val HXPHB_SWAP_STEP_DELAY = 300L

    /** [de.hxp.hxpaddons.utils.simulateScroll] amount `/hxp bz huntingbox`'s deposit step uses to advance from the first hotkeyed hotbar slot to each following one - see that function's own doc for why the sign is an unconfirmed guess. */
    private const val HXPHB_SCROLL_DIRECTION = 1

    /** `/hxp bz huntingbox`'s cap on move-into-hotbar+deposit passes (at most [HXPHB_HOTBAR_SLOTS] shard stacks fit per pass) - a safety net, not a realistic limit (160 shard stacks). */
    private const val HXPHB_MAX_PASSES = 20

    /** `/hxp bz huntingbox`/[depositHotbarIntoHuntingBox]'s usable hotbar slot count - slot 9 (the Nether Star) is never touched, same convention [huntingBoxHotkeys] already documents. */
    private const val HXPHB_HOTBAR_SLOTS = 8

    /**
     * Hypixel's own confirmation for a shift-right-click landing in the Hunting Box, confirmed live from
     * `26.1.2(1)`'s own chat log (`latest.log`, 2026-08-13): "§7You sent §a38 §aPolaris Shards §7to your
     * §aHunting Box§7." for a stack, or "§7You sent an §6Apex Dragon Shard §7to your §aHunting Box§7." for a
     * single item (note "an" instead of a count) - matched here against [ChatPacketEvent.value], which is
     * already stripped of color codes by the time it reaches any listener.
     */
    private val huntingBoxDepositRegex = Regex("You sent .+ to your Hunting Box\\.")

    /** Set by the `on<ChatPacketEvent>` block below whenever [huntingBoxDepositRegex] matches - polled by [depositHotbarWithConfirmation] to confirm a shift-right-click actually landed before moving to the next slot. */
    @Volatile
    private var lastHuntingBoxDepositAtMs: Long = 0L

    // The sign-edit GUI (see [flipOrder]) gets more slack than a regular container screen transition -
    // it's a different, heavier screen type to open, and both the packet submit and the confirm click
    // that follow it are the two steps that actually place the new order, so a race there is far more
    // costly (a malformed/premature order) than elsewhere in this flow.
    // Confirmed live: lowering these by the requested -300 would put SIGN_APPEAR_DELAY/SIGN_CONFIRM_DELAY at
    // 300ms and leave SIGN_TYPE_DELAY needing to go to 100ms - both under the 400ms floor established earlier
    // this session (randomDelay's jitter is additive-only specifically so no click ever fires under 400ms
    // after a GUI loads; going below the base itself would defeat that). Clamped at 400 instead of the exact
    // -300 everywhere it would otherwise land under that.
    private const val SIGN_APPEAR_DELAY = 400L
    private const val SIGN_TYPE_DELAY = 400L
    private const val SIGN_CONFIRM_DELAY = 400L

    // Anvil GUI never needs the sign's extra slack - it's a plain container like Manage Orders, just with
    // fewer, simpler slots (2 inputs + 1 result) to click through per combine.
    private const val ANVIL_APPEAR_DELAY = 500L
    private const val COMBINE_STEP_DELAY = 450L

    // Cancelling an order matters as much as the sign-price confirm does - a race here risks the cancel
    // not actually going through before the next step reads the (still stale) screen, so it gets its own,
    // longer wait rather than sharing GUI_APPEAR_DELAY with routine screen transitions. Raised specifically
    // on request so a Sell Offer cancel's "Order options" screen has more time to sit open/settle before the
    // next click, rather than sharing the same value cancels were just lowered to. Raised +400 again on
    // request, for the cancel-confirm button specifically and to generally leave the cancel GUI open longer.
    private const val CANCEL_CONFIRM_DELAY = 1900L
    // On request: a beat before the very first click in cancelAllOrders' whole sequence (clicking the order
    // itself to open "Order options") - previously fired the instant the slot was found, with no settle time
    // at all after Manage Orders itself just finished loading/scanning.
    private const val CANCEL_FIRST_CLICK_DELAY = 400L

    // Confirmed live: the whole order-creation flow ([placeOrderViaSearch] - search -> item page -> create
    // -> amount -> price -> place) failed under the shared GUI_APPEAR_DELAY/SIGN_CONFIRM_DELAY often enough,
    // both leading up to and following the "Create Buy Order"/"Create Sell Offer" click itself, to warrant
    // dedicated, longer waits throughout rather than just at that one click - same reasoning as
    // [CANCEL_CONFIRM_DELAY]. Each is its ordinary counterpart + 300ms. The "create" click's own transition
    // no longer just waits a fixed delay at all - see
    // its call site in [placeOrderViaSearch], which now polls (with a click retry) instead, since a wrong or
    // dropped click doesn't get fixed by waiting longer for it.
    private const val CREATE_ORDER_GUI_DELAY = GUI_APPEAR_DELAY + 300L
    private const val CREATE_ORDER_SIGN_CONFIRM_DELAY = SIGN_CONFIRM_DELAY + 300L

    private const val UNDERCUT_CHECK_INTERVAL = 10_000L
    private const val MAX_EXTRA_CLAIM_ATTEMPTS = 3
    private const val BAZAAR_NPC_NAME = "Bazaar"
    private const val BAZAAR_NPC_SEARCH_RADIUS = 3.0
    private const val BAZAAR_NPC_INTERACT_RANGE = 3.0
    // Confirmed live: Hypixel's actual per-order Buy Order cap depends on the item - 256 for an enchant book
    // (unstackable), but far higher for a normal stackable item. [maxOrderCapFor] picks the right one.
    private const val MAX_BUY_ORDER_AMOUNT = 256
    private const val MAX_BUY_ORDER_AMOUNT_GENERAL = 71_000
    private const val BOOKS_PER_LEVEL_5 = 16 // 2^4: level 1 -> 5 is four halvings
    private const val SELL_BATCH_THRESHOLD = 5 // sell+re-buy in batches of this many level 5 books instead of waiting for the whole run to finish
    private const val IDLE_PARTIAL_CLAIM_INTERVAL = 15_000L
    private const val INSUFFICIENT_FUNDS_RECHECK_INTERVAL = 10_000L
    private const val MAX_AUTO_REBUY_FAILURES = 5
    // On request: an outbid Buy Order's relist ([handleOutbid]) always spends this share of the current
    // purse on the fresh order, rather than just matching the old order's own (possibly outdated) amount -
    // separate from [maxOrderPursePercent], which sizes a *first* Buy Order to leave room for the Sell
    // Offer it'll later flip into. A relist isn't starting a new flip pair, it's just keeping the existing
    // buying side going, so it's fine to spend much more aggressively.
    private const val OUTBID_REBUY_PURSE_PERCENT = 90.0

    // "Best Flip" scoring (see [findBestFlips]) - standard Hypixel Bazaar tax taken off Sell Offer proceeds,
    // and two sanity filters: a liquidity floor (a handful of trades/hour is a stale/troll order sitting in
    // a thin book, not a real repeatable flip) and a max ROI (a spread that huge on a single flip is the
    // same kind of data artifact, not a legitimate ongoing arbitrage).
    private const val BAZAAR_TAX = 0.0125
    private const val HOURS_PER_WEEK = 168.0
    private const val BEST_FLIP_MIN_LIQUIDITY_PER_HOUR = 300.0
    private const val BEST_FLIP_MAX_SANE_ROI_PERCENT = 50.0

    // Self-logged price history for [isPriceSuspicious]'s historical-average check - one sample per item
    // per hour, kept for a rolling day, persisted to disk (see [loadPriceHistory]/[savePriceHistory]) so it
    // survives a restart instead of starting from nothing every session.
    private const val PRICE_HISTORY_SAMPLE_INTERVAL_MS = 60 * 60 * 1000L
    private const val PRICE_HISTORY_MAX_SAMPLES = 24

    private val notifyOnFlip by BooleanSetting("Notify On Flip", true, "Sends a chat message every time an order is claimed and re-created.")
    private val maxOrderEnabled by BooleanSetting(
        "Max Order", false,
        "Whenever this module places a fresh Buy Order on its own (the manual-search flip fallback, or the re-buy after a " +
            "plain flip), sizes it to the largest amount your purse and Hypixel's own cap actually allow instead of just " +
            "matching whatever amount filled last time - so every cycle flips as much volume as currently possible."
    )
    private val maxOrderPursePercent by NumberSetting(
        "Max Order Purse Usage %", 45, 1, 100, 1,
        desc = "Max Order (and Auto-Start Best Flip's opening order) only sizes a Buy Order against this share of the total " +
            "capital (purse plus whatever's tied up in other open orders), not all of it - so once that order fills, there's " +
            "still enough left over to immediately place the next one too, instead of waiting on the Sell Offer's proceeds " +
            "first. Keeps a Buy Order and a Sell Offer both open at once instead of the cycle stalling on funds."
    )
    private val combineBooks by BooleanSetting(
        "Books Combined", false,
        "When a Buy Order for a level 1 enchant book fills, claims it and crafts it up to level 5 in the anvil " +
            "instead of flipping - the resulting book (and any that couldn't be paired) get listed as new Sell Offers."
    )
    private val undercutStaleOrders by BooleanSetting(
        "Undercut Stale Book Orders", false,
        "Every ${UNDERCUT_CHECK_INTERVAL / 1000.0}s (whenever it isn't busy combining), checks a combined book's " +
            "Sell Offer against Hypixel's public Bazaar API - if someone's now selling lower than us, cancels all " +
            "Sell Offers for that item and re-lists the combined unsold amount at a fresh lowest price. Repeats until fully sold."
    )
    private val notifyOnUndercut by BooleanSetting(
        "Notify On Better Order", true,
        "Sends a chat message whenever the undercut watch detects it lost the lowest price and re-lists to reclaim it."
    ).withDependency { undercutStaleOrders }
    private val showBookLevels by BooleanSetting(
        "Show Book Levels", true,
        "Draws the enchant level as a number in the bottom-right of any combinable enchant book's slot, like Terminator's enchant label."
    )
    private val bookLevelColor by ColorSetting("Book Level Color", Color(255, 255, 85), desc = "Color of the level number drawn on combinable enchant books.").withDependency { showBookLevels }
    /** Set via `/hxp bz hotkeys`, not meant to be typed here directly - see [setHuntingBoxHotkeys]/[depositHotbarIntoHuntingBox]. */
    private val huntingBoxHotkeys = +StringSetting(
        "Hunting Box Hotkeys", "1 2 3 4 5 6 7 8", length = 32,
        desc = "Space-separated physical keys (slot 1-8 order, slot 9/Nether Star never touched) /hxp bz collect's inventory-full recovery presses to select each hotbar slot before shift-right-clicking it into the Hunting Box. Set this via /hxp bz hotkeys."
    )
    private val bazaarOpenMethod by SelectorSetting(
        "Open Bazaar Via", "/bz Command", listOf("/bz Command", "NPC Interaction"),
        desc = "Whether every Bazaar visit uses the /bz command or right-clicks a nearby Bazaar NPC instead. Falls back to /bz if no NPC is found nearby."
    )
    private const val BAZAAR_OPEN_VIA_NPC = 1 // index into bazaarOpenMethod's option list above
    private val minBuyOrderAmount by NumberSetting(
        "Min Book Buy Order Amount", 16, 16, MAX_BUY_ORDER_AMOUNT, 1,
        desc = "Books Combined never places a book Buy Order smaller than this - a smaller computed amount gets rounded up to it."
    )
    private val maxBuyOrderAmount by NumberSetting(
        "Max Book Buy Order Amount", MAX_BUY_ORDER_AMOUNT, 16, MAX_BUY_ORDER_AMOUNT, 1,
        desc = "Books Combined never places a book Buy Order larger than this (Hypixel's own hard cap is $MAX_BUY_ORDER_AMOUNT)."
    )

    private val bestFlipBudget by NumberSetting(
        "Best Flip Budget", 20_000_000, 1_000_000, 1_000_000_000, 1_000_000,
        desc = "The coin budget \"Find Best Flip\" scores candidates against - only rules out an item whose single-unit price " +
            "already exceeds this. The actual opening order is sized separately (purse and Max Order, if enabled), so this " +
            "budget is not a guarantee that a max-size order for the winner will fit within it."
    )
    private val autoStartBestFlip by BooleanSetting(
        "Auto-Start Best Flip", false,
        "When \"Find Best Flip\" finds a winner, immediately places a Buy Order for it (sized to your purse, capped at Best Flip Budget) instead of only reporting it in chat."
    )
    private val periodicBestFlipCheck by BooleanSetting(
        "Recheck Best Flip Periodically", false,
        "Automatically re-runs \"Find Best Flip\" every Best Flip Recheck Interval, instead of only when you press its keybind."
    )
    private val bestFlipRecheckHours by NumberSetting(
        "Best Flip Recheck Interval (Hours)", 4, 1, 24, 1,
        desc = "How often Recheck Best Flip Periodically re-runs the search."
    ).withDependency { periodicBestFlipCheck }
    private val manipulationCheckEnabled by BooleanSetting(
        "Best Flip Manipulation Check", true,
        "Skips a candidate if its top price differs too much from the volume-weighted price a bit deeper in the order book - " +
            "catches a single absurd order (a manipulator baiting bots, or a stale troll listing) inflating the apparent profit."
    )
    private val manipulationMaxDeviationPercent by NumberSetting(
        "Max Price Deviation %", 15, 1, 100, 1,
        desc = "How far the top price may differ from the deeper-book price before Best Flip Manipulation Check rejects the item."
    ).withDependency { manipulationCheckEnabled }

    val profitHud = +HUD(
        name = "Bazaar Flipper Profit",
        desc = "Shows realized profit (net coins in from Sell Offer claims minus coins out to Buy Order claims) and profit/hour since the last Reset Profit Stats.",
        toggleable = true
    ) { example ->
        val lines = if (example) {
            listOf("§6Total Profit: §a+1,234,567", "§6Profit/h: §a+123,456/h")
        } else {
            val elapsedHours = (System.currentTimeMillis() - profitTrackingStartedAt) / 3_600_000.0
            val perHour = if (elapsedHours > 0.01) totalProfit / elapsedHours else 0.0
            listOf(
                "§6Total Profit: ${profitColor(totalProfit)}${formatSignedPrice(totalProfit)}",
                "§6Profit/h: ${profitColor(perHour)}${formatSignedPrice(perHour)}/h"
            )
        }

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

    // Persistent - unlike a plain "read whatever's on screen right now" helper, these survive the GUI
    // actually closing, so orderStatusHud has something to keep showing at all times instead of blanking out
    // the moment the player isn't looking at the Bazaar. Fed from two places: whenever the player happens to
    // have the relevant screen open ([currentScreenTopPrices]/[currentManageOrdersSlots], read every frame
    // for free), and - the main source in practice - every time this module's own background checks
    // ([readMarketTopPrices], [openOrdersScreen]) already navigate there anyway, which happens continuously
    // once at least one order is being watched. Only ever overwritten with a genuinely fresh, non-null read;
    // never cleared back to null/empty just because a check came back empty-handed.
    //
    // @Volatile for the same reason [busyFlag] is an AtomicBoolean: these are written from background
    // coroutines (Dispatchers.Default's real thread pool, see busyFlag's own doc) and read every single frame
    // from [orderStatusHud]'s render lambda on the render thread - a plain var here has no guaranteed
    // cross-thread visibility, so the HUD could keep showing an arbitrarily stale value for a while after a
    // background check actually updated it. Always reassigned wholesale (never mutated in place), so a plain
    // @Volatile is enough here - no need for the AtomicBoolean-style compareAndSet [busy] needed.
    @Volatile private var lastKnownBid: Double? = null
    @Volatile private var lastKnownAsk: Double? = null
    @Volatile private var lastKnownOrderSlots: List<LiveOrderSlot> = emptyList()

    /**
     * If [mc.screen] right now is a Bazaar item page (showing "Create Buy Order" and/or "Create Sell Offer"),
     * reads their Top Orders/Offers straight off the already-loaded screen - no navigation, no delay, this is
     * exactly what's already rendered.
     */
    private fun currentScreenTopPrices(): Pair<Double?, Double?> {
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return null to null
        fun readTop(keyword: String, wantMax: Boolean): Double? {
            val slot = screen.findSlot(keyword) ?: return null
            val lore = screen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
            val prices = topOfferRegex.findAll(lore).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
            return if (wantMax) prices.maxOrNull() else prices.minOrNull()
        }
        return readTop("create buy order", wantMax = true) to readTop("create sell offer", wantMax = false)
    }

    // filled/total are nullable: reported live, an order still at 0% simply has no "Filled: X/Y" line in its
    // lore at all (not even "Filled: 0/X") - a plain, hard requirement for that fraction to parse used to
    // silently drop every such order out of this HUD's detection entirely (an order the player placed by hand
    // rather than this module, before it ever received its first fill, was invisible). Detection itself only
    // ever needs the "Buy "/"Sell " name prefix - see [orderPrefixRegex] - never the fraction. total now
    // mostly comes from [orderAmountRegex]/[offerAmountRegex] instead (see [parseOrderFillState]), which is
    // exactly what covers the 0%-filled case - filled is 0 in that case, not null, once a real order is
    // confirmed present via that declared total. price - "Price per unit:" ([pricePerUnitRegex]) - is read
    // the same way for every scanned order now too, on request, so the HUD can show it even for an order this
    // module never itself tracked (e.g. one placed by hand).
    private data class LiveOrderSlot(val itemName: String, val type: OrderType, val filled: Int?, val total: Int?, val price: Double?) {
        val isFullyFilled get() = filled != null && total != null && filled >= total
    }

    /**
     * Scans [screen] (assumed to already be Manage Orders) for every "BUY "/"SELL "-prefixed slot's name/type,
     * fill state ([parseOrderFillState]) and price ([pricePerUnitRegex]). Shared by [currentManageOrdersSlots]
     * (reading whatever's live on screen) and [openOrdersScreen] (caching into [lastKnownOrderSlots] every
     * time it successfully lands there, regardless of who/what triggered that visit).
     */
    private fun scanOrderSlots(screen: AbstractContainerScreen<*>): List<LiveOrderSlot> {
        val top = screen.topSlotCount()
        val result = mutableListOf<LiveOrderSlot>()
        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val prefixMatch = orderPrefixRegex.find(stack.hoverName.string.noControlCodes.trim()) ?: continue
            val itemName = prefixMatch.groupValues[2].trim()
            val type = if (prefixMatch.groupValues[1].equals("buy", ignoreCase = true)) OrderType.BUY else OrderType.SELL
            val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
            val (filled, total) = parseOrderFillState(lore, type)
            val price = pricePerUnitRegex.find(lore)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            result.add(LiveOrderSlot(itemName, type, filled, total, price))
        }
        return result
    }

    /** If [mc.screen] right now is Manage Orders, reads it via [scanOrderSlots] - no navigation, no delay, exactly what's already rendered. */
    private fun currentManageOrdersSlots(): List<LiveOrderSlot> {
        val screen = mc.screen as? AbstractContainerScreen<*> ?: return emptyList()
        return scanOrderSlots(screen)
    }

    /** Plain thousands-grouped integer (e.g. "12.345") - same "." every 3 digits as [formatGrouped], just without decimals/sign since a fill count is always a whole, non-negative number. */
    private fun formatGroupedInt(value: Int): String = value.toString().reversed().chunked(3).joinToString(".").reversed()

    /**
     * Three kinds of lines, all regardless of whether [undercutStaleOrders] is on or anything's actually
     * tracked, and all persistent (see [lastKnownBid]/[lastKnownAsk]/[lastKnownOrderSlots]'s own doc) -
     * they keep showing the last real read instead of blanking out the moment the Bazaar GUI closes:
     *
     * 1. Buy/Sell market top price - freshest if the item page happens to be open right now
     *    ([currentScreenTopPrices]), otherwise the last one this module's own background checks read.
     * 2. Manage Orders summary - how many open Buy/Sell orders there are and how many of each are fully
     *    filled, plus a per-order "filled/total" line - freshest if Manage Orders happens to be open right
     *    now ([currentManageOrdersSlots]), otherwise the last scan. A live, independent ground truth: a
     *    mismatch between what this shows and what the automated fully-filled detection
     *    ([discoverUntrackedOrders]/[checkForFilledTrackedOrders]/[findOrderSlot] - see their own
     *    "isFullyFilled" checks) actually acts on is exactly what a detection bug would look like from
     *    outside.
     * 3. Per tracked Buy/Sell order (if any): the market's own last-checked best ([TrackedBuyOrder.marketPrice]
     *    /[TrackedSellOrder.marketPrice], from [checkTrackedOrdersInGame]) next to ours, so whether we're
     *    still actually the best is visible at a glance without opening Manage Orders by hand. Green when
     *    ours is at least as good as the market's, red when it isn't (an undercut/outbid the watch hasn't
     *    caught up to yet).
     */
    val orderStatusHud = +HUD(
        name = "Bazaar Flipper Order Status",
        desc = "Always shows the last known Buy/Sell top prices and a Manage Orders summary (open/full counts, per-order fill), plus - per tracked order - the market's best next to yours.",
        toggleable = true
    ) { example ->
        val lines = if (example) {
            listOf(
                "§6Buy: §fHighest ${formatGrouped(1017.1)}",
                "§6Sell: §fLowest ${formatGrouped(1044.0)}",
                "§6Orders: §fBuy 2 (1 full) §7| §fSell 1 (0 full)",
                "§7Buy Crimson Essence: §f${formatGroupedInt(400)}/${formatGroupedInt(10_000)} §7@ §f${formatGrouped(1016.5)}",
                "§6Sell Crimson Essence: §fLowest ${formatGrouped(1044.0)} §7| §aMine ${formatGrouped(1044.0)}",
                "§6Buy Crimson Essence: §fHighest ${formatGrouped(1017.1)} §7| §cMine ${formatGrouped(1016.5)}",
            )
        } else {
            val (screenBid, screenAsk) = currentScreenTopPrices()
            // Freshest available wins - a live screen read over the cache - but a null/empty read (GUI not
            // currently showing that data) falls back to the last known value instead of showing nothing.
            screenBid?.let { lastKnownBid = it }
            screenAsk?.let { lastKnownAsk = it }
            val displayBid = screenBid ?: lastKnownBid
            val displayAsk = screenAsk ?: lastKnownAsk
            val priceLines = listOfNotNull(
                displayBid?.let { "§6Buy: §fHighest ${formatGrouped(it)}" },
                displayAsk?.let { "§6Sell: §fLowest ${formatGrouped(it)}" },
            )

            val screenOrders = currentManageOrdersSlots()
            if (screenOrders.isNotEmpty()) lastKnownOrderSlots = screenOrders
            val liveOrders = screenOrders.ifEmpty { lastKnownOrderSlots }
            val manageOrdersLines = if (liveOrders.isEmpty()) emptyList() else {
                val buyOrders = liveOrders.filter { it.type == OrderType.BUY }
                val sellOrders = liveOrders.filter { it.type == OrderType.SELL }
                val summary = "§6Orders: §fBuy ${buyOrders.size} (${buyOrders.count { it.isFullyFilled }} full) §7| " +
                    "§fSell ${sellOrders.size} (${sellOrders.count { it.isFullyFilled }} full)"
                val perOrder = liveOrders.map { o ->
                    val color = if (o.isFullyFilled) "§a" else "§7"
                    // total now mostly comes from the order's own declared "Order amount:"/"Offer amount:"
                    // (see LiveOrderSlot's own doc) - only shown as "?" if that's somehow also missing.
                    val filledText = formatGroupedInt(o.filled ?: 0)
                    val totalText = o.total?.let { formatGroupedInt(it) } ?: "?"
                    val priceText = o.price?.let { " §7@ §f${formatGrouped(it)}" } ?: ""
                    "$color${o.type.name.lowercase().replaceFirstChar { it.uppercase() }} ${o.itemName}: §f$filledText/$totalText$priceText"
                }
                listOf(summary) + perOrder
            }

            val sellLines = trackedSellOrders.values.map { t ->
                val market = t.marketPrice
                val mineColor = if (market == null) "§7" else if (t.price <= market) "§a" else "§c"
                "§6Sell ${t.itemName}: §fLowest ${market?.let { formatGrouped(it) } ?: "?"} §7| ${mineColor}Mine ${formatGrouped(t.price)}"
            }
            val buyLines = trackedBuyOrders.values.map { t ->
                val market = t.marketPrice
                val mineColor = if (market == null) "§7" else if (t.price >= market) "§a" else "§c"
                "§6Buy ${t.itemName}: §fHighest ${market?.let { formatGrouped(it) } ?: "?"} §7| ${mineColor}Mine ${formatGrouped(t.price)}"
            }
            (priceLines + manageOrdersLines + buyLines + sellLines).ifEmpty { listOf("§7No Buy/Sell data yet - open the Bazaar once.") }
        }

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

    private fun profitColor(value: Double): String = if (value >= 0) "§a" else "§c"
    // Grouped (thousands-separated via formatGrouped, e.g. "+1.234.567") rather than a plain ungrouped
    // number - the profit HUD is read-only display, unlike formatPrice's other callers which type this
    // straight into a Hypixel price input that needs a plain, ungrouped number.
    private fun formatSignedPrice(value: Double): String = (if (value >= 0) "+" else "") + formatGrouped(value)

    private enum class OrderType { BUY, SELL }

    private data class ClaimedOrder(val itemName: String, val type: OrderType, val amount: Int)

    private val filledOrderRegex = Regex("^\\[Bazaar] Your (Buy Order|Sell Offer) for ([\\d,]+)x (.+) was filled!$")
    // Anchored to "Filled:" (confirmed live wording, e.g. "Filled: 16/16 100%!") rather than matching any
    // bare "X/Y" pattern - an unanchored version risked grabbing an unrelated X/Y-shaped substring
    // elsewhere in the same lore (a coin amount, a vendor timestamp, ...) and silently mis-computing a
    // wildly wrong remaining/filled amount from it.
    //
    // Reported live: a large order's total showed as just "19" in this module's own HUD/logs when the real
    // amount was "19 thousand something" - the previous digits-and-commas-only pattern stopped matching at
    // the first character it didn't recognize, which a Hypixel-style abbreviated count ("19.2K", "1.5M", ...)
    // hits immediately at the decimal point, silently truncating to whatever came before it. Each number here
    // now also optionally matches a decimal fraction plus a K/M/B suffix, parsed by [parseAbbreviatedCount]
    // below - best-effort pending a live-log confirmation of the exact format (see that function's own doc).
    private val fillFractionRegex = Regex("filled:\\s*(\\d[\\d,]*(?:\\.\\d+)?[kmb]?)\\s*/\\s*(\\d[\\d,]*(?:\\.\\d+)?[kmb]?)", RegexOption.IGNORE_CASE)
    // Confirmed live: a claimed-but-not-yet-fully-collected order's slot keeps this line (with the
    // remaining count) until every item has actually been delivered to the inventory.
    private val itemsToClaimRegex = Regex("you have \\d+ items? to claim", RegexOption.IGNORE_CASE)
    // Same match as [itemsToClaimRegex] but with the count captured - used right before [claimOrderFully]
    // to know exactly how much is about to land in the inventory (see [cancelAllOrders]'s CancelResult.claimed).
    // Same K/M/B-abbreviation allowance as [fillFractionRegex] - see its own doc.
    private val itemsToClaimAmountRegex = Regex("you have ([\\d,]+(?:\\.\\d+)?[kmb]?) items? to claim", RegexOption.IGNORE_CASE)

    /**
     * Parses a Hypixel-style count that's either a plain comma-grouped integer ("19,234") or abbreviated with
     * a decimal + K/M/B suffix ("19.2K" -> 19,200, "1.5M" -> 1,500,000) - see [fillFractionRegex]'s own doc
     * for why this exists. Best-effort: the exact abbreviated format (rounding, capitalization, whether a
     * whole number ever drops its decimal point) is a guess pending live confirmation - logs via [devMessage]
     * whenever the abbreviated path actually fires, so a wrong guess is visible in the dev log rather than
     * silently producing another wrong number.
     */
    private fun parseAbbreviatedCount(raw: String): Int? {
        val trimmed = raw.trim()
        val suffix = trimmed.lastOrNull()?.uppercaseChar()
        val multiplier = when (suffix) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            else -> null
        }
        if (multiplier == null) return trimmed.replace(",", "").toIntOrNull()
        val numberPart = trimmed.dropLast(1).replace(",", "")
        val value = numberPart.toDoubleOrNull() ?: return null
        val result = (value * multiplier).toInt()
        devMessage("[BazaarFlipper] Parsed abbreviated count '$raw' as $result - confirm this matches the real in-game amount.")
        return result
    }

    /**
     * Reads an order slot's (filled, total) from its lore, given [type] to pick the right "declared total"
     * line ("Order amount:" for a Buy Order, "Offer amount:" for a Sell Offer - see [orderAmountRegex]/
     * [offerAmountRegex]'s own doc). On request: prefers that declared total over [fillFractionRegex]'s own
     * total group, since the declared line is expected to always be there while "Filled: X/Y" only shows up
     * once there's been at least some fill - falls back to the fraction's total only if the declared line
     * somehow isn't found. If no "Filled:" line is found at all but a declared total *is* (confirmed live: no
     * "Filled:" line means genuinely 0% so far, not "unreadable" - see [scanOrderSlots]'s own doc), filled
     * comes back 0 rather than null, since the slot is now provably a real, matched order either way.
     */
    private fun parseOrderFillState(lore: String, type: OrderType): Pair<Int?, Int?> {
        val fillMatch = fillFractionRegex.find(lore)
        val fractionFilled = fillMatch?.groupValues?.get(1)?.let { parseAbbreviatedCount(it) }
        val fractionTotal = fillMatch?.groupValues?.get(2)?.let { parseAbbreviatedCount(it) }
        val declaredRegex = if (type == OrderType.BUY) orderAmountRegex else offerAmountRegex
        val declaredTotal = declaredRegex.find(lore)?.groupValues?.get(1)?.let { parseAbbreviatedCount(it) }
        val total = declaredTotal ?: fractionTotal
        val filled = fractionFilled ?: total?.let { 0 }
        return filled to total
    }
    // "Flip Order"'s own tooltip lists the current best competing offers as e.g. "- 4.4 coins each | 64x
    // from 1 offer" - there's no separate screen to read prices from, and no "price" keyword on these
    // lines at all (unlike the "Current unit price: X coins" line above them, which is our own old price).
    private val topOfferRegex = Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s*coins each")
    // Our own order's listed price, as shown directly on its Manage Orders slot (e.g. "Price per unit:
    // 4.4 coins") - used to know what to compare the current market price against for [undercutStaleOrders].
    private val pricePerUnitRegex = Regex("price per unit:\\s*([\\d,]*\\.?\\d+)\\s*coins", RegexOption.IGNORE_CASE)
    // On request: an order's own declared full size ("Order amount: X" for a Buy Order, "Offer amount: X" for
    // a Sell Offer) - more reliable than [fillFractionRegex]'s total for a still-0%-filled order, whose
    // "Filled: X/Y" line isn't there at all yet (see that regex's own doc) - this line is expected to always
    // be present regardless of fill state. See [parseOrderFillState].
    private val orderAmountRegex = Regex("order amount:\\s*(\\d[\\d,]*(?:\\.\\d+)?[kmb]?)", RegexOption.IGNORE_CASE)
    private val offerAmountRegex = Regex("offer amount:\\s*(\\d[\\d,]*(?:\\.\\d+)?[kmb]?)", RegexOption.IGNORE_CASE)
    // Splits a Manage Orders slot's own display name into its "Buy"/"Sell" prefix and the item name.
    private val orderPrefixRegex = Regex("^(buy|sell)\\s+(.+)$", RegexOption.IGNORE_CASE)
    // Guessed wording for Hypixel's rejection when a Buy Order can't be paid for - never observed live, so
    // this is a best-effort keyword match (see [insufficientFundsRegex] usage in the chat listener).
    private val insufficientFundsRegex = Regex("(cannot afford|can't afford|can not afford|not enough coins|insufficient (funds|coins))", RegexOption.IGNORE_CASE)
    // Confirmed live: Hypixel's rejection when the inventory doesn't have room for a claim (e.g. "[Bazaar]
    // You don't have the space required to claim that!") - [claimOrderFully] stops retrying immediately on
    // this instead of burning through its remaining attempts against a full inventory.
    private val noSpaceToClaimRegex = Regex("you don't have the space required to claim that", RegexOption.IGNORE_CASE)
    // Confirmed live: Hypixel's rejection when cancelling an order that still has unclaimed goods sitting
    // on it (e.g. "[Bazaar] You have goods to claim on this order!") - [cancelAllOrders] claims those goods
    // first instead of ever hitting this, and treats it as a hard stop (not a retry) if it happens anyway.
    private val goodsToClaimOnCancelRegex = Regex("you have goods to claim on this order", RegexOption.IGNORE_CASE)

    // Splits e.g. "Ultimate Wise I" -> ("Ultimate Wise", "I") or "Experience 1" -> ("Experience", "1") -
    // both roman and arabic level suffixes are used across Bazaar-tradeable enchant books, so both are
    // matched and fed through the existing [romanToInt] (which already handles plain digit strings too).
    private val bookLevelRegex = Regex("^(.+?)\\s+([IVXLCDM]+|[0-9]+)$")
    private val romanNumerals = mapOf(1 to "I", 2 to "II", 3 to "III", 4 to "IV", 5 to "V")

    // Only ever touched from the client thread (ChatPacketEvent/TickEvent.End both fire there) - the
    // coroutine started per cycle only ever receives an immutable snapshot, never the deque itself.
    private val pendingFlips = ArrayDeque<ClaimedOrder>()

    // Item names (lowercase) [createBookSellOrder] has listed a Sell Offer for - a filled Sell Offer only
    // gets ignored by the normal flip path (see runCycle) if it's actually in here. [bookLevelRegex] alone
    // is far too broad a filter for that: plenty of ordinary Bazaar items (e.g. "Recombobulator 3000")
    // also end in a word made purely of digits or IVXLCDM letters and would get wrongly swallowed otherwise.
    private val trackedBookSellItems = mutableSetOf<String>()

    // No productId/snapshot-timestamp needed here (unlike an early version of this that polled Hypixel's
    // external Bazaar API): [readMarketTopPrice] always reads the live in-game price fresh on every check,
    // so there's no stale-snapshot window to guard against - reading back right after our own relist just
    // sees our own new listing as the current top, which naturally compares as "not beaten" rather than
    // needing an explicit self-undercut guard.
    // marketPrice: the market's own current best (lowest ask for a Sell Offer, highest bid for a Buy Order)
    // as of the last [checkTrackedOrdersInGame] pass - cached here purely for [orderStatusHud] to show
    // alongside [price] (ours), so whether we're still actually the best is visible from outside the Bazaar
    // GUI without having to open Manage Orders by hand. Null until the first check after tracking starts.
    private data class TrackedSellOrder(val itemName: String, var price: Double, var amount: Int, var marketPrice: Double? = null)
    private data class TrackedBuyOrder(val itemName: String, var price: Double, var amount: Int, var marketPrice: Double? = null)

    // Keyed by itemName.lowercase() - what [undercutStaleOrders]'s watch loop is currently tracking, for
    // ANY item (not just enchant books - see [trackOrderForUndercutWatch]). Populated by
    // [trackOrderForUndercutWatch], drained as orders fill/sell or the watch gives up.
    //
    // ConcurrentHashMap, not a plain mutableMapOf: [orderStatusHud]'s render lambda iterates `.values` on the
    // render thread every single frame, while background coroutines (trackOrderForUndercutWatch,
    // handleUndercut/handleOutbid, flipOrder/manualCreateOrder, all on Dispatchers.Default's pool) add/remove
    // entries with no relationship to the render thread at all - a plain HashMap offers no guarantees at all
    // for a structural change landing mid-iteration on another thread (not just "might throw
    // ConcurrentModificationException", genuinely undefined behavior per the collections contract).
    // ConcurrentHashMap's iterators are weakly consistent instead - safe to iterate concurrently with
    // modifications, never throws, may just not reflect a modification still in flight - exactly what a HUD
    // display needs, without having to wrap every single read/write site (there are many, spread across this
    // whole file) in a manual synchronized block the way [priceHistory] is.
    private val trackedSellOrders = java.util.concurrent.ConcurrentHashMap<String, TrackedSellOrder>()
    private val trackedBuyOrders = java.util.concurrent.ConcurrentHashMap<String, TrackedBuyOrder>()
    private var apiWatcherJob: Job? = null

    // Keyed by itemName.lowercase() (value = original casing, for search/messages) - every item this module
    // has ever started a flip for this session ([startFlip]) or is/was watching ([trackOrderForUndercutWatch]),
    // kept even after that item's orders all happen to hit zero. Confirmed live: a Buy Order that got
    // outbid/cancelled with its relist then failing (a transient GUI hiccup mid-navigation, no retry on that
    // whole attempt) used to just leave the item with *no* orders at all and nothing noticing -
    // [discoverUntrackedOrders]'s own "Sell Offer with no Buy Order" check only fired if a Sell Offer happened
    // to still be open, not for a fully empty item. This map is how that check also catches the fully-empty
    // case: any managed item with zero open Buy Orders, Sell Offer or not, gets a fresh Buy Order started.
    // Not pruned just because a particular scan found it with no orders - an item this module once cared
    // about should keep getting restarted, not silently drop off the list over a transient hiccup. It IS
    // pruned after [MAX_AUTO_REBUY_FAILURES] straight failed restart attempts (see [autoRebuyFailureCounts]
    // and [discoverUntrackedOrders]'s "ensure a Buy Order" loop) - confirmed live a bad item name (a /hxp bz flip
    // typo, or one that simply doesn't exist on the Bazaar) used to sit here forever, since [startFlip]
    // registers it here unconditionally before it can even fail: every discoverUntrackedOrders pass then
    // retried it again, forever, spamming chat with the same failure every IDLE_PARTIAL_CLAIM_INTERVAL with
    // no way to stop it short of disabling the whole module.
    private val activelyManagedItems = mutableMapOf<String, String>()

    // Consecutive failed auto-restart attempts per item (see [activelyManagedItems]'s own doc), keyed the
    // same way. Reset to 0 the moment a restart actually succeeds; the item is dropped from
    // [activelyManagedItems] once this hits [MAX_AUTO_REBUY_FAILURES] rather than being retried forever.
    private val autoRebuyFailureCounts = mutableMapOf<String, Int>()

    // Set when a Buy Order placement gets rejected for lack of funds (see [insufficientFundsRegex] - the
    // exact wording is a guess, unconfirmed live) - [createBookBuyOrder] refuses to place new orders while
    // this is true, and [ensureFundsRecoveryLoopRunning] clears it once "Claim All Coins" shows a balance.
    // @Volatile for the same cross-thread-visibility reason as [noSpaceToClaim]/[goodsToClaimOnCancel] right
    // below - set by the chat listener, read/cleared by suspend functions that can resume on a different
    // Dispatchers.Default pool thread. This one was missing it despite following the exact same pattern.
    @Volatile private var insufficientFunds = false
    private var fundsRecoveryJob: Job? = null

    // Set by the chat listener the instant Hypixel rejects a claim for lack of inventory space - checked
    // (and reset before each attempt) by [claimOrderFully] so it stops clicking immediately instead of
    // retrying into the same wall up to its max attempt count.
    @Volatile private var noSpaceToClaim = false

    // Set by the chat listener the instant Hypixel rejects a cancel because the order still has unclaimed
    // goods on it - checked (and reset before each attempt) by [cancelAllOrders] so it stops immediately
    // instead of looping on the same still-uncancelled order.
    @Volatile private var goodsToClaimOnCancel = false

    // Realized profit tracking for the "Bazaar Flipper Profit" HUD - a simple net-cash-flow model (see the
    // chat listener's "Claimed Nx X worth W coins bought for..."/"Claimed W coins from selling Nx X at..."
    // matches): every buy claim subtracts what it cost, every sell claim adds what it paid out. Persisted
    // (see [loadProfitData]/[saveProfitData]) so it survives a restart; [resetProfitStats] zeroes both.
    // @Volatile - same cross-thread reasoning as [lastKnownBid] etc.: written from the chat-listener thread,
    // read every frame by [profitHud]'s render lambda on the render thread. A non-volatile Double/Long write
    // also isn't even guaranteed atomic cross-thread per the JVM spec (only a 32-bit write is) - unlikely to
    // matter in practice on today's JVMs, but @Volatile removes the question entirely, matching this file's
    // own established pattern for chat-listener-set/background-read fields.
    @Volatile private var totalProfit = 0.0
    @Volatile private var profitTrackingStartedAt = System.currentTimeMillis()
    private val profitClaimBuyRegex = Regex("claimed [\\d,]+x .+ worth ([\\d,]+) coins bought for [\\d,.]+ each!", RegexOption.IGNORE_CASE)
    private val profitClaimSellRegex = Regex("claimed ([\\d,]+) coins from selling [\\d,]+x .+ at [\\d,.]+ each!", RegexOption.IGNORE_CASE)
    private val profitGson = GsonBuilder().create()
    private val profitDataFile = File(HxPMod.configFile, "bazaar-flipper-profit.json")

    // Self-logged price history for [isPriceSuspicious] - see [PRICE_HISTORY_SAMPLE_INTERVAL_MS]/[PRICE_HISTORY_MAX_SAMPLES].
    private data class PriceSample(val timestamp: Long, val bid: Double, val ask: Double)
    // Confirmed live entry points into this map run on genuinely separate coroutines with no ordering
    // relationship: the hourly price-history loop ([recordPriceSamples]) and "Find Best Flip" ([findBestFlips]
    // - both a manual keybind press and its own periodic recheck loop), both on Dispatchers.Default's pool. A
    // plain mutableMapOf here is an unsynchronized HashMap - two of those landing at the same moment is a real
    // (if rare) concurrent-mutation risk, up to a ConcurrentModificationException. Every read/write site below
    // synchronizes on this map itself rather than switching to a coroutine Mutex, since [isPriceSuspicious] -
    // one of the readers - is a plain (non-suspend) function called from inside a tight loop, where a Mutex
    // would need `runBlocking` or a much bigger refactor to use correctly.
    private val priceHistory = mutableMapOf<String, MutableList<PriceSample>>()
    private val priceHistoryGson = GsonBuilder().create()
    private val priceHistoryFile = File(HxPMod.configFile, "bazaar-flipper-price-history.json")

    // `/hxp bz flip` calls that arrived while [busy] was already true - see [retryPendingManualFlips]. A
    // LinkedHashSet so retries happen oldest-queued-first, and re-queueing an already-pending item (running
    // `/hxp bz flip` again for the same one) doesn't duplicate it.
    private val pendingManualFlips = LinkedHashSet<String>()

    /**
     * State for an in-progress `/hxp fuse bz` run (see [startShardFuse]): a Buy Order was placed for both
     * [shard1Name]/[shard2Name] (sized by [ShardFusionScanner]'s current best-fuse pick), and
     * [processClaimedOrders] routes a filled Buy Order for either one here ([handleClaimedFuseShard])
     * instead of the normal [flipOrder] resell path - claiming it plainly (like the book-combine flow's
     * left-click claim, not "Flip Order") and marking that leg claimed. Once both legs are claimed,
     * [Fuser.start] is triggered with these three names to actually run the fusions. `@Volatile` since
     * it's written by the `/hxp fuse bz`-starting coroutine and read/mutated by whichever coroutine processes
     * the filled-order chat trigger - not necessarily the same one, same reasoning as this file's other
     * chat-listener-adjacent flags (e.g. [insufficientFunds]).
     */
    private data class PendingFuse(
        val shard1Name: String, val shard1Amount: Int,
        val shard2Name: String, val shard2Amount: Int,
        val outputName: String,
        var shard1Claimed: Boolean = false,
        var shard2Claimed: Boolean = false,
    )
    @Volatile private var pendingFuse: PendingFuse? = null

    /**
     * `/hxp bz collect`'s state: keeps placing/re-listing Buy Orders for a fixed set of items until each has
     * accumulated [targetAmount] claimed, staying the highest bid the whole time (via the same
     * [trackOrderForUndercutWatch]/[checkTrackedOrdersInGame] outbid-watch loop used elsewhere in this
     * module - started directly here regardless of the [undercutStaleOrders] setting, since that toggle is
     * meant for the book-flip workflow specifically and this command should always keep re-listing). Keyed
     * by itemName.lowercase(), same convention as [activelyManagedItems]/[trackedBuyOrders].
     *
     * Deliberately intercepted at two separate points before either could fall into this module's normal
     * book-flip machinery (same reasoning [pendingFuse] documents for /hxp fuse bz): a full fill routes through
     * [processClaimedOrders]'s `when` (see [handleClaimedBuyCollect]) instead of the default [flipOrder], and
     * an outbid-triggered cancel routes through [handleOutbid]'s own special-case (see
     * [handleBuyCollectOutbid]) instead of [createBookBuyOrder]'s resell-claimed-goods consolidation - both
     * of those defaults would either resell these shards or resize the relist off purse%, neither of which is
     * right for "just keep buying toward a fixed target and hold it."
     */
    /** [viaNpc]: whether this item's Buy Order placements/re-lists should force NPC-interaction opening (see [openBazaar]'s `forceNpc`) - set once at `/hxp bz collect`/`/hxp bz collect npc` time and carried through every re-list, since [handleBuyCollectOutbid] has no other way to know which entry command started it. */
    private data class PendingBuyCollect(val itemName: String, val targetAmount: Int, var claimedAmount: Int = 0, val viaNpc: Boolean = false)
    private val pendingBuyCollect = java.util.concurrent.ConcurrentHashMap<String, PendingBuyCollect>()

    /**
     * [pendingBuyCollect] lookup by a CHAT-MESSAGE-derived item name (i.e. [ClaimedOrder.itemName], sourced
     * from [filledOrderRegex]) rather than the original command-typed name - unlike [handleOutbid]'s own
     * lookup (`tracked.itemName`, always the exact text `/hxp bz collect`/`/hxp bz collect npc` was typed with, never touched by
     * chat formatting), a plain `pendingBuyCollect[itemName.lowercase()]` here can silently miss.
     *
     * Confirmed live (2026-08-14): Hypixel pluralizes an item name in some of its own chat messages once the
     * amount involved is more than 1 - same behavior already seen in the Hunting Box deposit confirmation
     * ("You sent 38 Polaris Shards...", plural, vs. "You sent an Apex Dragon Shard...", singular for exactly
     * 1). A `/hxp bz collect queen bee shard 249` order's own "was filled!" message for any multi-unit fill therefore
     * reads "... Queen Bee Shard**s** was filled!", which never equals the stored key "queen bee shard" under
     * a plain lowercase comparison - `processClaimedOrders`' own collection-isolation check (added the same
     * day, see its own doc) then read this as "not part of any collection" and skipped the item's own fills
     * entirely, and before that fix existed, the same missed lookup sent those fills into the normal
     * `flipOrder`/resell path instead - either way, the actual root cause was always this name mismatch, not
     * whichever behavior happened to be wired to the "not found" case at the time.
     *
     * Tries an exact match first, then the same key with one trailing "s" added or removed either way, before
     * giving up - covers a plural-vs-singular mismatch in either direction without needing to know up front
     * which side (if any) is actually plural for a given item.
     */
    private fun findPendingBuyCollect(chatItemName: String): PendingBuyCollect? {
        val key = chatItemName.trim().lowercase()
        pendingBuyCollect[key]?.let { return it }
        if (key.endsWith("s")) pendingBuyCollect[key.dropLast(1)]?.let { return it }
        return pendingBuyCollect["${key}s"]
    }

    private var job: Job? = null

    // Confirmed live: [HxPMod.scope] has no dispatcher pinned to it, so every coroutine this module
    // launches actually runs on Dispatchers.Default's real multi-threaded pool, not one single thread taking
    // turns - a plain `var` here meant both a visibility gap (one coroutine's `busy = true` not guaranteed to
    // be seen by another running on a different pool thread right away) and, more importantly, a genuine
    // check-then-set race: two coroutines (e.g. handleUndercut and a /hxp bz flip call) could both read `busy ==
    // false` before either managed to set it, and both proceed to fight over the same live Bazaar GUI at
    // once. AtomicBoolean's get()/set() give every plain `busy`/`busy = ...` read and write elsewhere in this
    // file immediate cross-thread visibility for free (no call-site changes needed for those), and
    // [tryClaimBusy] gives the actual "am I the one starting something new" check-and-set sites (below) a
    // real atomic compareAndSet instead of two separate, racy steps.
    private val busyFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    private var busy: Boolean
        get() = busyFlag.get()
        set(value) { busyFlag.set(value) }

    /** Atomically claims [busy] - true only if this call is the one that actually flipped it from false to true, so the caller knows it (not some other coroutine that happened to check a moment earlier) owns this run. */
    private fun tryClaimBusy(): Boolean = busyFlag.compareAndSet(false, true)

    override fun onEnable() {
        super.onEnable()
        // Confirmed live: with only the periodic IDLE_PARTIAL_CLAIM_INTERVAL timer driving
        // discoverUntrackedOrders, a Buy/Sell order that was already fully filled *before* the module even
        // got turned on could sit unclaimed for up to that whole interval before the first check even ran -
        // and if busy is held the whole time by something else right after enabling (e.g. a /hxp bz flip call's own
        // placement flow), the periodic tick can end up skipped over and over for as long as that keeps
        // happening, with the check never actually landing. Firing one scan immediately on enable covers
        // exactly "I already have a full order sitting there" without waiting on the timer at all.
        HxPMod.scope.launch { discoverUntrackedOrders() }
    }

    override fun onDisable() {
        super.onDisable()
        job?.cancel()
        job = null
        busy = false
        pendingFlips.clear()
        trackedSellOrders.clear()
        trackedBuyOrders.clear()
        // Confirmed live: force-cancelling apiWatcherJob here could interrupt handleUndercut/handleOutbid
        // mid-cycle - right after the cancel step completed but before the relist that's supposed to follow
        // it, leaving an order cancelled with nothing replacing it and no error shown (the coroutine just
        // stops dead at whatever delay()/suspension point it was at). Left alone instead: its own loop
        // condition (`enabled && ...`) and `if (!enabled) break` check stop it on their own the moment any
        // in-flight cancel+relist call actually finishes, rather than severing it wherever it happened to be.
        // It'll exit within moments regardless since enabled is now false either way.
        trackedBookSellItems.clear()
        insufficientFunds = false
        fundsRecoveryJob?.cancel()
        fundsRecoveryJob = null
        pendingManualFlips.clear()
        activelyManagedItems.clear()
        autoRebuyFailureCounts.clear()
        pendingFuse = null
        pendingBuyCollect.clear()
    }

    init {
        loadProfitData()
        loadPriceHistory()

        registerSetting(
            KeybindSetting(
                "Manual Combine", GLFW.GLFW_KEY_UNKNOWN,
                "Crafts up every combinable book already in your inventory right now (see Books Combined), without waiting for a Buy Order fill."
            ).onPress { triggerManualCombine() }
        )
        registerSetting(
            KeybindSetting(
                "Manual Undercut Test", GLFW.GLFW_KEY_UNKNOWN,
                "Testing aid: force-cancels and immediately re-lists every book Buy/Sell order currently open in Manage Orders, regardless of price, to verify the cancel/re-list flow works."
            ).onPress { triggerManualUndercutTest() }
        )
        registerSetting(
            KeybindSetting(
                "Find Best Flip", GLFW.GLFW_KEY_UNKNOWN,
                "Scores every Bazaar item by profit-per-unit weighted by its slower side's trade volume (see Best Flip Budget), reports the top candidates in chat, and (with Auto-Start Best Flip on) places a Buy Order for the winner."
            ).onPress { triggerBestFlip() }
        )
        registerSetting(
            KeybindSetting(
                "Reset Profit Stats", GLFW.GLFW_KEY_UNKNOWN,
                "Zeroes out Total Profit and Profit/h (see the Bazaar Flipper Profit HUD) and restarts the clock."
            ).onPress { resetProfitStats() }
        )

        on<ChatPacketEvent> {
            if (!enabled) return@on
            if (insufficientFundsRegex.containsMatchIn(value)) {
                devMessage("[BazaarFlipper] Chat message matched the (guessed) insufficient-funds pattern: '$value' - pausing new Buy Order placement until coins are claimable.")
                insufficientFunds = true
                ensureFundsRecoveryLoopRunning()
                return@on
            }
            if (noSpaceToClaimRegex.containsMatchIn(value)) {
                noSpaceToClaim = true
                return@on
            }
            if (goodsToClaimOnCancelRegex.containsMatchIn(value)) {
                goodsToClaimOnCancel = true
                return@on
            }
            // Confirmed live wording (see e.g. "[Bazaar] Claimed 18x Rejuvenate I worth 390,215 coins bought
            // for 21,679 each!" / "[Bazaar] Claimed 2,033,929 coins from selling 4x Rejuvenate V at 514,919
            // each!") - net-cash-flow profit tracking for the "Bazaar Flipper Profit" HUD (see totalProfit's doc).
            profitClaimBuyRegex.find(value)?.let { m ->
                m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { worth ->
                    totalProfit -= worth
                    saveProfitData()
                }
                return@on
            }
            profitClaimSellRegex.find(value)?.let { m ->
                m.groupValues[1].replace(",", "").toDoubleOrNull()?.let { worth ->
                    totalProfit += worth
                    saveProfitData()
                }
                return@on
            }
            val match = filledOrderRegex.find(value) ?: return@on
            val type = if (match.groupValues[1] == "Buy Order") OrderType.BUY else OrderType.SELL
            val amount = match.groupValues[2].replace(",", "").toIntOrNull() ?: return@on
            val itemName = match.groupValues[3]
            pendingFlips.addLast(ClaimedOrder(itemName, type, amount))
        }

        // Deliberately its own listener, not folded into the block above - that one bails out entirely when
        // the module is toggled off ([enabled]), but /hxp bz huntingbox is a one-shot manual command that should work
        // regardless of whether Auto Bazaar Flipper itself is enabled.
        on<ChatPacketEvent> {
            if (huntingBoxDepositRegex.containsMatchIn(value)) {
                lastHuntingBoxDepositAtMs = System.currentTimeMillis()
            }
        }

        on<TickEvent.End> {
            if (!enabled || busy) return@on
            if (mc.player == null || mc.screen != null) return@on
            if (pendingFlips.isEmpty()) return@on

            val toProcess = pendingFlips.toList()
            pendingFlips.clear()
            runCycle(toProcess)
        }

        // Hypixel never sends a chat message for a Buy Order that's only *partially* filled (only for a
        // fully completed one) - so a partial fill would otherwise just sit unclaimed and un-combined
        // until the order eventually completes. Every IDLE_PARTIAL_CLAIM_INTERVAL, if nothing else is
        // going on, proactively check for one and start crafting up whatever's already been delivered.
        HxPMod.scope.launch {
            while (true) {
                delay(IDLE_PARTIAL_CLAIM_INTERVAL)
                if (!enabled || !combineBooks || busy) continue
                runPeriodicSafely("partial-fill check") { checkForPartiallyFilledBuyOrders() }
            }
        }

        // Every IDLE_PARTIAL_CLAIM_INTERVAL, while nothing else is going on, catches any already-filled order
        // sitting unclaimed with no active chat/watch trigger for it, and (with undercutStaleOrders on) also
        // starts the undercut/outbid watch on every untracked open order - see discoverUntrackedOrders' own
        // doc for both. Not gated on undercutStaleOrders itself (only enabled/busy) - catching a missed fill
        // has nothing to do with that setting, and used to silently never happen at all whenever it was off.
        HxPMod.scope.launch {
            while (true) {
                delay(IDLE_PARTIAL_CLAIM_INTERVAL)
                if (!enabled || busy) continue
                runPeriodicSafely("untracked-order discovery") { discoverUntrackedOrders() }
            }
        }

        // With Recheck Best Flip Periodically on: re-runs the same search "Find Best Flip" does, on its own,
        // every bestFlipRecheckHours - checked every IDLE_PARTIAL_CLAIM_INTERVAL rather than sleeping for the
        // whole interval up front, so toggling the setting (or changing the hour count) takes effect on the
        // next tick instead of only after whatever delay was already in flight when it changed.
        var lastBestFlipCheck = 0L
        HxPMod.scope.launch {
            while (true) {
                delay(IDLE_PARTIAL_CLAIM_INTERVAL)
                if (!enabled || !periodicBestFlipCheck || busy) continue
                val intervalMs = bestFlipRecheckHours * 60 * 60 * 1000L
                if (System.currentTimeMillis() - lastBestFlipCheck < intervalMs) continue
                lastBestFlipCheck = System.currentTimeMillis()
                runPeriodicSafely("Best Flip recheck") { triggerBestFlip() }
            }
        }

        // See [retryPendingManualFlips]'s own doc - automatically starts a queued `/hxp bz flip` item once the
        // module is free again, instead of the player having to retype the command later.
        HxPMod.scope.launch {
            while (true) {
                delay(IDLE_PARTIAL_CLAIM_INTERVAL)
                if (!enabled || busy || pendingManualFlips.isEmpty()) continue
                runPeriodicSafely("pending manual flip retry") { retryPendingManualFlips() }
            }
        }

        // Builds up [priceHistory] on its own, independent of "Find Best Flip" actually being used - so
        // [isPriceSuspicious]'s historical-average check has real data to compare against once it's needed,
        // instead of only starting to log the first time a search happens to run. Pure network fetch, no
        // screen interaction, so unlike the other periodic loops this doesn't need to wait for [busy].
        HxPMod.scope.launch {
            while (true) {
                delay(PRICE_HISTORY_SAMPLE_INTERVAL_MS)
                if (!enabled) continue
                runPeriodicSafely("price history sampling") {
                    val reply = RequestUtils.getBazaar().getOrNull() ?: return@runPeriodicSafely
                    recordPriceSamples(reply)
                }
            }
        }

        // Same hook Terminator uses for its enchant-abbreviation overlay - fires once per rendered item
        // slot (any screen), already carrying the stack and its top-left screen coordinates.
        on<RenderItemDecorationsEvent> {
            if (!enabled || !showBookLevels) return@on
            val (_, level, _) = parseCombinableBook(stack) ?: return@on
            val text = level.toString()
            guiGraphics.text(mc.font, text, x + 17 - mc.font.width(text), y + 9, bookLevelColor.rgba, true)
        }
    }

    /**
     * Called periodically (see [IDLE_PARTIAL_CLAIM_INTERVAL]) when nothing else is going on: scans Manage
     * Orders for a Buy Order that's *partially* filled (0 < filled < total) and currently claimable
     * ("click to claim" in its lore - confirmed live to appear even before an order is fully done), claims
     * what's available so far via [claimOrderFully] (the order itself stays open for the remainder), and
     * crafts up whatever that delivered instead of waiting for the order to fully complete before touching
     * it at all.
     */
    private suspend fun checkForPartiallyFilledBuyOrders() {
        if (!enabled) return
        if (!tryClaimBusy()) return
        try {
            val screen = openOrdersScreen() ?: run {
                devMessage("§cBazaarFlipper: couldn't open Manage Orders for the idle partial-fill check.")
                return
            }
            dumpScreen(screen, "Manage Orders (idle partial-fill check)")

            val top = screen.topSlotCount()
            var target: Pair<Int, String>? = null
            for (i in 0 until top) {
                val stack = screen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                val prefixMatch = orderPrefixRegex.find(stack.hoverName.string.noControlCodes.trim()) ?: continue
                if (!prefixMatch.groupValues[1].equals("buy", ignoreCase = true)) continue
                val itemName = prefixMatch.groupValues[2].trim()
                if (bookLevelRegex.find(itemName) == null) continue
                val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
                if (!lore.contains("click to claim", ignoreCase = true)) continue
                val (filled, total) = parseOrderFillState(lore, OrderType.BUY)
                if (filled == null || total == null || filled <= 0 || filled >= total) continue // 0% (nothing to claim yet) or 100% (the normal chat-triggered path already handles that)
                target = i to itemName
                break
            }
            if (target == null) {
                closeScreen()
                return
            }

            val (slot, itemName) = target
            devMessage("[BazaarFlipper] Idle check found a partially filled Buy Order for $itemName - claiming what's available so far.")
            claimOrderFully(slot, itemName)
            closeScreen()
            randomDelay(GUI_APPEAR_DELAY)

            for (book in detectAllCombinableBookTypes()) {
                try {
                    processBookType(book, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One book type throwing (e.g. an anvil GUI hiccup) used to abort this whole for loop -
                    // leaving every other combinable book type in the inventory untouched. Catching per book
                    // keeps the rest going, and this periodic check runs again on its own regardless.
                    HxPMod.logger.error("BazaarFlipper: idle partial-fill combine failed for ${book.baseName}", e)
                    devMessage("§cBazaarFlipper: combining ${book.baseName} failed (${e.message}) - moving on to the next book type.")
                }
            }
        } finally {
            busy = false
        }
    }

    /**
     * Single Manage Orders scan doing three independent jobs every time it runs:
     *
     * 1. Claims/flips any order already sitting at "click to claim" that this module doesn't know about -
     *    always, regardless of [undercutStaleOrders]. Confirmed live this used to just skip a filled order
     *    outright, on the assumption "the normal chat-triggered path" ([filledOrderRegex]'s listener) would
     *    catch it instead - but that only fires at the *moment* an order fills. An order that filled while
     *    the client wasn't even running (closed and reopened later), or whose chat message simply got missed,
     *    never enters [pendingFlips] and then just sits there fully filled indefinitely with nothing else in
     *    this module watching for it. This was originally gated behind [undercutStaleOrders] too (bundled
     *    into the untracked-order-discovery job below) - confirmed live that left a real gap whenever that
     *    setting was off: of two already-filled orders sitting there before the module even started, one
     *    might get caught by a coincidental live re-fill/chat event and one wouldn't, with nothing ever
     *    picking up the second. Claiming a fill has nothing to do with price-undercut watching, so it no
     *    longer depends on that setting.
     * 2. With [undercutStaleOrders] on: starts the undercut/outbid watch ([trackOrderForUndercutWatch]) on
     *    every *open* (not yet filled) Buy/Sell order - any item, not just books, unlike an earlier version
     *    of this that only looked at book-shaped names - that isn't already in
     *    [trackedSellOrders]/[trackedBuyOrders]. Covers an order this module didn't itself just (re)list
     *    (pre-existing when the setting got turned on, or placed by hand) so the watch actually reaches every
     *    open order instead of only ones this module's own flip/combine flow happens to have created.
     * 3. Always: any item with an open Sell Offer but *no* Buy Order at all anywhere in Manage Orders is one
     *    half of a flip cycle missing its other half - whatever cancelled/never-relisted it (an outbid
     *    renewal that ran out of budget, a manual cancel, disabling mid-cycle, ...), nothing else in this
     *    module proactively notices a Buy Order that's just plain absent, only ones that got outbid or filled.
     *    Starts a fresh one via [startFlip] the moment this scan (running every [IDLE_PARTIAL_CLAIM_INTERVAL]
     *    whenever nothing else is going on) finds the gap, instead of leaving the item to just sit there
     *    one-sided until the player notices by hand.
     *
     * Safe to call repeatedly: an already-tracked/already-covered order is simply skipped either way.
     */
    private suspend fun discoverUntrackedOrders() {
        if (!enabled) return
        if (!tryClaimBusy()) return
        try {
            val screen = openOrdersScreen() ?: run {
                devMessage("§cBazaarFlipper: couldn't open Manage Orders for the untracked-order discovery scan.")
                return
            }
            dumpScreen(screen, "Manage Orders (discovering untracked orders)")

            val top = screen.topSlotCount()
            val filled = mutableListOf<ClaimedOrder>()
            val itemsWithBuyOrder = mutableMapOf<String, String>()
            val itemsWithSellOrder = mutableMapOf<String, String>()
            for (i in 0 until top) {
                val stack = screen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                val prefixMatch = orderPrefixRegex.find(stack.hoverName.string.noControlCodes.trim()) ?: continue
                val itemName = prefixMatch.groupValues[2].trim()
                val type = if (prefixMatch.groupValues[1].equals("buy", ignoreCase = true)) OrderType.BUY else OrderType.SELL
                val key = itemName.lowercase()
                if (type == OrderType.BUY) itemsWithBuyOrder[key] = itemName else itemsWithSellOrder[key] = itemName

                if (type == OrderType.SELL && trackedSellOrders.containsKey(key)) continue
                if (type == OrderType.BUY && trackedBuyOrders.containsKey(key)) continue

                val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
                val (filledAmount, totalAmount) = parseOrderFillState(lore, type)
                // Confirmed live wording is "Filled: X/Y 100%!" the moment an order is fully filled - reading
                // filled==total straight off that fraction is the simplest, most direct signal there is.
                // Confirmed live an OR against "click to claim"/"items to claim" used to be here too, on the
                // assumption those only ever show up once an order's fully done - wrong: a Buy Order that's
                // only 0.6% filled already shows "You have 50 items to claim! / Click to claim!" for that
                // small delivered slice, well before the order as a whole is anywhere near filled. That OR
                // matched a barely-started order as "fully filled" and sent it into flipOrder, which claims
                // whatever's pending and creates a whole new opposite order off of it - firing on a 99%-still-
                // open order, not the actual completion. The text signals are now only a *fallback* for when
                // the fraction itself can't be parsed at all, never an override once it parses as "not yet".
                val isFullyFilled = if (filledAmount != null && totalAmount != null) {
                    filledAmount >= totalAmount
                } else {
                    itemsToClaimRegex.containsMatchIn(lore) || lore.contains("click to claim", ignoreCase = true)
                }
                if (isFullyFilled) {
                    val total = totalAmount ?: continue
                    filled.add(ClaimedOrder(itemName, type, total))
                    continue
                }
                if (!undercutStaleOrders) continue
                val price = pricePerUnitRegex.find(lore)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
                val total = totalAmount ?: continue

                devMessage("[BazaarFlipper] Found an untracked ${type.name.lowercase()} order for $itemName at $price coins - adding it to the undercut/outbid watch.")
                trackOrderForUndercutWatch(itemName, type, total, price)
            }
            closeScreen()

            if (filled.isNotEmpty()) {
                // Confirmed live this was devMessage-only, meaning it was invisible whenever the "HxPAddonsDev"
                // toggle was off - which meant nothing in normal chat ever showed this scan detecting anything
                // at all, dev-only diagnostics or not. This specific line - a full order actually detected and
                // about to be handled - is the one confirmation this feature working (or not) actually hinges
                // on, so it's plain modMessage now: always visible, not gated behind that toggle.
                modMessage("§aFound ${filled.size} fully filled order(s) with no active watch/chat trigger for ${filled.joinToString { it.itemName }} - claiming/flipping now.")
                processClaimedOrders(filled)
            }

            // Any managed item with zero open Buy Orders right now - whether it still has a Sell Offer open
            // or genuinely nothing at all (a relist that silently failed after an outbid/undercut cancel,
            // say) - is missing its buying half. Starts a fresh Buy Order right away rather than leaving it
            // for some other trigger that might never come. Checks every managed item, not just ones with a
            // Sell Offer still open, specifically so the "cancelled and nothing came back at all" case gets
            // caught too.
            //
            // Snapshotted via toList() - activelyManagedItems can be mutated below (a permanently-failing
            // item gets removed from it) partway through this same loop, which would otherwise throw a
            // ConcurrentModificationException iterating the live map directly.
            for ((key, itemName) in activelyManagedItems.entries.toList()) {
                if (key in itemsWithBuyOrder) {
                    // A Buy Order exists for it right now, however that happened (this loop's own retry,
                    // a manual /hxp bz flip, a normal flip cycle, ...) - whatever run of failures preceded it no
                    // longer reflects reality, so it shouldn't count against a future failure streak.
                    autoRebuyFailureCounts.remove(key)
                    continue
                }
                val reason = if (key in itemsWithSellOrder) "only a Sell Offer" else "no orders at all"
                modMessage("§eAuto Bazaar Flipper: no Buy Order found for §f$itemName§e ($reason) - starting a fresh one.")
                // Each item's restart gets its own try/catch - confirmed live an exception restarting one
                // item (e.g. a stale/unexpected screen mid-navigation) used to abort this whole loop outright,
                // silently skipping every other managed item still waiting behind it in the same pass.
                try {
                    val result = startFlip(itemName, "Auto Buy Order")
                    if (result == FlipStartResult.STARTED) {
                        autoRebuyFailureCounts.remove(key)
                    } else {
                        recordAutoRebuyFailure(key, itemName)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HxPMod.logger.error("BazaarFlipper: auto Buy Order restart failed for $itemName", e)
                    modMessage("§cAuto Bazaar Flipper §4ran into an error§c restarting the Buy Order for $itemName, check logs for details - continuing with the rest.")
                    recordAutoRebuyFailure(key, itemName)
                }
            }
        } finally {
            busy = false
        }
    }

    /**
     * Counts one more failed auto-restart for [key]/[itemName] (see [autoRebuyFailureCounts]'s own doc),
     * dropping it from [activelyManagedItems] entirely once it hits [MAX_AUTO_REBUY_FAILURES] straight
     * failures - a permanently-bad item (typo'd via /hxp bz flip, or one that just doesn't exist on the Bazaar)
     * would otherwise retry, fail, and spam chat forever, every [IDLE_PARTIAL_CLAIM_INTERVAL], with no way
     * to stop it short of disabling the whole module. A transient failure (temporarily out of funds, a slow
     * GUI) still gets [MAX_AUTO_REBUY_FAILURES] more tries before that happens, and any success in between
     * resets the count back to 0.
     */
    private fun recordAutoRebuyFailure(key: String, itemName: String) {
        val fails = (autoRebuyFailureCounts[key] ?: 0) + 1
        autoRebuyFailureCounts[key] = fails
        if (fails >= MAX_AUTO_REBUY_FAILURES) {
            modMessage(
                "§cAuto Bazaar Flipper: giving up auto-restarting a Buy Order for §f$itemName§c after $fails failed " +
                    "attempts in a row - removing it from the managed list. Use §f/hxp bz flip $itemName§c to retry it by hand."
            )
            activelyManagedItems.remove(key)
            autoRebuyFailureCounts.remove(key)
        }
    }

    /**
     * With [insufficientFunds] set: every [INSUFFICIENT_FUNDS_RECHECK_INTERVAL], checks Manage Orders'
     * "Claim All Coins" button (confirmed live text: "You have X coins to claim!" vs. "You don't have any
     * coins to claim.") - once there's a balance (from a Sell Offer having sold in the meantime), claims it
     * and clears the flag so [createBookBuyOrder] resumes placing new orders. The Sell Offer/undercut side
     * of this module isn't paused by any of this - it keeps running independently the whole time.
     */
    private fun ensureFundsRecoveryLoopRunning() {
        if (fundsRecoveryJob?.isActive == true) return
        fundsRecoveryJob = HxPMod.scope.launch {
            while (enabled && insufficientFunds) {
                delay(INSUFFICIENT_FUNDS_RECHECK_INTERVAL)
                if (!enabled) break
                if (busy) continue
                checkForClaimableCoins()
            }
        }
    }

    private suspend fun checkForClaimableCoins() {
        if (!enabled) return
        if (!tryClaimBusy()) return
        try {
            val screen = openOrdersScreen() ?: run {
                devMessage("§cBazaarFlipper: couldn't open Manage Orders to check for claimable coins.")
                return
            }
            dumpScreen(screen, "Manage Orders (waiting for funds)")

            val top = screen.topSlotCount()
            var coinsSlot: Int? = null
            for (i in 0 until top) {
                val stack = screen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                if (!stack.hoverName.string.noControlCodes.contains("claim all coins", ignoreCase = true)) continue
                val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
                if (lore.contains("don't have any coins", ignoreCase = true)) break
                coinsSlot = i
                break
            }
            if (coinsSlot == null) {
                devMessage("[BazaarFlipper] No coins to claim yet - still waiting for a sale, checking again in ${INSUFFICIENT_FUNDS_RECHECK_INTERVAL / 1000}s.")
                closeScreen()
                return
            }

            devMessage("[BazaarFlipper] Coins are now claimable - claiming and resuming Buy Order placement.")
            click(coinsSlot)
            randomDelay(GUI_APPEAR_DELAY)
            (mc.screen as? AbstractContainerScreen<*>)?.let { dumpScreen(it, "After claiming coins") }
            closeScreen()
            insufficientFunds = false
        } finally {
            busy = false
        }
    }

    /**
     * "Manual Combine" keybind: skips waiting for a Buy Order fill entirely and just crafts up whatever
     * combinable books ([detectAllCombinableBookTypes]) are already sitting in the inventory right now -
     * e.g. ones bought or claimed by hand. No triggering order exists here, so [processBookType] gets no
     * re-buy fallback amount for a book that doesn't reach level 5 (nothing to fall back to).
     */
    private fun triggerManualCombine() {
        if (!enabled) return
        if (mc.player == null || mc.screen != null) {
            modMessage("§cAuto Bazaar Flipper: can't start the manual combine right now (no player, or a screen is already open).")
            return
        }
        val bookTypes = detectAllCombinableBookTypes()
        if (bookTypes.isEmpty()) {
            modMessage("§cAuto Bazaar Flipper: no combinable books found in your inventory.")
            return
        }
        if (!tryClaimBusy()) {
            modMessage("§cAuto Bazaar Flipper: already busy, ignoring the manual combine trigger.")
            return
        }
        modMessage("§aManually triggered: crafting up ${bookTypes.size} book type(s) found in your inventory (${bookTypes.joinToString { it.baseName }}).")
        job = HxPMod.scope.launch {
            try {
                for (book in bookTypes) {
                    try {
                        processBookType(book, null)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One book type throwing used to abort this whole for loop via the outer catch below
                        // - e.g. 10x held Rejuvenate I sitting untouched just because some other book type
                        // processed first hit a hiccup. Catching per book keeps working through the rest.
                        HxPMod.logger.error("BazaarFlipper: manual combine failed for ${book.baseName}", e)
                        modMessage("§cAuto Bazaar Flipper: combining ${book.baseName} failed (${e.message}) - moving on to the next book type.")
                    }
                }
            } catch (e: CancellationException) {
                // Confirmed live: a manual disable mid-operation cancels whatever [job] is currently running -
                // that's the intended effect of disabling, not an actual failure, so it shouldn't get logged
                // and reported to chat as "ran into an error" like a real bug would.
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: manual combine failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c during the manual combine, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    /**
     * "Manual Undercut Test" keybind: for verifying the cancel/re-list flow works without waiting for a
     * real undercut/outbid to happen naturally. Scans Manage Orders for every Buy/Sell order that looks
     * like a book (any "Buy"/"Sell <Name> <Level>" slot [bookLevelRegex] matches), then force-cancels and
     * re-lists each one via the exact same [cancelAllOrders]/[createBookSellOrder]/[createBookBuyOrder]
     * path the real API watcher uses - regardless of whether it's actually been undercut/outbid. Re-tracks
     * whatever gets relisted (rather than leaving any pre-existing tracked entry for it stale).
     */
    private fun triggerManualUndercutTest() {
        if (!enabled) return
        if (!tryClaimBusy()) {
            modMessage("§cAuto Bazaar Flipper: already busy, ignoring the manual undercut test.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                val screen = openOrdersScreen() ?: run {
                    modMessage("§cAuto Bazaar Flipper: couldn't open Manage Orders for the manual undercut test.")
                    return@launch
                }
                dumpScreen(screen, "Manage Orders (manual undercut test scan)")

                val found = mutableListOf<Triple<String, OrderType, Int>>()
                val top = screen.topSlotCount()
                for (i in 0 until top) {
                    val stack = screen.menu.items.getOrNull(i) ?: continue
                    if (stack.isEmpty) continue
                    val prefixMatch = orderPrefixRegex.find(stack.hoverName.string.noControlCodes.trim()) ?: continue
                    val itemName = prefixMatch.groupValues[2].trim()
                    if (bookLevelRegex.find(itemName) == null) continue
                    val type = if (prefixMatch.groupValues[1].equals("buy", ignoreCase = true)) OrderType.BUY else OrderType.SELL
                    val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
                    val total = parseOrderFillState(lore, type).second ?: continue
                    found.add(Triple(itemName, type, total))
                }
                closeScreen()

                if (found.isEmpty()) {
                    modMessage("§cAuto Bazaar Flipper: no book orders found in Manage Orders to test.")
                    return@launch
                }
                modMessage("§aManual undercut test: cancelling and re-listing ${found.size} order(s): ${found.joinToString { "${it.second.name.lowercase()} ${it.first}" }}.")

                for ((itemName, type, amount) in found) {
                    try {
                        randomDelay(400)
                        // Same "order is being renewed to be lowest/highest again" reasoning as
                        // handleUndercut/handleOutbid - this test simulates exactly that action.
                        val cancelResult = cancelAllOrders(itemName, type, amount, claimPendingGoods = true)
                        if (!cancelResult.confirmed) {
                            // Same reasoning as createBookSellOrder/createBookBuyOrder's own matching check -
                            // don't relist on top of an old order this call never actually confirmed gone.
                            devMessage("[BazaarFlipper] Manual undercut test: couldn't confirm $itemName's old order actually got cancelled - skipping the relist.")
                            continue
                        }
                        val remaining = cancelResult.remaining
                        if (remaining < 1) {
                            devMessage("[BazaarFlipper] Manual undercut test: nothing left of $itemName's ${type.name.lowercase()} order(s) to re-list.")
                            continue
                        }
                        randomDelay(GUI_APPEAR_DELAY)
                        if (type == OrderType.SELL) createBookSellOrder(itemName, remaining, cancelExisting = false) else createBookBuyOrder(itemName, remaining, cancelExisting = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One order's cancel/re-list hitting an unexpected screen used to abort this whole
                        // test via the outer catch below - leaving every order after it in [found] untouched.
                        // Catching per-order here keeps working through the rest of the list regardless.
                        HxPMod.logger.error("BazaarFlipper: manual undercut test failed for $itemName", e)
                        devMessage("§cBazaarFlipper: manual undercut test for $itemName failed (${e.message}) - moving on to the next order.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: manual undercut test failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c during the manual undercut test, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    private data class BestFlipCandidate(
        val itemName: String,
        val bid: Double,
        val ask: Double,
        val profitPerUnit: Double,
        val sellRatePerHour: Double,
        val buyRatePerHour: Double,
        val limitingRatePerHour: Double,
        val scorePerHour: Double,
        val roiPercent: Double,
    )

    /**
     * Turns a raw Bazaar product ID (e.g. "ENCHANTED_SLIME_BALL") into something readable ("Enchanted Slime
     * Ball"). Best-effort - not guaranteed to match the item's real in-game display name exactly (see
     * [findOrderSlotByName] for that) - but this result also gets fed straight into the actual in-game search
     * ([placeOrderViaSearch]) for [startBestFlipOrder], so getting it right matters beyond just the chat
     * report. Confirmed live: Essences are the one case that's flipped from the product ID's own word order -
     * "ESSENCE_CRIMSON" is "Crimson Essence" in-game, not "Essence Crimson".
     */
    private fun humanizeProductId(productId: String): String {
        val base = productId.substringBefore(":")
        val essenceType = base.removePrefix("ESSENCE_").takeIf { it != base }
        return if (essenceType != null) {
            essenceType.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) } + " Essence"
        } else {
            base.split("_").joinToString(" ") { it.lowercase().replaceFirstChar(Char::titlecase) }
        }
    }

    /** Volume-weighted average price across [orders] up to [depth] units total - null if [orders] is empty or has zero total amount. */
    private fun weightedDepthPrice(orders: List<BazaarApiData.Order>, depth: Long): Double? {
        var remaining = depth
        var totalCost = 0.0
        var totalUnits = 0L
        for (order in orders) {
            if (remaining <= 0) break
            val take = minOf(order.amount, remaining)
            totalCost += take * order.pricePerUnit
            totalUnits += take
            remaining -= take
        }
        if (totalUnits <= 0L) return null
        return totalCost / totalUnits
    }

    /**
     * With [manipulationCheckEnabled] on: [BestFlipCandidate]'s bid/ask (from [BazaarApiData.QuickStatus],
     * already somewhat volume-weighted by Hypixel itself) still only reflects a handful of the very top
     * orders - a single order sized to dominate just that window (a manipulator baiting bots into a bad
     * trade, or a leftover troll listing) can still skew it. Two independent checks, either one enough to
     * reject: (1) re-derives both prices from the full [product.sellSummary]/[product.buySummary] order-book
     * arrays over a much deeper window (one full max-size order's worth, see [maxOrderCapFor]) - a real,
     * broadly-supported price shouldn't move much once you look past just the very top of the book; (2)
     * compares against [priceHistory]'s own self-logged average for this item (once at least 3 hourly
     * samples exist) - catches a slower pump that's still consistent-looking within a single snapshot's
     * depth but is well above what this item has actually been trading at over the last day.
     */
    private fun isPriceSuspicious(product: BazaarApiData.Product, qs: BazaarApiData.QuickStatus): Boolean {
        if (!manipulationCheckEnabled) return false
        val maxDeviation = manipulationMaxDeviationPercent / 100.0

        // sell_summary backs qs.sellPrice (what we'd get selling), buy_summary backs qs.buyPrice (what
        // we'd pay buying) - see the field-name gotcha noted on [BazaarApiData.QuickStatus].
        val depth = maxOrderCapFor(humanizeProductId(product.productId)).toLong()
        val deepBid = weightedDepthPrice(product.sellSummary, depth)
        if (deepBid != null && deepBid > 0.0 && abs(qs.sellPrice - deepBid) / deepBid > maxDeviation) return true
        val deepAsk = weightedDepthPrice(product.buySummary, depth)
        if (deepAsk != null && deepAsk > 0.0 && abs(qs.buyPrice - deepAsk) / deepAsk > maxDeviation) return true

        // Snapshotted (a defensive copy) while holding the lock, same reasoning as [priceHistory]'s own doc -
        // the actual averaging below then runs lock-free, off a list nothing else can concurrently mutate.
        val samples = synchronized(priceHistory) { priceHistory[product.productId]?.toList() }
        if (samples != null && samples.size >= 3) {
            val histBid = samples.map { it.bid }.average()
            val histAsk = samples.map { it.ask }.average()
            if (histBid > 0.0 && abs(qs.sellPrice - histBid) / histBid > maxDeviation) return true
            if (histAsk > 0.0 && abs(qs.buyPrice - histAsk) / histAsk > maxDeviation) return true
        }
        return false
    }

    /** Appends one [PriceSample] per product to [priceHistory] (at most one per [PRICE_HISTORY_SAMPLE_INTERVAL_MS], safe to call more often than that), trims each to [PRICE_HISTORY_MAX_SAMPLES], and persists the result. */
    private fun recordPriceSamples(reply: BazaarApiData.Reply) {
        val now = System.currentTimeMillis()
        // Whole loop under one lock (see [priceHistory]'s own doc) - this only ever runs once an hour (or on
        // a manual Find Best Flip/its recheck), so briefly blocking a concurrent reader for the handful of
        // milliseconds this loop takes is a non-issue.
        synchronized(priceHistory) {
            for ((id, product) in reply.products) {
                val qs = product.quickStatus ?: continue
                if (qs.sellPrice <= 0.0 || qs.buyPrice <= 0.0) continue
                val list = priceHistory.getOrPut(id) { mutableListOf() }
                if (list.isNotEmpty() && now - list.last().timestamp < PRICE_HISTORY_SAMPLE_INTERVAL_MS) continue
                list.add(PriceSample(now, qs.sellPrice, qs.buyPrice))
                while (list.size > PRICE_HISTORY_MAX_SAMPLES) list.removeAt(0)
            }
        }
        savePriceHistory()
    }

    private fun savePriceHistory() {
        try {
            priceHistoryFile.parentFile?.mkdirs()
            priceHistoryFile.createNewFile()
            val json = synchronized(priceHistory) { priceHistoryGson.toJson(priceHistory) }
            priceHistoryFile.writeText(json)
        } catch (e: Exception) {
            HxPMod.logger.error("BazaarFlipper: failed to save price history", e)
        }
    }

    private fun loadPriceHistory() {
        try {
            if (!priceHistoryFile.exists() || priceHistoryFile.readText().isBlank()) return
            val type = object : TypeToken<MutableMap<String, MutableList<PriceSample>>>() {}.type
            val loaded: MutableMap<String, MutableList<PriceSample>>? = priceHistoryGson.fromJson(priceHistoryFile.readText(), type)
            if (loaded == null) return
            // Only ever called once from init{}, before any background loop that touches priceHistory even
            // exists yet - synchronized purely for consistency with every other access site, not because a
            // real race is reachable here.
            synchronized(priceHistory) {
                priceHistory.clear()
                priceHistory.putAll(loaded)
            }
        } catch (e: Exception) {
            HxPMod.logger.error("BazaarFlipper: failed to load price history", e)
        }
    }

    private fun resetProfitStats() {
        totalProfit = 0.0
        profitTrackingStartedAt = System.currentTimeMillis()
        saveProfitData()
        modMessage("§aAuto Bazaar Flipper: profit stats reset.")
    }

    private fun saveProfitData() {
        try {
            profitDataFile.parentFile?.mkdirs()
            profitDataFile.createNewFile()
            val obj = com.google.gson.JsonObject().apply {
                addProperty("totalProfit", totalProfit)
                addProperty("profitTrackingStartedAt", profitTrackingStartedAt)
            }
            profitDataFile.writeText(profitGson.toJson(obj))
        } catch (e: Exception) {
            HxPMod.logger.error("BazaarFlipper: failed to save profit data", e)
        }
    }

    private fun loadProfitData() {
        try {
            if (!profitDataFile.exists() || profitDataFile.readText().isBlank()) return
            val obj = com.google.gson.JsonParser.parseString(profitDataFile.readText()).asJsonObject
            totalProfit = obj.get("totalProfit")?.asDouble ?: 0.0
            profitTrackingStartedAt = obj.get("profitTrackingStartedAt")?.asLong ?: System.currentTimeMillis()
        } catch (e: Exception) {
            HxPMod.logger.error("BazaarFlipper: failed to load profit data", e)
        }
    }

    /**
     * "Find Best Flip" keybind: scores every Bazaar product Hypixel's public API reports (see
     * [RequestUtils.getBazaar] - same endpoint [undercutStaleOrders] used before switching to in-game price
     * reads, still the only source for the hourly trade-volume stats this needs) and ranks them by expected
     * coins/hour for a Buy-Order-then-Sell-Offer flip: `profit-per-unit * min(sellRate, buyRate)` - the
     * *lower* of how fast the item sells and how fast it gets bought, per the user's own reasoning: an item
     * that moves fast in both directions at a modest margin beats one with a huge margin that barely trades,
     * since a flip is only ever as fast as its slower side. `quick_status.sellPrice`/`buyPrice` are
     * confirmed live to mean "what you'd get selling"/"what you'd pay buying" respectively (opposite of what
     * the field names suggest), and `sellMovingWeek`/`buyMovingWeek` are total volume over the trailing week
     * - divided by 168 for an hourly rate. Guarded by three filters: a liquidity floor and a max-ROI sanity
     * cap (see [BEST_FLIP_MIN_LIQUIDITY_PER_HOUR]/[BEST_FLIP_MAX_SANE_ROI_PERCENT], both against thin/stale
     * order-book artifacts), [isPriceSuspicious] (the top price disagreeing too much with the order book a
     * bit deeper - see [manipulationCheckEnabled]), and [budget] (excludes anything where a single *unit*
     * already costs the whole budget or more - actual order sizing is a separate concern, handled by
     * [startBestFlipOrder]/[calculateMaxBuyAmount]). Returns the top [limit], highest score first.
     */
    private suspend fun findBestFlips(budget: Double, limit: Int = 5): List<BestFlipCandidate> {
        val reply = RequestUtils.getBazaar().getOrElse {
            devMessage("§cBazaarFlipper: failed to fetch the Bazaar API for Best Flip: ${it.message}")
            return emptyList()
        }
        recordPriceSamples(reply)

        val candidates = mutableListOf<BestFlipCandidate>()
        for (product in reply.products.values) {
            val qs = product.quickStatus ?: continue
            val bid = qs.sellPrice
            val ask = qs.buyPrice
            if (bid <= 0.0 || ask <= 0.0) continue

            val profitPerUnit = ask * (1 - BAZAAR_TAX) - bid
            if (profitPerUnit <= 0.0) continue

            val sellRate = qs.sellMovingWeek / HOURS_PER_WEEK
            val buyRate = qs.buyMovingWeek / HOURS_PER_WEEK
            val limitingRate = minOf(sellRate, buyRate)
            if (limitingRate < BEST_FLIP_MIN_LIQUIDITY_PER_HOUR) continue

            val roiPercent = profitPerUnit / bid * 100.0
            if (roiPercent > BEST_FLIP_MAX_SANE_ROI_PERCENT) continue

            val itemName = humanizeProductId(product.productId)
            // Only checks that a single unit is affordable, not that a *full max-size* order fits the
            // budget - [maxOrderCapFor]'s general-item cap (71,000) made that latter check absurdly strict
            // for anything with a non-trivial price, excluding perfectly good flips just because 71,000 of
            // them together would cost more than the budget. Actual order sizing is handled separately, by
            // [startBestFlipOrder]/[calculateMaxBuyAmount] - the budget here only rules out an item you
            // couldn't even buy one of.
            if (bid >= budget) continue

            if (isPriceSuspicious(product, qs)) {
                devMessage("[BazaarFlipper] Best Flip: skipping $itemName - top price deviates too far from the deeper order book (possible manipulation/stale listing).")
                continue
            }

            candidates.add(
                BestFlipCandidate(
                    itemName = itemName,
                    bid = bid, ask = ask, profitPerUnit = profitPerUnit,
                    sellRatePerHour = sellRate, buyRatePerHour = buyRate, limitingRatePerHour = limitingRate,
                    scorePerHour = profitPerUnit * limitingRate, roiPercent = roiPercent,
                )
            )
        }
        return candidates.sortedByDescending { it.scorePerHour }.take(limit)
    }

    private enum class FlipStartResult { STARTED, FAILED }

    /**
     * Places the actual opening Buy Order for [itemName] to start a brand-new flip - used both by "Find
     * Best Flip" (only when [autoStartBestFlip] is on) and the `/hxp bz flip <item>` command
     * ([startManualFlip]). Sized the same way [calculateMaxBuyAmount] sizes every other re-buy in this module
     * (see its own doc for why: [maxOrderPursePercent] of total capital, but never more than the real liquid
     * purse, and the full purse instead of just that percentage once [itemName] already has one leg of the
     * flip pair open). Both reads come from one [readBazaarPortfolio] call rather than two separate Manage
     * Orders visits. [bestFlipBudget] still applies on top as a hard ceiling either way - starting a flip in
     * an item this module has never run before is exactly the case that budget exists to cap, whether the
     * item was picked by the scoring or by hand. Capped at Hypixel's own per-order limit. [source] is just
     * for the chat/log wording (e.g. "Best Flip", "/hxp bz flip").
     *
     * Confirmed live this used to take a pre-read `bid: Double` parameter, with every caller first making a
     * *whole separate* navigation pass (via [readMarketTopPrice]) just to obtain it before this function's
     * own navigation to the same item page to actually place the order - two round trips for one visit. The
     * price is now read live inside [placeOrderViaSearch]'s single navigation instead, via its
     * `amountProvider` callback below. [fallbackBid] (Best Flip's scoring pass, which only has an
     * externally-fetched API price to begin with) is used only if that live in-game read comes back null.
     */
    private suspend fun startFlip(itemName: String, source: String, fallbackBid: Double? = null): FlipStartResult {
        // Registers this item as managed regardless of undercutStaleOrders - see activelyManagedItems' own
        // doc - so discoverUntrackedOrders keeps restarting a Buy Order for it even if the watch itself is off.
        activelyManagedItems[itemName.lowercase()] = itemName

        val purse = readPurseBalance()
        val portfolio = readBazaarPortfolio()

        val itemKey = itemName.lowercase()
        val totalCapital = purse?.plus(portfolio.lockedValue)
        val itemAlreadyActive = itemKey in portfolio.activeItems

        openBazaar()
        // Not requiring "bazaar" in the title here - openBazaar() may have been a no-op (already sitting in
        // a container screen), and not every screen within the Bazaar's own navigation tree has that word in
        // its title anyway (confirmed live, see openBazaar's doc). placeOrderViaSearch backs out via
        // "Go Back" on its own regardless of where this lands.
        val mainScreen = waitForScreen { true } ?: run {
            modMessage("§cAuto Bazaar Flipper: Bazaar menu did not open in time to start $itemName.")
            return FlipStartResult.FAILED
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(mainScreen, "Bazaar main ($source start: $itemName)")

        val amount = placeOrderViaSearch(itemName, OrderType.BUY, mainScreen) { topPrice ->
            val bid = topPrice ?: fallbackBid
            if (bid == null) {
                modMessage("§cAuto Bazaar Flipper: couldn't read a current Bazaar price for $itemName - not starting $source.")
                return@placeOrderViaSearch 0
            }
            val desiredShare = if (itemAlreadyActive) (purse ?: bestFlipBudget.toDouble())
                else (totalCapital ?: bestFlipBudget.toDouble()) * (maxOrderPursePercent / 100.0)
            val usableCapital = if (purse != null) minOf(desiredShare, purse) else desiredShare
            val capital = minOf(usableCapital, bestFlipBudget.toDouble())
            val sized = (capital / (bid + 0.1)).toInt().coerceIn(0, maxOrderCapFor(itemName))
            if (sized <= 0) {
                modMessage("§cAuto Bazaar Flipper: not enough coins/budget to start a $source Buy Order for $itemName.")
            }
            sized
        }
        if (amount <= 0) return FlipStartResult.FAILED
        closeScreen()
        if (!ensureOrderExists(itemName, amount, OrderType.BUY, "$source Buy Order")) return FlipStartResult.FAILED

        modMessage("§a$source: placed a Buy Order for §f${amount}x $itemName§a.")
        if (undercutStaleOrders) {
            randomDelay(GUI_APPEAR_DELAY)
            val price = readOwnPrice(itemName, OrderType.BUY)
            if (price != null) trackOrderForUndercutWatch(itemName, OrderType.BUY, amount, price)
        }
        return FlipStartResult.STARTED
    }

    /**
     * Entry point for the `/hxp bz flip <item>` command: turns the module on first if it wasn't already (so the
     * normal claim/flip/re-buy/undercut-watch cycle takes over from here on its own), and starts it through
     * the exact same [startFlip] every other flip-starting path uses (Max Order-style capital sizing, etc.) -
     * no Best Flip scoring or manipulation checks, since the player is choosing this item themselves rather
     * than asking this module to judge it. If the module is already [busy] with something else, the item
     * name is queued in [pendingManualFlips] instead of just failing - [retryPendingManualFlips] (a periodic
     * loop, see its own doc) automatically starts it later on its own once free, so the player doesn't have
     * to remember to retype the command.
     */
    internal fun startManualFlip(rawItemName: String) {
        val itemName = rawItemName.trim()
        if (itemName.isEmpty()) {
            modMessage("§cAuto Bazaar Flipper: usage is /hxp bz flip <item name>.")
            return
        }
        if (!enabled) {
            toggle()
        }
        if (!tryClaimBusy()) {
            // Already-busy just gets queued too instead of dropped outright - the retry loop will pick it
            // up on its own next tick once whatever's currently running clears, no need to retype anything.
            pendingManualFlips.add(itemName)
            modMessage("§eAuto Bazaar Flipper: busy right now - queued §f$itemName§e, will start it automatically once free.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                startFlip(itemName, "/hxp bz flip")
            } catch (e: CancellationException) {
                // Confirmed live: disabling the module mid-flip cancels whatever [job] is currently running -
                // that's the intended effect of disabling, not an actual failure (was previously caught by
                // the generic Exception branch below and reported to chat as "ran into an error" on every
                // manual disable that happened to land mid-/hxp bz flip).
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: /hxp bz flip failed for $itemName", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c starting $itemName, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Every [IDLE_PARTIAL_CLAIM_INTERVAL], while nothing else is going on, tries the next item queued in
     * [pendingManualFlips] (`/hxp bz flip` calls that arrived while [busy] was already true) - one at a time,
     * oldest first, so this doesn't stack up several Bazaar navigations in a single tick. Re-reads a fresh
     * market price rather than reusing a stale one from whenever it was first queued.
     */
    private suspend fun retryPendingManualFlips() {
        if (!enabled || pendingManualFlips.isEmpty()) return
        if (!tryClaimBusy()) return
        try {
            val itemName = pendingManualFlips.firstOrNull() ?: return
            startFlip(itemName, "/hxp bz flip")
            pendingManualFlips.remove(itemName)
        } finally {
            busy = false
        }
    }

    /**
     * Entry point for `/hxp fuse bz`: makes [de.hxp.hxpaddons.utils.skyblock.fusion.ShardFusionScanner]'s current
     * top pick "just work" without the player manually pricing/buying the two input shards themselves - picks
     * the best fuse, Buy-Orders as many complete sets of both shards as the purse (capped the same way
     * [startFlip] caps every other fresh Buy Order - [maxOrderPursePercent] of capital, [bestFlipBudget] as a
     * hard ceiling) can afford, then waits for both to fill (via the normal filled-order chat trigger, routed
     * through [pendingFuse]/[handleClaimedFuseShard] instead of the usual resell path) before handing off to
     * [Fuser.start]. Deliberately does NOT reuse [startFlip] - that registers the item in
     * [activelyManagedItems] for this module's normal ongoing claim/resell cycle, which is exactly what must
     * NOT happen here (these shards get fused, not sold back).
     */
    fun startShardFuse() {
        if (pendingFuse != null) {
            modMessage("§eA /hxp fuse bz run is already in progress - waiting for both shards to fill.")
            return
        }
        if (!enabled) toggle()
        if (!tryClaimBusy()) {
            modMessage("§eAuto Bazaar Flipper is busy right now - try /hxp fuse bz again shortly.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                runShardFuseStart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: /hxp fuse bz failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c during /hxp fuse bz, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun runShardFuseStart() {
        modMessage("§7/hxp fuse bz: scanning for the current best shard fusion...")
        val best = ShardFusionScanner.scan(limit = 1).getOrElse {
            modMessage("§cAuto Bazaar Flipper: shard-fusion scan failed (${it.message}).")
            return
        }.firstOrNull() ?: run {
            modMessage("§eAuto Bazaar Flipper: no profitable shard fusion currently found.")
            return
        }

        val purse = readPurseBalance()
        val portfolio = readBazaarPortfolio()
        val totalCapital = purse?.plus(portfolio.lockedValue)
        // Same capital-sizing shape as [startFlip]: a share of total capital (purse + whatever's already
        // locked in other orders), never more than the real liquid purse, [bestFlipBudget] as a hard ceiling
        // on top either way.
        val desiredShare = (totalCapital ?: bestFlipBudget.toDouble()) * (maxOrderPursePercent / 100.0)
        val usableCapital = if (purse != null) minOf(desiredShare, purse) else desiredShare
        val budget = minOf(usableCapital, bestFlipBudget.toDouble())

        val setCost = best.input1Qty * best.input1Price + best.input2Qty * best.input2Price
        val maxSets = (budget / setCost).toInt()
        if (maxSets <= 0) {
            modMessage(
                "§cAuto Bazaar Flipper: not enough budget for even one ${best.input1Name}+${best.input2Name} fuse " +
                    "(need ~${setCost.toLong()} coins, budget ~${budget.toLong()})."
            )
            return
        }
        val amount1 = (maxSets * best.input1Qty).coerceAtMost(maxOrderCapFor(best.input1Name))
        val amount2 = (maxSets * best.input2Qty).coerceAtMost(maxOrderCapFor(best.input2Name))

        modMessage(
            "§7/hxp fuse bz: best fuse is §f${best.input1Name} + ${best.input2Name} -> ${best.outputName}§7 " +
                "(~${formatGrouped(best.profitPerHour)}/h) - buying §f${amount1}x ${best.input1Name}§7 and §f${amount2}x ${best.input2Name}§7..."
        )

        openBazaar()
        val firstScreen = waitForScreen { true } ?: run {
            modMessage("§cAuto Bazaar Flipper: Bazaar menu did not open in time for /hxp fuse bz.")
            return
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(firstScreen, "Bazaar main (/hxp fuse bz: ${best.input1Name})")

        val placed1 = placeOrderViaSearch(best.input1Name, OrderType.BUY, firstScreen) { amount1 }
        if (placed1 <= 0) {
            modMessage("§cAuto Bazaar Flipper: failed to place a Buy Order for ${best.input1Name} - aborting /hxp fuse bz.")
            return
        }
        closeScreen()
        if (!ensureOrderExists(best.input1Name, placed1, OrderType.BUY, "/hxp fuse bz")) return

        randomDelay(GUI_APPEAR_DELAY)
        openBazaar()
        val secondScreen = waitForScreen { true } ?: run {
            modMessage("§cAuto Bazaar Flipper: Bazaar menu did not reopen in time for ${best.input2Name} - ${best.input1Name}'s order is already live, cancel it manually if you don't want it.")
            return
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(secondScreen, "Bazaar main (/hxp fuse bz: ${best.input2Name})")

        val placed2 = placeOrderViaSearch(best.input2Name, OrderType.BUY, secondScreen) { amount2 }
        if (placed2 <= 0) {
            modMessage("§cAuto Bazaar Flipper: failed to place a Buy Order for ${best.input2Name} - ${best.input1Name}'s order is already live, cancel it manually if you don't want it.")
            return
        }
        closeScreen()
        if (!ensureOrderExists(best.input2Name, placed2, OrderType.BUY, "/hxp fuse bz")) return

        pendingFuse = PendingFuse(best.input1Name, placed1, best.input2Name, placed2, best.outputName)
        modMessage(
            "§aAuto Bazaar Flipper: Buy Orders placed for §f${placed1}x ${best.input1Name}§a and §f${placed2}x ${best.input2Name}§a - " +
                "will fuse into §f${best.outputName}§a automatically once both fill."
        )
    }

    /**
     * One "Find Best Flip" result line, with a clickable "§b[Fuse options?]" suffix appended
     * (2026-08-13, on request) - runs `/hxp fuse craft <itemName>` ([de.hxp.hxpaddons.commands.fuseCraftCommand])
     * when clicked, i.e. [ShardFusionScanner.scanBestRecipesFor]'s "what are the best ways to FUSE my way into
     * this item" answer for whichever item Best Flip just suggested buying outright. Deliberately unconditional
     * (no upfront check whether [c.itemName] is even a shard) - that would mean running a full fusion scan for
     * every one of Best Flip's own candidates just to decide whether to show the hint, when clicking it already
     * costs nothing until actually pressed; a non-shard item just gets `/hxp fuse craft`'s normal "no viable
     * fusion produces this" reply, which is a perfectly fine answer on its own.
     */
    private fun bestFlipCandidateLine(index: Int, c: BestFlipCandidate): Component {
        val summary = Component.literal(
            "§7${index + 1}. §f${c.itemName}§7: §a${formatGrouped(c.profitPerUnit)}§7/unit profit, " +
                "§f${formatGrouped(c.limitingRatePerHour.toInt().toDouble())}§7/h throughput §7-> " +
                "§a${formatGrouped(c.scorePerHour)}§7 coins/h potential (${"%.1f".format(c.roiPercent)}% ROI) "
        )
        val fuseLink = Component.literal("§b[Fuse options?]").withStyle {
            it.withClickEvent(ClickEvent.RunCommand("/hxp fuse craft ${c.itemName}"))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("§7Click to check the best shard-fusion recipes to produce §f${c.itemName}§7 instead of buying it outright.")))
        }
        return summary.append(fuseLink)
    }

    private fun triggerBestFlip() {
        if (!enabled) return
        if (!tryClaimBusy()) {
            modMessage("§cAuto Bazaar Flipper: already busy, ignoring the Best Flip trigger.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                val budget = bestFlipBudget.toDouble()
                val candidates = findBestFlips(budget)
                if (candidates.isEmpty()) {
                    modMessage("§cAuto Bazaar Flipper: no flip cleared the liquidity/ROI sanity checks within a ${formatGrouped(budget)} budget.")
                    return@launch
                }

                modMessage("§aBest Flip candidates (budget §f${formatGrouped(budget)}§a coins):")
                candidates.forEachIndexed { i, c -> modMessage(bestFlipCandidateLine(i, c)) }

                if (autoStartBestFlip) {
                    val best = candidates.first()
                    startFlip(best.itemName, "Best Flip", fallbackBid = best.bid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: Best Flip search failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c finding the Best Flip, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Claims/flips (or combine-crafts, for a combinable book) every order in [toProcess] - shared by
     * [runCycle] (which wraps this in its own [busy]/[job] management for a fire-and-forget call) and any
     * caller that's already inside its own busy=true/finally scope (e.g. [discoverUntrackedOrders], which
     * needs to await this directly rather than have a second, concurrent [runCycle] job race its own
     * `finally { busy = false }`).
     *
     * Confirmed live: with a single try/catch wrapped around the whole loop, an exception while flipping the
     * *first* filled order in a batch (e.g. two orders filling close enough together to land in the same
     * toProcess list) aborted the loop outright, silently dropping every order after it - since pendingFlips
     * was already cleared before this ran, that dropped order never got reprocessed. Each order now gets its
     * own try/catch so one failure doesn't take the rest of the batch down with it.
     *
     * Confirmed live (2026-08-14): while a `/hxp bz collect`/`/hxp bz collect npc` collection was active, a completely unrelated
     * order filling (something with no connection to the collection at all) still fell through to the normal
     * `flipOrder` path below and got claimed + turned into a Sell Offer - exactly what a player isolating one
     * item's accumulation (e.g. `/hxp bz collect queen bee shard 249`) does NOT want happening to anything else in
     * the meantime. Fixed by skipping any order outright, before it ever reaches the dispatch below, if
     * [pendingBuyCollect] is non-empty and this particular item isn't one of the collections currently
     * tracked - on request ("er hat andere orders geclaimt und sie gesell ordert obwohl er das nicht sollte"
     * / "alle andere items ignorieren ... wirklich nur den queen bee shard").
     */
    private suspend fun processClaimedOrders(toProcess: List<ClaimedOrder>) {
        for (order in toProcess) {
            try {
                val collect = findPendingBuyCollect(order.itemName)
                if (pendingBuyCollect.isNotEmpty() && collect == null) {
                    devMessage("[BazaarFlipper] Ignoring filled ${order.type.name.lowercase()} order for ${order.itemName} - a /hxp bz collect/hxp bz collect npc collection is active and this item isn't part of it.")
                    continue
                }

                val bookMatch = if (combineBooks) bookLevelRegex.find(order.itemName) else null
                val bookLevel = bookMatch?.let { romanToInt(it.groupValues[2]) }
                val fuse = pendingFuse
                when {
                    bookMatch != null && order.type == OrderType.BUY && bookLevel == 1 && order.amount >= 2 -> {
                        val isArabic = bookMatch.groupValues[2].all { it.isDigit() }
                        combineAndSellBooks(order, bookMatch.groupValues[1], isArabic)
                    }
                    // A Sell Offer for an item WE listed via the combine flow (tracked, not just
                    // book-name-shaped - see trackedBookSellItems) already has its level 1 re-buy
                    // handled at the end of combineAndSellBooks, so the normal flip path must not
                    // also fire here (it would buy the sold book straight back).
                    order.type == OrderType.SELL && trackedBookSellItems.contains(order.itemName.lowercase()) -> {
                        trackedSellOrders.remove(order.itemName.lowercase())
                        devMessage("[BazaarFlipper] Ignoring filled Sell Offer for ${order.itemName} - already handled by the Books Combined cycle.")
                    }
                    // /hxp fuse bz's own two Buy Orders - see [pendingFuse]'s own doc for why these must not fall
                    // through to the normal resell path.
                    fuse != null && order.type == OrderType.BUY && order.itemName.equals(fuse.shard1Name, ignoreCase = true) ->
                        handleClaimedFuseShard(order, fuse, isShard1 = true)
                    fuse != null && order.type == OrderType.BUY && order.itemName.equals(fuse.shard2Name, ignoreCase = true) ->
                        handleClaimedFuseShard(order, fuse, isShard1 = false)
                    // /hxp bz collect's tracked Buy Orders - see [pendingBuyCollect]'s own doc for why these must not
                    // fall through to the normal resell path either.
                    collect != null && order.type == OrderType.BUY -> handleClaimedBuyCollect(order, collect)
                    else -> flipOrder(order)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: flipping ${order.itemName} failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c flipping ${order.itemName}, check logs for details - continuing with the rest of the batch.")
            }
            randomDelay(400)
        }
    }

    /** Fire-and-forget entry point (chat-triggered fills, via the TickEvent.End listener): manages [busy]/[job] itself, so never call this from somewhere that's already inside its own busy=true/finally scope - see [processClaimedOrders] for that case (e.g. [discoverUntrackedOrders], [checkForFilledTrackedOrders]). */
    private fun runCycle(toProcess: List<ClaimedOrder>) {
        if (!tryClaimBusy()) {
            // Lost the race for [busy] to some other coroutine that grabbed it in between this call's own
            // caller checking `busy` and actually reaching here (both of runCycle's callers run on their own
            // separate coroutine - the tick-event listener and the undercut watcher's safety net - so this
            // genuinely can happen with [HxPMod.scope]'s multi-threaded dispatcher). The batch is already
            // out of [pendingFlips] by this point, so it can't just be dropped - re-queued instead, so the
            // very next tick (or the periodic discovery scan, which independently re-detects the same
            // fully-filled orders anyway) picks it back up instead of it silently vanishing.
            devMessage("[BazaarFlipper] runCycle: lost the busy race for ${toProcess.joinToString { it.itemName }} - re-queuing for the next tick.")
            pendingFlips.addAll(toProcess)
            return
        }
        job = HxPMod.scope.launch {
            try {
                processClaimedOrders(toProcess)
            } finally {
                closeScreen()
                busy = false
            }
        }
    }

    /**
     * Runs one [init]-registered periodic loop's per-tick [action], catching anything it throws instead of
     * letting it escape into the loop's own `while (true)`. Confirmed live: unlike [apiWatcherJob] (which
     * restarts itself the next time [trackOrderForUndercutWatch] runs), none of these `while (true)` loops
     * are ever restarted once their coroutine ends - a single uncaught exception (a stale screen, a bad
     * item name, a network hiccup) used to silently kill that whole periodic mechanism (e.g. the "always
     * relist a missing Buy Order" check) for the rest of the session, with nothing beyond a stack trace in
     * the log to show it had happened.
     */
    private suspend fun runPeriodicSafely(label: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HxPMod.logger.error("BazaarFlipper: periodic $label failed", e)
            devMessage("§cBazaarFlipper: periodic $label failed (${e.message}) - will retry next interval.")
        }
    }

    /**
     * Jittered pacing delay so the click cadence doesn't look robotic - but additive-only (base + random
     * 0..30%), never subtracted, so [base] stays a hard floor: a click gated by this never fires faster
     * than [base] after the GUI it's clicking in appeared.
     */
    private suspend fun randomDelay(base: Long) {
        val jitter = (base * 0.3).toLong().coerceAtLeast(1)
        delay(base + Random.nextLong(0, jitter + 1))
    }

    /** Mirrors NoammAddons' `GuiUtils.clickSlot` - always reads the live open menu instead of a cached screen. */
    /**
     * Bounds-checks against whatever menu is actually open at execution time (not when this was called) -
     * confirmed live that a stale slot index (the screen having already moved on to something smaller by
     * the time [mc.execute] ran this) crashes the client with an [IndexOutOfBoundsException] otherwise.
     */
    private fun click(slotIndex: Int, button: Int = 0, clickType: ContainerInput = ContainerInput.PICKUP) {
        mc.execute {
            val menu = mc.player?.containerMenu ?: return@execute
            if (slotIndex !in menu.slots.indices) {
                devMessage("§cBazaarFlipper: skipped click on slot #$slotIndex - current menu only has ${menu.slots.size} slots (screen changed underneath us?).")
                return@execute
            }
            mc.player?.clickSlot(menu.containerId, slotIndex, button, clickType)
        }
    }

    private suspend fun flipOrder(order: ClaimedOrder) {
        val ordersScreen = openOrdersScreen() ?: run {
            modMessage("§cAuto Bazaar Flipper: couldn't open Manage Orders for ${order.itemName}.")
            return
        }
        dumpScreen(ordersScreen, "Manage Orders")

        val slot = findOrderSlot(ordersScreen, order) ?: run {
            devMessage("§cBazaarFlipper: couldn't find the order slot for ${order.amount}x ${order.itemName} (${order.type.name}) - already claimed manually?")
            closeScreen()
            return
        }
        val wantType = if (order.type == OrderType.BUY) OrderType.SELL else OrderType.BUY

        // Right-click claims the filled order and opens an "Order options" screen whose "Flip Order"
        // button already lists the current best competing offers in its own tooltip. Server-lag failsafe:
        // if the click doesn't register (no options screen shows up), re-opens Manage Orders and re-finds
        // the slot fresh rather than blindly re-clicking a slot index that might not even be valid anymore.
        var optionsScreen: AbstractContainerScreen<*>? = null
        var claimSlot = slot
        var claimAttempts = 0
        while (optionsScreen == null && claimAttempts < 3) {
            claimAttempts++
            click(claimSlot, 1)
            randomDelay(GUI_APPEAR_DELAY)
            optionsScreen = mc.screen as? AbstractContainerScreen<*>
            if (optionsScreen != null || claimAttempts >= 3) break
            devMessage("[BazaarFlipper] No options screen after right-clicking ${order.itemName}'s filled order (attempt $claimAttempts, lag?) - reopening Manage Orders and retrying.")
            val retryOrdersScreen = openOrdersScreen() ?: break
            claimSlot = findOrderSlot(retryOrdersScreen, order) ?: break
        }
        val confirmedOptionsScreen = optionsScreen ?: run {
            devMessage("§cBazaarFlipper: no container screen open after right-clicking the filled order for ${order.itemName} (gave up after $claimAttempts attempt(s)).")
            return
        }
        dumpScreen(confirmedOptionsScreen, "After right-click")

        val flipSlot = confirmedOptionsScreen.findSlot("flip order")
        if (flipSlot == null) {
            // Observed for a filled Sell Offer (which wants to create a new Buy Order back) - Hypixel's
            // "Flip Order" convenience only seems to exist one direction, so fall back to manually
            // searching the item up and creating the opposite order from scratch.
            devMessage("[BazaarFlipper] No 'Flip Order' button for ${order.amount}x ${order.itemName} (${order.type.name}) - using manual search + create ${wantType.name.lowercase()} order flow.")
            manualCreateOrder(order, wantType, confirmedOptionsScreen)
            return
        }
        val flipLore = confirmedOptionsScreen.menu.items.getOrNull(flipSlot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
        val topOffers = topOfferRegex.findAll(flipLore).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
        if (topOffers.isEmpty()) {
            devMessage("§cBazaarFlipper: couldn't read any 'Top Offers' prices from the Flip Order button for ${order.itemName}. Lore: $flipLore")
            closeScreen()
            return
        }
        val targetPrice = topOffers.min() - 0.1

        // Flip Order opens a real sign-edit GUI (not a chest-like container) to type the new price into.
        click(flipSlot)
        randomDelay(SIGN_APPEAR_DELAY)

        val signScreen = mc.screen as? AbstractSignEditScreen ?: run {
            devMessage("§cBazaarFlipper: expected a sign price input after 'Flip Order' for ${order.itemName}, got ${mc.screen?.let { it::class.simpleName } ?: "no screen"}.")
            closeScreen()
            return
        }
        // Extra pause before "typing" - gives the sign GUI time to fully settle before we submit text to it.
        randomDelay(SIGN_TYPE_DELAY)
        submitSignText(signScreen, formatPrice(targetPrice))
        randomDelay(SIGN_CONFIRM_DELAY)

        // [submitSignText] closes the sign screen itself (mc.setScreen(null)) the instant it sends the
        // price - a single fixed-delay-then-check right after used to occasionally read that still-null gap
        // as "nothing came back, no place-order button needed", silently skip the click below, and then
        // closeScreen() a screen that was just about to open. Polling (bounded, same pattern
        // [openOrdersScreen] uses) instead of one snapshot catches a confirmation screen that's merely a beat
        // late rather than genuinely absent - [findPlaceOrderSlot] on a still-null result is only actually
        // "not needed" once this window has run out.
        val afterSign = waitForScreen(3000) { true }
        afterSign?.let { dumpScreen(it, "After sign submit") }

        val placeSlot = findPlaceOrderSlot(afterSign, wantType, order.itemName)
        if (placeSlot != null) {
            randomDelay(SIGN_CONFIRM_DELAY)
            click(placeSlot)
            randomDelay(SIGN_CONFIRM_DELAY)
            (mc.screen as? AbstractContainerScreen<*>)?.let { dumpScreen(it, "After place order") }
        } else if (afterSign == null) {
            devMessage("[BazaarFlipper] No confirmation screen appeared after submitting the Flip Order sign price for ${order.itemName} within 3s - assuming none was needed.")
        }

        closeScreen()
        if (!ensureOrderExists(order.itemName, order.amount, wantType, "Flip")) return

        if (notifyOnFlip) {
            modMessage(
                "§aFlipped §f${order.amount}x ${order.itemName}§a: claimed ${order.type.name.lowercase()} order §7-> §a" +
                    "created new ${wantType.name.lowercase()} order at §f${formatPrice(targetPrice)}§a."
            )
        }
        // Confirmed live: the just-claimed order.type side is gone now (claimed away by this exact flip) -
        // its tracked entry (if any) never got removed here, only the new wantType side got (re)tracked. A
        // stale leftover entry then kept getting "checked" every pass against the live market with its old
        // price/amount, and if a *later*, unrelated order of that same item+type ever got listed again, an
        // undercut/outbid comparison against that stale entry could act on the wrong order entirely.
        if (order.type == OrderType.SELL) trackedSellOrders.remove(order.itemName.lowercase())
        else trackedBuyOrders.remove(order.itemName.lowercase())
        if (undercutStaleOrders) trackOrderForUndercutWatch(order.itemName, wantType, order.amount, targetPrice)
        if (order.type == OrderType.BUY) rebuySameItemAfterFlip(order.itemName, order.amount)
    }

    /**
     * Fallback path for when "Flip Order" isn't available (observed for a filled Sell Offer): manually
     * navigates back out to the main Bazaar screen, uses its item search, creates the opposite order
     * from scratch with the same amount, and prices it via the "+0.1"/"-0.1" preset button - the same
     * one this module used before "Flip Order" was discovered for the other direction.
     */
    private suspend fun manualCreateOrder(order: ClaimedOrder, wantType: OrderType, fromScreen: AbstractContainerScreen<*>) {
        // Max Order only makes sense sizing a Buy Order (capped by purse/Hypixel's cap) - a Sell Offer
        // prices off whatever's physically held, so order.amount is already the most it could ever be.
        val amount = if (wantType == OrderType.BUY && maxOrderEnabled) {
            calculateMaxBuyAmount(order.itemName)?.takeIf { it > 0 } ?: order.amount
        } else order.amount

        if (placeOrderViaSearch(order.itemName, wantType, fromScreen) { amount } <= 0) return
        closeScreen()
        if (!ensureOrderExists(order.itemName, amount, wantType, "Flip")) return

        if (notifyOnFlip) {
            modMessage(
                "§aFlipped §f${order.amount}x ${order.itemName}§a: claimed ${order.type.name.lowercase()} order §7-> §a" +
                    "created new ${wantType.name.lowercase()} order for §f${amount}x§a (manual search flow)."
            )
        }
        // Same stale-entry cleanup as flipOrder's matching comment - the claimed order.type side is gone now.
        if (order.type == OrderType.SELL) trackedSellOrders.remove(order.itemName.lowercase())
        else trackedBuyOrders.remove(order.itemName.lowercase())
        if (undercutStaleOrders) {
            randomDelay(GUI_APPEAR_DELAY)
            val price = readOwnPrice(order.itemName, wantType)
            if (price != null) trackOrderForUndercutWatch(order.itemName, wantType, amount, price)
            else devMessage("§cBazaarFlipper: couldn't read ${order.itemName}'s listed price - not starting the undercut watch for it.")
        }
        if (order.type == OrderType.BUY) rebuySameItemAfterFlip(order.itemName, order.amount)
    }

    /**
     * A plain flip (see [flipOrder]/[manualCreateOrder]) only ever creates the *opposite* order - a filled
     * Buy Order claims into a Sell Offer, with nothing left buying [itemName] again afterwards, so the
     * cycle stops dead until the player re-lists a Buy Order by hand. Mirrors how the "Books Combined" cycle
     * already re-buys after selling: places a fresh Buy Order for the same [itemName]/[amount] right after,
     * so a plain (non-combine) flip keeps running on its own too. Skipped for a level 5 enchant book (see
     * both call sites) - that's the combine flow's own end product, meant to be crafted up from a level 1
     * Buy Order rather than bought directly at level 5.
     */
    private suspend fun rebuySameItemAfterFlip(itemName: String, defaultAmount: Int) {
        val bookMatch = bookLevelRegex.find(itemName)
        if (bookMatch != null && romanToInt(bookMatch.groupValues[2]) == 5) {
            devMessage("[BazaarFlipper] Not re-buying $itemName after flipping it - it's a level 5 book (combine flow's end product).")
            return
        }

        val amount: Int
        if (maxOrderEnabled) {
            // calculateMaxBuyAmount already accounts for the purse, so 0/null here means the same thing the
            // non-Max-Order purse check below means: nothing affordable right now.
            val maxAmount = calculateMaxBuyAmount(itemName)
            if (maxAmount == null || maxAmount <= 0) {
                devMessage(
                    "[BazaarFlipper] Skipping the re-buy of $itemName for now - Max Order found nothing affordable. The Sell " +
                        "Offer just listed will flip back into a new Buy Order once it fills and gets claimed."
                )
                return
            }
            amount = maxAmount
        } else {
            // The Buy Order that just filled already spent its coins, and the Sell Offer this flip just
            // created (see flipOrder/manualCreateOrder's call site) hasn't paid out yet - claiming it happens
            // later, once it fills. So the purse might not actually cover a fresh Buy Order right now. Check
            // first rather than attempting and failing: if there's not enough, just skip for now rather than
            // erroring - once that Sell Offer fills, the normal chat-triggered flip path (a filled Sell Offer
            // flips into a new Buy Order, see flipOrder) re-buys automatically anyway, by which point
            // claiming it has put the coins back in the purse. Fails open (proceeds as before) if either
            // number can't be read, rather than blocking the whole re-buy on an unconfirmed price/purse read.
            val estimatedUnitCost = readMarketTopPrice(itemName, OrderType.SELL)
            val purse = readPurseBalance()
            if (estimatedUnitCost != null && purse != null && purse < estimatedUnitCost * defaultAmount) {
                devMessage(
                    "[BazaarFlipper] Skipping the re-buy of ${defaultAmount}x $itemName for now - purse ($purse) looks short of " +
                        "the estimated cost (${estimatedUnitCost * defaultAmount}). The Sell Offer just listed will flip back " +
                        "into a new Buy Order once it fills and gets claimed, by which point there should be coins again."
                )
                return
            }
            amount = defaultAmount
        }

        openBazaar()
        // See startFlip's matching comment - not requiring "bazaar" in the title, openBazaar() may have been
        // a no-op and placeOrderViaSearch backs out on its own regardless of where this lands.
        val mainScreen = waitForScreen { true } ?: run {
            devMessage("§cBazaarFlipper: Bazaar menu did not open in time to re-buy ${amount}x $itemName after flipping it.")
            return
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(mainScreen, "Bazaar main (re-buy $itemName after flip)")

        if (placeOrderViaSearch(itemName, OrderType.BUY, mainScreen) { amount } <= 0) return
        closeScreen()
        if (!ensureOrderExists(itemName, amount, OrderType.BUY, "Re-buy Buy Order")) return

        if (notifyOnFlip) {
            modMessage("§aKeeping the cycle going: placed a new Buy Order for §f${amount}x $itemName§a.")
        }
        if (undercutStaleOrders) {
            randomDelay(GUI_APPEAR_DELAY)
            val price = readOwnPrice(itemName, OrderType.BUY)
            if (price != null) trackOrderForUndercutWatch(itemName, OrderType.BUY, amount, price)
            else devMessage("§cBazaarFlipper: couldn't read $itemName's listed price - not starting the outbid watch for it.")
        }
    }

    /**
     * Core of [manualCreateOrder] and [createBookSellOrder]: from any screen, navigates to the item
     * search (backing out first if [fromScreen] doesn't already show one), searches for [itemName],
     * opens its page, creates the [wantType] order, and prices it via the "+0.1"/"-0.1" preset. Returns 0
     * (after already closing the screen and logging) on any unexpected step, or the actual amount placed
     * on success, so callers only need to handle the success path.
     *
     * The amount itself comes from [amountProvider] rather than a plain fixed number, called with the
     * current top competing price read straight off the "$createKeyword" button's own lore the moment it's
     * found (null if that couldn't be read - only relevant for a BUY order, whose amount is usually
     * price-dependent; a SELL offer's provider can just ignore it). Confirmed live this used to be a plain
     * `amount: Int` parameter, with callers like [startFlip] first making a *whole separate* navigation pass
     * (via [readMarketTopPrice]) just to read this same price, closing that, and then calling this function
     * to navigate right back to the very same item page a second time to actually place the order - two
     * round trips to Hypixel for what's really one visit. Reading the price here, in the middle of the one
     * navigation this already has to do to click "$createKeyword" anyway, avoids that entirely.
     */
    private suspend fun placeOrderViaSearch(
        itemName: String,
        wantType: OrderType,
        fromScreen: AbstractContainerScreen<*>,
        amountProvider: suspend (topPrice: Double?) -> Int,
    ): Int {
        var screen = fromScreen
        var searchSlot: Int? = screen.findSlot("search")
        var backAttempts = 0
        while (searchSlot == null && backAttempts++ < 3) {
            val backSlot = screen.findSlot("go back") ?: run {
                devMessage("§cBazaarFlipper: no 'Go Back' button found for $itemName while navigating to search.")
                closeScreen()
                return 0
            }
            click(backSlot)
            randomDelay(CREATE_ORDER_GUI_DELAY)
            screen = mc.screen as? AbstractContainerScreen<*> ?: run {
                devMessage("§cBazaarFlipper: no container screen open after 'Go Back' for $itemName.")
                return 0
            }
            dumpScreen(screen, "After Go Back")
            searchSlot = screen.findSlot("search")
        }
        val foundSearchSlot = searchSlot ?: run {
            devMessage("§cBazaarFlipper: couldn't find a 'Search' button while navigating back for $itemName.")
            closeScreen()
            return 0
        }

        click(foundSearchSlot)
        randomDelay(CREATE_ORDER_GUI_DELAY)
        if (!submitTextInput(itemName)) {
            devMessage("§cBazaarFlipper: couldn't submit search text '$itemName' - unexpected screen: ${mc.screen?.let { it::class.simpleName } ?: "none"}.")
            closeScreen()
            return 0
        }
        // Same self-closing-sign gap as the Flip Order price prompt (see [flipOrder]'s matching comment) -
        // poll instead of one snapshot check.
        val resultsScreen = waitForScreen(3000) { true } ?: run {
            devMessage("§cBazaarFlipper: no container screen open after searching for $itemName.")
            return 0
        }
        dumpScreen(resultsScreen, "Search results")

        val itemSlot = findBestItemMatch(resultsScreen, itemName) ?: run {
            devMessage("§cBazaarFlipper: couldn't find '$itemName' in the search results.")
            closeScreen()
            return 0
        }
        click(itemSlot)
        // Confirmed live: a fixed delay + single snapshot check here kept closing out on $itemName's item
        // page before it had actually finished appearing (often enough to notice) - waits longer and polls
        // specifically for the "$createKeyword" button to actually be present, rather than just "some
        // screen showed up" (which could still be the stale search results, a half-rendered item page, etc.).
        val createKeyword = if (wantType == OrderType.SELL) "create sell offer" else "create buy order"
        val itemScreen = waitForScreen(6000) { it.findSlot(createKeyword) != null } ?: run {
            devMessage("§cBazaarFlipper: no '$createKeyword' button appeared on $itemName's page within 6s of selecting it from search.")
            return 0
        }
        dumpScreen(itemScreen, "Item page")

        val createSlot = itemScreen.findSlot(createKeyword) ?: run {
            devMessage("§cBazaarFlipper: no '$createKeyword' button found on $itemName's page.")
            closeScreen()
            return 0
        }
        // Reads the current top competing price straight off the "$createKeyword" button's own lore -
        // same "Top Orders"/"Top Offers" list [readMarketTopPrice] used to make a whole separate navigation
        // pass just to read (see this function's doc comment). Buy Orders list bids descending (highest =
        // current top bid, what we'd need to outbid); Sell Offers list asks ascending (lowest = current top
        // ask, what we'd need to undercut).
        val createLore = itemScreen.menu.items.getOrNull(createSlot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
        val topPrices = topOfferRegex.findAll(createLore).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
        val topPrice = if (wantType == OrderType.SELL) topPrices.minOrNull() else topPrices.maxOrNull()
        if (topPrice == null) {
            devMessage("§cBazaarFlipper: couldn't read any '$createKeyword' Top Orders/Offers prices for $itemName. Lore: $createLore")
        }
        val amount = amountProvider(topPrice)
        if (amount <= 0) {
            devMessage("§cBazaarFlipper: amountProvider returned $amount for $itemName - not placing an order.")
            closeScreen()
            return 0
        }
        // Confirmed live: this specific transition kept failing even after raising the flat delay - a wrong
        // or dropped click doesn't get fixed by waiting longer for it. So instead of one fixed wait + a
        // single check, this polls for the title to actually change away from the item page (same pattern
        // [openOrdersScreen] already uses for its own "Manage Orders" transition), and if it still hasn't
        // after a few seconds, clicks [createSlot] again once before giving up - covers a lost/mis-registered
        // click, not just a slow-to-render screen.
        //
        // Confirmed live: the screen *title* changes before its *content* (buttons/slots) actually populates
        // - a dump right after a title-only wait once showed a "How many do you want?" screen with every
        // single slot still blank. So every wait below polls for the specific button it's about to click,
        // not just "the title changed" or "some screen is open".
        val itemPageTitle = itemScreen.title.string.noControlCodes
        // Sell Offers price off whatever's already in the inventory - Hypixel goes straight from "Create
        // Sell Offer" to the price-preset screen, no separate amount step. Buy Orders have nothing physical
        // to size off of, so those still go through "Custom Amount" -> sign input first.
        val priceKeyword = if (wantType == OrderType.SELL) "-0.1" else "+0.1"
        val firstScreenKeyword = if (wantType == OrderType.BUY) "custom amount" else priceKeyword
        click(createSlot)
        var priceScreen = waitForScreen(6000) { it.title.string.noControlCodes != itemPageTitle && it.findSlot(firstScreenKeyword) != null }
        if (priceScreen == null) {
            devMessage("§cBazaarFlipper: no '$firstScreenKeyword' button appeared for $itemName after clicking '$createKeyword' within 6s - retrying the click once.")
            click(createSlot)
            priceScreen = waitForScreen(6000) { it.title.string.noControlCodes != itemPageTitle && it.findSlot(firstScreenKeyword) != null } ?: run {
                devMessage("§cBazaarFlipper: no '$firstScreenKeyword' button appeared for $itemName, even after retrying the '$createKeyword' click.")
                return 0
            }
        }
        dumpScreen(priceScreen, if (wantType == OrderType.SELL) "Price screen (sell offer, amount = held stack)" else "Amount screen")

        if (wantType == OrderType.BUY) {
            val customAmountSlot = priceScreen.findSlot("custom amount") ?: run {
                devMessage("§cBazaarFlipper: no 'Custom Amount' button found for $itemName.")
                closeScreen()
                return 0
            }
            click(customAmountSlot)
            randomDelay(CREATE_ORDER_GUI_DELAY)
            if (!submitTextInput(amount.toString())) {
                devMessage("§cBazaarFlipper: couldn't submit amount '$amount' for $itemName - unexpected screen: ${mc.screen?.let { it::class.simpleName } ?: "none"}.")
                closeScreen()
                return 0
            }
            // Same self-closing-sign gap as the Flip Order price prompt, plus the same title-before-content
            // gap as above - poll for the actual preset button, not just "some screen".
            priceScreen = waitForScreen(6000) { it.findSlot(priceKeyword) != null } ?: run {
                devMessage("§cBazaarFlipper: no '$priceKeyword' button appeared on the price screen for $itemName after entering the amount.")
                return 0
            }
            dumpScreen(priceScreen, "Price screen")
        }

        // Same preset button this module used before "Flip Order" was found for the other direction:
        // buy orders outbid the current highest via "+0.1", sell offers undercut the current lowest via "-0.1".
        val priceSlot = priceScreen.findSlot(priceKeyword) ?: run {
            devMessage("§cBazaarFlipper: no '$priceKeyword' button found on the price screen for $itemName.")
            closeScreen()
            return 0
        }
        click(priceSlot)
        randomDelay(CREATE_ORDER_SIGN_CONFIRM_DELAY)

        val confirmScreen = mc.screen as? AbstractContainerScreen<*>
        confirmScreen?.let { dumpScreen(it, "Before place order") }

        val placeSlot = findPlaceOrderSlot(confirmScreen, wantType, itemName)
        if (placeSlot != null) {
            click(placeSlot)
            randomDelay(CREATE_ORDER_GUI_DELAY)
            (mc.screen as? AbstractContainerScreen<*>)?.let { dumpScreen(it, "After place order") }
        }
        return amount
    }

    private data class DetectedBook(val baseName: String, val isArabic: Boolean)

    /**
     * Parses a combinable enchant book's name/level out of [stack]'s lore - the marker is "Combinable in
     * Anvil" (confirmed live), with the enchant+level itself sitting on the first non-blank lore line after
     * that marker (e.g. "Bank I"). Returns null for anything else (a non-book item, or an item with no such
     * lore at all). Shared by [detectAllCombinableBookTypes] and the level-number overlay ([showBookLevels]).
     */
    private fun parseCombinableBook(stack: ItemStack): Triple<String, Int, Boolean>? {
        if (stack.isEmpty) return null
        val lore = stack.loreString.map { it.noControlCodes }
        val markerIndex = lore.indexOfFirst { it.contains("Combinable in Anvil", ignoreCase = true) }
        if (markerIndex == -1) return null
        val nameLine = lore.drop(markerIndex + 1).firstOrNull { it.isNotBlank() } ?: return null
        val match = bookLevelRegex.find(nameLine.trim()) ?: return null
        val isArabic = match.groupValues[2].all { it.isDigit() }
        return Triple(match.groupValues[1], romanToInt(match.groupValues[2]), isArabic)
    }

    /**
     * Scans the player's real inventory for every distinct combinable enchant book currently held (any
     * level), not just one specific enchant. Used so a whole batch of leftovers from *other* enchants
     * sitting in the inventory (a previous partial run, a second concurrently-flipped book) get crafted up
     * in the same pass instead of being ignored because they don't match whatever book triggered this
     * particular cycle.
     */
    private fun detectAllCombinableBookTypes(): Set<DetectedBook> {
        val found = mutableSetOf<DetectedBook>()
        mc.player?.inventory?.forEach { stack ->
            val (baseName, _, isArabic) = parseCombinableBook(stack) ?: return@forEach
            found.add(DetectedBook(baseName, isArabic))
        }
        return found
    }

    /**
     * "Books Combined" entry point (see class doc): claims [order] with a plain left-click (goes straight
     * to the inventory, unlike [flipOrder]'s right-click), then crafts up *every* combinable book type
     * currently held (see [detectAllCombinableBookTypes] - not just [baseName], in case other leftovers
     * are sitting in the inventory too) as far as each one goes, see [craftBooksUpToLevel5]. Only once
     * every detected type is maxed out (or genuinely stuck) does anything get listed as a Sell Offer,
     * rather than selling a half-finished batch the moment a single combine hiccups (confirmed live: that
     * used to happen with a full inventory, leaving level 2/3 leftovers sold off instead of pushed the
     * rest of the way to level 5 - and separately, a *different* book type sitting in the inventory used
     * to get left behind entirely). Finishes each book type with a fresh level 1 Buy Order sized to match
     * how many level 5 books actually resulted (16 per book), so the cycle keeps running at that scale.
     */
    private suspend fun combineAndSellBooks(order: ClaimedOrder, baseName: String, isArabic: Boolean) {
        val ordersScreen = openOrdersScreen() ?: run {
            modMessage("§cAuto Bazaar Flipper: couldn't open Manage Orders for ${order.itemName}.")
            return
        }
        dumpScreen(ordersScreen, "Manage Orders (combine)")

        val slot = findOrderSlot(ordersScreen, order) ?: run {
            devMessage("§cBazaarFlipper: couldn't find the order slot for ${order.amount}x ${order.itemName} (combine) - already claimed manually?")
            closeScreen()
            return
        }
        // On request: checked before claiming anything - see [hasInventorySpaceFor]'s own doc for why this
        // and the later cancel-to-consolidate step (inside processBookType) both only ever add items, and
        // why doing both close together for up to 256 individual books at once is a real overflow risk.
        val tightOnSpace = !hasInventorySpaceFor(order.itemName, order.amount)
        // Plain left-click just claims the order (books go straight to the inventory) - the right-click
        // used elsewhere in this module instead opens the "Flip Order" follow-up screen.
        claimOrderFully(slot, order.itemName)
        dumpPlayerInventory("Player inventory right after claiming ${order.itemName}")
        closeScreen()
        randomDelay(GUI_APPEAR_DELAY)

        val bookTypes = detectAllCombinableBookTypes().ifEmpty { setOf(DetectedBook(baseName, isArabic)) }
        devMessage("[BazaarFlipper] Crafting up ${bookTypes.size} book type(s) this pass: ${bookTypes.joinToString { it.baseName }}.")

        for (book in bookTypes) {
            val fallbackRebuy = if (book.baseName.equals(baseName, ignoreCase = true)) order.amount else null
            try {
                processBookType(book, fallbackRebuy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One book type throwing here used to propagate all the way up through runCycle's own
                // try/catch, aborting not just this book type but every other queued order in the same
                // cycle too. Catching per book keeps the rest of this pass (and the rest of runCycle) going.
                HxPMod.logger.error("BazaarFlipper: combine failed for ${book.baseName}", e)
                modMessage("§cAuto Bazaar Flipper: combining ${book.baseName} failed (${e.message}) - moving on to the next book type.")
            }
        }

        if (tightOnSpace) {
            // On request: claim first, sell/cancel+relist normally (both already just happened above), THEN
            // /pickupstash to recover anything that overflowed instead of landing in the inventory directly,
            // THEN run every book type through the exact same craft/sell/rebuy pass once more so whatever came
            // back actually gets combined and listed too - craftBooksUpToLevel5 always re-reads the real held
            // amount from scratch, so this naturally folds newly-recovered books straight back in.
            runPickupStash()
            for (book in bookTypes) {
                val fallbackRebuy = if (book.baseName.equals(baseName, ignoreCase = true)) order.amount else null
                try {
                    processBookType(book, fallbackRebuy)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    HxPMod.logger.error("BazaarFlipper: post-/pickupstash combine failed for ${book.baseName}", e)
                    modMessage("§cAuto Bazaar Flipper: combining ${book.baseName} failed after /pickupstash (${e.message}).")
                }
            }
        }
    }

    /**
     * Runs one book type all the way through: craft, sell in [SELL_BATCH_THRESHOLD]-sized batches as soon
     * as they're ready (see [craftBooksUpToLevel5]'s level 4 handling), keep checking the bazaar for more
     * already-claimable Buy Orders to top up with, and only once genuinely stuck - no more pairable books
     * at any level, no further order to claim - sell whatever's left and re-buy for the next round.
     * [fallbackRebuyAmount] is used only if this book never reaches level 5 - normally that's the amount
     * of the Buy Order that triggered this whole cycle (a known-good number); for a book with no such
     * order behind it (e.g. one only found via a manual [triggerManualCombine]), pass null to skip the
     * re-buy entirely rather than guessing an amount.
     */
    private suspend fun processBookType(book: DetectedBook, fallbackRebuyAmount: Int?) {
        var (finalLevel, finalCount) = craftBooksUpToLevel5(book.baseName, book.isArabic)
        var attempts = 0

        while (true) {
            if (finalLevel == 5 && finalCount >= SELL_BATCH_THRESHOLD) {
                createBookSellOrder(bookDisplayName(book.baseName, 5, book.isArabic), finalCount)
                randomDelay(400)
                createBookBuyOrder(bookDisplayName(book.baseName, 1, book.isArabic), finalCount * BOOKS_PER_LEVEL_5)
                randomDelay(400)
                attempts = 0 // progress happened - keep going for whatever's left
                val result = craftBooksUpToLevel5(book.baseName, book.isArabic)
                finalLevel = result.first
                finalCount = result.second
                continue
            }

            val claimedMore = claimAnotherLevel1Order(book.baseName, book.isArabic)
            if (claimedMore) {
                attempts = 0
                val result = craftBooksUpToLevel5(book.baseName, book.isArabic)
                finalLevel = result.first
                finalCount = result.second
                continue
            }
            attempts++
            if (attempts >= MAX_EXTRA_CLAIM_ATTEMPTS) break
        }

        // Genuinely stuck now. Only an actual level 5 completion gets sold - anything still sitting below
        // that (whether finalCount itself, if finalLevel < 5, or an odd-count leftover at some lower level
        // that craftBooksUpToLevel5 never explicitly tracks) is kept rather than sold off half-finished:
        // booksNeededToCompleteLevel5 sizes the follow-up Buy Order to exactly finish it into another
        // level 5 book once it fills (that new order re-triggers this whole cycle, and craftBooksUpToLevel5
        // re-reads the real inventory at every level, so the kept leftover gets folded straight back in).
        if (finalLevel == 5 && finalCount >= 1) {
            createBookSellOrder(bookDisplayName(book.baseName, 5, book.isArabic), finalCount)
        }

        randomDelay(400)
        val topUp = booksNeededToCompleteLevel5(book.baseName, book.isArabic)
        val rebuyAmount = when {
            finalLevel == 5 && finalCount >= 1 -> finalCount * BOOKS_PER_LEVEL_5 + topUp
            fallbackRebuyAmount != null -> fallbackRebuyAmount + topUp
            topUp > 0 -> topUp
            else -> null // nothing sold, nothing to top up, and no known fallback amount - skip rather than guess
        }
        if (rebuyAmount != null) {
            createBookBuyOrder(bookDisplayName(book.baseName, 1, book.isArabic), rebuyAmount)
        } else {
            devMessage("[BazaarFlipper] ${book.baseName} - nothing sold and nothing to top up, skipping the re-buy.")
        }
        randomDelay(400)
    }

    /**
     * How many more level 1 books are needed so everything currently held below level 5 - summed by
     * "worth" in level 1 units (level 2 = 2, level 3 = 4, level 4 = 8) - reaches the next complete level 5
     * book (worth [BOOKS_PER_LEVEL_5]). E.g. one leftover level 2 book (worth 2) needs 14 more to become a
     * level 5 (2 to pair it into a level 3, 4 more to pair that into a level 4, 8 more to pair that into a
     * level 5) - matches [processBookType] keeping leftovers instead of selling them off half-finished.
     */
    private fun booksNeededToCompleteLevel5(baseName: String, isArabic: Boolean): Int {
        var totalValue = 0
        for (level in 1..4) {
            totalValue += countHeldItems(bookDisplayName(baseName, level, isArabic)) * (1 shl (level - 1))
        }
        if (totalValue == 0) return 0
        val remainder = totalValue % BOOKS_PER_LEVEL_5
        return if (remainder == 0) 0 else BOOKS_PER_LEVEL_5 - remainder
    }

    /**
     * Looks for another already-claimable ("click to claim") level 1 [baseName] Buy Order in Manage Orders
     * and claims it if found - used by [combineAndSellBooks] to top up leftovers instead of selling them
     * half-combined. Returns whether one was found and claimed.
     */
    private suspend fun claimAnotherLevel1Order(baseName: String, isArabic: Boolean): Boolean {
        val level1Name = bookDisplayName(baseName, 1, isArabic)
        val screen = openOrdersScreen() ?: run {
            devMessage("§cBazaarFlipper: couldn't open Manage Orders to look for another claimable $level1Name order.")
            return false
        }
        dumpScreen(screen, "Manage Orders (looking for another claimable $level1Name order)")

        val top = screen.topSlotCount()
        var slot: Int? = null
        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.noControlCodes.trim()
            if (!name.startsWith("buy", ignoreCase = true)) continue
            if (!containsBookLevel(name, level1Name)) continue
            val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
            if (!lore.contains("click to claim", ignoreCase = true)) continue
            slot = i
            break
        }
        if (slot == null) {
            closeScreen()
            return false
        }

        devMessage("[BazaarFlipper] Found another claimable $level1Name Buy Order - claiming it too before selling.")
        claimOrderFully(slot, level1Name)
        closeScreen()
        randomDelay(GUI_APPEAR_DELAY)
        return true
    }

    /**
     * Left-clicks the filled order at [slot] repeatedly instead of once - confirmed live that Hypixel
     * delivers a large claim in batches rather than all at once (a single click on a 16x order's own
     * "You have N items to claim!" line only counted down to 9, not 0). Keeps clicking until that line is
     * gone from the slot's lore (fully claimed) or [maxAttempts] is exceeded.
     *
     * Each click waits for [waitForGuiUpdate] (a genuine content change on this same screen, settled
     * [GUI_UPDATE_SETTLE_MS]) rather than a fixed [randomDelay] before reading the result - same fix as
     * [Fuser]'s `waitForGuiUpdate` for the identical failure mode: reading the screen right after a fixed
     * wait risks catching the *pre-click* lore if Hypixel's update packet happens to lag past that window,
     * which would misread an already-fully-claimed slot as still pending (or vice versa) and either loop one
     * click too many or stop one short.
     *
     * A full inventory ([noSpaceToClaim]) just stops the clicking early rather than burning through the
     * remaining attempts against the same wall - it's not treated as an error: every caller of this
     * (combine-related claiming only) already runs its normal craft/combine pass on whatever *did* make it
     * into the inventory right after, which is exactly what's needed to free up space for the rest, and
     * anything still left unclaimed on the order just gets picked up by the next pass over it.
     */
    private suspend fun claimOrderFully(slot: Int, itemName: String, maxAttempts: Int = 12) {
        noSpaceToClaim = false
        var screen = mc.screen as? AbstractContainerScreen<*> ?: return
        repeat(maxAttempts) { attempt ->
            val previousSignature = screen.contentSignature()
            click(slot, 0)
            val updated = waitForGuiUpdate(previousSignature, settleMs = GUI_UPDATE_SETTLE_MS) ?: run {
                devMessage("§cBazaarFlipper: claimOrderFully saw no GUI update after left-click attempt ${attempt + 1} on $itemName - stopping (${maxAttempts - attempt - 1} attempt(s) not tried).")
                return
            }
            screen = updated
            if (noSpaceToClaim) {
                devMessage("[BazaarFlipper] $itemName's inventory space ran out mid-claim - moving on to combine what's already in hand instead of retrying.")
                return
            }
            dumpScreen(screen, "After claim (left-click) attempt ${attempt + 1}")
            val lore = screen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
            if (!itemsToClaimRegex.containsMatchIn(lore)) return
        }
        devMessage("§cBazaarFlipper: gave up claiming $itemName after $maxAttempts left-clicks - some may remain unclaimed.")
    }

    /**
     * `/hxp fuse bz` counterpart to [flipOrder]: [order] is one of [fuse]'s two shard legs, claimed with the
     * same plain left-click [combineAndSellBooks] uses (goes straight to the inventory) rather than
     * [flipOrder]'s right-click "Flip Order" resell path - these shards are meant to be fused, not sold
     * back. Once both legs are marked claimed, hands off to [Fuser.start] to actually run the fusions
     * (the player must already be holding the physical Fusion item - same prerequisite `/hxp fuse run` itself has,
     * this doesn't buy or equip that).
     */
    private suspend fun handleClaimedFuseShard(order: ClaimedOrder, fuse: PendingFuse, isShard1: Boolean) {
        val ordersScreen = openOrdersScreen() ?: run {
            modMessage("§cAuto Bazaar Flipper: couldn't open Manage Orders to claim ${order.itemName} for /hxp fuse bz.")
            return
        }
        dumpScreen(ordersScreen, "Manage Orders (/hxp fuse bz claim)")

        val slot = findOrderSlot(ordersScreen, order) ?: run {
            devMessage("§cBazaarFlipper: couldn't find the order slot for ${order.amount}x ${order.itemName} (/hxp fuse bz) - already claimed manually?")
            closeScreen()
            return
        }
        claimOrderFully(slot, order.itemName)
        closeScreen()
        randomDelay(GUI_APPEAR_DELAY)

        if (isShard1) fuse.shard1Claimed = true else fuse.shard2Claimed = true
        modMessage("§a/hxp fuse bz: claimed §f${order.amount}x ${order.itemName}§a.")

        if (!fuse.shard1Claimed || !fuse.shard2Claimed) return
        pendingFuse = null
        modMessage("§a/hxp fuse bz: both shards claimed - starting the fuse into §f${fuse.outputName}§a (make sure you're holding the Fusion item).")
        Fuser.start("${fuse.shard1Name} | ${fuse.shard2Name} | ${fuse.outputName}")
    }

    /**
     * `/hxp bz collect <item> <amount>, <item2> <amount2>, ...` entry point (and `/hxp bz collect npc`'s, with [viaNpc] true -
     * identical behavior, the only difference is every Buy Order placement/re-list for this batch opens the
     * Bazaar via NPC right-click instead of `/bz`, see [openBazaar]'s `forceNpc`): keeps placing/re-listing
     * Buy Orders for each item until [PendingBuyCollect.claimedAmount] reaches its target, staying the
     * highest bid the whole time, then plays a 5s alert once every item is done. See [pendingBuyCollect]'s
     * own doc for why this needs its own claim/outbid handling instead of reusing the normal flip cycle.
     */
    fun startBuyCollect(spec: String, viaNpc: Boolean = false) {
        val entries = parseBuyCollectSpec(spec)
        if (entries.isEmpty()) {
            val npcSuffix = if (viaNpc) " npc" else ""
            modMessage("§cAuto Bazaar Flipper: couldn't parse '$spec' - use §f/hxp bz collect <item> <amount>, <item2> <amount2>, ...$npcSuffix§c (e.g. /hxp bz collect Hideonwall Shard 210, Puck Shard 210$npcSuffix).")
            return
        }
        if (!enabled) toggle()
        if (!tryClaimBusy()) {
            modMessage("§eAuto Bazaar Flipper is busy right now - try §f/hxp bz collect $spec${if (viaNpc) " npc" else ""}§e again shortly.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                for ((itemName, amount) in entries) {
                    val key = itemName.lowercase()
                    if (pendingBuyCollect.containsKey(key)) {
                        modMessage("§e/hxp bz collect: already collecting §f$itemName§e - skipping (cancel it first if you want to restart with a different amount).")
                        continue
                    }
                    pendingBuyCollect[key] = PendingBuyCollect(itemName, amount, viaNpc = viaNpc)
                    placeBuyCollectOrder(itemName, amount, viaNpc)
                }
            } finally {
                closeScreen()
                busy = false
            }
        }
    }

    /**
     * `/hxp bz collect stop` entry point: stops tracking every item currently active under `/hxp bz collect`/`/hxp bz collect npc` - clears
     * [pendingBuyCollect] and drops the matching entries from [trackedBuyOrders] so
     * [checkTrackedOrdersInGame]'s outbid watch stops touching them too. Does NOT cancel any Buy Order
     * that's already listed on the Bazaar - those are left exactly as-is (still filling, still the highest
     * bid or not) for the player to handle manually via Manage Orders; this only stops the *bot* from
     * further re-listing/claiming/sweeping them.
     */
    fun stopBuyCollect() {
        if (pendingBuyCollect.isEmpty()) {
            modMessage("§eAuto Bazaar Flipper: no /hxp bz collect/hxp bz collect npc collection is currently running.")
            return
        }
        val summary = pendingBuyCollect.values.joinToString(", ") { "${it.itemName} (${it.claimedAmount}/${it.targetAmount})" }
        for (collect in pendingBuyCollect.values) {
            trackedBuyOrders.remove(collect.itemName.lowercase())
        }
        pendingBuyCollect.clear()
        modMessage("§a/hxp bz collect stop: stopped tracking: §f$summary§a. Any Buy Orders already listed are left as-is - cancel them yourself in Manage Orders if you don't want them anymore.")
    }

    /**
     * `/hxp bz collect continue <item>, <item2>, ...` entry point (and `/hxp bz collect continue npc`'s, with [viaNpc] true -
     * identical otherwise, mirrors the `/hxp bz collect`/`/hxp bz collect npc` split): adopts an ALREADY-LISTED Buy Order for
     * each item into the same `/hxp bz collect`-managed lifecycle ([pendingBuyCollect]) instead of placing a new one
     * - no amount argument, the order's own listed total becomes the target. Built for exactly the
     * client-restart scenario: [pendingBuyCollect] is pure in-memory state, wiped by a restart, but a Buy
     * Order it placed earlier is still live server-side and just sitting there un-managed - this picks it
     * back up (claims whatever's already claimable, starts the outbid watch on whatever's still unfilled)
     * without needing to remember/retype the original target amount.
     */
    fun continueBuyCollect(spec: String, viaNpc: Boolean = false) {
        val itemNames = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (itemNames.isEmpty()) {
            val npcSuffix = if (viaNpc) " npc" else ""
            modMessage("§cAuto Bazaar Flipper: /hxp bz collect continue needs at least one item name, e.g. §f/hxp bz collect continue Hideonwall Shard, Puck Shard$npcSuffix")
            return
        }
        if (!enabled) toggle()
        if (!tryClaimBusy()) {
            modMessage("§eAuto Bazaar Flipper is busy right now - try §f/hxp bz collect continue $spec${if (viaNpc) " npc" else ""}§e again shortly.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                for (itemName in itemNames) {
                    adoptExistingBuyOrder(itemName, viaNpc)
                }
            } finally {
                closeScreen()
                busy = false
            }
        }
    }

    /** [continueBuyCollect]'s per-item worker - see its own doc. [viaNpc]: see [openBazaar]'s `forceNpc`. */
    private suspend fun adoptExistingBuyOrder(itemName: String, viaNpc: Boolean = false) {
        val key = itemName.lowercase()
        if (pendingBuyCollect.containsKey(key)) {
            modMessage("§e/hxp bz collect continue: already collecting §f$itemName§e - skipping.")
            return
        }
        // Placeholder so openBazaar()'s pendingBuyCollect-viaNpc check (see its own doc) already forces NPC
        // opening for the lookup below too, before the real target/claimed amounts are known - overwritten
        // with the real entry (or removed) once they are.
        pendingBuyCollect[key] = PendingBuyCollect(itemName, targetAmount = Int.MAX_VALUE, viaNpc = viaNpc)

        val screen = openOrdersScreen() ?: run {
            modMessage("§cAuto Bazaar Flipper: /hxp bz collect continue couldn't open Manage Orders to look up $itemName.")
            pendingBuyCollect.remove(key)
            return
        }
        val slot = findOrderSlotByName(screen, itemName, OrderType.BUY) ?: run {
            modMessage("§c/hxp bz collect continue: no existing Buy Order found for §f$itemName§c - nothing to resume.")
            closeScreen()
            pendingBuyCollect.remove(key)
            return
        }
        val lore = screen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
        val (filled, total) = parseOrderFillState(lore, OrderType.BUY)
        if (total == null || total <= 0) {
            modMessage("§c/hxp bz collect continue: couldn't read $itemName's order size - not adopting it.")
            closeScreen()
            pendingBuyCollect.remove(key)
            return
        }

        // Claim whatever's already sitting claimable before starting to track it, so claimedAmount reflects
        // reality from the start rather than waiting for the next periodic sweep.
        var claimedNow = 0
        if (itemsToClaimRegex.containsMatchIn(lore)) {
            claimedNow = itemsToClaimAmountRegex.find(lore)?.groupValues?.get(1)?.let { parseAbbreviatedCount(it) } ?: 0
            claimOrderFully(slot, itemName)
        }
        closeScreen()
        randomDelay(GUI_APPEAR_DELAY)

        pendingBuyCollect[key] = PendingBuyCollect(itemName, targetAmount = total, claimedAmount = claimedNow, viaNpc = viaNpc)

        val remainingUnfilled = total - (filled ?: 0)
        if (remainingUnfilled > 0) {
            val price = readOwnPrice(itemName, OrderType.BUY)
            if (price != null) {
                trackOrderForUndercutWatch(itemName, OrderType.BUY, remainingUnfilled, price)
            } else {
                devMessage("§cBazaarFlipper: /hxp bz collect continue couldn't read $itemName's listed price - won't watch it for outbids until the next full-fill check.")
            }
        }
        modMessage("§a/hxp bz collect continue: resumed tracking §f$itemName§a - target §f${total}x§a, §f${claimedNow}x§a already claimed (order shows ${filled ?: 0}/$total filled).")

        if (claimedNow >= total) finishBuyCollectIfComplete(itemName)
    }

    /** "Hideonwall Shard 210, Puck Shard 210" -> [("Hideonwall Shard", 210), ("Puck Shard", 210)] - the trailing integer in each comma-separated segment is the amount, everything before it is the item name (so names with spaces, e.g. "Hideonwall Shard", work fine). */
    private fun parseBuyCollectSpec(spec: String): List<Pair<String, Int>> {
        val entryRegex = Regex("""^(.+?)\s+(\d+)$""")
        return spec.split(",").mapNotNull { segment ->
            val match = entryRegex.find(segment.trim()) ?: return@mapNotNull null
            val name = match.groupValues[1].trim()
            val amount = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (name.isEmpty() || amount <= 0) null else name to amount
        }
    }

    /** Places a fresh Buy Order for [itemName] sized at [amount] and starts the outbid watch on it - used both for /hxp bz collect's initial placement and for re-listing the remainder after an outbid. Removes [itemName] from [pendingBuyCollect] on any failure so it doesn't sit there tracked with nothing actually listed. [viaNpc] forces NPC-interaction opening (see [openBazaar]'s `forceNpc`) - passed through from [PendingBuyCollect.viaNpc] by every caller except the very first placement in [startBuyCollect], which passes it directly. */
    private suspend fun placeBuyCollectOrder(itemName: String, amount: Int, viaNpc: Boolean = false): Boolean {
        openBazaar(forceNpc = viaNpc)
        val mainScreen = waitForScreen { true } ?: run {
            modMessage("§cAuto Bazaar Flipper: /hxp bz collect couldn't open the Bazaar to buy ${amount}x $itemName.")
            pendingBuyCollect.remove(itemName.lowercase())
            return false
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(mainScreen, "Bazaar main (/hxp bz collect $itemName)")

        if (placeOrderViaSearch(itemName, OrderType.BUY, mainScreen) { amount } <= 0) {
            modMessage("§cAuto Bazaar Flipper: /hxp bz collect failed to place a Buy Order for ${amount}x $itemName.")
            pendingBuyCollect.remove(itemName.lowercase())
            return false
        }
        closeScreen()
        if (!ensureOrderExists(itemName, amount, OrderType.BUY, "/hxp bz collect Buy Order")) {
            pendingBuyCollect.remove(itemName.lowercase())
            return false
        }
        randomDelay(GUI_APPEAR_DELAY)
        val price = readOwnPrice(itemName, OrderType.BUY)
        if (price == null) {
            devMessage("§cBazaarFlipper: /hxp bz collect couldn't read $itemName's listed price - it won't stay watched for outbids until the next full-fill check.")
        } else {
            trackOrderForUndercutWatch(itemName, OrderType.BUY, amount, price)
        }
        modMessage("§a/hxp bz collect: placed a Buy Order for §f${amount}x $itemName§a, watching to stay the highest bid.")
        return true
    }

    /**
     * [handleOutbid]'s /hxp bz collect special-case: cancels the outbid order (claiming whatever had already
     * delivered - that's real progress toward the target, tallied into [PendingBuyCollect.claimedAmount]
     * rather than resold), then re-lists only the still-missing amount at a fresh top price. If the claimed
     * goods alone already reached the target, skips the re-list entirely.
     */
    private suspend fun handleBuyCollectOutbid(tracked: TrackedBuyOrder, collect: PendingBuyCollect) {
        val cancelResult = cancelAllOrders(tracked.itemName, OrderType.BUY, tracked.amount, claimPendingGoods = true)
        if (noSpaceToClaim) {
            devMessage("[BazaarFlipper] /hxp bz collect: inventory ran out of space cancelling/claiming ${tracked.itemName} - depositing the hotbar into the Hunting Box.")
            depositHotbarIntoHuntingBox()
            noSpaceToClaim = false
            // On request: a full inventory can leave OTHER tracked orders sitting with unclaimed goods too,
            // not just this one - sweep everything now that space is free rather than waiting for the next
            // periodic check. Excludes tracked.itemName - cancelAllOrders above already claimed whatever it
            // had pending as part of its own claim-then-cancel sequence.
            claimAllPendingBuyCollectGoods(excludeItemName = tracked.itemName)
        }
        if (!cancelResult.confirmed) {
            devMessage("§cBazaarFlipper: /hxp bz collect couldn't confirm ${tracked.itemName}'s outbid Buy Order actually cancelled - leaving the watch as-is, will retry next tick.")
            return
        }
        collect.claimedAmount += cancelResult.claimed
        trackedBuyOrders.remove(tracked.itemName.lowercase())

        val remaining = collect.targetAmount - collect.claimedAmount
        if (remaining <= 0) {
            finishBuyCollectIfComplete(collect.itemName)
            return
        }
        if (!placeBuyCollectOrder(tracked.itemName, remaining, collect.viaNpc)) return
        if (notifyOnUndercut) {
            modMessage("§a/hxp bz collect: got outbid on §f${tracked.itemName}§a - re-listed §f${remaining}x§a to stay the highest bid (§f${collect.claimedAmount}/${collect.targetAmount}§a claimed so far).")
        }
    }

    /** [processClaimedOrders]'s /hxp bz collect special-case for a fully-filled tracked Buy Order - claims it, tallies it, and either re-lists any shortfall or finishes up. */
    private suspend fun handleClaimedBuyCollect(order: ClaimedOrder, collect: PendingBuyCollect) {
        val ordersScreen = openOrdersScreen() ?: run {
            modMessage("§cAuto Bazaar Flipper: couldn't open Manage Orders to claim ${order.itemName} for /hxp bz collect.")
            return
        }
        dumpScreen(ordersScreen, "Manage Orders (/hxp bz collect claim)")
        val slot = findOrderSlot(ordersScreen, order) ?: run {
            devMessage("§cBazaarFlipper: /hxp bz collect couldn't find the order slot for ${order.amount}x ${order.itemName} - already claimed manually?")
            closeScreen()
            return
        }
        claimOrderFully(slot, order.itemName)
        if (noSpaceToClaim) {
            devMessage("[BazaarFlipper] /hxp bz collect: inventory ran out of space claiming ${order.itemName} - depositing the hotbar into the Hunting Box and retrying the claim.")
            closeScreen()
            depositHotbarIntoHuntingBox()
            noSpaceToClaim = false
            randomDelay(GUI_APPEAR_DELAY)
            val retryScreen = openOrdersScreen()
            val retrySlot = retryScreen?.let { findOrderSlot(it, order) }
            if (retryScreen != null && retrySlot != null) {
                claimOrderFully(retrySlot, order.itemName)
            } else {
                devMessage("§cBazaarFlipper: /hxp bz collect couldn't re-find ${order.itemName}'s order slot after freeing space - it may still be partially unclaimed, next full-fill check will pick it up.")
            }
            closeScreen()
            // On request: a full inventory can leave OTHER tracked orders sitting with unclaimed goods too,
            // not just this one - sweep everything now that space is free rather than waiting for the next
            // periodic check. Excludes order.itemName - the retry claim right above already handled it.
            claimAllPendingBuyCollectGoods(excludeItemName = order.itemName)
        }
        closeScreen()
        randomDelay(GUI_APPEAR_DELAY)

        collect.claimedAmount += order.amount
        trackedBuyOrders.remove(order.itemName.lowercase())
        modMessage("§a/hxp bz collect: claimed §f${order.amount}x ${order.itemName}§a (§f${collect.claimedAmount}/${collect.targetAmount}§a).")

        val remaining = collect.targetAmount - collect.claimedAmount
        if (remaining > 0) {
            // Shouldn't normally happen (orders are always sized at exactly what's still missing), but covers
            // it defensively rather than silently stopping short of the target.
            placeBuyCollectOrder(order.itemName, remaining, collect.viaNpc)
            return
        }
        finishBuyCollectIfComplete(order.itemName)
    }

    /**
     * Sweeps every `/hxp bz collect`/`/hxp bz collect npc`-tracked item's Buy Order in Manage Orders for ANY currently-claimable
     * goods (`itemsToClaimRegex`, regardless of whether the order is 100% filled) and claims them, adding
     * the claimed amount straight to [PendingBuyCollect.claimedAmount] - on request, run after every
     * inventory-space recovery ([depositHotbarIntoHuntingBox]) since a full inventory can leave *multiple*
     * tracked orders sitting with unclaimed goods, not just the one that triggered the recovery, and once
     * per [checkTrackedOrdersInGame] pass (the periodic "still the highest bid?" check) so partially-filled
     * goods don't sit unclaimed for a whole extra [UNDERCUT_CHECK_INTERVAL] just because the order isn't
     * 100% filled yet ([checkForFilledTrackedOrders] only ever claims fully-filled orders).
     *
     * [excludeItemName]: skips that one item - used by [handleBuyCollectOutbid]/[handleClaimedBuyCollect]
     * when they call this right after already claiming that exact item's order themselves, so it isn't
     * double-counted here too.
     */
    private suspend fun claimAllPendingBuyCollectGoods(excludeItemName: String? = null) {
        if (pendingBuyCollect.isEmpty()) return
        var screen = openOrdersScreen() ?: run {
            devMessage("§cBazaarFlipper: /hxp bz collect couldn't open Manage Orders for its claim-everything sweep.")
            return
        }
        for (collect in pendingBuyCollect.values.toList()) {
            if (excludeItemName != null && collect.itemName.equals(excludeItemName, ignoreCase = true)) continue
            val slot = findOrderSlotByName(screen, collect.itemName, OrderType.BUY) ?: continue
            val lore = screen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
            if (!itemsToClaimRegex.containsMatchIn(lore)) continue
            val claimable = itemsToClaimAmountRegex.find(lore)?.groupValues?.get(1)?.let { parseAbbreviatedCount(it) } ?: 0
            if (claimable <= 0) continue

            claimOrderFully(slot, collect.itemName)
            collect.claimedAmount += claimable
            trackedBuyOrders.remove(collect.itemName.lowercase())
            devMessage("[BazaarFlipper] /hxp bz collect: claim-everything sweep claimed ${claimable}x ${collect.itemName} (§f${collect.claimedAmount}/${collect.targetAmount}).")
            if (collect.claimedAmount >= collect.targetAmount) {
                finishBuyCollectIfComplete(collect.itemName)
            }

            screen = mc.screen as? AbstractContainerScreen<*> ?: openOrdersScreen() ?: run {
                devMessage("§cBazaarFlipper: /hxp bz collect lost Manage Orders mid-sweep - stopping early, the rest gets picked up next pass.")
                return
            }
        }
        closeScreen()
    }

    /**
     * `/hxp bz afk`'s claim step: claims every currently-claimable order in Manage Orders - ANY order, Buy or
     * Sell, tracked by this module or not - whose lore matches [itemsToClaimRegex], not just
     * [pendingBuyCollect]'s own tracked subset like [claimAllPendingBuyCollectGoods] above. Deliberately its
     * own plain scan rather than reusing [discoverUntrackedOrders] (that one also starts undercut watches and
     * auto-restocks [activelyManagedItems] - side effects unrelated to this command's plain "claim everything"
     * ask). [openOrdersScreen] itself already sweeps ready Sell Offer proceeds unconditionally on every visit
     * ([claimAllReadySellOrders]) - this additionally handles filled Buy Orders, which need an explicit claim
     * to actually receive the purchased items (a Sell Offer's claim is just coins, no inventory-item action
     * needed beyond what already happens automatically).
     */
    private suspend fun claimAllOrdersInManageOrders() {
        var screen = openOrdersScreen() ?: run {
            devMessage("§cBazaarFlipper: /hxp bz afk couldn't open Manage Orders to claim.")
            return
        }
        var claimedCount = 0
        var iterations = 0
        while (iterations++ < 40) {
            val top = screen.topSlotCount()
            var found: Pair<Int, String>? = null
            for (i in 0 until top) {
                val stack = screen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                val prefixMatch = orderPrefixRegex.find(stack.hoverName.string.noControlCodes.trim()) ?: continue
                val itemName = prefixMatch.groupValues[2].trim()
                val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
                if (itemsToClaimRegex.containsMatchIn(lore)) {
                    found = i to itemName
                    break
                }
            }
            val (slot, itemName) = found ?: break
            claimOrderFully(slot, itemName)
            claimedCount++
            randomDelay(GUI_APPEAR_DELAY)
            screen = mc.screen as? AbstractContainerScreen<*> ?: openOrdersScreen() ?: run {
                devMessage("§cBazaarFlipper: /hxp bz afk lost Manage Orders mid-claim sweep - stopping early.")
                return
            }
        }
        closeScreen()
        if (claimedCount > 0) devMessage("[BazaarFlipper] /hxp bz afk: claimed $claimedCount order(s) in Manage Orders.")
    }

    /**
     * `/hxp bz collect`/`/hxp bz collect npc`'s inventory-full recovery (on request): cycles hotbar slots 1-8 ONLY (never slot
     * 9 - that's where the player keeps a Nether Star), shift-right-clicking each in turn - Hypixel's
     * Hunting Box, like Sacks, accepts a whole held stack straight into storage via shift+right-click with
     * no GUI needing to be open (per explicit confirmation this doesn't open a screen).
     *
     * Which physical key selects each slot comes from [huntingBoxHotkeys] (`/hxp bz hotkeys`, on request) rather than
     * assuming a vanilla "1".."8" hotbar binding - keys are pressed via
     * [de.hxp.hxpaddons.utils.simulateKeyPressChar] (AWT's layout-aware `getExtendedKeyCodeForChar`, not
     * [simulateKeyPress]'s GLFW-keycode path) specifically so non-ASCII keys like "ä" resolve correctly
     * against whatever OS keyboard layout is active, without this mod needing to hardcode a layout.
     *
     * On request: the shift-right-click itself can land the player back in the Bazaar (or some other
     * screen) as a side effect - after each one, this waits (bounded [HOTBAR_DEPOSIT_GUI_WAIT_MS]) for any
     * container screen to appear, and the moment one does, waits [GUI_UPDATE_SETTLE_MS] more before closing
     * it, rather than assuming shift-right-click never opens anything. If nothing opens within the timeout,
     * moves straight on to the next slot.
     *
     * Entirely unconfirmed live - first time this exact mechanic gets exercised. If it doesn't actually free
     * space (e.g. the Hunting Box has its own capacity limit, or shift-right-click needs the item to be a
     * specific shard type it recognizes rather than working generically), the caller's retry will simply find
     * the inventory still full and give up the same way it already did before this existed.
     */
    private suspend fun depositHotbarIntoHuntingBox() {
        val keys = huntingBoxHotkeys.value.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.take(8)
        if (keys.isEmpty()) {
            devMessage("§cBazaarFlipper: /hxp bz collect: no Hunting Box hotkeys configured (see §f/hxp bz hotkeys§c) - skipping the inventory-space recovery.")
            return
        }
        for (key in keys) {
            simulateKeyPressChar(key)
            randomDelay(HOTBAR_DEPOSIT_STEP_DELAY)
            simulateRightClickWithShiftHeld()

            val appearStart = System.currentTimeMillis()
            var opened: AbstractContainerScreen<*>? = null
            while (System.currentTimeMillis() - appearStart < HOTBAR_DEPOSIT_GUI_WAIT_MS) {
                opened = mc.screen as? AbstractContainerScreen<*>
                if (opened != null) break
                delay(30)
            }
            if (opened != null) {
                devMessage("[BazaarFlipper] /hxp bz collect: a screen ('${opened.title.string.noControlCodes}') opened after shift-right-clicking hotbar key '$key' - closing it in ${GUI_UPDATE_SETTLE_MS}ms.")
                delay(GUI_UPDATE_SETTLE_MS)
                closeScreen()
            }
            randomDelay(HOTBAR_DEPOSIT_STEP_DELAY)
        }
        devMessage("[BazaarFlipper] /hxp bz collect: cycled ${keys.size} hotbar slot(s) (keys: ${keys.joinToString(" ")}) into the Hunting Box.")
    }

    /**
     * `/hxp bz hotkeys <key1> <key2> ...` entry point: sets which physical key selects each hotbar slot (in slot
     * 1-8 order) for [depositHotbarIntoHuntingBox] - e.g. `/hxp bz hotkeys 1 2 3 4 5 6 7 8` for a vanilla layout, or
     * `/hxp bz hotkeys f 2 ä j i o l ,` for a custom one. Only the first 8 tokens are kept (slot 9 - the Nether Star -
     * is never touched); only each token's first character is used (single physical keys only, not named
     * keys like "space"). Persisted via [huntingBoxHotkeys] like any other setting.
     */
    fun setHuntingBoxHotkeys(spec: String) {
        val tokens = spec.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            modMessage("§cAuto Bazaar Flipper: /hxp bz hotkeys needs at least one key, e.g. §f/hxp bz hotkeys 1 2 3 4 5 6 7 8§c or §f/hxp bz hotkeys f 2 ä j i o l ,")
            return
        }
        if (tokens.size > 8) {
            modMessage("§eAuto Bazaar Flipper: /hxp bz hotkeys only uses the first 8 keys (hotbar slot 9 - the Nether Star - is never touched) - ignoring ${tokens.size - 8} extra.")
        }
        val keys = tokens.take(8).map { it.first() }
        huntingBoxHotkeys.value = keys.joinToString(" ")
        modMessage("§a/hxp bz hotkeys: Hunting Box hotkeys set to §f${keys.joinToString(" ")}§a (slot 1 -> slot ${keys.size}).")
    }

    private fun isShardStack(stack: ItemStack): Boolean =
        !stack.isEmpty && stack.hoverName.string.noControlCodes.contains("shard", ignoreCase = true)

    /**
     * `/hxp bz huntingbox`'s move-into-hotbar step: relocates every shard stack sitting in the player's main inventory
     * into an empty one of hotbar slots 1-8 (slot 9/Nether Star never touched, same convention
     * [huntingBoxHotkeys] documents), via a plain `SWAP` container click against the player's own permanent
     * inventory menu ([net.minecraft.world.entity.player.Player.getInventoryMenu], always container id 0 -
     * works whether or not any screen is currently open, since this menu always exists server-side). This is
     * the same primitive vanilla's own "hover a slot, press 1-9" hotbar-swap shortcut uses, just sent
     * directly via [clickSlot] instead of simulating a mouse hover + keypress - on request ("guck dir dafür
     * an wie odin das mit slot binding macht um sachen hin und her zu shiften und mach es auch so"), same
     * click-by-index+type pattern this whole file already uses everywhere else.
     *
     * Deliberately does NOT hardcode which menu slot numbers are "main inventory" vs "hotbar" (older
     * Minecraft versions' well-known `InventoryMenu` layout - crafting 0-4, armor 5-8, main storage 9-35,
     * hotbar 36-44 - is unconfirmed for this specific version, which turned out on inspection to have grown
     * new armor/equipment-related fields on [net.minecraft.world.entity.player.Inventory] that could plausibly
     * have shifted the menu's own slot numbering too). Instead, every [net.minecraft.world.inventory.Slot] in
     * the live menu is filtered to only the ones actually backed by the player's own `Inventory` object
     * (`slot.container === player.inventory` - armor/crafting-result slots are backed by separate container
     * objects, so this excludes them automatically regardless of how the menu is laid out), then split into
     * hotbar vs. main storage via [net.minecraft.world.entity.player.Inventory.isHotbarSlot] (asking the game
     * itself rather than assuming index ranges).
     *
     * Returns how many shard stacks got moved (bounded by however many hotbar slots 1-8 were actually empty).
     * `suspend` (not a plain function) so it can pace each SWAP click [HXPHB_SWAP_STEP_DELAY] apart rather
     * than firing all of them back-to-back in the same tick - on request ("zwischen jeden switch in die
     * hotbar 300ms wartet also 1 swappen 300ms warten nächsten swappen").
     */
    /**
     * True if any shard stack is still sitting in the player's main inventory (not the hotbar) - `/hxp bz huntingbox`'s
     * actual "am I done yet" signal, on request ("wenn kein shard mehr im inventar ist stoppen"). Deliberately
     * separate from [moveShardsIntoHotbar]'s own return value: that one can also come back 0 when the hotbar
     * is simply full of non-shard items with no free slot to move into, which isn't the same thing as "there's
     * nothing left to do" - this checks the actual stopping condition directly instead of inferring it from a
     * move that happened not to move anything.
     */
    private fun anyShardInMainInventory(): Boolean {
        val player = mc.player ?: return false
        val inventory = player.inventory
        return player.inventoryMenu.slots.any {
            it.container === inventory && !Inventory.isHotbarSlot(it.containerSlot) && isShardStack(it.item)
        }
    }

    private suspend fun moveShardsIntoHotbar(): Int {
        val player = mc.player ?: return 0
        val inventory = player.inventory
        val menu = player.inventoryMenu
        val containerId = menu.containerId

        val ownSlots = menu.slots.filter { it.container === inventory }
        val hotbarSlots = ownSlots.filter { Inventory.isHotbarSlot(it.containerSlot) }.sortedBy { it.containerSlot }.take(HXPHB_HOTBAR_SLOTS)
        val mainSlots = ownSlots.filterNot { Inventory.isHotbarSlot(it.containerSlot) }

        val freeHotbar = hotbarSlots.filter { !it.hasItem() }
        if (freeHotbar.isEmpty()) return 0

        val shardSlots = mainSlots.filter { isShardStack(it.item) }
        if (shardSlots.isEmpty()) return 0

        // mc.execute, not a direct clickSlot call - confirmed live elsewhere in this file (see the shared
        // click() helper) that this module's coroutines run on Dispatchers.Default's real thread pool, not
        // the render thread clickSlot's underlying packet-send needs to run on.
        var moved = 0
        for ((source, hotbar) in shardSlots.zip(freeHotbar)) {
            val sourceIndex = source.index
            val hotbarButton = hotbar.containerSlot
            mc.execute { mc.player?.clickSlot(containerId, sourceIndex, hotbarButton, ContainerInput.SWAP) }
            moved++
            randomDelay(HXPHB_SWAP_STEP_DELAY)
        }
        devMessage("[BazaarFlipper] /hxp bz huntingbox: moved $moved shard stack(s) into $moved of ${freeHotbar.size} free hotbar slot(s) (${shardSlots.size - moved} shard stack(s) still waiting for a free slot next pass).")
        return moved
    }

    /**
     * True if hotbar slot [index] (0-based, 0 = slot 1) currently holds an item - re-queried live each call,
     * same `slot.container === player.inventory` + [net.minecraft.world.entity.player.Inventory.isHotbarSlot]
     * filtering [moveShardsIntoHotbar]/[anyShardInMainInventory] already use.
     */
    private fun hotbarSlotHasItem(index: Int): Boolean {
        val player = mc.player ?: return false
        val inventory = player.inventory
        val hotbarSlots = player.inventoryMenu.slots
            .filter { it.container === inventory && Inventory.isHotbarSlot(it.containerSlot) }
            .sortedBy { it.containerSlot }
        return hotbarSlots.getOrNull(index)?.hasItem() ?: false
    }

    /**
     * `/hxp bz huntingbox`'s deposit step: cycles hotbar slots 1-8 into the Hunting Box - only the FIRST slot is
     * selected via its configured [huntingBoxHotkeys] physical key ([simulateKeyPressChar]), every slot after
     * that is reached with a single mouse-wheel notch ([simulateScroll]) instead of its own hotkey (on
     * request - "nur den ersten slot hotkeyt und dann per mausrad swappt"). Each slot gets a shift-held
     * right-click ([simulateRightClickWithShiftHeld]) - 2026-08-13: briefly switched to a plain
     * [simulateRightClick] on request ("er sneakt nicht"), then switched right back the same day once live
     * testing showed the deposit needs the sneak held after all ("er soll sneaken hat es aber nicht gemacht") -
     * matches [depositHotbarIntoHuntingBox]'s own shift-right-click.
     *
     * Waits for Hypixel's own [huntingBoxDepositRegex] chat confirmation after each right-click before moving
     * to the next slot, retrying the same slot every [HXPHB_RETRY_DELAY] up to [HXPHB_MAX_RETRIES_PER_SLOT]
     * times if no confirmation shows up. An empty slot exhausting its retry budget without ever confirming is
     * the expected/normal case, not an error.
     *
     * The moment a slot's confirmation actually arrives, this scrolls straight to the next slot with no
     * further wait (on request - "wenn die chat message kommt direkt zum nächsten swappen") - [HXPHB_STEP_DELAY]
     * is only still applied after a slot that never confirmed at all, as a settle margin before moving on from
     * that more uncertain state.
     *
     * Before scrolling into any slot past the first, [hotbarSlotHasItem] checks that upcoming slot first -
     * if it's already empty, the whole deposit cycle stops right there instead of continuing to scroll/click
     * through the rest (on request - "aufhört sobald im slot rechts von dem in den er gerade ist nichts mehr
     * ist"). Relies on [moveShardsIntoHotbar] always filling free hotbar slots left-to-right, so an empty slot
     * means every slot after it is empty too in the common case (an all-shard, no-other-items hotbar).
     */
    private suspend fun depositHotbarWithConfirmation() {
        val keys = huntingBoxHotkeys.value.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.take(HXPHB_HOTBAR_SLOTS)
        if (keys.isEmpty()) {
            modMessage("§cAuto Bazaar Flipper: /hxp bz huntingbox: no Hunting Box hotkeys configured (see §f/hxp bz hotkeys§c) - aborting.")
            return
        }
        simulateKeyPressChar(keys.first())
        randomDelay(HXPHB_STEP_DELAY)

        for (index in keys.indices) {
            if (index > 0) {
                if (!hotbarSlotHasItem(index)) {
                    devMessage("[BazaarFlipper] /hxp bz huntingbox: hotbar slot ${index + 1} is empty - stopping the deposit cycle early.")
                    break
                }
                simulateScroll(HXPHB_SCROLL_DIRECTION)
                randomDelay(HXPHB_STEP_DELAY)
            }

            var confirmed = false
            for (attempt in 1..HXPHB_MAX_RETRIES_PER_SLOT) {
                val clickedAt = System.currentTimeMillis()
                simulateRightClickWithShiftHeld()
                val deadline = clickedAt + HXPHB_RETRY_DELAY
                while (System.currentTimeMillis() < deadline) {
                    if (lastHuntingBoxDepositAtMs >= clickedAt) {
                        confirmed = true
                        break
                    }
                    delay(20)
                }
                if (confirmed) break
            }
            if (!confirmed) {
                devMessage("[BazaarFlipper] /hxp bz huntingbox: no Hunting Box confirmation for hotbar slot ${index + 1} after $HXPHB_MAX_RETRIES_PER_SLOT attempt(s) - likely an empty slot, moving on.")
                randomDelay(HXPHB_STEP_DELAY)
            }
        }
    }

    /**
     * The actual `/hxp bz huntingbox` sweep, minus busy-claiming/chat-reporting - split out so [runFuseAfkCycle] can run
     * it back-to-back with the claim/relist steps under one shared [busy] claim, instead of it trying (and
     * failing) to claim [busy] a second time via [startHuntingBoxCollect] itself. Returns how many shard
     * stacks got deposited in total.
     *
     * Runs one [depositHotbarWithConfirmation] pass FIRST, before ever touching [moveShardsIntoHotbar] - on
     * request ("erst einmal alles weg packen bevor er in die hotbar shiftet da da noch welche sein können"),
     * in case the player already had shard stacks sitting in hotbar slots 1-8 for some unrelated reason
     * before running this command - clears those out first rather than only ever depositing what THIS
     * command itself moved there.
     *
     * After that, repeats [moveShardsIntoHotbar] + [depositHotbarWithConfirmation] in passes (at most
     * [HXPHB_HOTBAR_SLOTS] stacks fit in the usable hotbar per pass) until [anyShardInMainInventory] says
     * there's nothing left to move (on request - "wenn kein shard mehr im inventar ist stoppen"), capped at
     * [HXPHB_MAX_PASSES] as a safety net either way (e.g. if the hotbar ever gets stuck full of non-shard
     * items with nowhere to move a remaining shard into).
     */
    private suspend fun collectShardsIntoHuntingBox(): Int {
        depositHotbarWithConfirmation()

        var pass = 0
        var totalStacks = 0
        while (pass < HXPHB_MAX_PASSES && anyShardInMainInventory()) {
            val moved = moveShardsIntoHotbar()
            pass++
            totalStacks += moved
            randomDelay(HXPHB_STEP_DELAY)
            depositHotbarWithConfirmation()
        }
        return totalStacks
    }

    /** `/hxp bz huntingbox` entry point - see [collectShardsIntoHuntingBox]'s own doc for the actual sweep logic. */
    fun startHuntingBoxCollect() {
        if (!tryClaimBusy()) {
            modMessage("§cAuto Bazaar Flipper: already busy, ignoring /hxp bz huntingbox.")
            return
        }
        modMessage("§7/hxp bz huntingbox: collecting shards into the Hunting Box...")
        job = HxPMod.scope.launch {
            try {
                val totalStacks = collectShardsIntoHuntingBox()
                if (totalStacks == 0) {
                    modMessage("§eAuto Bazaar Flipper: /hxp bz huntingbox found no shards in your inventory to deposit.")
                } else {
                    modMessage("§aAuto Bazaar Flipper: /hxp bz huntingbox deposited $totalStacks shard stack(s) into the Hunting Box.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: /hxp bz huntingbox failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c during /hxp bz huntingbox, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    /** Drops [itemName] from [pendingBuyCollect] once its target is met; once the whole batch is empty, plays the completion alert. */
    private fun finishBuyCollectIfComplete(itemName: String) {
        pendingBuyCollect.remove(itemName.lowercase())
        modMessage("§a/hxp bz collect: §f$itemName§a done.")
        if (pendingBuyCollect.isEmpty()) {
            modMessage("§a/hxp bz collect: all items collected!")
            playBuyCollectCompleteAlert()
        }
    }

    /** Repeats a short ping for ~5s (a single sound event doesn't sustain that long on its own) so it's noticeable even if the player isn't looking at chat. */
    private fun playBuyCollectCompleteAlert() {
        setTitle("§a/hxp bz collect done!")
        HxPMod.scope.launch {
            repeat(12) {
                playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f)
                delay(420L)
            }
        }
    }

    /**
     * Combines held [baseName] books up in the anvil (`/av`) as far as possible, capped at level 5.
     * Re-reads the actual held amount from the inventory at the *start of every level* (via
     * [countHeldItems]) rather than trusting a running in-memory count - so books left over from an
     * earlier, separate call to this function (a previous partial/paused attempt, or ones
     * [claimAnotherLevel1Order] just added) get folded back in and retried automatically instead of
     * needing to be threaded through by hand. No leftovers list is returned: whatever didn't make it into
     * the final (level, count) just stays in the inventory at whatever level it's stuck on, ready to be
     * picked up again by the next call, or sold as-is by the caller once it truly gives up retrying.
     *
     * Level 4 -> 5 is combined one pair at a time (not the whole level in one sweep like the lower
     * levels), checking the level 5 count after each single combine and stopping the instant it reaches
     * [SELL_BATCH_THRESHOLD] - confirmed live that combining the *entire* held batch before ever checking
     * meant far more than the intended batch size could pile up and get sold in one go instead of exactly
     * [SELL_BATCH_THRESHOLD] at a time. Any level 4 books left over from stopping early just remain in the
     * inventory for the next call to pick back up, same as a paired-away odd count would.
     */
    private suspend fun craftBooksUpToLevel5(baseName: String, isArabic: Boolean): Pair<Int, Int> {
        sendCommand("av")
        // waitForAnvilScreen already logs the diagnostic on timeout - nothing more to add here.
        val anvilScreen = waitForAnvilScreen() ?: return 1 to countHeldItems(bookDisplayName(baseName, 1, isArabic))
        randomDelay(ANVIL_APPEAR_DELAY)
        dumpScreen(anvilScreen, "Anvil opened")
        dumpPlayerInventory("Player inventory in anvil (crafting $baseName up)")

        var level = 1
        var count = 0

        while (level <= 5) {
            count = countHeldItems(bookDisplayName(baseName, level, isArabic))
            if (level == 5 || count < 2) break

            if (level == 4) {
                var remaining = count
                while (remaining >= 2) {
                    if (countHeldItems(bookDisplayName(baseName, 5, isArabic)) >= SELL_BATCH_THRESHOLD) break
                    val combined = combinePairsInAnvil(anvilScreen, baseName, level, isArabic, 1)
                    if (combined < 1) {
                        devMessage("§cBazaarFlipper: combine failed for ${bookDisplayName(baseName, level, isArabic)} - will re-check level $level next time round.")
                        break
                    }
                    remaining -= 2
                }
            } else {
                val pairs = count / 2
                val combined = combinePairsInAnvil(anvilScreen, baseName, level, isArabic, pairs)
                if (combined < pairs) {
                    devMessage("§cBazaarFlipper: only combined $combined/$pairs pairs of ${bookDisplayName(baseName, level, isArabic)} - will re-check level $level next time round.")
                }
            }
            level++
        }
        closeScreen()
        return level.coerceAtMost(5) to count
    }

    /**
     * Combines up to [pairs] pairs of level-[level] [baseName] books in the currently open anvil.
     * Confirmed live: claimed books aren't one stack, they're individual unstackable "Enchanted Book"
     * items (the enchant/level text lives in the lore, not the name) - so each pair needs two separate
     * matching inventory slots. Confirmed live layout: the target/sacrifice input slots sit one GUI row (9
     * slots) below the "Combine Items" button, 2 columns to either side of it - placing a valid pair there
     * makes a live "Bank II - this is the item you will get" preview appear one row *above* the button.
     * Clicking "Combine Items" consumes both inputs (the target slot ends up empty, it does NOT hold the
     * result) and re-labels the button itself to "Claim the result item above!" - the actual result sits
     * in that same preview slot above the button, confirmed by checking the button's new text together
     * with the level+1 book's name/lore actually being there before collecting it. If either check fails,
     * whatever's sitting in the result/target/sacrifice slots is collected as-is and the call stops rather
     * than repeating a step that isn't working. Returns the number of pairs actually combined.
     */
    private suspend fun combinePairsInAnvil(screen: AbstractContainerScreen<*>, baseName: String, level: Int, isArabic: Boolean, pairs: Int): Int {
        if (pairs <= 0) return 0
        val bookName = bookDisplayName(baseName, level, isArabic)
        val nextName = bookDisplayName(baseName, level + 1, isArabic)

        val combineSlot = screen.findSlot("combine items") ?: run {
            devMessage("§cBazaarFlipper: no 'Combine Items' button found in the anvil.")
            return 0
        }
        val targetSlot = combineSlot - 2 + 9
        val sacrificeSlot = combineSlot + 2 + 9
        devMessage("[BazaarFlipper] Anvil combine button #$combineSlot, target slot #$targetSlot, sacrifice slot #$sacrificeSlot.")

        fun findBookSlot(): Int? = screen.findInventorySlot {
            !it.isEmpty && containsBookLevel(it.hoverName.string.noControlCodes + " " + it.loreString.joinToString(" ") { l -> l.noControlCodes }, bookName)
        }
        fun slotText(index: Int): String? = screen.menu.items.getOrNull(index)?.takeIf { !it.isEmpty }
            ?.let { it.hoverName.string.noControlCodes + " " + it.loreString.joinToString(" ") { l -> l.noControlCodes } }

        var done = 0
        for (i in 0 until pairs) {
            val firstSlot = findBookSlot() ?: run {
                devMessage("§cBazaarFlipper: couldn't find a $bookName to combine (pair ${i + 1}/$pairs) - out of books.")
                return done
            }
            click(firstSlot)
            randomDelay(COMBINE_STEP_DELAY)
            click(targetSlot)
            randomDelay(COMBINE_STEP_DELAY)

            val secondSlot = findBookSlot() ?: run {
                devMessage("§cBazaarFlipper: only found 1 more $bookName, need a second to combine (pair ${i + 1}/$pairs) - returning it.")
                click(targetSlot)
                randomDelay(COMBINE_STEP_DELAY)
                val emptyBack = screen.findInventorySlot { it.isEmpty }
                if (emptyBack != null) {
                    click(emptyBack)
                    randomDelay(COMBINE_STEP_DELAY)
                }
                return done
            }
            click(secondSlot)
            randomDelay(COMBINE_STEP_DELAY)
            click(sacrificeSlot)
            randomDelay(COMBINE_STEP_DELAY)
            dumpScreen(screen, "Anvil before combining $bookName pair ${i + 1}/$pairs")

            click(combineSlot)
            randomDelay(COMBINE_STEP_DELAY)
            dumpScreen(screen, "Anvil after combining $bookName pair ${i + 1}/$pairs")

            // Confirmed live: the result does NOT appear in the target slot (that's consumed, correctly,
            // as an input) - it lands one row above the "Combine Items" button, which itself re-labels to
            // "Claim the result item above!" once a combine succeeds. Both are checked before collecting.
            val resultSlot = combineSlot - 9
            val buttonText = slotText(combineSlot) ?: ""
            val resultText = slotText(resultSlot)
            if (!buttonText.contains("claim", ignoreCase = true) || resultText == null || !containsBookLevel(resultText, nextName)) {
                devMessage(
                    "§cBazaarFlipper: combine didn't confirm for pair ${i + 1}/$pairs (button='$buttonText', " +
                        "result slot #$resultSlot='${resultText ?: "empty"}') - stopping and collecting whatever's there."
                )
                click(resultSlot, 0, ContainerInput.QUICK_MOVE)
                randomDelay(COMBINE_STEP_DELAY)
                click(targetSlot, 0, ContainerInput.QUICK_MOVE)
                randomDelay(COMBINE_STEP_DELAY)
                click(sacrificeSlot, 0, ContainerInput.QUICK_MOVE)
                randomDelay(COMBINE_STEP_DELAY)
                break
            }
            click(resultSlot, 0, ContainerInput.QUICK_MOVE)
            randomDelay(COMBINE_STEP_DELAY)
            done++
        }
        return done
    }

    /**
     * Waits for `/av` to open a screen. Accepts either a real vanilla [AnvilScreen] or - matching every
     * other screen in this module - any container screen whose title mentions "anvil", in case Hypixel's
     * version turns out to be a disguised chest GUI rather than a true anvil menu like the rest of the
     * Bazaar flow is. Logs the actual screen (or lack of one) on timeout so a failed attempt is
     * diagnosable from the dev log instead of just silently falling back.
     */
    private suspend fun waitForAnvilScreen(timeoutMs: Long = 5000): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val current = mc.screen
            if (current is AnvilScreen) return current
            if (current is AbstractContainerScreen<*> && current.title.string.noControlCodes.contains("anvil", true)) return current
            delay(50)
        }
        devMessage("§cBazaarFlipper: anvil screen never appeared after '/av' - screen after ${timeoutMs}ms: ${mc.screen?.let { "${it::class.simpleName} (title='${(it as? AbstractContainerScreen<*>)?.title?.string ?: "?"}')" } ?: "none"}.")
        return null
    }

    private fun bookDisplayName(baseName: String, level: Int, isArabic: Boolean): String =
        "$baseName ${if (isArabic) level.toString() else romanNumerals.getValue(level)}"

    /**
     * Checks whether [text] contains [bookName] as a whole level, not just as a prefix - a naive
     * `.contains("Bank I")` also matches "Bank II"/"Bank III"/"Bank IV" (roman numerals share prefixes:
     * "I" is a prefix of "II"/"III"/"IV", and arabic levels would have the same issue past level 9), which
     * would let this module pair a level 1 book with a level 2+ one it mistook for a match.
     */
    private fun containsBookLevel(text: String, bookName: String): Boolean =
        Regex(Regex.escape(bookName) + "(?![IVXLCDM0-9])", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Hypixel's real per-order Buy Order cap for [itemName] - [MAX_BUY_ORDER_AMOUNT] (256) for an enchant book, [MAX_BUY_ORDER_AMOUNT_GENERAL] (71,000) for anything else. */
    private fun maxOrderCapFor(itemName: String): Int =
        if (bookLevelRegex.find(itemName) != null) MAX_BUY_ORDER_AMOUNT else MAX_BUY_ORDER_AMOUNT_GENERAL

    /**
     * Sells [amount]x [itemName] via a fresh `/bz` search - same flow [manualCreateOrder] uses, just
     * starting from scratch instead of an already-open screen. [startWatcher] is false when this is called
     * from inside [handleUndercut]'s own relist step, so re-listing doesn't stack a second, redundant
     * tracked entry on top of the one already being updated there. [cancelExisting] likewise defaults to
     * true - any pre-existing Sell Offer(s) for [itemName] are cancelled first so there's only ever one
     * consolidated listing per book instead of several small ones piling up. A Sell Offer prices off
     * whatever's physically held, so the cancelled offer's unsold books (returned straight to the
     * inventory) get folded into the new listing automatically - no amount math needed here, unlike Buy
     * Orders (see [createBookBuyOrder]). Returns the actual amount listed, or 0 if nothing got listed.
     */
    private suspend fun createBookSellOrder(
        itemName: String,
        amount: Int,
        startWatcher: Boolean = true,
        cancelExisting: Boolean = true,
        fallbackAmount: Int = 0,
        claimPendingGoods: Boolean = false,
    ): Int {
        var listAmount = amount
        if (cancelExisting) {
            // Confirmed live: cancelling an outbid/undercut relist's order (amount=0, purely relying on
            // [cancelAllOrders] reading back the real remaining amount) used to pass a hardcoded 0 fallback
            // here - if the order's lore didn't have a parseable "Filled: X/Y" line for whatever reason (e.g.
            // a still-0%-filled order), [cancelAllOrders] silently reported 0 recovered even though it really
            // did cancel something, and this whole relist just gave up with "nothing to sell" right after.
            // [fallbackAmount] (the caller's last known tracked amount) is a far better fallback than 0.
            val cancelResult = cancelAllOrders(itemName, OrderType.SELL, fallbackAmount, claimPendingGoods)
            if (!cancelResult.confirmed) {
                // Reported live (same bug, buy side - see createBookBuyOrder's matching comment): proceeding
                // to list [amount] as a brand new Sell Offer here regardless used to risk stacking it right on
                // top of an old one [cancelAllOrders] never actually confirmed was gone. Skip this round
                // instead - the next undercut-watch tick (or combine pass) retries the cancel from scratch.
                devMessage("§cBazaarFlipper: couldn't confirm $itemName's old Sell Offer actually got cancelled - skipping this relist rather than risking a duplicate.")
                return 0
            }
            if (cancelResult.remaining > 0) {
                listAmount = countHeldItems(itemName).takeIf { it > 0 } ?: (amount + cancelResult.remaining)
                devMessage("[BazaarFlipper] Consolidating: cancelled an existing Sell Offer for $itemName (${cancelResult.remaining} unsold) - listing ${listAmount}x together.")
            }
        }
        if (listAmount <= 0) {
            devMessage("[BazaarFlipper] Nothing to sell for $itemName - skipping.")
            return 0
        }

        openBazaar()
        // See startFlip's matching comment - not requiring "bazaar" in the title, openBazaar() may have been
        // a no-op and placeOrderViaSearch backs out on its own regardless of where this lands.
        val mainScreen = waitForScreen { true } ?: run {
            devMessage("§cBazaarFlipper: Bazaar menu did not open in time to sell ${listAmount}x $itemName.")
            return 0
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(mainScreen, "Bazaar main (sell $itemName)")

        if (placeOrderViaSearch(itemName, OrderType.SELL, mainScreen) { listAmount } <= 0) return 0
        closeScreen()
        if (!ensureOrderExists(itemName, listAmount, OrderType.SELL, "Sell Offer")) return 0
        trackedBookSellItems.add(itemName.lowercase())
        if (notifyOnFlip) {
            modMessage("§aCombined books: listed §f${listAmount}x $itemName§a as a new Sell Offer.")
        }
        if (startWatcher && undercutStaleOrders) {
            randomDelay(GUI_APPEAR_DELAY)
            val price = readOwnPrice(itemName, OrderType.SELL)
            if (price != null) trackOrderForUndercutWatch(itemName, OrderType.SELL, listAmount, price)
            else devMessage("§cBazaarFlipper: couldn't read $itemName's listed price - not starting the undercut watch for it.")
        }
        return listAmount
    }

    /**
     * Re-buys [amount]x [itemName] via a fresh `/bz` search, so the combine cycle restarts on its own once
     * this filled order's books have been sold. [cancelExisting] (default true) cancels any pre-existing
     * Buy Order(s) for [itemName] first and adds back whatever was still unfilled to [amount] - unlike a
     * Sell Offer, a Buy Order's size has to be typed explicitly, so the unfilled remainder has to be folded
     * in by hand here rather than Hypixel doing it automatically. The final amount is clamped between
     * [minBuyOrderAmount] and [maxBuyOrderAmount] (itself capped at Hypixel's own [MAX_BUY_ORDER_AMOUNT] hard
     * limit for a book - confirmed live it rejects anything above 256 for those; a non-book item re-bought
     * through this same function instead clamps to the much higher [MAX_BUY_ORDER_AMOUNT_GENERAL]). Returns
     * the actual amount bought, or 0 if nothing got placed.
     *
     * [sizeFromPursePercent] (only set by [handleOutbid], see [OUTBID_REBUY_PURSE_PERCENT]'s own doc)
     * overrides [amount]'s normal remaining/claimed consolidation entirely: sizes the relisted order off that
     * percentage of the purse (read fresh after the cancel above, so it already reflects whatever just got
     * freed up) instead, since an outbid relist should keep spending aggressively rather than just matching
     * whatever the old order happened to be sized at.
     *
     * With [cancelExisting], cancelling the old Buy Order can also claim goods it had already delivered (see
     * [cancelAllOrders]'s [CancelResult.claimed]) - those items are now just sitting in the inventory, so by
     * default this turns around and lists them as a Sell Offer via [createBookSellOrder] (consolidated with
     * any Sell Offer(s) already open for [itemName], same as everywhere else in this module), regardless of
     * whether the re-buy itself below succeeds - an unrelated Buy Order failure shouldn't strand already-
     * claimed goods unsold.
     *
     * [sellClaimedGoods] (default true, matching every caller before `/hxp bz relist` existed) turns that off -
     * `startRelistAll` passes false, on request ("wenn er relistet und was in der order drin war was er
     * geclaimt hat an items das nicht sell ordern") - a plain relist should just cancel+recreate the same
     * order, not also start a brand new Sell Offer as a side effect of whatever happened to be claimable at
     * cancel time. [claimPendingGoods] itself still has to stay on regardless (Hypixel refuses to cancel an
     * order with unclaimed goods sitting on it), this only controls what happens to the goods once claimed.
     */
    private suspend fun createBookBuyOrder(
        itemName: String,
        amount: Int,
        startWatcher: Boolean = true,
        cancelExisting: Boolean = true,
        fallbackAmount: Int = 0,
        claimPendingGoods: Boolean = false,
        sizeFromPursePercent: Double? = null,
        sellClaimedGoods: Boolean = true,
    ): Int {
        if (insufficientFunds) {
            devMessage("[BazaarFlipper] Skipping Buy Order for ${amount}x $itemName - waiting for funds to become available.")
            return 0
        }
        var buyAmount = amount
        var claimedAmount = 0
        if (cancelExisting) {
            // See createBookSellOrder's matching comment - [fallbackAmount] (the caller's last known tracked
            // amount) covers cancelAllOrders silently reporting 0 recovered when the order's lore didn't have
            // a parseable "Filled: X/Y" line, even though a cancel really did happen.
            val cancelResult = cancelAllOrders(itemName, OrderType.BUY, fallbackAmount, claimPendingGoods)
            if (!cancelResult.confirmed) {
                // Reported live: an old Buy Order that [cancelAllOrders] never actually confirmed cancelled
                // (e.g. it claimed some pending goods first, then the cancel-click sequence itself failed or
                // bailed out) used to still fall through to one of the branches below and place a brand new
                // Buy Order regardless - sized off whatever of amount/remaining/claimed happened to be
                // nonzero - stacking it right on top of the old one still sitting there, uncancelled. Skips
                // the re-buy entirely this round instead; the claimed-goods sell-off below still runs
                // unconditionally (whatever got physically delivered shouldn't sit unsold just because the
                // cancel confirmation is uncertain), and the next undercut-watch tick retries the cancel fresh.
                devMessage("§cBazaarFlipper: couldn't confirm $itemName's old Buy Order actually got cancelled - skipping the re-buy this round rather than risking a duplicate order.")
                buyAmount = 0
            } else if (sizeFromPursePercent != null) {
                // On request (handleOutbid only, see OUTBID_REBUY_PURSE_PERCENT's own doc): always spend a
                // fixed share of the purse on the relist rather than just matching the old order's amount.
                // Reads the purse *after* the cancel above, so it already reflects whatever coins that just
                // freed up. Estimates the per-unit price the same way calculateMaxBuyAmount/startFlip do -
                // current top bid + the same 0.1 outbid margin placeOrderViaSearch's own "+0.1" preset adds -
                // a slight overestimate of price (so a slight underestimate of amount) is the safe direction.
                val purse = readPurseBalance()
                val topBuyPrice = readMarketTopPrice(itemName, OrderType.BUY)
                buyAmount = if (purse != null && topBuyPrice != null) {
                    ((purse * (sizeFromPursePercent / 100.0)) / (topBuyPrice + 0.1)).toInt()
                } else {
                    devMessage("§cBazaarFlipper: couldn't read $itemName's purse/price to size the relisted Buy Order off $sizeFromPursePercent% - falling back to the usual consolidation amount.")
                    if (cancelResult.remaining > 0) amount + cancelResult.remaining else cancelResult.claimed
                }
                devMessage("[BazaarFlipper] $itemName: sizing the relisted Buy Order off $sizeFromPursePercent% of purse ($purse) at ~${topBuyPrice}/unit -> ${buyAmount}x.")
            } else if (cancelResult.remaining > 0) {
                buyAmount = amount + cancelResult.remaining
                devMessage("[BazaarFlipper] Consolidating: cancelled an existing Buy Order for $itemName (${cancelResult.remaining} unfilled) - now buying ${buyAmount}x together.")
            } else if (cancelResult.claimed > 0) {
                // Confirmed live: an outbid check on an order that had already 100% filled by the time it was
                // caught cancels clean (nothing left unfilled - [remaining] is genuinely 0) but still claims
                // everything that was delivered. That used to fall straight through to "nothing to buy" and
                // just stop the whole cycle right after selling the claimed goods off - correct that the old
                // order's gone, wrong that nothing re-buys to keep flipping. Re-buys the same size that was
                // just claimed/sold instead of quietly going idle, same as every other flip in this module
                // re-buying after selling (see rebuySameItemAfterFlip's matching reasoning). Only reached now
                // when [cancelResult.confirmed] is also true - see the branch above for why that matters.
                buyAmount = cancelResult.claimed
                devMessage("[BazaarFlipper] ${itemName}'s outbid Buy Order had fully filled (nothing unfilled to consolidate) - re-buying ${buyAmount}x to keep the cycle going.")
            }
            claimedAmount = cancelResult.claimed
        }

        // Wrapped so the claimed-goods sell-off below always runs once cancelling is done, regardless of
        // which path out of the actual re-buy attempt gets hit (nothing to buy, Bazaar didn't open, the
        // placement itself failed, ...) - an unrelated Buy Order failure shouldn't strand already-claimed
        // goods unsold.
        val placedAmount = run buyOrder@{
            if (buyAmount <= 0) {
                devMessage("[BazaarFlipper] Nothing to buy for $itemName - skipping.")
                return@buyOrder 0
            }
            // Min/Max Book Buy Order Amount only makes sense for the book-combine workflow (batch sizing for
            // craft-up-to-level-5 runs) - this same function now also handles the generalized undercut watch's
            // re-buys for arbitrary (non-book) items (see [rebuySameItemAfterFlip]/[handleOutbid]), which should
            // only ever be capped at Hypixel's own hard limit, never padded up to the book-specific minimum.
            val isBook = bookLevelRegex.find(itemName) != null
            val clampedAmount = if (isBook) {
                buyAmount.coerceIn(minBuyOrderAmount, maxBuyOrderAmount.coerceAtMost(MAX_BUY_ORDER_AMOUNT))
            } else {
                buyAmount.coerceAtMost(MAX_BUY_ORDER_AMOUNT_GENERAL)
            }
            if (clampedAmount != buyAmount) {
                devMessage("[BazaarFlipper] $itemName Buy Order clamped from ${buyAmount}x to ${clampedAmount}x (${if (isBook) "Min/Max Book Buy Order Amount" else "Hypixel's Buy Order cap"}).")
            }
            buyAmount = clampedAmount

            openBazaar()
            // See startFlip's matching comment - not requiring "bazaar" in the title, openBazaar() may have been
            // a no-op and placeOrderViaSearch backs out on its own regardless of where this lands.
            val mainScreen = waitForScreen { true } ?: run {
                devMessage("§cBazaarFlipper: Bazaar menu did not open in time to re-buy ${buyAmount}x $itemName.")
                return@buyOrder 0
            }
            randomDelay(GUI_APPEAR_DELAY)
            dumpScreen(mainScreen, "Bazaar main (re-buy $itemName)")

            if (placeOrderViaSearch(itemName, OrderType.BUY, mainScreen) { buyAmount } <= 0) return@buyOrder 0
            closeScreen()
            if (!ensureOrderExists(itemName, buyAmount, OrderType.BUY, "Buy Order")) return@buyOrder 0

            if (notifyOnFlip) {
                modMessage("§aCombined books: placed a new Buy Order for §f${buyAmount}x $itemName§a to keep the cycle going.")
            }
            if (startWatcher && undercutStaleOrders) {
                randomDelay(GUI_APPEAR_DELAY)
                val price = readOwnPrice(itemName, OrderType.BUY)
                if (price != null) trackOrderForUndercutWatch(itemName, OrderType.BUY, buyAmount, price)
                else devMessage("§cBazaarFlipper: couldn't read $itemName's listed price - not starting the outbid watch for it.")
            }
            buyAmount
        }

        if (claimedAmount > 0) {
            if (!sellClaimedGoods) {
                devMessage("[BazaarFlipper] ${claimedAmount}x $itemName was already delivered on that cancelled Buy Order - leaving it in the inventory (sellClaimedGoods=false).")
            } else {
                devMessage("[BazaarFlipper] ${claimedAmount}x $itemName was already delivered on that cancelled Buy Order - listing it as a Sell Offer (consolidated with any already open).")
                // On request: this claim (the Buy Order cancel above) and the Sell Offer cancel-to-consolidate
                // right below it both only add items to the inventory - see [hasInventorySpaceFor]'s own doc.
                // Checked here since [claimedAmount] is already known and this is the exact point the second
                // (sell-side) add is about to happen.
                val tightOnSpace = !hasInventorySpaceFor(itemName, claimedAmount)
                // claimedAmount only comes from a claimPendingGoods=true cancel to begin with (see below), so
                // this is always part of that same outdated-order-renewal sequence - fine to also claim pending
                // goods on whatever Sell Offer this consolidates with.
                createBookSellOrder(itemName, claimedAmount, cancelExisting = true, claimPendingGoods = true)
                if (tightOnSpace) {
                    // Claim, then cancel+relist normally (both just happened above), THEN /pickupstash, THEN
                    // list whatever came back too - same sequence as combineAndSellBooks' matching fallback.
                    runPickupStash()
                    val recovered = countHeldItems(itemName)
                    if (recovered > 0) {
                        devMessage("[BazaarFlipper] Recovered ${recovered}x $itemName from /pickupstash - listing it too.")
                        createBookSellOrder(itemName, recovered, cancelExisting = true, claimPendingGoods = true)
                    }
                }
            }
        }
        return placedAmount
    }

    /**
     * Opens Manage Orders and checks whether a Buy/Sell order for [itemName] is actually listed there.
     * Returns null (rather than false) if Manage Orders couldn't even be opened to check - that's "unable to
     * verify", not "confirmed absent", and callers should treat it as if the order is probably fine rather
     * than retrying and risking placing a duplicate on top of one that's actually there.
     */
    private suspend fun orderExists(itemName: String, type: OrderType): Boolean? {
        val screen = openOrdersScreen() ?: run {
            devMessage("§cBazaarFlipper: couldn't open Manage Orders to verify $itemName's ${type.name.lowercase()} order was placed.")
            return null
        }
        val exists = findOrderSlotByName(screen, itemName, type) != null
        closeScreen()
        return exists
    }

    /**
     * Server-lag failsafe used by every order creation in this module (flip, manual flip, book sell/buy,
     * post-flip re-buy): confirmed live that under lag, the final "Place Order" click sometimes doesn't
     * actually register server-side even though every screen transition along the way looked completely
     * normal client-side - silently leaving nothing listed at all. Re-opens Manage Orders to confirm
     * [itemName]'s [type] order is really there after [placeOrderViaSearch] already reported success, and -
     * only on a *confirmed* absence (see [orderExists] - "couldn't check" is deliberately not treated as
     * "missing", to avoid placing a duplicate on top of one that's actually fine) - retries the whole
     * placement once, from scratch, via a fresh `/bz` search. [contextLabel] is just for the log/chat
     * wording (e.g. "Buy Order", "Sell Offer", "Flip", "Re-buy").
     */
    private suspend fun ensureOrderExists(itemName: String, amount: Int, type: OrderType, contextLabel: String): Boolean {
        randomDelay(GUI_APPEAR_DELAY)
        if (orderExists(itemName, type) != false) return true // true, or null ("can't verify" - assume it's fine)

        devMessage("[BazaarFlipper] $contextLabel for ${amount}x $itemName didn't show up in Manage Orders (lag?) - retrying once.")
        openBazaar()
        // See startFlip's matching comment - not requiring "bazaar" in the title, openBazaar() may have been
        // a no-op (e.g. still sitting in Manage Orders from the [orderExists] check above) and
        // placeOrderViaSearch backs out on its own regardless of where this lands.
        val retryScreen = waitForScreen { true } ?: run {
            modMessage("§cAuto Bazaar Flipper: $contextLabel for ${amount}x $itemName failed to place, and the retry couldn't even reopen the Bazaar menu.")
            return false
        }
        randomDelay(GUI_APPEAR_DELAY)
        dumpScreen(retryScreen, "Bazaar main ($contextLabel retry for $itemName)")

        if (placeOrderViaSearch(itemName, type, retryScreen) { amount } <= 0) return false
        closeScreen()
        randomDelay(GUI_APPEAR_DELAY)

        if (orderExists(itemName, type) == false) {
            modMessage("§cAuto Bazaar Flipper: $contextLabel for ${amount}x $itemName still didn't go through after retrying - check Manage Orders manually.")
            return false
        }
        devMessage("[BazaarFlipper] Retry succeeded - $contextLabel for ${amount}x $itemName is now listed.")
        return true
    }

    /** Opens Manage Orders, reads [itemName]'s own Buy/Sell order price via [pricePerUnitRegex], and closes again. */
    private suspend fun readOwnPrice(itemName: String, type: OrderType): Double? {
        val screen = openOrdersScreen() ?: return null
        val slot = findOrderSlotByName(screen, itemName, type)
        val price = slot?.let { screen.menu.items.getOrNull(it)?.loreString?.joinToString(" ") { l -> l.noControlCodes } }
            ?.let { pricePerUnitRegex.find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() }
        closeScreen()
        return price
    }

    /** One Manage Orders scan's worth of portfolio info - see [readBazaarPortfolio]. */
    private data class BazaarPortfolio(val lockedValue: Double, val activeItems: Set<String>)

    /**
     * Single Manage Orders pass that reads everything [calculateTotalCapital] and [startBestFlipOrder]'s
     * concurrent-flip cap both need, so sizing a Best Flip order only ever opens Manage Orders once instead
     * of twice: [BazaarPortfolio.lockedValue] sums the remaining value still tied up in every currently open
     * Buy Order and Sell Offer (unfilled amount * price each) - coins that already left the purse (a Buy
     * Order) or items not yet sold back into coins (a Sell Offer), neither of which [readPurseBalance] can
     * see. [BazaarPortfolio.activeItems] is the distinct (lowercased) item names with at least one open order
     * of either type, filled or not - "how many different items this module currently has a flip going in".
     * A fully-filled ("click to claim") order still counts toward [activeItems] (it's still an active flip
     * mid-cycle) but not [lockedValue] (that value has already effectively left the bazaar, sitting as
     * claimable items/coins rather than still at risk in an open order). Returns an empty/zeroed portfolio if
     * Manage Orders can't even be opened - callers combine [lockedValue] with the purse, and "couldn't check
     * what's locked" shouldn't block sizing off of the purse alone.
     */
    private suspend fun readBazaarPortfolio(): BazaarPortfolio {
        val screen = openOrdersScreen() ?: return BazaarPortfolio(0.0, emptySet())
        var lockedValue = 0.0
        val activeItems = mutableSetOf<String>()
        val top = screen.topSlotCount()
        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.noControlCodes.trim()
            val prefixMatch = orderPrefixRegex.find(name) ?: continue
            activeItems.add(prefixMatch.groupValues[2].trim().lowercase())

            val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
            if (lore.contains("click to claim", ignoreCase = true)) continue
            val price = pricePerUnitRegex.find(lore)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: continue
            val type = if (prefixMatch.groupValues[1].equals("buy", ignoreCase = true)) OrderType.BUY else OrderType.SELL
            val (filled, totalAmount) = parseOrderFillState(lore, type)
            if (filled == null || totalAmount == null) continue
            val remaining = (totalAmount - filled).coerceAtLeast(0)
            lockedValue += remaining * price
        }
        closeScreen()
        return BazaarPortfolio(lockedValue, activeItems)
    }

    /**
     * Purse plus everything still tied up in open Buy/Sell orders ([readBazaarPortfolio]) - the real total
     * working capital for flipping, not just what's liquid right now. Using the purse alone would under-count
     * as more gets tied up across concurrent flips over time (several open orders at once, e.g. from "Best
     * Flip" or just running multiple items), making [maxOrderPursePercent]'s share shrink relative to actual
     * wealth instead of scaling up with it. Returns null (purse itself unreadable) only when the caller truly
     * can't size anything off of this at all.
     */
    /**
     * With [maxOrderEnabled] on: how big a Buy Order for [itemName] could be sized right now, so a flip
     * spends a big share of the available capital each cycle instead of just re-buying whatever amount
     * happened to fill last time.
     *
     * Confirmed live this used to size off [maxOrderPursePercent] of *total* capital (purse plus whatever's
     * still tied up in other open orders) directly, treating that whole share as spendable - but coins
     * locked into some *other* open order aren't actually in the purse right now, so a big enough locked
     * balance could size a Buy Order this module then couldn't actually afford (e.g. total capital 9M with
     * only ~500k really liquid still sized a ~4M order). [usableCapital] below is now always additionally
     * capped at the real, current [purse] on top of the percentage share, so it can never exceed what's
     * actually spendable.
     *
     * The percentage throttle itself only exists to leave room for a *second*, not-yet-placed order for this
     * same item (a Buy Order reserves room for the Sell Offer it'll flip into, and vice versa) - once
     * [itemName] already has one leg of that pair open ([BazaarPortfolio.activeItems]), this is that second
     * order, and there's nothing further to reserve for, so the whole purse is fair game rather than just
     * [maxOrderPursePercent] of it. Estimates the per-unit price from the current highest buy order
     * ([readMarketTopPrice]) plus the same 0.1 outbid margin [placeOrderViaSearch]'s "+0.1" preset always
     * adds - a slight overestimate of price (so a slight underestimate of amount) is the safe direction here,
     * rather than sizing an order the capital can't actually cover by a hair. Clamped to Hypixel's real
     * per-order cap (see [maxOrderCapFor]) - or the book-specific [maxBuyOrderAmount] setting instead, for a
     * book. Returns null (caller falls back to its own default amount) if either the price or the purse can't
     * be read right now.
     */
    private suspend fun calculateMaxBuyAmount(itemName: String): Int? {
        val topBuyPrice = readMarketTopPrice(itemName, OrderType.BUY) ?: return null
        val purse = readPurseBalance() ?: return null
        val portfolio = readBazaarPortfolio()
        val itemAlreadyActive = itemName.lowercase() in portfolio.activeItems

        val totalCapital = purse + portfolio.lockedValue
        val desiredShare = if (itemAlreadyActive) purse else totalCapital * (maxOrderPursePercent / 100.0)
        val usableCapital = minOf(desiredShare, purse)
        val estimatedUnitCost = topBuyPrice + 0.1
        val affordable = (usableCapital / estimatedUnitCost).toInt().coerceAtLeast(0)

        val isBook = bookLevelRegex.find(itemName) != null
        val hardCap = if (isBook) maxBuyOrderAmount.coerceAtMost(MAX_BUY_ORDER_AMOUNT) else MAX_BUY_ORDER_AMOUNT_GENERAL
        val maxAmount = affordable.coerceAtMost(hardCap)
        devMessage(
            "[BazaarFlipper] Max Order for $itemName: purse=$purse, locked=${portfolio.lockedValue}, alreadyActive=$itemAlreadyActive, usable=$usableCapital, " +
                "estimated unit cost=$estimatedUnitCost -> affordable=$affordable, capped to $maxAmount."
        )
        return maxAmount
    }

    /** Convenience single-type wrapper around [readMarketTopPrices] - see its own doc. */
    private suspend fun readMarketTopPrice(itemName: String, type: OrderType): Double? {
        val (bid, ask) = readMarketTopPrices(itemName)
        return if (type == OrderType.SELL) ask else bid
    }

    /**
     * Reads [itemName]'s current best competing prices - both sides at once - straight from its own Bazaar
     * item page's "Create Buy Order" and "Create Sell Offer" buttons, each listing its own "Top Orders"/"Top
     * Offers" (same shape, confirmed live, as the "Flip Order" tooltip [flipOrder] already parses with
     * [topOfferRegex]): "Create Sell Offer"'s Top Offers are the current asks - the *lowest* one is what
     * would undercut a tracked Sell Offer. "Create Buy Order"'s Top Orders are the current bids - the
     * *highest* one is what would outbid a tracked Buy Order. Returns (bid, ask) - either half null if that
     * button/its prices couldn't be read.
     *
     * Confirmed live: Hypixel already shows both buttons side by side on the exact same item page - checking
     * an item tracked on *both* sides ([checkTrackedOrdersInGame]) used to mean two full separate
     * navigations (search -> item page, twice in a row) for data that's sitting right there together on the
     * first visit. Reads both here in one pass instead.
     *
     * Confirmed live this used to instead read "Buy Instantly"/"Sell Instantly", which turned out to be the
     * wrong data entirely for the Buy Order side: "Sell Instantly" shows a *stack total* for however much of
     * the item the player currently holds ("Your Crimson Essence: 12,564 / Amount: 12,564x / Total:
     * Loading..." - not even a stable number until that placeholder resolves), not a per-unit market price at
     * all - so it depended on the player's own inventory and could return null indefinitely while still
     * "Loading...". "Create Buy Order"/"Create Sell Offer"'s own Top Orders/Offers have no such dependency.
     *
     * Used instead of Hypixel's external Bazaar API (an earlier version of this used that, but it only has a
     * name->productId mapping worked out for enchant books and can't generalize to arbitrary items) so the
     * undercut/outbid watch works for anything, not just books. Both come back null on any navigation
     * failure; the caller just skips this item's check until the next interval.
     */
    private suspend fun readMarketTopPrices(itemName: String): Pair<Double?, Double?> {
        openBazaar()
        // See startFlip's matching comment - not requiring "bazaar" in the title, openBazaar() may have been
        // a no-op and the "Go Back"/search navigation below backs out on its own regardless of where this lands.
        var screen = waitForScreen { true } ?: run {
            devMessage("§cBazaarFlipper: Bazaar menu did not open in time to read $itemName's current market prices.")
            return null to null
        }
        randomDelay(GUI_APPEAR_DELAY)

        var searchSlot: Int? = screen.findSlot("search")
        var backAttempts = 0
        while (searchSlot == null && backAttempts++ < 3) {
            val backSlot = screen.findSlot("go back") ?: break
            click(backSlot)
            randomDelay(GUI_APPEAR_DELAY)
            screen = mc.screen as? AbstractContainerScreen<*> ?: return null to null
            searchSlot = screen.findSlot("search")
        }
        val foundSearchSlot = searchSlot ?: run {
            devMessage("§cBazaarFlipper: couldn't find a 'Search' button while reading $itemName's market prices.")
            closeScreen()
            return null to null
        }
        click(foundSearchSlot)
        randomDelay(GUI_APPEAR_DELAY)
        if (!submitTextInput(itemName)) {
            closeScreen()
            return null to null
        }
        // Same self-closing-sign gap as the Flip Order price prompt - poll instead of one snapshot check.
        val resultsScreen = waitForScreen(3000) { true } ?: return null to null
        val itemSlot = findBestItemMatch(resultsScreen, itemName) ?: run {
            closeScreen()
            return null to null
        }
        click(itemSlot)
        val itemScreen = waitForScreen(6000) { it.findSlot("create buy order") != null || it.findSlot("create sell offer") != null } ?: return null to null
        dumpScreen(itemScreen, "Item page (reading market prices for $itemName)")

        fun readTop(keyword: String, wantMax: Boolean): Double? {
            val slot = itemScreen.findSlot(keyword) ?: return null
            val lore = itemScreen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { l -> l.noControlCodes } ?: ""
            val topPrices = topOfferRegex.findAll(lore).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
            return if (wantMax) topPrices.maxOrNull() else topPrices.minOrNull()
        }
        // Buy Orders (bids) list descending - highest is best/current top bid. Sell Offers (asks) list
        // ascending - lowest is best/current top ask. Taking max/min explicitly rather than relying on list
        // order either way.
        val bid = readTop("create buy order", wantMax = true)
        val ask = readTop("create sell offer", wantMax = false)
        if (bid == null && ask == null) {
            devMessage("§cBazaarFlipper: couldn't read any Top Orders/Offers prices for $itemName.")
        }
        // Feeds orderStatusHud's persistent cache directly, rather than relying on the HUD's own per-frame
        // screen read to happen to catch this same screen while it's briefly open mid-navigation - this runs
        // on every single price check this module's background loops already do, so the HUD keeps updating
        // continuously as long as at least one order is being watched, with no dependency on rendering timing.
        bid?.let { lastKnownBid = it }
        ask?.let { lastKnownAsk = it }
        // Deliberately NOT closing here on success - every caller of this needs the Bazaar again right after
        // (a portfolio check, then the actual order placement), and [openBazaar] now skips re-sending /bz
        // when already in a bazaar-titled screen, so leaving this one open lets that next step reuse the
        // same session instead of tearing it down and rebuilding a fresh one just to read one price.
        return bid to ask
    }

    private fun trackOrderForUndercutWatch(itemName: String, type: OrderType, amount: Int, price: Double) {
        activelyManagedItems[itemName.lowercase()] = itemName
        if (type == OrderType.SELL) {
            trackedSellOrders[itemName.lowercase()] = TrackedSellOrder(itemName, price, amount)
        } else {
            trackedBuyOrders[itemName.lowercase()] = TrackedBuyOrder(itemName, price, amount)
        }
        devMessage("[BazaarFlipper] Watching ${type.name.lowercase()} order for $itemName - listed at $price coins.")
        ensureUndercutWatcherRunning()
    }

    /**
     * With [undercutStaleOrders] on: a single shared loop (not one per tracked order) reads each tracked
     * order's current best competing price directly from its own Bazaar item page (see
     * [readMarketTopPrice]) every [UNDERCUT_CHECK_INTERVAL] and re-lists it if beaten - works for any item,
     * not just enchant books. Skips the check entirely while [busy] (a craft/claim cycle is already driving
     * the screen) rather than fighting over it - since the loop still ticks every [UNDERCUT_CHECK_INTERVAL]
     * regardless, the very next tick after busy clears picks it back up, so a combine run finishing doesn't
     * leave an undercut/outbid unhandled for long. Stops itself once nothing is left to track;
     * [trackOrderForUndercutWatch] restarts it on the next listing.
     */
    private fun ensureUndercutWatcherRunning() {
        if (apiWatcherJob?.isActive == true) return
        apiWatcherJob = HxPMod.scope.launch {
            while (enabled && (trackedSellOrders.isNotEmpty() || trackedBuyOrders.isNotEmpty())) {
                delay(UNDERCUT_CHECK_INTERVAL)
                if (!enabled) break
                if (busy) continue // a craft/claim cycle already owns the screen - try again next tick
                checkTrackedOrdersInGame()
            }
        }
    }

    private suspend fun checkTrackedOrdersInGame() {
        val sellSnapshot = trackedSellOrders.values.toList()
        val buySnapshot = trackedBuyOrders.values.toList()
        if (sellSnapshot.isEmpty() && buySnapshot.isEmpty()) return

        // Confirmed live: this used to hold [busy] only for the brief handleUndercut/handleOutbid relist
        // calls below, not for the price-reading loop itself - so the multi-item readMarketTopPrices scan
        // (a full openBazaar+search+item-page navigation per tracked item, easily several seconds for more
        // than a handful of items) ran with busy sitting at false the whole time, same as every other
        // GUI-touching background function in this module DOESN'T do. Any other coroutine's own busy check
        // (discoverUntrackedOrders, a manual /hxp bz flip, triggerBestFlip, ...) could see busy == false mid-scan
        // and start its own navigation at the very same time, both fighting over the same live Bazaar screen.
        // Claims busy for this whole pass instead - handleUndercut/handleOutbid no longer claim it themselves
        // (they're only ever called from here, already inside this same scope; claiming again would just
        // deadlock against the claim already held a few stack frames up), and checkForFilledTrackedOrders's
        // own final claim/flip step now runs [processClaimedOrders] directly rather than through the
        // fire-and-forget [runCycle] (which claims busy on its own) for the same reason.
        if (!tryClaimBusy()) return // something else already owns the screen - this loop's own next tick (UNDERCUT_CHECK_INTERVAL) tries again
        try {
            // Every pass starts from a clean slate rather than silently continuing from whatever screen
            // happened to be left open - closes it here so the first readMarketTopPrices call below's own
            // openBazaar() actually sends a fresh /bz instead of reusing (and navigating on top of) something
            // stale.
            closeScreen()

            // Confirmed live: an item's own Bazaar page already shows "Create Buy Order" and "Create Sell
            // Offer" (and both of their Top Orders/Offers) side by side - an item tracked on *both* sides used
            // to get checked via two entirely separate passes (this loop used to run all Sell items, then all
            // Buy items as a second loop), each calling readMarketTopPrice for its own single side, so the
            // same item's page got visited twice in a row for data that was sitting right there together on
            // the first visit. Grouped by item name (case-insensitive) so each gets exactly one
            // [readMarketTopPrices] visit, covering whichever side(s) are actually tracked for it.
            val itemNames = (sellSnapshot.map { it.itemName } + buySnapshot.map { it.itemName }).distinctBy { it.lowercase() }

            for (itemName in itemNames) {
                val key = itemName.lowercase()
                val sellTracked = trackedSellOrders[key]
                val buyTracked = trackedBuyOrders[key]
                if (sellTracked == null && buyTracked == null) continue // sold/filled/cancelled since the snapshot was taken

                val (highestBuy, lowestSell) = readMarketTopPrices(itemName)

                if (sellTracked != null && lowestSell != null) {
                    // Cached regardless of whether this counts as "undercut" below - orderStatusHud shows it
                    // alongside our own price every tick, not just on the ticks something actually changed.
                    sellTracked.marketPrice = lowestSell
                    if (lowestSell < sellTracked.price) {
                        devMessage("[BazaarFlipper] Detected an undercut on $itemName: ours=${sellTracked.price}, now as low as $lowestSell - cancelling and re-listing.")
                        try {
                            handleUndercut(sellTracked)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Confirmed live: an uncaught exception here (e.g. a stale/unexpected screen
                            // mid-relist) used to kill this whole coroutine outright. Catching per-order keeps
                            // the rest of this pass (and every later tracked item) going.
                            HxPMod.logger.error("BazaarFlipper: undercut re-list failed for $itemName", e)
                            devMessage("§cBazaarFlipper: undercut re-list for $itemName failed (${e.message}) - moving on to the next tracked order.")
                        }
                    }
                }

                if (buyTracked != null && highestBuy != null) {
                    buyTracked.marketPrice = highestBuy
                    if (highestBuy > buyTracked.price) {
                        devMessage("[BazaarFlipper] Detected our Buy Order for $itemName got outbid: ours=${buyTracked.price}, now as high as $highestBuy - cancelling and re-listing.")
                        try {
                            handleOutbid(buyTracked)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            HxPMod.logger.error("BazaarFlipper: outbid re-list failed for $itemName", e)
                            devMessage("§cBazaarFlipper: outbid re-list for $itemName failed (${e.message}) - moving on to the next tracked order.")
                        }
                    }
                }
            }

            checkForFilledTrackedOrders()
            // On request: after every "am I still the highest bid" pass, also sweep /hxp bz collect's tracked items
            // for anything currently claimable, not just fully-filled ones - see the function's own doc.
            claimAllPendingBuyCollectGoods()
        } finally {
            busy = false
        }
    }

    /**
     * Safety net alongside the chat-triggered fill detection ([filledOrderRegex]'s listener, which normally
     * drives [runCycle] on its own): after every price check above, opens Manage Orders once more and looks
     * for any *still-tracked* order that's already sitting at "click to claim" - in case that chat message
     * got missed (e.g. buried under unrelated chat spam) while this watcher was busy elsewhere. Routes
     * through the exact same [processClaimedOrders]/[flipOrder] a normal chat-triggered fill uses - a fully
     * filled Sell Offer gets its coins claimed, a fully filled Buy Order gets claimed and flipped into a
     * fresh Sell Offer - rather than a separate, parallel claim path.
     *
     * Only ever called from inside [checkTrackedOrdersInGame]'s own busy-protected scope, so this calls
     * [processClaimedOrders] directly (already-busy, awaited) rather than the fire-and-forget [runCycle]
     * (which claims [busy] itself, and would just fail that claim - busy already being held a few stack
     * frames up by the very call that got here) - same reasoning [discoverUntrackedOrders] awaits it directly
     * for.
     */
    private suspend fun checkForFilledTrackedOrders() {
        if (trackedSellOrders.isEmpty() && trackedBuyOrders.isEmpty()) return
        val screen = openOrdersScreen() ?: return
        val top = screen.topSlotCount()
        val filled = mutableListOf<ClaimedOrder>()
        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val prefixMatch = orderPrefixRegex.find(stack.hoverName.string.noControlCodes.trim()) ?: continue
            val itemName = prefixMatch.groupValues[2].trim()
            val key = itemName.lowercase()
            val type = if (prefixMatch.groupValues[1].equals("buy", ignoreCase = true)) OrderType.BUY else OrderType.SELL
            if (type == OrderType.SELL && !trackedSellOrders.containsKey(key)) continue
            if (type == OrderType.BUY && !trackedBuyOrders.containsKey(key)) continue

            val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
            // Same signals as discoverUntrackedOrders, same fix - "Filled: X/Y" with X>=Y is trusted
            // exclusively whenever it parses; the claim-prompt phrases are only a fallback for when it
            // doesn't, never an OR that can override a fraction that parsed as "not yet" (a barely-started
            // order can already show "click to claim" for whatever tiny slice has delivered so far).
            val (filledAmount, totalAmount) = parseOrderFillState(lore, type)
            val isFullyFilled = if (filledAmount != null && totalAmount != null) {
                filledAmount >= totalAmount
            } else {
                itemsToClaimRegex.containsMatchIn(lore) || lore.contains("click to claim", ignoreCase = true)
            }
            if (!isFullyFilled) continue
            val total = totalAmount ?: continue
            filled.add(ClaimedOrder(itemName, type, total))
        }
        closeScreen()
        if (filled.isEmpty()) return

        // Same reasoning as discoverUntrackedOrders' matching change - always visible, not dev-toggle-gated.
        modMessage("§aUndercut-watch safety check found ${filled.size} fully filled order(s) for ${filled.joinToString { it.itemName }} - claiming/flipping now.")
        processClaimedOrders(filled)
    }

    /**
     * Routes entirely through [createBookSellOrder]'s own `cancelExisting = true` consolidation instead of
     * manually cancelling here and relisting with just that one cancelled order's leftover amount - the
     * latter used to miss *other* pre-existing Sell Offers for the same item that this specific tracked
     * entry didn't know about (e.g. one from a slightly-differently-timed listing), silently under-buying
     * the re-list. Passing amount 0 means "nothing new to add, just keep what's already out there alive".
     * Works for any item despite the "Book" in the function name - that's just this function's original
     * purpose, its actual logic (search, consolidate, list) has no book-specific behavior.
     */
    private suspend fun handleUndercut(tracked: TrackedSellOrder) {
        if (!enabled) return
        // No busy claim here - the only caller, checkTrackedOrdersInGame, already holds it for this entire
        // pass. Claiming again here would just deadlock: tryClaimBusy() would spin against the claim already
        // held a few stack frames up, which never releases until this very call returns.
        val newAmount = createBookSellOrder(tracked.itemName, 0, startWatcher = false, cancelExisting = true, fallbackAmount = tracked.amount, claimPendingGoods = true)
        if (newAmount < 1) {
            devMessage("[BazaarFlipper] Nothing left of ${tracked.itemName}'s sell offer(s) to re-list after cancelling.")
            trackedSellOrders.remove(tracked.itemName.lowercase())
            return
        }
        val newPrice = readOwnPrice(tracked.itemName, OrderType.SELL)
        if (newPrice == null) {
            devMessage("§cBazaarFlipper: couldn't read ${tracked.itemName}'s new price after re-listing - stopping the undercut watch for it.")
            trackedSellOrders.remove(tracked.itemName.lowercase())
            return
        }
        if (notifyOnUndercut) {
            modMessage("§aGot undercut on §f${tracked.itemName}§a - now the best order again at §f${formatPrice(newPrice)}§a coins.")
        }
        tracked.price = newPrice
        tracked.amount = newAmount
    }

    /** Same reasoning as [handleUndercut] - routes through [createBookBuyOrder]'s own consolidation rather than duplicating the cancel+relist logic here. */
    private suspend fun handleOutbid(tracked: TrackedBuyOrder) {
        if (!enabled) return
        // /hxp bz collect's tracked items must not go through createBookBuyOrder below - see [pendingBuyCollect]'s
        // own doc for why (it would resize off purse% and resell any partially-claimed goods).
        val collect = pendingBuyCollect[tracked.itemName.lowercase()]
        if (collect != null) {
            handleBuyCollectOutbid(tracked, collect)
            return
        }
        // See handleUndercut's matching comment - same reasoning for not claiming busy here.
        val newAmount = createBookBuyOrder(
            tracked.itemName, 0, startWatcher = false, cancelExisting = true, fallbackAmount = tracked.amount,
            claimPendingGoods = true, sizeFromPursePercent = OUTBID_REBUY_PURSE_PERCENT,
        )
        if (newAmount < 1) {
            devMessage("[BazaarFlipper] Nothing left of ${tracked.itemName}'s buy order(s) to re-list after cancelling.")
            trackedBuyOrders.remove(tracked.itemName.lowercase())
            return
        }
        val newPrice = readOwnPrice(tracked.itemName, OrderType.BUY)
        if (newPrice == null) {
            devMessage("§cBazaarFlipper: couldn't read ${tracked.itemName}'s new price after re-listing - stopping the outbid watch for it.")
            trackedBuyOrders.remove(tracked.itemName.lowercase())
            return
        }
        if (notifyOnUndercut) {
            modMessage("§aGot outbid on §f${tracked.itemName}§a - now the best order again at §f${formatPrice(newPrice)}§a coins.")
        }
        tracked.price = newPrice
        tracked.amount = newAmount
    }

    /** [relistAllOrders]'s outcome - `pairs` is how many distinct (item, type) listings were found at all (0 means nothing was open to begin with). */
    private data class RelistResult(val pairs: Int, val relisted: Int, val failed: Int)

    /**
     * The actual cancel-and-relist-everything sweep, minus busy-claiming/chat-reporting - split out so
     * [runFuseAfkCycle] can run it back-to-back with the claim/collect steps under one shared [busy] claim,
     * instead of it trying (and failing) to claim [busy] a second time via [startRelistAll] itself.
     *
     * Cancels and relists EVERY currently open Bazaar order (Buy Orders and Sell Offers alike), regardless of
     * whether it's still the best price or not - unlike the module's own undercut/outbid watch, which only
     * relists an order once it's actually been beaten (on request - "alle orders unabhängig davon ob sie
     * lowest sind oder nicht einfach jede order die ich im bazaar hab einmal reliste"). Routes through the
     * exact same [createBookSellOrder]/[createBookBuyOrder] `cancelExisting = true, amount = 0` consolidation
     * [handleUndercut]/[handleOutbid] already use for a single relist ("nothing new to add, just keep what's
     * already out there alive"), just looped over every distinct (item, type) pair [scanOrderSlots] currently
     * finds in Manage Orders instead of one flagged item. [cancelAllOrders] (called inside those two
     * functions) already cancels every order for a given item+type in one go, so one call per distinct pair
     * sweeps all of that item's listings even if there happened to be more than one.
     *
     * [viaNpc] is opened once up front via [openBazaar] - every downstream call ([openOrdersScreen],
     * [cancelAllOrders], [createBookSellOrder]/[createBookBuyOrder]) sees a container screen already open and
     * skips its own opening step (`openBazaar` no-ops whenever `mc.screen` is already a container screen), so
     * this is the only place `viaNpc` needs threading through - same trick [PendingBuyCollect.viaNpc] uses,
     * see [openBazaar]'s own doc for why `forceNpc` isn't plumbed any deeper than that.
     *
     * `createBookBuyOrder`'s call below passes `sellClaimedGoods = false` (see that parameter's own doc) - on
     * request, cancelling a Buy Order that had already delivered some goods should NOT also automatically
     * list those goods as a brand new Sell Offer here, unlike [handleUndercut]/[handleOutbid]'s own relists.
     * A plain relist is meant to just refresh the existing order, not start an unrelated second one as a side
     * effect of whatever happened to be claimable at cancel time.
     */
    private suspend fun relistAllOrders(viaNpc: Boolean): RelistResult {
        openBazaar(forceNpc = viaNpc)
        val screen = openOrdersScreen() ?: run {
            modMessage("§cAuto Bazaar Flipper: relist couldn't open Manage Orders.")
            return RelistResult(0, 0, 0)
        }
        val orders = scanOrderSlots(screen)
        if (orders.isEmpty()) return RelistResult(0, 0, 0)

        val distinctOrders = orders.map { it.itemName to it.type }.distinct()
        modMessage("§7Relisting ${distinctOrders.size} item/type pair(s) (${orders.size} listing(s) total)...")

        var relisted = 0
        var failed = 0
        for ((itemName, type) in distinctOrders) {
            val fallback = orders.filter { it.itemName == itemName && it.type == type }
                .sumOf { ((it.total ?: 0) - (it.filled ?: 0)).coerceAtLeast(0) }
            val newAmount = if (type == OrderType.BUY) {
                createBookBuyOrder(itemName, 0, startWatcher = false, cancelExisting = true, fallbackAmount = fallback, claimPendingGoods = true, sellClaimedGoods = false)
            } else {
                createBookSellOrder(itemName, 0, startWatcher = false, cancelExisting = true, fallbackAmount = fallback, claimPendingGoods = true)
            }
            if (newAmount >= 1) {
                relisted++
                devMessage("[BazaarFlipper] relist: relisted $itemName's ${type.name.lowercase()} order(s) (${newAmount}x).")
            } else {
                failed++
                devMessage("§cBazaarFlipper: relist: nothing left of $itemName's ${type.name.lowercase()} order(s) to relist (or the cancel failed) - check Dev Messages above for details.")
            }
            randomDelay(GUI_APPEAR_DELAY)
        }
        return RelistResult(distinctOrders.size, relisted, failed)
    }

    /** `/hxp bz relist` (via `/bz` command) / `/hxp bz relist npc` (via NPC right-click) entry point - see [relistAllOrders]'s own doc for the actual sweep logic. */
    fun startRelistAll(viaNpc: Boolean) {
        if (!tryClaimBusy()) {
            modMessage("§cAuto Bazaar Flipper: already busy, ignoring /hxp bz relist.")
            return
        }
        job = HxPMod.scope.launch {
            try {
                val result = relistAllOrders(viaNpc)
                if (result.pairs == 0) {
                    modMessage("§eAuto Bazaar Flipper: no open Bazaar orders to relist.")
                } else {
                    modMessage("§aAuto Bazaar Flipper: /hxp bz relist done - relisted ${result.relisted} order(s)${if (result.failed > 0) "§c, ${result.failed} failed (check Dev Messages)" else ""}.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HxPMod.logger.error("BazaarFlipper: /hxp bz relist failed", e)
                modMessage("§cAuto Bazaar Flipper §4ran into an error§c during /hxp bz relist, check logs for details.")
            } finally {
                busy = false
            }
        }
    }

    /** `/hxp bz afk`'s AFK-cycle interval (5 minutes, on request). */
    private const val FUSE_AFK_INTERVAL_MS = 5 * 60_000L

    private var fuseAfkJob: Job? = null

    /**
     * `/hxp bz afk` toggle: every [FUSE_AFK_INTERVAL_MS], runs claim -> collect into Hunting Box -> relist
     * all, in that order - on request, one AFK macro combining [claimAllOrdersInManageOrders],
     * [collectShardsIntoHuntingBox] and [relistAllOrders] (each normally a standalone command's own internal
     * logic, called directly here rather than through their public `start*` wrappers - those each claim
     * [busy] and report to chat on their own, which would make every single 5-minute cycle fail its own busy
     * check calling the next step; [runFuseAfkCycle] claims [busy] once for the whole three-step cycle
     * instead). A second `/hxp bz afk` while already running stops the loop, same as [stopFuseAfk] - kept as
     * a convenience alongside the explicit `/hxp bz afk stop` (on request, "welcher alles stoppt") since
     * toggling can be ambiguous if the player isn't sure whether it's currently running.
     */
    fun toggleFuseAfk() {
        if (fuseAfkJob != null) {
            stopFuseAfk()
            return
        }
        modMessage("§a/hxp bz afk started - claim/collect/relist will run every ${FUSE_AFK_INTERVAL_MS / 60_000} minute(s). Run /hxp bz afk again (or /hxp bz afk stop) to stop it.")
        fuseAfkJob = HxPMod.scope.launch {
            while (true) {
                runPeriodicSafely("fuseafk cycle") { runFuseAfkCycle() }
                delay(FUSE_AFK_INTERVAL_MS)
            }
        }
    }

    /**
     * `/hxp bz afk stop` - explicitly stops the AFK loop (idempotent, a no-op message if it wasn't running).
     * Cancelling [fuseAfkJob] also cancels whatever [runFuseAfkCycle] call is currently in flight (same
     * coroutine, not a detached child) at its next suspension point - its own `finally { busy = false }`
     * still runs on the way out (a plain non-suspending assignment always runs during cancellation unwind),
     * so this can't leave [busy] stuck true even if a cycle was mid-claim/collect/relist when stopped.
     */
    fun stopFuseAfk() {
        val running = fuseAfkJob
        if (running == null) {
            modMessage("§eAuto Bazaar Flipper: /hxp bz afk isn't running.")
            return
        }
        running.cancel()
        fuseAfkJob = null
        modMessage("§cAuto Bazaar Flipper: /hxp bz afk stopped.")
    }

    /**
     * `/hxp stop`'s BazaarFlipper half (see [de.hxp.hxpaddons.commands.mainCommand]) - stops every
     * fusing-related thing this module can currently have in flight (/hxp fuse bz, /hxp bz collect, /hxp bz collect npc, /hxp bz huntingbox,
     * /hxp bz relist, /hxp bz relist npc, /hxp bz afk), without touching this module's unrelated always-on behavior
     * (book undercut watching, manual flips, etc. - [onDisable] is the "stop literally everything" version
     * of this, this is scoped to just the fusing toolkit). Returns what was actually stopped, empty if
     * nothing was running, so the caller can report a precise summary instead of a blanket "stopped".
     */
    fun stopAllFusingActions(): List<String> {
        val stopped = mutableListOf<String>()

        if (job?.isActive == true) {
            job?.cancel()
            job = null
            busy = false
            stopped += "in-progress action (/hxp fuse bz, /hxp bz huntingbox, or /hxp bz relist)"
        }
        if (pendingFuse != null) {
            pendingFuse = null
            stopped += "/hxp fuse bz order tracking"
        }
        if (pendingBuyCollect.isNotEmpty()) {
            for (collect in pendingBuyCollect.values) trackedBuyOrders.remove(collect.itemName.lowercase())
            pendingBuyCollect.clear()
            stopped += "/hxp bz collect-/hxp bz collect npc order tracking"
        }
        if (fuseAfkJob != null) {
            fuseAfkJob?.cancel()
            fuseAfkJob = null
            stopped += "/hxp bz afk loop"
        }

        return stopped
    }

    /** One `/hxp bz afk` cycle: claim everything claimable, sweep shards into the Hunting Box, relist every open order - see [toggleFuseAfk]'s own doc for why these are called directly instead of through their public commands. */
    private suspend fun runFuseAfkCycle() {
        if (!tryClaimBusy()) {
            devMessage("[BazaarFlipper] /hxp bz afk: skipped this cycle - module already busy with something else.")
            return
        }
        try {
            modMessage("§7/hxp bz afk: claiming orders...")
            claimAllOrdersInManageOrders()

            modMessage("§7/hxp bz afk: collecting shards into the Hunting Box...")
            val totalStacks = collectShardsIntoHuntingBox()
            devMessage("[BazaarFlipper] /hxp bz afk: deposited $totalStacks shard stack(s).")

            modMessage("§7/hxp bz afk: relisting orders...")
            val result = relistAllOrders(viaNpc = false)

            modMessage(
                "§a/hxp bz afk: cycle done - claimed orders, deposited $totalStacks shard stack(s), relisted ${result.relisted} order(s)" +
                    "${if (result.failed > 0) "§c, ${result.failed} failed" else ""}§a."
            )
        } finally {
            busy = false
        }
    }

    /**
     * [remaining]: total unfilled amount recovered by cancelling. [claimed]: total already-delivered amount
     * claimed off pending orders along the way (see [cancelAllOrders]'s doc) - physically sitting in the
     * inventory now, not just a number.
     *
     * [confirmed]: true only when [cancelAllOrders] actually verified the matching order(s) are genuinely
     * gone now - false for every "gave up without confirming" bail-out (no cancel button found, a click never
     * registered, Manage Orders wouldn't reopen, ...). Reported live: `remaining == 0 && claimed > 0` used to
     * be read by [createBookSellOrder]/[createBookBuyOrder] as "the old order fully filled and is gone, safe
     * to place a fresh one" - but that exact shape also happens when claiming a partial order's pending goods
     * succeeded and the *actual* cancel click then failed/bailed out, leaving the old order still sitting
     * there, uncancelled, unfilled remainder and all. Both callers now also require [confirmed] before
     * treating "nothing left to consolidate" as true, rather than risking a brand new order stacked on top of
     * one that's still open.
     */
    private data class CancelResult(val remaining: Int, val claimed: Int, val confirmed: Boolean)

    /**
     * Cancels every open Buy/Sell order for [itemName] in Manage Orders (the user asked for "all" to be
     * cancelled, not just one, in case more than one ended up listed), returning the total unfilled amount
     * recovered. The cancel button's exact wording is unconfirmed (never exercised live before), so it's
     * tried under a few likely names and heavily dumped like the rest of this module - if none is found,
     * it stops and leaves whatever's left listed rather than guessing further.
     *
     * Confirmed live: Hypixel refuses to cancel an order that still has unclaimed goods sitting on it
     * ("You have goods to claim on this order!") - cancelling one used to just silently loop back onto the
     * exact same still-open order forever. With [claimPendingGoods] on, a slot with pending goods
     * ([itemsToClaimRegex] in its lore) is claimed first via [claimOrderFully] before ever clicking cancel;
     * if that claim can't fully clear it (e.g. no inventory space - see [noSpaceToClaim]) or the rejection
     * happens anyway, this stops and leaves the order listed rather than looping on it. [CancelResult.claimed]
     * tallies up how much that step actually delivered (read from "You have N items to claim!" right before
     * claiming it) so [createBookBuyOrder] can turn around and list it as a Sell Offer instead of leaving it
     * to just sit in the inventory unsold.
     *
     * [claimPendingGoods] defaults to off: claiming mid-cancel only makes sense when the whole point of this
     * cancel is to relist the order fresh anyway (an undercut/outbid renewal - [handleUndercut]/[handleOutbid]
     * are the only callers that pass `true`) - for every other reason this gets called (e.g. the book-combine
     * cycle's normal re-buy/re-sell consolidation), a pending-goods rejection just means the cancel is skipped
     * this round via the existing [goodsToClaimOnCancel] check below, same as before this parameter existed.
     */
    private suspend fun cancelAllOrders(itemName: String, type: OrderType, fallbackAmount: Int, claimPendingGoods: Boolean = false): CancelResult {
        var totalRemaining = 0
        var totalClaimed = 0
        var screen = openOrdersScreen() ?: run {
            devMessage("§cBazaarFlipper: couldn't open Manage Orders while cancelling $itemName's ${type.name.lowercase()} order(s).")
            return CancelResult(totalRemaining, totalClaimed, confirmed = false)
        }
        // Safety cap: this loop re-finds the same slot every iteration, so anything that keeps rejecting
        // the cancel without ever tripping one of the explicit failure checks below (a persistent, unknown
        // server-side refusal) would otherwise spin forever. A player realistically never has more than a
        // handful of orders open for the same single item, so this is generous headroom, not a real limit.
        var iterations = 0
        val maxIterations = 20
        while (iterations++ < maxIterations) {
            dumpScreen(screen, "Manage Orders (cancelling $itemName)")

            // Not finding a matching slot here is a *confirmed* "nothing left" - whether that's true from the
            // very first check (genuinely nothing to cancel) or after looping back from a cancel this same
            // call already confirmed succeeded (totalRemaining += remaining below), either way Manage Orders
            // itself, scanned directly with no navigation involved, is the ground truth for "is it still
            // listed" - unlike every bail-out further down this function, which gives up mid-navigation
            // without ever actually seeing that outcome for itself.
            var slot = findOrderSlotByName(screen, itemName, type) ?: run {
                closeScreen()
                return CancelResult(totalRemaining, totalClaimed, confirmed = true)
            }
            var lore = screen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""

            if (claimPendingGoods && itemsToClaimRegex.containsMatchIn(lore)) {
                // Captured before claiming: whether this order was already 100% filled going into the claim
                // (nothing left to ever fill) - used right below to judge whether the order slot vanishing
                // *after* claiming means "fully consumed, confirmed gone" or "still has an unfilled remainder,
                // something else went wrong" - see that check's own comment.
                val (preClaimFilled, preClaimTotal) = parseOrderFillState(lore, type)
                val wasFullyFilledBeforeClaim = preClaimFilled != null && preClaimTotal != null && preClaimFilled >= preClaimTotal

                val claimAmount = itemsToClaimAmountRegex.find(lore)?.groupValues?.get(1)?.let { parseAbbreviatedCount(it) } ?: 0
                devMessage("[BazaarFlipper] $itemName's order still has goods to claim - claiming those first so the cancel doesn't get rejected.")
                claimOrderFully(slot, itemName)
                if (noSpaceToClaim) {
                    devMessage("[BazaarFlipper] Leaving $itemName's order listed for now - couldn't clear its pending goods due to inventory space, so skipping the cancel this round instead of retrying.")
                    closeScreen()
                    return CancelResult(totalRemaining, totalClaimed, confirmed = false)
                }
                totalClaimed += claimAmount
                randomDelay(GUI_APPEAR_DELAY)
                screen = openOrdersScreen() ?: run {
                    devMessage("§cBazaarFlipper: couldn't reopen Manage Orders after claiming $itemName's pending goods.")
                    return CancelResult(totalRemaining, totalClaimed, confirmed = false)
                }
                dumpScreen(screen, "Manage Orders (cancelling $itemName, after claiming pending goods)")
                slot = findOrderSlotByName(screen, itemName, type) ?: run {
                    closeScreen()
                    // Reported live: this used to always mean "gone, safe to treat as fully consolidated" -
                    // wrong whenever the claimed goods were only a partial fill's already-delivered slice,
                    // with a genuine unfilled remainder still open right after (claiming a filled portion
                    // does NOT remove the rest of the order) - some other glitch losing that still-open
                    // order's slot got silently read as "it's done", and a caller then placed a brand new
                    // order on top of the one still actually sitting there uncancelled. Only trust "gone" here
                    // if the order was already 100% filled *before* this claim - the one case where Hypixel
                    // completing the claim genuinely does remove the order, nothing left to ever fill.
                    return CancelResult(totalRemaining, totalClaimed, confirmed = wasFullyFilledBeforeClaim)
                }
                lore = screen.menu.items.getOrNull(slot)?.loreString?.joinToString(" ") { it.noControlCodes } ?: ""
            }

            val (filled, total) = parseOrderFillState(lore, type)
            val remaining = if (total != null && filled != null) (total - filled).coerceAtLeast(0) else fallbackAmount

            goodsToClaimOnCancel = false

            // Confirmed live this used to pick the click button from [type] alone (right-click for any BUY
            // order, left-click for any SELL order) - wrong for an *unfilled* Buy Order, whose own lore says
            // "Click to view options!" (plain left-click), same as a Sell Offer. Only a *filled-but-not-yet-
            // claimed* Buy Order's lore actually says "Right-Click for options!" (left-click there instead
            // claims it, per claimOrderFully/flipOrder elsewhere) - a static per-type rule right-clicked an
            // unfilled Buy Order that wanted a left-click, which either no-ops or lands on the wrong thing
            // depending on what's rendered underneath, not the "Order options" screen the rest of this loop
            // expects. Reading the actual wording out of this order's own lore instead of guessing from
            // [type] covers both states correctly.
            val needsRightClick = lore.contains("right-click for options", ignoreCase = true)
            // On request: a beat before this first click - previously fired the instant the slot was found,
            // right off whatever Manage Orders scan/claim step happened to precede it, with no settle time.
            randomDelay(CANCEL_FIRST_CLICK_DELAY)
            // Confirmed live: the very first click on the order itself can just not register (a dropped
            // click, same class of issue [openOrdersScreen]'s own "Manage Orders" click and [flipOrder]'s
            // right-click-to-claim already retry) - this used to poll once and give up outright if no
            // cancel-shaped screen showed up, silently leaving the order listed with no error surfaced beyond
            // a dev-only log line. Re-clicks the same slot (still on the same Manage Orders screen - nothing
            // else about the click failing would have navigated anywhere) up to twice more before giving up.
            var optionsScreen: AbstractContainerScreen<*>? = null
            var clickAttempts = 0
            while (optionsScreen == null && clickAttempts < 3) {
                clickAttempts++
                click(slot, if (needsRightClick) 1 else 0)
                // Confirmed live: reading mc.screen straight off a flat delay here (rather than polling like
                // the rest of this module does at every other transition) hit the exact same title-before-
                // content race documented elsewhere (see waitForScreen's own doc) - "Order options" reporting
                // as open before its own slots had actually repopulated, so findSlot below could still match
                // a leftover from the *previous* screen sitting in that index rather than the real Cancel
                // button a few slots off. Polls specifically for a cancel-shaped button instead of trusting a
                // fixed wait.
                optionsScreen = waitForScreen(CANCEL_CONFIRM_DELAY) {
                    it.findSlot("cancel order", "cancel sell offer", "cancel buy order", "cancel") != null
                }
                if (goodsToClaimOnCancel) {
                    devMessage("§cBazaarFlipper: $itemName's order still has goods to claim - stopping instead of retrying the cancel.")
                    closeScreen()
                    return CancelResult(totalRemaining, totalClaimed, confirmed = false)
                }
                if (optionsScreen == null && clickAttempts < 3) {
                    devMessage("[BazaarFlipper] Click on $itemName's order to open cancel options didn't register (attempt $clickAttempts) - retrying.")
                    randomDelay(GUI_APPEAR_DELAY)
                }
            }
            if (optionsScreen == null) {
                devMessage("§cBazaarFlipper: no options screen (with a cancel button) appeared after clicking $itemName's order to cancel it (gave up after $clickAttempts attempt(s)).")
                return CancelResult(totalRemaining, totalClaimed, confirmed = false)
            }
            dumpScreen(optionsScreen, "Order options ($itemName) - cancelling")

            val cancelSlot = optionsScreen.findSlot("cancel order") ?: optionsScreen.findSlot("cancel sell offer")
                ?: optionsScreen.findSlot("cancel buy order") ?: optionsScreen.findSlot("cancel")
            if (cancelSlot == null) {
                devMessage("§cBazaarFlipper: no cancel button found for $itemName's order - leaving it listed.")
                closeScreen()
                return CancelResult(totalRemaining, totalClaimed, confirmed = false)
            }
            click(cancelSlot)
            // Confirmed live: the first click alone doesn't cancel it - Hypixel needs a second click to
            // confirm. Same polling as above (not a flat delay + snapshot) before re-searching for the
            // button, in case confirming re-labels it (e.g. to "Click again to confirm!") or moves it to a
            // new slot once the screen actually settles. A timeout here (no confirm-shaped button ever
            // showed up) is treated as "no confirm step needed" rather than an error, same as the old
            // null-screen fallback did.
            val confirmScreen = waitForScreen(CANCEL_CONFIRM_DELAY) {
                it.findSlot("cancel order", "cancel sell offer", "cancel buy order", "confirm", "cancel") != null
            }
            if (goodsToClaimOnCancel) {
                devMessage("§cBazaarFlipper: $itemName's order still has goods to claim - stopping instead of retrying the cancel.")
                closeScreen()
                return CancelResult(totalRemaining, totalClaimed, confirmed = false)
            }
            if (confirmScreen != null) {
                dumpScreen(confirmScreen, "Order options ($itemName) - confirming cancel")
                val confirmSlot = confirmScreen.findSlot("cancel order") ?: confirmScreen.findSlot("cancel sell offer")
                    ?: confirmScreen.findSlot("cancel buy order") ?: confirmScreen.findSlot("confirm") ?: confirmScreen.findSlot("cancel")
                click(confirmSlot ?: cancelSlot)
                randomDelay(CANCEL_CONFIRM_DELAY)
                if (goodsToClaimOnCancel) {
                    devMessage("§cBazaarFlipper: $itemName's order still has goods to claim - stopping instead of retrying the cancel.")
                    closeScreen()
                    return CancelResult(totalRemaining, totalClaimed, confirmed = false)
                }
            }
            totalRemaining += remaining

            // Confirmed live: confirming a cancel lands straight back in Manage Orders - no need to close
            // and re-navigate (`/bz` -> Manage Orders) from scratch for the next iteration. Only falls back
            // to a full re-open if that assumption turns out wrong (some other screen, or none at all).
            // Polled (not a flat-delay snapshot) for the same title-before-content reason as above.
            val afterCancel = waitForScreen(CANCEL_CONFIRM_DELAY) { true }
            if (afterCancel != null && afterCancel.title.string.noControlCodes.contains("order", true)) {
                dumpScreen(afterCancel, "After cancelling order ($itemName) - back in Manage Orders")
                screen = afterCancel
                continue
            }

            // The cancel+confirm sequence above already went through cleanly (no goodsToClaimOnCancel
            // rejection at any step) before reaching here - this specific order's cancel is confirmed even
            // though Manage Orders itself won't reopen to keep checking for any others still listed.
            devMessage("§cBazaarFlipper: didn't land back in Manage Orders after confirming the cancel for $itemName (got ${afterCancel?.title?.string ?: "no screen"}) - reopening it.")
            closeScreen()
            randomDelay(GUI_APPEAR_DELAY)
            screen = openOrdersScreen() ?: return CancelResult(totalRemaining, totalClaimed, confirmed = true)
        }
        // Hit the iteration cap while findOrderSlotByName kept finding another matching order each time (the
        // only way this loop keeps going) - not confirmed, there's still at least one order this call knows
        // about but never got to.
        devMessage("§cBazaarFlipper: cancelling $itemName's ${type.name.lowercase()} order(s) hit the $maxIterations-iteration safety cap - giving up for this call, whatever's left stays listed.")
        closeScreen()
        return CancelResult(totalRemaining, totalClaimed, confirmed = false)
    }

    private fun findOrderSlotByName(screen: AbstractContainerScreen<*>, itemName: String, type: OrderType): Int? {
        val top = screen.topSlotCount()
        val prefix = if (type == OrderType.BUY) "buy" else "sell"
        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.noControlCodes.trim()
            if (!name.startsWith(prefix, ignoreCase = true)) continue
            if (!containsBookLevel(name, itemName)) continue
            return i
        }
        return null
    }

    /**
     * Submits text into whatever input Hypixel opened after a "type a value" button - a real sign-edit
     * GUI is the confirmed mechanism (see "Flip Order"'s price prompt), so that's tried first; falls back
     * to a plain chat message (the original, untested guess for "Custom Amount") if the screen closed to
     * nothing instead. Returns false if neither shape matched, so the caller can log and bail out.
     */
    private fun submitTextInput(text: String): Boolean {
        val signScreen = mc.screen as? AbstractSignEditScreen
        if (signScreen != null) {
            submitSignText(signScreen, text)
            return true
        }
        if (mc.screen == null) {
            sendChatMessage(text)
            return true
        }
        return false
    }

    /**
     * The final confirm button - tries the obvious "Place Order"/"Confirm" names first, then falls back
     * to just "Buy Order"/"Sell Offer" + the item name together (a guess for what it might actually be
     * called instead, unconfirmed without a live test).
     */
    private fun findPlaceOrderSlot(screen: AbstractContainerScreen<*>?, wantType: OrderType, itemName: String): Int? {
        if (screen == null) return null
        val typeKeyword = if (wantType == OrderType.SELL) "sell offer" else "buy order"
        // Confirmed live: the confirm button on the price screen is just named e.g. "Sell Offer" (no
        // "place"/item name attached) - the "place ..." guesses and the combo fallback stay as belt-and-braces.
        return screen.findSlot("place order")
            ?: screen.findSlot("place buy order")
            ?: screen.findSlot("place sell offer")
            ?: screen.findSlot("confirm")
            ?: screen.findSlot(typeKeyword)
            ?: screen.findSlot(typeKeyword, itemName.lowercase(), requireAll = true)
    }

    /**
     * Search results can list several items whose name merely contains [itemName] (e.g. searching
     * "Rose" also turning up "Wild Rose", "Rose Gold", ...) without the actual match sitting first -
     * picks the slot whose own display name is closest to [itemName] (exact match always wins outright,
     * otherwise the smallest edit distance) instead of just the first substring hit.
     */
    private fun findBestItemMatch(screen: AbstractContainerScreen<*>, itemName: String): Int? {
        val top = screen.topSlotCount()
        val target = itemName.lowercase()
        var bestSlot = -1
        var bestScore = Int.MAX_VALUE

        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val candidate = stack.hoverName.string.noControlCodes.trim().lowercase()
            if (!candidate.contains(target)) continue

            val score = if (candidate == target) 0 else levenshtein(candidate, target)
            if (score < bestScore) {
                bestScore = score
                bestSlot = i
            }
        }
        return bestSlot.takeIf { it != -1 }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }

    private fun submitSignText(signScreen: AbstractSignEditScreen, text: String) {
        val sign = (signScreen as AbstractSignEditScreenAccessor).sign
        val originalLines = Array(4) { i -> sign.frontText.getMessage(i, false).string }
        devMessage("[BazaarFlipper] Sign lines before edit: ${originalLines.joinToString(" | ")}")
        mc.execute {
            mc.player?.connection?.send(ServerboundSignUpdatePacket(sign.blockPos, true, text, originalLines[1], originalLines[2], originalLines[3]))
            mc.setScreen(null)
        }
    }

    /**
     * Matches by item name + order type; prefers a slot whose lore actually says "click to claim" (the
     * confirmed text for a filled order) and whose fill total matches [order]'s amount, falling back to
     * looser matches so a wording mismatch doesn't stall the whole flip.
     */
    /**
     * Matches by item name + order type, and only ever considers a slot whose lore says "click to claim"
     * (confirmed text for a fully filled order) - if there are multiple orders for the same item (e.g. two
     * separate Buy Orders), a still-partial one is never touched, even as a last resort. Among claimable
     * matches, prefers the one whose fill total matches [order]'s amount so a wording mismatch doesn't
     * stall the whole flip; falls back to any claimable match otherwise.
     */
    private fun findOrderSlot(screen: AbstractContainerScreen<*>, order: ClaimedOrder): Int? {
        val top = screen.topSlotCount()
        // The order type shows up as a "BUY "/"SELL " prefix on the item's own display name (e.g.
        // "BUY Wild Rose"), not as a "Buy Order"/"Sell Offer" phrase anywhere in the lore.
        val namePrefix = if (order.type == OrderType.BUY) "buy" else "sell"
        var claimableFallback = -1

        // order.itemName comes from the "was filled!" chat message (filledOrderRegex), which - confirmed
        // live, see findPendingBuyCollect's own doc - Hypixel sometimes pluralizes for a multi-unit fill
        // ("Queen Bee Shards"), while the Manage Orders slot's own hoverName always shows the item's plain
        // singular name ("BUY Queen Bee Shard"). A plain .contains(order.itemName) would then never match at
        // all (the GUI's shorter singular text can't contain the longer plural search term) - also tries the
        // name with one trailing "s" stripped, same fallback strategy findPendingBuyCollect uses.
        val itemNameSingular = if (order.itemName.endsWith("s")) order.itemName.dropLast(1) else null

        for (i in 0 until top) {
            val stack = screen.menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val name = stack.hoverName.string.noControlCodes
            val matchesItemName = name.contains(order.itemName, ignoreCase = true) ||
                (itemNameSingular != null && name.contains(itemNameSingular, ignoreCase = true))
            if (!matchesItemName) continue
            if (!name.trim().startsWith(namePrefix, ignoreCase = true)) continue

            val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
            val (filledAmount, total) = parseOrderFillState(lore, order.type)
            // Confirmed live: this used to require "click to claim" specifically - broke the moment
            // claimAllReadySellOrders (runs on every openOrdersScreen visit, see its own doc) already claimed
            // this exact order's pending coins earlier in the very same cycle (e.g. discoverUntrackedOrders'
            // own scan opening Manage Orders once to detect the fill, then flipOrder opening it again right
            // after to act on it) - "click to claim" was gone by the second visit even though the order was
            // still sitting there 100% filled, so this never matched it again and the order was stuck forever
            // (re-detected as filled every scan, never actually flipped/cancelled/replaced). "Filled: X/Y"
            // with X>=Y is the same direct signal used elsewhere in this module and doesn't depend on whether
            // there happen to be unclaimed coins left at the exact moment this runs. Trusted exclusively
            // whenever it parses - "click to claim" alone (fallback only for when it doesn't) can also show
            // up for a barely-started order that's nowhere near actually filled, once any small slice of it
            // has delivered.
            val isFullyFilled = if (filledAmount != null && total != null) {
                filledAmount >= total
            } else {
                lore.contains("click to claim", ignoreCase = true)
            }
            if (!isFullyFilled) continue // not fully filled yet - never claim this one

            if (claimableFallback == -1) claimableFallback = i
            if (total == order.amount) return i
        }
        return claimableFallback.takeIf { it != -1 }
    }

    /**
     * Opens the Bazaar according to [bazaarOpenMethod] - either the `/bz` command (default), or by
     * right-clicking a nearby NPC named [BAZAAR_NPC_NAME] (see [interactWithBazaarNpc]), ported from how
     * the bundled `reference/quoi` project's "Three Weirdos" puzzle solver interacts with NPCs
     * (`ServerboundInteractPacket`, sent twice to mirror vanilla's own double-send). Confirmed live: this
     * must NOT silently fall back to `/bz` when no NPC is nearby - that defeats the point of picking NPC
     * Interaction in the first place. If no NPC is found/reachable, nothing is sent at all; the caller's
     * own `waitForScreen(...)` timeout after this handles it the same way any other failure does.
     *
     * Skips sending anything at all if a container screen is already open - suspected live (the "Loading..."
     * placeholders and shifting slot counts seen elsewhere in this module line up with it): one `/hxp bz flip` used
     * to tear down and rebuild a *fresh* Bazaar session up to three separate times in a row
     * ([readMarketTopPrice], then [openOrdersScreen] for the portfolio check, then [startFlip]'s own
     * placement), each a full close-and-reopen round trip to Hypixel rather than just navigating within one
     * still-open session the way Hypixel's own "Go Back" buttons are clearly meant to be used. Deliberately
     * not checking the title for "bazaar" specifically - confirmed live that not every screen this module
     * ends up on within the Bazaar's own navigation tree has it (e.g. an item's own detail page is titled
     * just "Essence ➜ Crimson Essence", nothing about "Bazaar" in it at all) - any open container screen is
     * good enough, since this module never has a legitimate reason to call this while sitting in some
     * unrelated screen anyway. Bazaar navigation should stay inside a single session wherever the flow allows
     * it instead of restarting one from scratch at every step.
     */
    /** [forceNpc]: used by `/hxp bz collect npc` (see [PendingBuyCollect.viaNpc]) to always go through [interactWithBazaarNpc] for this call, regardless of the "Open Bazaar Via" setting - independent of [bazaarOpenMethod] entirely. */
    private fun openBazaar(forceNpc: Boolean = false) {
        if (mc.screen is AbstractContainerScreen<*>) return
        // Reported live: passing forceNpc only at the very first placement wasn't enough - every later step
        // for the SAME /hxp bz collect npc batch (outbid re-lists, claim sweeps, openOrdersScreen/ensureOrderExists/
        // cancelAllOrders' own internal retries) calls this plain, unparameterized openBazaar() deep inside
        // shared machinery that has no way to thread a per-item viaNpc flag through. Rather than threading
        // forceNpc through a dozen shared functions also used by every other flip in this module, this checks
        // pendingBuyCollect directly (same class, already has it) - as long as ANY /hxp bz collect npc-started batch is
        // still tracked, EVERY openBazaar() call anywhere goes through the NPC regardless of caller. Trade-off
        // (accepted, matches the fact [busy] already serializes all Bazaar GUI use to one operation at a time
        // module-wide): an unrelated /hxp bz flip flip started while an /hxp bz collect npc batch is still active would also open
        // via NPC for that call.
        val useNpc = forceNpc || bazaarOpenMethod == BAZAAR_OPEN_VIA_NPC || pendingBuyCollect.values.any { it.viaNpc }
        if (useNpc) {
            if (!interactWithBazaarNpc()) devMessage("§cBazaarFlipper: ${if (forceNpc) "/hxp bz collect npc" else "NPC opening is active"} but no Bazaar NPC was reachable - not falling back to /bz.")
            return
        }
        sendCommand("bz")
    }

    /**
     * Right-clicks the nearest entity within [BAZAAR_NPC_SEARCH_RADIUS] whose name contains
     * [BAZAAR_NPC_NAME], provided it's within [BAZAAR_NPC_INTERACT_RANGE]. Returns whether a packet was
     * actually sent. Unconfirmed live: this Minecraft version's `ServerboundInteractPacket` shape differs
     * from the one `reference/quoi` targets (no more separate INTERACT/INTERACT_AT/ATTACK action - just a
     * single `(entityId, hand, location, usingSecondaryAction)` record), so whether `location` wants a
     * relative-to-entity offset (quoi's old convention, used here) or an absolute world position is a
     * best-effort guess pending a live test.
     */
    private fun interactWithBazaarNpc(): Boolean {
        val player = mc.player ?: return false
        val level = mc.level ?: return false

        val npc = level.entitiesForRendering()
            .filter { it.customName?.string?.noControlCodes?.contains(BAZAAR_NPC_NAME, ignoreCase = true) == true }
            .filter { it.distanceToSqr(player) <= BAZAAR_NPC_SEARCH_RADIUS * BAZAAR_NPC_SEARCH_RADIUS }
            .minByOrNull { it.distanceToSqr(player) }
        if (npc == null) {
            devMessage("§cBazaarFlipper: no nearby NPC named '$BAZAAR_NPC_NAME' found within $BAZAAR_NPC_SEARCH_RADIUS blocks.")
            return false
        }
        val distance = player.distanceTo(npc)
        if (distance > BAZAAR_NPC_INTERACT_RANGE) {
            devMessage("§cBazaarFlipper: found '$BAZAAR_NPC_NAME' NPC but it's ${"%.1f".format(distance)} blocks away (max $BAZAAR_NPC_INTERACT_RANGE).")
            return false
        }

        devMessage("[BazaarFlipper] Interacting with Bazaar NPC '${npc.customName?.string}' (id ${npc.id}, ${"%.1f".format(distance)} blocks away).")
        val relativeHit = npc.boundingBox.center.subtract(npc.position())
        mc.execute {
            val connection = mc.player?.connection ?: return@execute
            // Sent twice - mirrors vanilla's own double interact-at send (and reference/quoi's identical pattern).
            repeat(2) { connection.send(ServerboundInteractPacket(npc.id, InteractionHand.MAIN_HAND, relativeHit, player.isShiftKeyDown)) }
        }
        return true
    }

    /**
     * Also claims every ready Sell Offer's proceeds ([claimAllReadySellOrders]) unconditionally, every single
     * time this opens Manage Orders for whatever reason - a price check, a portfolio scan, an undercut-watch
     * verification, whatever. This used to be opt-in (only the book-combine flow claimed), then got
     * restricted further to book items only - confirmed live neither was actually what was wanted: proceeds
     * from *any* item's Sell Offer should get swept up the moment this is already sitting in Manage Orders
     * anyway, filled or still partially open (Hypixel marks a Sell Offer "click to claim" the moment there's
     * *any* unclaimed proceeds pending, not just once it's 100% sold - see [claimAllReadySellOrders]'s own
     * doc), rather than waiting for a full order specifically.
     */
    private suspend fun openOrdersScreen(retryAfterClaim: Boolean = true): AbstractContainerScreen<*>? {
        openBazaar()
        // See openBazaar's own doc - not requiring "bazaar" in the title, it may have been a no-op (already
        // sitting in some other container screen, e.g. wherever a preceding readMarketTopPrice call left off)
        // and the "Go Back" loop below finds "Manage Orders" regardless of where this lands.
        var mainScreen = waitForScreen { true } ?: run {
            devMessage("§cBazaarFlipper: Bazaar menu did not open in time.")
            return null
        }
        randomDelay(GUI_APPEAR_DELAY)

        // Confirmed live: /bz can reopen wherever the player last left off in the Bazaar GUI (e.g. "Bazaar ➜
        // Oddities", a sub-category, or even an item's own page) instead of resetting to the true root menu.
        // Backs out via "Go Back" until the real, unscoped "Manage Orders" is actually findable (see
        // [findGlobalManageOrders] - both an item page and a category page have their own scoped decoys),
        // same pattern [placeOrderViaSearch] already uses for "Search".
        var backAttempts = 0
        while (mainScreen.findGlobalManageOrders() == null && backAttempts++ < 5) {
            val backSlot = mainScreen.findSlot("go back") ?: break
            click(backSlot)
            mainScreen = waitForScreen(3000) { true } ?: run {
                devMessage("§cBazaarFlipper: lost the Bazaar screen while backing out to find 'Manage Orders'.")
                return null
            }
        }
        dumpScreen(mainScreen, "Bazaar main (finding Manage Orders)")

        // Confirmed live: a "Manage Orders" click occasionally silently no-ops - [click]'s own bounds-check
        // logs "current menu only has N slots" and returns without clicking anything when the slot index
        // found a moment earlier no longer exists in the live menu (its total slot count had changed by click
        // time) - the caller has no way to tell that happened short of the following wait timing out. Re-scans
        // and re-clicks fresh up to twice more instead of trusting a single attempt.
        val mainTitle = mainScreen.title.string.noControlCodes
        var ordersScreen: AbstractContainerScreen<*>? = null
        var manageAttempts = 0
        while (ordersScreen == null && manageAttempts < 3) {
            manageAttempts++
            val manageSlot = mainScreen.findGlobalManageOrders() ?: run {
                devMessage("§cBazaarFlipper: could not find 'Manage Orders' button (still on '$mainTitle' after backing out).")
                return null
            }
            click(manageSlot)
            ordersScreen = waitForScreen(3000) { it.title.string.noControlCodes != mainTitle }
            if (ordersScreen == null && manageAttempts < 3) {
                devMessage("[BazaarFlipper] 'Manage Orders' click (attempt $manageAttempts) didn't open a new screen - re-scanning and retrying.")
                mainScreen = waitForScreen(3000) { true } ?: run {
                    devMessage("§cBazaarFlipper: lost the Bazaar screen while retrying 'Manage Orders'.")
                    return null
                }
            }
        }
        if (ordersScreen == null) {
            devMessage("§cBazaarFlipper: orders screen did not open in time after $manageAttempts attempt(s).")
            return null
        }
        val ordersTitle = ordersScreen.title.string.noControlCodes
        // On request: detected via [isManageOrdersScreen] (the "Claim All Coins" button) instead of the
        // title text - confirmed live the title itself already varies ("Co-op Bazaar Orders" on a co-op
        // profile is apparently just what the real, correct, unscoped screen is called there), so title
        // matching alone was never fully reliable to begin with. What actually indicates a wrong screen was
        // clicking a scoped decoy in the first place - already prevented at the source by
        // [findGlobalManageOrders].
        if (!ordersScreen.isManageOrdersScreen()) {
            devMessage("§cBazaarFlipper: unexpected screen after 'Manage Orders' (no 'Claim All Coins' button - title was '$ordersTitle').")
            closeScreen()
            return null
        }
        randomDelay(GUI_APPEAR_DELAY)
        claimAllReadySellOrders(ordersScreen)

        // Confirmed live (see claimAllReadySellOrders' own doc): a "click to claim" click doesn't always
        // claim in place - it can pop a completely different screen instead ("Order options"). Every caller
        // of openOrdersScreen trusts its return value as "the currently open Manage Orders screen" and clicks
        // slot indices straight off it - handing back the pre-claim `ordersScreen` reference regardless used
        // to silently do exactly that whenever claiming had actually left something else open, the same
        // stale-screen click-the-wrong-thing failure mode already hunted down elsewhere in this file (see
        // claimAllReadySellOrders/cancelAllOrders/flipOrder's own matching fixes), just never closed off here
        // at this function's own exit point. Re-reads whatever's actually live now instead of assuming
        // `ordersScreen` still describes it; if claiming left something unexpected open, closes it and makes
        // one bounded retry (`retryAfterClaim` false on that retry, so this can't recurse forever) rather than
        // hand back or scan a screen that might not even still be open.
        val liveAfterClaim = mc.screen as? AbstractContainerScreen<*>
        val finalOrdersScreen = if (liveAfterClaim != null && liveAfterClaim.title.string.noControlCodes.contains("order", true)) {
            liveAfterClaim
        } else if (retryAfterClaim) {
            devMessage(
                "[BazaarFlipper] Manage Orders screen changed unexpectedly while claiming ready Sell Offers " +
                    "(now '${liveAfterClaim?.title?.string ?: "no screen"}') - closing and reopening it fresh."
            )
            closeScreen()
            randomDelay(GUI_APPEAR_DELAY)
            return openOrdersScreen(retryAfterClaim = false)
        } else {
            devMessage(
                "§cBazaarFlipper: Manage Orders screen still not what's expected after reopening post-claim " +
                    "(got '${liveAfterClaim?.title?.string ?: "no screen"}') - giving up for this call."
            )
            return null
        }

        // Feeds orderStatusHud's persistent cache directly (same reasoning as readMarketTopPrices' matching
        // comment) - every single Manage Orders visit this module's own background flows already make
        // (claiming, cancelling, checking for outbid/undercut, ...) refreshes the HUD's summary, so it keeps
        // updating continuously without depending on the HUD's own per-frame screen read happening to land
        // while this screen is open.
        val slots = scanOrderSlots(finalOrdersScreen)
        if (slots.isNotEmpty()) lastKnownOrderSlots = slots
        return finalOrdersScreen
    }

    /**
     * Claims every ready Sell Offer's proceeds in [screen] (any "SELL ..." slot whose lore says "click to
     * claim" - the coins from a completed sale) before handing control back. Confirmed live this wasn't
     * happening reliably: nothing in this module claimed Sell Offer coins at all before, only Buy Order
     * items - a completed sale's coins could just sit there indefinitely. Hooked directly into
     * [openOrdersScreen] itself so every single caller benefits automatically instead of needing to
     * remember to check.
     *
     * Confirmed live: clicking a "click to claim" slot doesn't always claim in place - it can occasionally
     * open a completely different screen instead ("Order options", same as right-clicking a filled Buy
     * Order). Every loop iteration used to keep re-scanning the *original* [screen] parameter regardless -
     * once the live menu had actually moved on to something else, that scan was reading frozen/stale data
     * while [click] sends its next click against whatever's *actually* open now
     * ([mc.player]`.containerMenu`), so a slot index that meant "this Sell Offer" in the stale scan could
     * land on an unrelated button a few slots off in the real, current screen - exactly the "clicks a few
     * slots off" behavior seen live during a cancel cycle (this runs on every single Manage Orders visit,
     * including the several [cancelAllOrders] makes per cancel). Now re-scans whatever's actually live after
     * each click, and bails out the moment the title changes to anything other than this same screen, instead
     * of ever clicking against a snapshot that might not describe the current screen anymore.
     */
    private suspend fun claimAllReadySellOrders(screen: AbstractContainerScreen<*>) {
        var currentScreen = screen
        val expectedTitle = screen.title.string.noControlCodes
        while (true) {
            val top = currentScreen.topSlotCount()
            var slot: Int? = null
            for (i in 0 until top) {
                val stack = currentScreen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                val name = stack.hoverName.string.noControlCodes.trim()
                if (!name.startsWith("sell", ignoreCase = true)) continue
                val lore = stack.loreString.joinToString(" ") { it.noControlCodes }
                if (!lore.contains("click to claim", ignoreCase = true)) continue
                slot = i
                break
            }
            if (slot == null) return

            devMessage("[BazaarFlipper] Claiming ready Sell Offer proceeds at slot #$slot.")
            click(slot, 0)
            randomDelay(GUI_APPEAR_DELAY)
            val nextScreen = mc.screen as? AbstractContainerScreen<*> ?: return
            dumpScreen(nextScreen, "After claiming sell offer proceeds")
            val nextTitle = nextScreen.title.string.noControlCodes
            if (nextTitle != expectedTitle) {
                devMessage("[BazaarFlipper] Screen changed unexpectedly while claiming Sell Offer proceeds (now '$nextTitle') - stopping here instead of clicking against a stale scan.")
                return
            }
            currentScreen = nextScreen
        }
    }

    /**
     * Confirmed live: [mc.execute] only *queues* the close for the render thread rather than performing it
     * there and then - a caller that immediately turns around and checks [mc.screen] right after calling
     * this (e.g. [openBazaar]'s "already sitting in a container screen, skip sending /bz" no-op check) can
     * still see the screen that's merely about to close, silently swallowing the /bz and leaving nothing
     * open at all (seen live: [readBazaarPortfolio] closing Manage Orders straight into [startFlip]'s own
     * [openBazaar] call, which then waited 5s for a screen that was never going to appear). So this briefly
     * polls (bounded, same shape as [waitForScreen]'s own polling) until [mc.screen] actually goes null
     * before returning, instead of trusting the queue to have drained by the time the caller looks again.
     */
    private suspend fun closeScreen() {
        if (mc.screen == null) return
        mc.execute { mc.setScreen(null) }
        var waited = 0L
        while (mc.screen != null && waited < 500) {
            delay(20)
            waited += 20
        }
    }

    /**
     * Logs its own timing/outcome (dev-only, via [devMessage]) on top of the actual poll - not just whether
     * it succeeded, but how long a successful wait actually took (to see the real latency distribution these
     * screen transitions need live) and, on a timeout, exactly what screen (if any) was sitting there
     * instead (null - genuinely nothing; the wrong screen/title entirely - the preceding click likely didn't
     * do what was expected; or a container screen that just never matched [predicate]) - the three look the
     * same as a bare "timed out" from the caller's side but point at very different root causes.
     */
    private suspend fun waitForScreen(timeoutMs: Long = 5000, predicate: (AbstractContainerScreen<*>) -> Boolean): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            (mc.screen as? AbstractContainerScreen<*>)?.let {
                if (predicate(it)) {
                    // Confirmed live: the instant a screen/slot first satisfies predicate is not necessarily
                    // stable - its title can arrive before its actual slot contents finish populating in a
                    // follow-up packet, and polling every 50ms could catch that exact half-loaded frame. Debounce:
                    // wait a beat (400ms - the same floor every click elsewhere in this module keeps since a GUI
                    // last appeared, so a match coming out of this function is never acted on any sooner than
                    // that) and re-check the (possibly since-updated) screen still matches before trusting it,
                    // rather than acting on what might just be a mid-update snapshot. If it stopped matching (the
                    // screen moved on again in that gap), fall through and keep polling instead of returning
                    // something already stale.
                    delay(400)
                    val settled = mc.screen as? AbstractContainerScreen<*>
                    if (settled != null && predicate(settled)) {
                        val elapsed = System.currentTimeMillis() - start
                        if (elapsed > 200) {
                            devMessage("[BazaarFlipper] waitForScreen: matched after ${elapsed}ms (title='${settled.title.string.noControlCodes}').")
                        }
                        return settled
                    }
                }
            }
            delay(50)
        }
        val finalScreen = mc.screen
        val describedScreen = when {
            finalScreen == null -> "none"
            finalScreen is AbstractContainerScreen<*> -> "${finalScreen::class.simpleName} (title='${finalScreen.title.string.noControlCodes}', didn't match predicate)"
            else -> finalScreen::class.simpleName ?: "unknown"
        }
        devMessage("[BazaarFlipper] waitForScreen: timed out after ${timeoutMs}ms - screen at timeout: $describedScreen.")
        return null
    }

    private fun AbstractContainerScreen<*>.topSlotCount(): Int =
        (menu.items.size - 36).coerceAtLeast(0)

    /**
     * Concatenation of every top-slot's name+count+lore - same technique [Fuser]'s `contentSignature` uses
     * to tell a genuinely-updated screen apart from a stale one that merely still satisfies some predicate
     * (Hypixel resends fresh container contents after every click, even when the *next* target happened to
     * already be visible pre-click - matching on content, not just title/`Screen` identity, since these
     * custom GUIs often update in place rather than opening a new `Screen`).
     */
    private fun AbstractContainerScreen<*>.contentSignature(): String =
        (0 until topSlotCount()).joinToString("|") { i ->
            val stack = menu.items.getOrNull(i)
            if (stack == null || stack.isEmpty) "" else "${stack.hoverName.string}:${stack.count}:${stack.loreString.joinToString(";") { l -> l.noControlCodes }}"
        }

    /**
     * Polls until the currently open screen's [contentSignature] differs from [previousSignature] (a genuine
     * update, not the same pre-click content still sitting there) AND matches [predicate], then settles
     * [settleMs] and re-verifies both before returning - mirrors [Fuser]'s `waitForGuiUpdate`/
     * `GUI_UPDATE_SETTLE_MS`. Used wherever a click is expected to change a slot in place within the *same*
     * screen (e.g. [claimOrderFully]'s repeated same-slot clicks) rather than open a whole new one, where
     * [waitForScreen]'s own predicate-only matching could return instantly on the stale pre-click state.
     */
    private suspend fun waitForGuiUpdate(
        previousSignature: String,
        settleMs: Long = 200L,
        timeoutMs: Long = 5000L,
        predicate: (AbstractContainerScreen<*>) -> Boolean = { true },
    ): AbstractContainerScreen<*>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val screen = mc.screen as? AbstractContainerScreen<*>
            if (screen != null && screen.contentSignature() != previousSignature && predicate(screen)) {
                delay(settleMs)
                val settled = mc.screen as? AbstractContainerScreen<*>
                if (settled != null && settled.contentSignature() != previousSignature && predicate(settled)) return settled
            }
            delay(50)
        }
        return null
    }

    /**
     * Searches the player-inventory portion of the currently open menu (everything after [topSlotCount]).
     * Confirmed live for the `/av` anvil too: its full dump ran 90 slots total (54 GUI + the standard 36
     * player slots appended after), so the same formula the rest of this module already relies on holds
     * here as well - no need to search the GUI's own area, which would risk matching an empty decorative
     * slot when looking for somewhere to return a leftover item.
     */
    private fun AbstractContainerScreen<*>.findInventorySlot(predicate: (ItemStack) -> Boolean): Int? {
        val top = topSlotCount()
        for (i in top until menu.items.size) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (predicate(stack)) return i
        }
        return null
    }

    private fun AbstractContainerScreen<*>.findSlot(vararg keywords: String, requireAll: Boolean = false): Int? {
        val top = topSlotCount()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val text = (stack.hoverName.string + " " + stack.loreString.joinToString(" ")).noControlCodes.lowercase()
            val matched = if (requireAll) keywords.all { text.contains(it.lowercase()) }
            else keywords.any { text.contains(it.lowercase()) }
            if (matched) return i
        }
        return null
    }

    /**
     * The global "Manage Orders" button (all of the player's open orders across every item) - [openOrdersScreen]
     * needs specifically this one, not a decoy [findSlot] alone would happily match first. Confirmed live
     * there are (at least) two scoped decoys, each with its own "Manage Orders" button whose lore gives away
     * the narrower scope: an item's own detail page has one scoped to just that product ("...for this
     * product."), and a category listing page (e.g. "Oddities ➜ Essence") has one scoped to every product in
     * that category ("...for these products."). Since [openOrdersScreen] now often starts from wherever a
     * preceding step left off (an item or category page included, see [openBazaar]'s doc) rather than always
     * forcing a fresh trip to the true root, plain [findSlot] used to grab whichever scoped one it hit first
     * and open the wrong thing entirely. Excludes any match whose own lore says "for this product" or "for
     * these products".
     *
     * Confirmed live there's a *third* variant of the item-page decoy: when the item already has an order
     * open, its lore doesn't say "You don't have any ongoing orders for this product." at all - it instead
     * shows that order directly ("Your order: BUY 11,837x Crimson Essence for 1,016.3 each"), with no "for
     * this product" text to catch. Missing this let the item-page decoy slip through as if it were the real
     * global button whenever the item being viewed happened to already have an order on it - clicking it
     * landed somewhere that still didn't contain "Manage Orders"/an order list, so the "Go Back" loop above
     * never even started (this looked like a match on the very first check) and the whole call chain ended up
     * bouncing between the item page and its own category page instead of ever reaching the true root.
     */
    private fun AbstractContainerScreen<*>.findGlobalManageOrders(): Int? {
        val top = topSlotCount()
        for (i in 0 until top) {
            val stack = menu.items.getOrNull(i) ?: continue
            if (stack.isEmpty) continue
            val text = (stack.hoverName.string + " " + stack.loreString.joinToString(" ")).noControlCodes.lowercase()
            if (!text.contains("manage orders")) continue
            if (text.contains("for this product") || text.contains("for these products") || text.contains("your order:")) continue
            return i
        }
        return null
    }

    /**
     * On request: whether [this] is genuinely the (real, unscoped) Manage Orders screen - detected via the
     * "Claim All Coins" button ([checkForClaimableCoins] already relies on this same item existing there)
     * rather than the screen's own title. More robust than title matching: confirmed live the title text
     * itself already varies (e.g. "Co-op Bazaar Orders" on a co-op profile - see [openOrdersScreen]'s own
     * doc), and a fixed, always-present button is a more reliable signal than trying to match every wording
     * variant of the title by hand.
     */
    private fun AbstractContainerScreen<*>.isManageOrdersScreen(): Boolean = findSlot("claim all coins") != null

    private fun formatPrice(price: Double): String =
        if (price == Math.floor(price)) price.toLong().toString() else "%.1f".format(Locale.US, price)

    /**
     * Same rounding as [formatPrice], but with a "." every 3 digits of the integer part (German-style
     * grouping) for easier reading in chat reports (see "Find Best Flip") - never used for anything actually
     * typed into a Hypixel price input, which needs a plain, ungrouped number.
     */
    private fun formatGrouped(value: Double): String {
        val plain = formatPrice(value)
        val negative = plain.startsWith("-")
        val unsigned = plain.removePrefix("-")
        val dotIndex = unsigned.indexOf('.')
        val intPart = if (dotIndex >= 0) unsigned.substring(0, dotIndex) else unsigned
        val fractionPart = if (dotIndex >= 0) unsigned.substring(dotIndex + 1) else null
        val grouped = intPart.reversed().chunked(3).joinToString(".").reversed()
        return (if (negative) "-" else "") + grouped + (fractionPart?.let { ",$it" } ?: "")
    }

    /**
     * On request: claiming a Buy Order and cancelling a Sell Offer close together only ever *add* items to
     * the inventory - a claim delivers what was bought, cancelling a Sell Offer returns whatever's still
     * unsold - neither one removes anything. Doing both in the same pass (see [combineAndSellBooks]'s
     * up-to-256-unstackable-books case, and [createBookBuyOrder]'s post-outbid claimed-goods resale) risks
     * overflowing a nearly full inventory even though either action alone might have fit. This estimates
     * whether [amount] more of [itemName] would currently fit - best-effort: [estimatedMaxStackSize] guesses
     * stack size from whether it's an enchant book (always 1) or anything else (assumed 64, the common case
     * for Bazaar-tradeable materials) rather than reading a real max-stack-size off an actual ItemStack (there
     * usually isn't one on hand yet to read it from), and doesn't credit room already partially used by an
     * existing stack of the same item - both err toward under-estimating free space, the safe direction here.
     */
    private fun hasInventorySpaceFor(itemName: String, amount: Int): Boolean {
        if (amount <= 0) return true
        val maxStackSize = estimatedMaxStackSize(itemName)
        val capacity = freeInventoryCapacity(maxStackSize)
        val fits = amount <= capacity
        devMessage(
            "[BazaarFlipper] Inventory space check for ${amount}x $itemName: estimated free capacity=$capacity " +
                "(assumed stack size $maxStackSize) -> " +
                if (fits) "fits." else "TIGHT - will run /pickupstash and re-check once this pass is done."
        )
        return fits
    }

    private fun estimatedMaxStackSize(itemName: String): Int = if (bookLevelRegex.find(itemName) != null) 1 else 64

    /** Empty main-inventory slots (36: hotbar + main, excludes armor/offhand) times [maxStackSize] - see [hasInventorySpaceFor]'s own doc for why this is a deliberate under-estimate rather than an exact figure. */
    private fun freeInventoryCapacity(maxStackSize: Int): Int {
        var emptySlots = 0
        // Same forEachIndexed iteration [dumpPlayerInventory]/[detectAllCombinableBookTypes] already use -
        // Inventory's own backing list isn't accessible directly, only via Iterable.
        mc.player?.inventory?.forEachIndexed { i, stack -> if (i < 36 && stack.isEmpty) emptySlots++ }
        return emptySlots * maxStackSize
    }

    /** On request: recovers whatever Hypixel had to stash instead of delivering directly because the inventory was too full at claim/cancel time - see [hasInventorySpaceFor]'s callers. Command name confirmed by the user. */
    private suspend fun runPickupStash() {
        devMessage("[BazaarFlipper] Inventory was tight during that claim/cancel pass - running /pickupstash to recover anything that overflowed.")
        sendCommand("pickupstash")
        randomDelay(GUI_APPEAR_DELAY)
    }

    /**
     * Confirmed live: claimed Bazaar-tradeable enchant books are individual "Enchanted Book" items - the
     * actual enchant/level text ("Bank I") is only in the lore, never the display name - so [name] is
     * matched against name+lore combined via [containsBookLevel] (a plain `.contains` would also count
     * higher-level books whose roman numeral happens to start with this one's).
     */
    private fun countHeldItems(name: String): Int =
        mc.player?.inventory
            ?.filter { !it.isEmpty && containsBookLevel(it.hoverName.string.noControlCodes + " " + it.loreString.joinToString(" ") { l -> l.noControlCodes }, name) }
            ?.sumOf { it.count } ?: 0

    /** Dumps every non-empty slot (name + lore) in the player's real inventory via [devMessage] - independent of whatever menu is currently open, unlike [dumpScreen]. */
    private fun dumpPlayerInventory(label: String) {
        val text = buildString {
            append("[BazaarFlipper] ").append(label).append('\n')
            mc.player?.inventory?.forEachIndexed { i, stack ->
                if (stack.isEmpty) return@forEachIndexed
                append("  #").append(i).append(": ").append(stack.hoverName.string.noControlCodes).append(" x").append(stack.count)
                val lore = stack.loreString.joinToString(" / ") { it.noControlCodes }
                if (lore.isNotBlank()) append(" | ").append(lore)
                append('\n')
            }
        }
        devMessage(text)
    }

    /** Dumps every non-empty slot's name + lore via [devMessage] (which mirrors to Discord if a debug webhook is set) - the fastest way to correct wrong keywords once this runs against the live GUI. */
    private fun dumpScreen(screen: AbstractContainerScreen<*>, label: String) {
        val top = screen.topSlotCount()
        val text = buildString {
            append("[BazaarFlipper] ").append(label).append(" | title='").append(screen.title.string.noControlCodes).append("'\n")
            for (i in 0 until top) {
                val stack = screen.menu.items.getOrNull(i) ?: continue
                if (stack.isEmpty) continue
                append("  #").append(i).append(": ").append(stack.hoverName.string.noControlCodes)
                val lore = stack.loreString.joinToString(" / ") { it.noControlCodes }
                if (lore.isNotBlank()) append(" | ").append(lore)
                append('\n')
            }
        }
        devMessage(text)
    }
}
