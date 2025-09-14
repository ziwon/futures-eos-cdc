package com.trading.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Represents a trading signal from various timeframes
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Signal(
    val symbol: String,
    val side: Side,
    val qty: Double,
    val price: Double,
    val timeframe: String,  // "1m", "5m", "15m"
    val ts: Long,
    @JsonProperty("processed_at")
    val processedAt: Instant? = null
) {
    fun withProcessedAt(instant: Instant) = copy(processedAt = instant)

    val strength: SignalStrength
        get() = when (timeframe) {
            "1m" -> SignalStrength.WEAK
            "5m" -> SignalStrength.MEDIUM
            "15m" -> SignalStrength.STRONG
            else -> SignalStrength.UNKNOWN
        }
}

enum class Side {
    BUY, SELL
}

enum class SignalStrength(val weight: Int) {
    WEAK(1),
    MEDIUM(2),
    STRONG(3),
    UNKNOWN(0)
}