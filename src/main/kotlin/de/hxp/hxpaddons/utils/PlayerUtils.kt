package de.hxp.hxpaddons.utils

import com.mojang.blaze3d.platform.InputConstants
import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import java.awt.Robot
import java.awt.event.InputEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.StringUtil
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

fun playSoundSettings(soundSettings: Triple<String, Float, Float>) {
    val (soundName, volume, pitch) = soundSettings
    val identifier = Identifier.tryParse(StringUtil.filterText(soundName.lowercase())) ?: return
    playSoundAtPlayer(SoundEvent.createVariableRangeEvent(identifier), volume, pitch)
}

fun playSoundAtPlayer(event: SoundEvent, volume: Float = 1f, pitch: Float = 1f) = mc.execute {
    mc.soundManager.playDelayed(SimpleSoundInstance.forUI(event, pitch, volume), 0)
}

fun setTitle(title: String) {
    mc.gui.setTimes(0, 20, 5)
    mc.gui.setTitle(Component.literal(title))
}

fun setSubtitle(subtitle: String) {
    mc.gui.setTimes(0, 20, 5)
    mc.gui.setSubtitle(Component.literal(subtitle))
}

fun alert(title: String, playSound: Boolean = true) {
    setTitle(title)
    if (playSound) playSoundAtPlayer(SoundEvents.NOTE_BLOCK_PLING.value())
}

fun playAlertSound(durationMs: Long = 400L, event: SoundEvent = SoundEvents.NOTE_BLOCK_PLING.value(), volume: Float = 1f, pitch: Float = 1f) {
    val instance = SimpleSoundInstance.forUI(event, pitch, volume)
    mc.execute { mc.soundManager.play(instance) }
    HxPMod.scope.launch {
        delay(durationMs)
        mc.execute { mc.soundManager.stop(instance) }
    }
}

fun getPositionString(): String {
    with(mc.player?.blockPosition() ?: BlockPos(0, 0, 0)) {
        return "x: $x, y: $y, z: $z"
    }
}

private val purseRegex = Regex("purse:\\s*([\\d,]*\\.?\\d+)", RegexOption.IGNORE_CASE)

/**
 * Reads the player's current coin balance off the Skyblock sidebar scoreboard's "Purse: X" line -
 * Hypixel renders sidebar lines via a team prefix/suffix wrapped around a (usually blank) fake score
 * holder name rather than the line's real text living in the holder name itself, same as
 * NoammAddons/reference mods' own scoreboard readers do it. Returns null if not in Skyblock, no sidebar
 * is shown, or the "Purse" line isn't found/parseable (e.g. while some other scoreboard is displayed) -
 * callers should treat that as "unknown" rather than "zero coins".
 */
fun readPurseBalance(): Double? {
    val scoreboard = mc.level?.scoreboard ?: return null
    val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
    for (score in scoreboard.listPlayerScores(objective)) {
        val name = score.ownerName().string
        val team = scoreboard.getPlayersTeam(name)
        val line = (if (team != null) PlayerTeam.formatNameForTeam(team, Component.literal(name)) else Component.literal(name))
            .string.noControlCodes
        val match = purseRegex.find(line) ?: continue
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }
    return null
}

private val robot: Robot? by lazy {
    runCatching { Robot() }
        .onFailure { modMessage("§c[Robot] ${it::class.simpleName}: ${it.message}") }
        .getOrNull()
}

fun simulateLeftClick() {
    val robot = robot ?: return
    runCatching {
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }.onFailure { modMessage("§c[simulateLeftClick] ${it::class.simpleName}: ${it.message}") }
}

fun simulateRightClick() {
    val robot = robot ?: return
    runCatching {
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
    }.onFailure { modMessage("§c[simulateRightClick] ${it::class.simpleName}: ${it.message}") }
}

fun simulateMiddleClick() {
    val robot = robot ?: return
    runCatching {
        robot.mousePress(InputEvent.BUTTON2_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON2_DOWN_MASK)
    }.onFailure { modMessage("§c[simulateMiddleClick] ${it::class.simpleName}: ${it.message}") }
}

