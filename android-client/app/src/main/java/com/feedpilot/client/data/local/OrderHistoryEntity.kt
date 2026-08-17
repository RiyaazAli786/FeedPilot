package com.feedpilot.client.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Where an order in the history came from. Stored as the enum name so the column stays
 * readable in the DB and no Room TypeConverter is needed.
 */
enum class OrderSource(val label: String) {
    /** Placed by this app's user through the SMM user API — we paid for it. */
    APP("App Order"),

    /** Pulled from the SMM admin queue for us to fulfil — someone else paid for it. */
    EXTERNAL("External Order");

    companion object {
        fun from(raw: String?): OrderSource =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: APP
    }
}

@Entity(tableName = "order_history")
data class OrderHistoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val smmOrderId: String? = null,
    val providerNickname: String,
    val providerUrl: String,
    val targetUsername: String,
    val orderType: String,
    val quantity: Int,
    val completedCount: Int = 0,
    val coinsSpent: Long,
    val status: String,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    /** [OrderSource] name. Defaults to APP so existing rows keep their meaning. */
    val source: String = OrderSource.APP.name
) {
    val orderSource: OrderSource get() = OrderSource.from(source)
}
