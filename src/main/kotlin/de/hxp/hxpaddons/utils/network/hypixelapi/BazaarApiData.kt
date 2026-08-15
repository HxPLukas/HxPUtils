package de.hxp.hxpaddons.utils.network.hypixelapi

import com.google.gson.annotations.SerializedName

object BazaarApiData {

    data class Reply(
        val success: Boolean = false,
        val lastUpdated: Long = 0,
        val products: Map<String, Product> = emptyMap(),
    )

    data class Product(
        @SerializedName("product_id")
        val productId: String,
        @SerializedName("sell_summary")
        val sellSummary: List<Order> = emptyList(),
        @SerializedName("buy_summary")
        val buySummary: List<Order> = emptyList(),
        @SerializedName("quick_status")
        val quickStatus: QuickStatus? = null,
    )

    data class Order(
        val amount: Long,
        val pricePerUnit: Double,
        val orders: Int,
    )

    /**
     * Confirmed live: despite the field names, "sellPrice" is what you'd get instantly SELLING (the current
     * top buy order/bid) and "buyPrice" is what you'd pay instantly BUYING (the current top sell offer/ask)
     * - i.e. profit-per-unit for a Buy-Order-then-Sell-Offer flip is `buyPrice - sellPrice`, not the other
     * way round. `sellMovingWeek`/`buyMovingWeek` are the total units sold/bought over the trailing week -
     * divide by 168 for an average per-hour rate (see [de.hxp.hxpaddons.features.impl.skyblock.BazaarFlipper]'s
     * "Best Flip" feature).
     */
    data class QuickStatus(
        val sellPrice: Double = 0.0,
        val sellVolume: Long = 0,
        val sellMovingWeek: Long = 0,
        val sellOrders: Int = 0,
        val buyPrice: Double = 0.0,
        val buyVolume: Long = 0,
        val buyMovingWeek: Long = 0,
        val buyOrders: Int = 0,
    )
}