/**
 * One mouse-wheel notch, via AWT's `Robot.mouseWheel` - outside a GUI this cycles the selected hotbar slot
 * exactly like a real scroll would, so a caller can advance from an already-selected slot without needing
 * that next slot's own physical key (used by `/hxp bz huntingbox`'s [de.hxp.hxpaddons.features.impl.skyblock.BazaarFlipper.depositHotbarWithConfirmation]
 * to hotkey only the first hotbar slot, then scroll to each following one).
 *
 * [amount]'s sign->direction mapping is an unconfirmed guess (positive = scroll down/toward the player in
 * AWT's own convention) - Minecraft's own scroll-to-hotbar-slot direction isn't independently verified here,
 * so if the wrong neighboring slot ends up selected, flip the sign at the call site first before suspecting
 * anything else.
 */
fun simulateScroll(amount: Int) {
    val robot = robot ?: return
    runCatching {
        robot.mouseWheel(amount)
    }.onFailure { modMessage("§c[simulateScroll] ${it::class.simpleName}: ${it.message}") }
}

/** [simulateRightClickWithShiftHeld]'s explicit hold before clicking and again before releasing - see that function's own doc for why this was added. */
private const val SNEAK_HOLD_MS = 120L

/**
 * Holds Shift for the duration of a right-click - e.g. Hypixel's Sacks/Hunting Box accept a held item straight
 * into storage via shift+right-click, no GUI needed. AWT's `KeyEvent.VK_SHIFT` specifically (not
 * [simulateKeyPress]'s GLFW-keycode path - GLFW's `GLFW_KEY_LEFT_SHIFT` (340) and AWT's `VK_SHIFT` (16) are
 * different numeric codes, only coincide for digits/letters).
 *
 * 2026-08-13: confirmed live the original version - `keyPress`->`mousePress`->`mouseRelease`->`keyRelease`
 * fired back-to-back with no gap at all - did NOT actually make the player sneak. Minecraft's own sneak state
 * is tick-based (the client only picks up a key's current down/up state once per tick, ~50ms at 20 TPS - not
 * instantly off the raw OS event Robot generates), so a Shift press immediately followed by a click most
 * likely raced ahead of the client ever registering "sneak started" before the interact packet went out. Now
 * explicitly holds Shift for [SNEAK_HOLD_MS] before clicking, and keeps it held that long again afterward
 * before releasing, giving the client's own tick loop time to catch up on both ends. `Thread.sleep` (not a
 * suspend delay) is fine here - every other call in this function is already a blocking OS-level Robot call,
 * this doesn't add any new blocking behavior in kind, just extends how long it already blocks for.
 */
fun simulateRightClickWithShiftHeld() {
    val robot = robot ?: return
    runCatching {
        robot.keyPress(java.awt.event.KeyEvent.VK_SHIFT)
        Thread.sleep(SNEAK_HOLD_MS)
        robot.mousePress(InputEvent.BUTTON3_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK)
        Thread.sleep(SNEAK_HOLD_MS)
        robot.keyRelease(java.awt.event.KeyEvent.VK_SHIFT)
    }.onFailure { modMessage("§c[simulateRightClickWithShiftHeld] ${it::class.simpleName}: ${it.message}") }
}

fun simulateKeyPress(key: InputConstants.Key) {
    if (key == InputConstants.UNKNOWN || key.type != InputConstants.Type.KEYSYM) return
    val robot = robot ?: return
    runCatching {
        robot.keyPress(key.value)
        robot.keyRelease(key.value)
    }.onFailure { modMessage("§c[simulateKeyPress] ${it::class.simpleName}: ${it.message}") }
}

/**
 * Presses+releases whatever physical key produces [char] on the OS's *current* keyboard layout, via AWT's
 * `KeyEvent.getExtendedKeyCodeForChar` - unlike [simulateKeyPress] (a fixed GLFW keycode, assumes a US/ANSI-
 * like layout), this resolves layout-dependent characters (e.g. German "ä", "ö") correctly without this mod
 * needing to hardcode any specific layout. Used by `/hxp bz hotkeys`'s configurable hotbar-slot keys.
 */
fun simulateKeyPressChar(char: Char) {
    val robot = robot ?: return
    val code = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(char.code)
    if (code == java.awt.event.KeyEvent.VK_UNDEFINED) {
        modMessage("§c[simulateKeyPressChar] no key mapping found for '$char' on this keyboard layout.")
        return
    }
    runCatching {
        robot.keyPress(code)
        robot.keyRelease(code)
    }.onFailure { modMessage("§c[simulateKeyPressChar] ${it::class.simpleName}: ${it.message}") }
}