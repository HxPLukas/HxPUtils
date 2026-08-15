package de.hxp.hxpaddons.features.impl.skyblock

import de.hxp.hxpaddons.HxPMod
import de.hxp.hxpaddons.HxPMod.mc
import de.hxp.hxpaddons.events.TickEvent
import de.hxp.hxpaddons.events.core.on
import de.hxp.hxpaddons.features.Category
import de.hxp.hxpaddons.features.Module
import de.hxp.hxpaddons.utils.formatNumber
import de.hxp.hxpaddons.utils.modMessage
import de.hxp.hxpaddons.utils.noControlCodes
import de.hxp.hxpaddons.utils.playAlertSound
import de.hxp.hxpaddons.utils.simulateKeyPress
import de.hxp.hxpaddons.utils.useHeldItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

object AutoFish : Module(
    name = "Auto Fish",
    description = "Reels in and recasts automatically when a fish bites.",
    category = Category.SKYBLOCK
) {
    private const val MAX_CLICKS_PER_MOB = 3

    private var actionCooldown = false
    private var lastTriggeredStandId = -1

    private fun findCombatTarget(level: Level, origin: Vec3): LivingEntity? {
        val area = AABB(origin.x - 6, origin.y - 6, origin.z - 6, origin.x + 6, origin.y + 6, origin.z + 6)
        return level.getEntitiesOfClass(LivingEntity::class.java, area) { it !is ArmorStand && it !is Player }
            .minByOrNull { it.distanceToSqr(origin) }
    }

    private fun LivingEntity.isPuddleJumper(): Boolean = name.string.noControlCodes.equals("Puddle Jumper", ignoreCase = true)

    override fun onEnable() {
        super.onEnable()
        actionCooldown = false
        lastTriggeredStandId = -1
    }

    override fun onDisable() {
        super.onDisable()
        actionCooldown = false
    }

    override fun onKeybind() {
        toggle()
        if (enabled) modMessage("§aAuto Fish §2started§a.")
        else modMessage("§cAuto Fish §4stopped§c.")
    }

    init {
        on<TickEvent.End> {
            if (!enabled || actionCooldown) return@on
            val hook = mc.player?.fishing ?: return@on
            val level = mc.level ?: return@on

            val pos = hook.position()
            val area = AABB(pos.x - 5, pos.y - 5, pos.z - 5, pos.x + 5, pos.y + 5, pos.z + 5)

            val biteStand = level.getEntitiesOfClass(ArmorStand::class.java, area).firstOrNull { stand ->
                stand.customName?.string == "!!!" && stand.id != lastTriggeredStandId
            } ?: return@on

            lastTriggeredStandId = biteStand.id
            actionCooldown = true
            HxPMod.scope.launch {
                delay(Random.nextLong(0, 131))
                useHeldItem()

                if (Combat.enabled) {
                    delay(Random.nextLong(110, 161))
                    simulateKeyPress(Combat.combatKey)
                    delay(Random.nextLong(70, 101))

                    val target = findCombatTarget(level, pos)
                    when {
                        // Puddle Jumper can't be killed - hops to a new spot up to 5 times and needs to be
                        // re-cast onto each time, which this simple "spam right-click at melee range" loop
                        // can't do, so it bails out instead of flailing at an immortal mob for 20s.
                        target != null && target.isPuddleJumper() -> {
                            playAlertSound()
                            modMessage("§cAuto Fish §4stopped§c (Puddle Jumper - reel it in manually).")
                            mc.execute { toggle() }
                            return@launch
                        }
                        target != null && target.maxHealth >= Combat.mobHpThreshold -> {
                            playAlertSound()
                            modMessage("§cAuto Fish §4stopped§c (mob HP above ${formatNumber(Combat.mobHpThreshold.toString())}).")
                            mc.execute { toggle() }
                            return@launch
                        }
                        target != null -> {
                            var clicks = 0
                            while (enabled && target.isAlive && !target.isRemoved && clicks < MAX_CLICKS_PER_MOB) {
                                useHeldItem()
                                clicks++
                                delay(Random.nextLong(60, 91))
                            }
                        }
                        else -> useHeldItem()
                    }

                    delay(Random.nextLong(80, 121))
                    simulateKeyPress(Combat.rodKey)
                    delay(Random.nextLong(160, 211))
                    useHeldItem()
                } else {
                    delay(Random.nextLong(180, 261))
                    useHeldItem()
                }
                delay(2000L)
                actionCooldown = false
            }
        }
    }
}
