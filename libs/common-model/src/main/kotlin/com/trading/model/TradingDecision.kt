package com.trading.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * Represents a trading decision based on aggregated signals
 */
data class TradingDecision(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val action: Action,
    val confidence: Double,
    @JsonProperty("suggested_price")
    val suggestedPrice: Double,
    @JsonProperty("suggested_qty")
    val suggestedQty: Double,
    val signals: List<Signal>,
    val timestamp: Instant = Instant.now(),
    val reason: DecisionReason
) {
    enum class Action {
        STRONG_BUY,
        BUY,
        HOLD,
        SELL,
        STRONG_SELL
    }

    enum class DecisionReason(val description: String) {
        ALIGNED_SIGNALS("Multiple timeframes align"),
        DIVERGENT_SIGNALS("Conflicting signals across timeframes"),
        INSUFFICIENT_DATA("Not enough signals for decision"),
        MOMENTUM_SHIFT("Detected momentum change"),
        VOLUME_SPIKE("Unusual volume detected")
    }

    companion object {
        fun create(
            symbol: String,
            action: Action,
            confidence: Double,
            price: Double,
            qty: Double,
            signals: List<Signal>,
            reason: DecisionReason
        ) = TradingDecision(
            symbol = symbol,
            action = action,
            confidence = confidence,
            suggestedPrice = price,
            suggestedQty = qty,
            signals = signals,
            reason = reason
        )
    }
}