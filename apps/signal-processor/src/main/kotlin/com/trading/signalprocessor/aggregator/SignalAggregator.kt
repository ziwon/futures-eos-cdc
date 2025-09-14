package com.trading.signalprocessor.aggregator

import com.trading.model.Signal
import com.trading.model.Side
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.sqrt

class SignalAggregator {
    private val signalsByTimeframe = ConcurrentHashMap<String, MutableList<Signal>>()
    private val avgPriceByTimeframe = ConcurrentHashMap<String, Double>()
    private val buySellBalance = ConcurrentHashMap<String, Int>()

    fun add(signal: Signal): SignalAggregator {
        // Update signals list
        signalsByTimeframe.compute(signal.timeframe) { _, signals ->
            val list = signals?.toMutableList() ?: mutableListOf()
            list.add(signal)
            // Keep only last 10 signals per timeframe
            if (list.size > 10) list.removeAt(0)
            list
        }

        // Update average price
        avgPriceByTimeframe[signal.timeframe] = signalsByTimeframe[signal.timeframe]
            ?.map { it.price }
            ?.average() ?: signal.price

        // Update buy/sell balance
        buySellBalance.compute(signal.timeframe) { _, balance ->
            val current = balance ?: 0
            current + if (signal.side == Side.BUY) 1 else -1
        }

        return this
    }

    fun hasMinimumSignals(): Boolean = signalsByTimeframe.size >= 2

    fun getSignalCount(): Int = signalsByTimeframe.values.sumOf { it.size }

    fun getAllSignals(): List<Signal> = signalsByTimeframe.values
        .flatten()
        .sortedByDescending { it.ts }

    fun getAveragePrice(): Double =
        if (avgPriceByTimeframe.isEmpty()) 0.0
        else avgPriceByTimeframe.values.average()

    fun getNetBuySellBalance(): Int = buySellBalance.values.sum()

    fun getSignalAlignment(): Double {
        if (signalsByTimeframe.isEmpty()) return 0.0

        val directions = signalsByTimeframe.values.map { signals ->
            signals.groupBy { it.side }
                .maxByOrNull { it.value.size }
                ?.key
        }.toSet()

        return if (directions.size == 1) 1.0 else 0.5
    }

    fun getPriceVolatility(): Map<String, Double> {
        return signalsByTimeframe.entries.associate { (timeframe, signals) ->
            timeframe to calculateVolatility(signals.map { it.price })
        }
    }

    private fun calculateVolatility(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0

        val mean = prices.average()
        val variance = prices.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }

    // Serialization support for Kafka state stores
    data class SerializableState(
        val signalsByTimeframe: Map<String, List<Signal>>,
        val avgPriceByTimeframe: Map<String, Double>,
        val buySellBalance: Map<String, Int>
    )

    fun toSerializable(): SerializableState = SerializableState(
        signalsByTimeframe.toMap(),
        avgPriceByTimeframe.toMap(),
        buySellBalance.toMap()
    )

    companion object {
        fun fromSerializable(state: SerializableState): SignalAggregator {
            return SignalAggregator().apply {
                signalsByTimeframe.putAll(state.signalsByTimeframe.mapValues { it.value.toMutableList() })
                avgPriceByTimeframe.putAll(state.avgPriceByTimeframe)
                buySellBalance.putAll(state.buySellBalance)
            }
        }
    }
}