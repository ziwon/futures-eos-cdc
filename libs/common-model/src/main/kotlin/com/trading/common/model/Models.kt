package com.trading.common.model

import java.time.Instant

data class Signal(
    val tf: String,           // 1m | 5m | 15m
    val symbol: String,
    val price: Double,
    val volume: Double,
    val trend: Double,        // -1.0 .. 1.0
    val score: Double,        // 0 .. 100
    val occurredAt: Instant = Instant.now(),
    val source: String = "signal-gen"
)

data class Decision(
    val symbol: String,
    val decision: String,     // BUY | SELL | HOLD
    val confidence: Double,   // 0 .. 1
    val reason: String,
    val windowStart: Instant? = null,
    val windowEnd: Instant? = null,
    val strategy: String = "default",
    val occurredAt: Instant = Instant.now()
)

