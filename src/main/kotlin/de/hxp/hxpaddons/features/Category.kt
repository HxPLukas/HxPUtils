package de.hxp.hxpaddons.features

import de.hxp.hxpaddons.features.Category.Companion.categories

@ConsistentCopyVisibility
data class Category private constructor(val name: String) {
    companion object {

        /**
         * Map containing all the categories, with the key being the name.
         */
        val categories: LinkedHashMap<String, Category> = linkedMapOf()

        @JvmField
        val GENERAL = custom(name = "General")
        @JvmField
        val GARDEN = custom(name = "Garden")
        @JvmField
        val SKYBLOCK = custom(name = "Skyblock")
        @JvmField
        val RENDER = custom(name = "Render")
        @JvmField
        val DUNGEON = custom(name = "Dungeon")
        @JvmField
        val GUI = custom(name = "Gui")
        // @JvmField
        // val BOSS = custom(name = "Boss")2
        // @JvmField
        // val NETHER = custom(name = "Nether")
        // @JvmField
        // val MINING = custom(name = "Mining") // was only used by Crystal Hollows Map, removed for HxPUtils

        /**
         * Returns a category with name provided.
         *
         * If a category with the same name has already been made, it won't reallocate.
         * Otherwise, it will be added to [categories].
         */
        fun custom(name: String): Category {
            return categories.getOrPut(name) { Category(name) }
        }
    }
}