package com.trading.ordermanager.model

import java.time.Instant
import java.util.UUID

data class TradingDecision(
    val symbol: String,
    val action: String,  // BUY, SELL, HOLD, STRONG_BUY, STRONG_SELL
    val confidence: Double,
    val signals: List<Map<String, Any>>? = null,  // Array of signal objects
    val timestamp: String? = null,
    val reason: String? = null
)

data class Order(
    val id: UUID = UUID.randomUUID(),
    val clientOrderId: String,
    val symbol: String,
    val side: String,  // BUY or SELL
    val qty: Double,
    val price: Double,
    val status: String = "PENDING",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

data class OutboxEvent(
    val eventId: UUID = UUID.randomUUID(),
    val aggregateType: String,
    val aggregateId: UUID,
    val type: String,
    val payload: String,
    val occurredAt: Instant = Instant.now(),
    val occurredAtMs: Long = System.currentTimeMillis()
)

enum class OrderStatus {
    PENDING,
    FILLED,
    PARTIALLY_FILLED,
    CANCELED,
    REJECTED
}