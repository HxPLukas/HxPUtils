package de.hxp.hxpaddons.utils.skyblock.mining

import net.minecraft.core.BlockPos

/**
 * from quoi (GPL-3.0)
 * original: https://github.com/pigeonlover1998/quoi/blob/26.1.x/src/main/kotlin/quoi/module/impl/mining/enums/CrystalHollowsQuarter.kt
 */
enum class CrystalHollowsQuarter(private val predicate: (BlockPos) -> Boolean) {
    JUNGLE({ it.x <= 576 && it.z <= 576 }),
    PRECURSOR_REMNANTS({ it.x > 448 && it.z > 448 }),
    GOBLIN_HOLDOUT({ it.x <= 576 && it.z > 448 }),
    MITHRIL_DEPOSITS({ it.x > 448 && it.z <= 576 }),
    MAGMA_FIELDS({ it.y < 80 }),
    ANY({ true });

    fun test(pos: BlockPos) = predicate(pos)
}
