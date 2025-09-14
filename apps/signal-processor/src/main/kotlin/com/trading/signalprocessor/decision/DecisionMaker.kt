package com.trading.signalprocessor.decision

import com.trading.model.TradingDecision
import com.trading.signalprocessor.aggregator.SignalAggregator
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import kotlin.random.Random

private val logger = LoggerFactory.getLogger(DecisionMaker::class.java)

object DecisionMaker {
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor()
        .asCoroutineDispatcher()

    suspend fun decide(
        symbol: String,
        aggregator: SignalAggregator
    ): TradingDecision? = coroutineScope {
        val signals = aggregator.getAllSignals()
        if (signals.isEmpty()) {
            logger.warn("No signals available for {}", symbol)
            return@coroutineScope null
        }

        // Parallel analysis using virtual threads
        val alignmentDeferred = async(virtualThreadDispatcher) { aggregator.getSignalAlignment() }
        val balanceDeferred = async(virtualThreadDispatcher) { aggregator.getNetBuySellBalance() }
        val volatilityDeferred = async(virtualThreadDispatcher) { aggregator.getPriceVolatility() }

        val alignment = alignmentDeferred.await()
        val balance = balanceDeferred.await()
        val volatility = volatilityDeferred.await()

        makeDecision(symbol, aggregator, alignment, balance, volatility)
    }

    private fun makeDecision(
        symbol: String,
        aggregator: SignalAggregator,
        alignment: Double,
        balance: Int,
        volatility: Map<String, Double>
    ): TradingDecision {
        val avgPrice = aggregator.getAveragePrice()
        val signals = aggregator.getAllSignals()
        val suggestedQty = calculateQuantity(signals.map { it.qty })

        val (action, confidence, priceAdjustment, reason) = when {
            balance > 5 -> {
                logger.debug("Strong buy signal for {}: balance={}, alignment={}", symbol, balance, alignment)
                val conf = calculateConfidence(alignment, balance, volatility)
                val reason = if (alignment > 0.8) {
                    TradingDecision.DecisionReason.ALIGNED_SIGNALS
                } else {
                    TradingDecision.DecisionReason.MOMENTUM_SHIFT
                }
                Tuple4(TradingDecision.Action.STRONG_BUY, conf, 0.998, reason)
            }
            balance > 2 -> {
                logger.debug("Buy signal for {}: balance={}, alignment={}", symbol, balance, alignment)
                val conf = calculateConfidence(alignment, balance, volatility)
                Tuple4(TradingDecision.Action.BUY, conf, 0.999, TradingDecision.DecisionReason.ALIGNED_SIGNALS)
            }
            balance < -5 -> {
                logger.debug("Strong sell signal for {}: balance={}, alignment={}", symbol, balance, alignment)
                val conf = calculateConfidence(alignment, -balance, volatility)
                val reason = if (alignment > 0.8) {
                    TradingDecision.DecisionReason.ALIGNED_SIGNALS
                } else {
                    TradingDecision.DecisionReason.MOMENTUM_SHIFT
                }
                Tuple4(TradingDecision.Action.STRONG_SELL, conf, 1.002, reason)
            }
            balance < -2 -> {
                logger.debug("Sell signal for {}: balance={}, alignment={}", symbol, balance, alignment)
                val conf = calculateConfidence(alignment, -balance, volatility)
                Tuple4(TradingDecision.Action.SELL, conf, 1.001, TradingDecision.DecisionReason.ALIGNED_SIGNALS)
            }
            else -> {
                logger.debug("Hold signal for {}: balance={}, alignment={}", symbol, balance, alignment)
                val reason = if (alignment < 0.6) {
                    TradingDecision.DecisionReason.DIVERGENT_SIGNALS
                } else {
                    TradingDecision.DecisionReason.INSUFFICIENT_DATA
                }
                Tuple4(TradingDecision.Action.HOLD, 0.5, 1.0, reason)
            }
        }

        return TradingDecision.create(
            symbol = symbol,
            action = action,
            confidence = confidence,
            price = avgPrice * priceAdjustment,
            qty = if (action == TradingDecision.Action.HOLD) 0.0 else suggestedQty,
            signals = signals,
            reason = reason
        )
    }

    private fun calculateConfidence(
        alignment: Double,
        absBalance: Int,
        volatility: Map<String, Double>
    ): Double {
        val avgVolatility = volatility.values
            .filter { it.isFinite() }
            .average()
            .takeIf { it.isFinite() } ?: 0.1

        val volatilityFactor = maxOf(0.3, 1.0 - (avgVolatility / 100))
        val balanceFactor = minOf(1.0, absBalance / 10.0)

        return minOf(1.0, alignment * 0.4 + balanceFactor * 0.4 + volatilityFactor * 0.2)
    }

    private fun calculateQuantity(quantities: List<Double>): Double {
        val avgQty = quantities.take(5)
            .average()
            .takeIf { it.isFinite() } ?: 0.1

        val randomFactor = 0.9 + Random.nextDouble() * 0.2
        return (avgQty * randomFactor * 10000).toInt() / 10000.0
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}