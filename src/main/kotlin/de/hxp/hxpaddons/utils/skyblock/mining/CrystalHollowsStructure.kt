package de.hxp.hxpaddons.utils.skyblock.mining

import de.hxp.hxpaddons.utils.Color
import de.hxp.hxpaddons.utils.Colors
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

enum class CrystalHollowsStructureType {
    FAIRY_GROTTO, CH_CRYSTALS, CH_MOB_SPOTS, GOLDEN_DRAGON
}

/**
 * from quoi (GPL-3.0)
 * original: https://github.com/pigeonlover1998/quoi/blob/26.1.x/src/main/kotlin/quoi/module/impl/mining/enums/Structure.kt
 *
 * Each structure is a fixed vertical column of blocks ([blocks], top-to-bottom irrelevant - matched
 * bottom-up from wherever scanning starts) at a given x/z - a `null` entry means "any block". Once a
 * column matches, the reported position is the scan position offset by [xOffset]/[yOffset]/[zOffset].
 */
enum class CrystalHollowsStructure(
    val colour: Color,
    val blocks: List<Block?>,
    val type: CrystalHollowsStructureType,
    val quarter: CrystalHollowsQuarter,
    val displayName: String,
    val xOffset: Int,
    val yOffset: Int,
    val zOffset: Int,
    val canBeMultiple: Boolean = false
) {
    QUEEN(
        Color(255, 165, 0),
        listOf(Blocks.STONE, Blocks.ACACIA_LOG, Blocks.ACACIA_LOG, Blocks.ACACIA_LOG, Blocks.CAULDRON, Blocks.FIRE),
        CrystalHollowsStructureType.CH_CRYSTALS, CrystalHollowsQuarter.GOBLIN_HOLDOUT, "Goblin Queen", 0, 5, 0
    ),

    DIVAN(
        Color(0, 0, 255),
        listOf(Blocks.QUARTZ_PILLAR, Blocks.QUARTZ_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.CHISELED_STONE_BRICKS),
        CrystalHollowsStructureType.CH_CRYSTALS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Mines of Divan", 0, 5, 0
    ),

    CITY(
        Colors.WHITE,
        listOf(
            Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE, Blocks.COBBLESTONE,
            Blocks.COBBLESTONE_STAIRS, Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DARK_OAK_STAIRS
        ),
        CrystalHollowsStructureType.CH_CRYSTALS, CrystalHollowsQuarter.PRECURSOR_REMNANTS, "Precursor City", 24, 0, -17
    ),

    TEMPLE(
        Colors.MINECRAFT_DARK_GREEN,
        listOf(Blocks.BEDROCK, Blocks.BEDROCK, Blocks.CLAY, Blocks.CLAY),
        CrystalHollowsStructureType.CH_CRYSTALS, CrystalHollowsQuarter.JUNGLE, "Jungle Temple", -45, 47, -18
    ),

    KING(
        Colors.MINECRAFT_YELLOW,
        listOf(Blocks.RED_WOOL, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_STAIRS),
        CrystalHollowsStructureType.CH_CRYSTALS, CrystalHollowsQuarter.GOBLIN_HOLDOUT, "Goblin King", 1, -1, 2
    ),

    BAL(
        Colors.MINECRAFT_RED,
        listOf(
            Blocks.LAVA, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER,
            Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER, Blocks.BARRIER
        ),
        CrystalHollowsStructureType.CH_CRYSTALS, CrystalHollowsQuarter.MAGMA_FIELDS, "Bal", 0, 1, 0
    ),

    FAIRY_GROTTO(
        Color(242, 127, 165),
        listOf(Blocks.MAGENTA_STAINED_GLASS),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Fairy Grotto", 0, 0, 0, canBeMultiple = true
    ),

    RUINS_GROTTO_1(
        Color(242, 127, 165),
        listOf(Blocks.STONE, Blocks.LANTERN, Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS_PANE),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Ruins Grotto 1", 0, 0, 0, canBeMultiple = true
    ),

    RUINS_GROTTO_2(
        Color(242, 127, 165),
        listOf(
            Blocks.STONE, Blocks.LANTERN, Blocks.COARSE_DIRT, Blocks.MAGENTA_STAINED_GLASS, null,
            Blocks.MAGENTA_STAINED_GLASS
        ),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Ruins Grotto 2", 0, 0, 0, canBeMultiple = true
    ),

    RUINS_GROTTO_3(
        Color(242, 127, 165),
        listOf(
            Blocks.STONE, Blocks.DIRT, Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS_PANE
        ),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Ruins Grotto 3", 0, 0, 0, canBeMultiple = true
    ),

    SHRINE_GROTTO(
        Color(242, 127, 165),
        listOf(
            Blocks.LANTERN, Blocks.STONE, Blocks.CLAY, Blocks.LANTERN, Blocks.MAGENTA_STAINED_GLASS,
            Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS
        ),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Shrine Grotto", 0, 0, 0, canBeMultiple = true
    ),

    SPIRAL_GROTTO(
        Color(242, 127, 165),
        listOf(
            Blocks.POLISHED_ANDESITE, Blocks.STONE_BRICKS, Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS,
            Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS_PANE, Blocks.MAGENTA_STAINED_GLASS_PANE,
            Blocks.MAGENTA_STAINED_GLASS_PANE, null, null, null, null, null, null, Blocks.LANTERN
        ),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Spiral Grotto", 0, 0, 0, canBeMultiple = true
    ),

    WATERFALL_GROTTO(
        Color(242, 127, 165),
        listOf(
            Blocks.MAGENTA_STAINED_GLASS, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE,
            Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE,
            Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.WATER
        ),
        CrystalHollowsStructureType.FAIRY_GROTTO, CrystalHollowsQuarter.ANY, "Waterfall Grotto", 0, 0, 0, canBeMultiple = true
    ),

    GOBLIN_HALL(
        Color(128, 128, 128),
        listOf(
            Blocks.SPRUCE_PLANKS, null, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, null, null,
            Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, null, null, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS,
            null, Blocks.SPRUCE_PLANKS
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.GOBLIN_HOLDOUT, "Goblin Hall", 0, 7, 0
    ),

    GOBLIN_RING(
        Color(128, 128, 128),
        listOf(
            Blocks.OAK_FENCE, Blocks.SKELETON_SKULL, null, null, null, null, Blocks.OAK_SLAB, Blocks.OAK_SLAB,
            null, null, null, Blocks.SPRUCE_PLANKS
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.GOBLIN_HOLDOUT, "Goblin Ring", 0, 11, 0
    ),

    GRUNT_BRIDGE(
        Color(128, 128, 128),
        listOf(
            Blocks.STONE_BRICK_STAIRS, null, null, null, null, Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, null,
            Blocks.SMOOTH_STONE_SLAB, Blocks.STONE_BRICKS, null, null, null, Blocks.STONE_BRICKS,
            Blocks.SMOOTH_STONE_SLAB
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Grunt Bridge", 0, -1, -45
    ),

    CORLEONE_DOCK(
        Colors.BLACK,
        listOf(
            Blocks.POLISHED_GRANITE, Blocks.WATER, Blocks.WATER, null, null, null, null, null, null, null,
            Blocks.STONE, Blocks.CYAN_TERRACOTTA, Blocks.STONE_BRICKS, Blocks.STONE, Blocks.FIRE
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Corleone Dock", 23, 11, 17
    ),

    CORLEONE_HOLE(
        Colors.BLACK,
        listOf(
            Blocks.SMOOTH_STONE_SLAB, null, null, null, null, null, null, null, null, null, null, null, null,
            null, Blocks.SMOOTH_STONE_SLAB, Blocks.SMOOTH_STONE_SLAB, null, Blocks.SMOOTH_STONE_SLAB,
            Blocks.STONE_BRICKS
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Corleone Hole", 0, -3, 34
    ),

    GRUNT_RAILS_1(
        Color(128, 128, 128),
        listOf(
            Blocks.SPRUCE_PLANKS, null, Blocks.OAK_WALL_SIGN, null, null, null, null, Blocks.SPRUCE_PLANKS,
            Blocks.TNT
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Grunt Rails 1", 0, 0, 0
    ),

    GRUNT_HERO_STATUE(
        Color(128, 128, 128),
        listOf(
            Blocks.SMOOTH_STONE_SLAB, Blocks.DIORITE, Blocks.DIORITE, Blocks.DIORITE, Blocks.DIORITE,
            Blocks.COBBLESTONE, Blocks.POLISHED_ANDESITE, Blocks.COBBLESTONE, Blocks.STONE_STAIRS
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Grunt Hero Statue", 0, 0, 0
    ),

    SMALL_GRUNT_BRIDGE(
        Color(128, 128, 128),
        listOf(
            Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, null, Blocks.SPRUCE_STAIRS, Blocks.OAK_LOG,
            Blocks.OAK_FENCE, Blocks.TORCH
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MITHRIL_DEPOSITS, "Small Grunt Bridge", 0, 0, 0
    ),

    KEY_GUARDIAN_SPIRAL(
        Color(128, 128, 128),
        listOf(
            Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_PLANKS, Blocks.GLOWSTONE, Blocks.WHITE_CARPET, null,
            Blocks.JUNGLE_SLAB, null, Blocks.JUNGLE_STAIRS, Blocks.STONE, Blocks.STONE, Blocks.STONE
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.JUNGLE, "Key Guardian Spiral", 0, 0, 0
    ),

    SLUDGE_WATERFALLS(
        Color(128, 128, 128),
        listOf(
            Blocks.STONE, Blocks.DIRT, Blocks.POLISHED_GRANITE, Blocks.JUNGLE_STAIRS, Blocks.AIR, Blocks.AIR,
            Blocks.AIR, Blocks.AIR, Blocks.AIR
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.JUNGLE, "Sludge Waterfalls", 0, 0, 0
    ),

    SLUDGE_BRIDGES(
        Color(128, 128, 128),
        listOf(
            Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_STAIRS,
            Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.JUNGLE_STAIRS,
            Blocks.JUNGLE_PLANKS, Blocks.GRANITE, Blocks.GRANITE
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.JUNGLE, "Sludge Bridges", 0, 0, 0
    ),

    YOG_BRIDGE(
        Color(128, 128, 128),
        listOf(
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS,
            Blocks.ANDESITE, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_STAIRS,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.ANDESITE, Blocks.STONE_BRICKS,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICKS, Blocks.RAIL
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.MAGMA_FIELDS, "Yog Bridge", 0, 15, 0
    ),

    ODAWA(
        Color(128, 128, 128),
        listOf(
            Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG, Blocks.SPRUCE_STAIRS,
            Blocks.SPRUCE_STAIRS, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LOG, Blocks.JUNGLE_LOG, Blocks.HAY_BLOCK,
            Blocks.YELLOW_TERRACOTTA
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.JUNGLE, "Odawa", 0, 0, 0
    ),

    MINI_JUNGLE_TEMPLE(
        Color(128, 128, 128),
        listOf(
            Blocks.POLISHED_ANDESITE, Blocks.ANDESITE, Blocks.STONE_BRICK_STAIRS, Blocks.ANDESITE, Blocks.ANDESITE,
            Blocks.STONE, Blocks.ANDESITE, Blocks.STONE, Blocks.ANDESITE, Blocks.ANDESITE,
            Blocks.STONE_BRICK_STAIRS, Blocks.ANDESITE, Blocks.STONE, Blocks.ANDESITE
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.JUNGLE, "Mini Jungle Temple", 0, 0, 0
    ),

    PRECURSOR_TRIPWIRE_CHAMBER(
        Color(128, 128, 128),
        listOf(
            Blocks.DIORITE, Blocks.DIORITE, Blocks.SMOOTH_STONE_SLAB, Blocks.SMOOTH_STONE_SLAB,
            Blocks.SMOOTH_STONE_SLAB, Blocks.DIORITE, Blocks.DIORITE, Blocks.DIORITE, Blocks.SMOOTH_STONE_SLAB,
            Blocks.SMOOTH_STONE_SLAB, Blocks.DIORITE, Blocks.SMOOTH_STONE_SLAB, Blocks.SMOOTH_STONE_SLAB,
            Blocks.SMOOTH_STONE_SLAB
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.PRECURSOR_REMNANTS, "Precursor Tripwire", 0, 0, 0
    ),

    PRECURSOR_TALL_PILLARS(
        Color(128, 128, 128),
        listOf(
            Blocks.POLISHED_DIORITE, Blocks.POLISHED_DIORITE, Blocks.DIORITE, Blocks.DIORITE,
            Blocks.POLISHED_DIORITE, Blocks.POLISHED_DIORITE, Blocks.POLISHED_ANDESITE, Blocks.DIORITE,
            Blocks.DIORITE, Blocks.DIORITE, Blocks.DIORITE, Blocks.POLISHED_DIORITE, Blocks.DIORITE,
            Blocks.POLISHED_ANDESITE, Blocks.POLISHED_DIORITE, Blocks.DIORITE, Blocks.DIORITE, Blocks.DIORITE,
            Blocks.DIORITE, Blocks.POLISHED_DIORITE
        ),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.PRECURSOR_REMNANTS, "Precursor Pillars", 0, 0, 0
    ),

    GOBLIN_HOLE_CAMP(
        Color(128, 128, 128),
        listOf(Blocks.NETHERRACK, Blocks.NETHERRACK, Blocks.OAK_FENCE, Blocks.OAK_FENCE, Blocks.OAK_LOG, Blocks.OAK_LOG),
        CrystalHollowsStructureType.CH_MOB_SPOTS, CrystalHollowsQuarter.GOBLIN_HOLDOUT, "Goblin Hole Camp", 0, 0, 0
    ),

    GOLDEN_DRAGON(
        Color(0, 255, 255),
        listOf(
            Blocks.STONE, Blocks.RED_TERRACOTTA, Blocks.RED_TERRACOTTA, Blocks.RED_TERRACOTTA,
            Blocks.SKELETON_SKULL, Blocks.RED_WOOL
        ),
        CrystalHollowsStructureType.GOLDEN_DRAGON, CrystalHollowsQuarter.ANY, "Golden Dragon", 0, -3, 5
    ),

    GOLDEN_DRAGON_ALT(
        Color(0, 255, 255),
        listOf(Blocks.SANDSTONE, Blocks.ORANGE_TERRACOTTA, Blocks.ORANGE_TERRACOTTA),
        CrystalHollowsStructureType.GOLDEN_DRAGON, CrystalHollowsQuarter.ANY, "Golden Dragon", 0, 0, 0
    )
}
