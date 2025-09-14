package com.trading.kafka.topics

/**
 * Centralized topic definitions
 */
object Topics {
    const val SIGNAL_1M = "trading.signal.1m"
    const val SIGNAL_5M = "trading.signal.5m"
    const val SIGNAL_15M = "trading.signal.15m"
    const val ORDERS = "trading.orders"
    const val DECISIONS = "trading.decisions"

    val ALL_SIGNAL_TOPICS = listOf(SIGNAL_1M, SIGNAL_5M, SIGNAL_15M)

    fun getSignalTopic(timeframe: String): String = when (timeframe) {
        "1m" -> SIGNAL_1M
        "5m" -> SIGNAL_5M
        "15m" -> SIGNAL_15M
        else -> throw IllegalArgumentException("Unknown timeframe: $timeframe")
    }
}