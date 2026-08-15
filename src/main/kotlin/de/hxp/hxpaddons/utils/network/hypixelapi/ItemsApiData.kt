package de.hxp.hxpaddons.utils.network.hypixelapi

object ItemsApiData {

    data class Reply(
        val success: Boolean = false,
        val items: List<Item> = emptyList(),
    )

    data class Item(
        val id: String,
        val name: String = "",
        val unstackable: Boolean = false,
    )
}
