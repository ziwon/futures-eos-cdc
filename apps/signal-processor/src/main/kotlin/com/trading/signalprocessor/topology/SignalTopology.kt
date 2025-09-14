package com.trading.signalprocessor.topology

import com.trading.kafka.serde.JsonSerdes
import com.trading.kafka.topics.Topics
import com.trading.model.Signal
import com.trading.model.TradingDecision
import com.trading.model.serde.JsonMapper
import com.trading.signalprocessor.aggregator.SignalAggregator
import com.trading.signalprocessor.decision.DecisionMaker
import kotlinx.coroutines.runBlocking
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.*
import org.apache.kafka.streams.state.Stores
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

private val logger = LoggerFactory.getLogger(SignalTopology::class.java)

object SignalTopology {
    private const val AGGREGATED_STORE = "signal-aggregations"

    fun build(): Topology {
        val builder = StreamsBuilder()

        // Add state store for aggregations
        builder.addStateStore(
            Stores.keyValueStoreBuilder(
                Stores.persistentKeyValueStore(AGGREGATED_STORE),
                Serdes.String(),
                Serdes.String()
            )
        )

        // Process each signal stream
        val signals1m = processSignalStream(builder, Topics.SIGNAL_1M, "1m")
        val signals5m = processSignalStream(builder, Topics.SIGNAL_5M, "5m")
        val signals15m = processSignalStream(builder, Topics.SIGNAL_15M, "15m")

        // Merge all signal streams
        val allSignals = signals1m
            .merge(signals5m)
            .merge(signals15m)

        // Group by symbol and window for aggregation
        val windowedSignals = allSignals
            .groupByKey(Grouped.with(Serdes.String(), JsonSerdes.signalSerde()))
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
            .aggregate(
                { SignalAggregator() },
                { _, signal, aggregator -> aggregator.add(signal) },
                Materialized.with(Serdes.String(), aggregatorSerde())
            )

        // Make trading decisions using virtual threads
        windowedSignals
            .toStream()
            .filter { _, aggregator -> aggregator?.hasMinimumSignals() ?: false }
            .mapValues { windowedKey, aggregator ->
                val symbol = windowedKey.key()
                // DecisionMaker uses virtual threads under the hood
                runBlocking {
                    logger.info(
                        "Making decision for {} with {} signals",
                        symbol,
                        aggregator.getSignalCount()
                    )
                    DecisionMaker.decide(symbol, aggregator)
                }
            }
            .filter { _, decision -> decision != null }
            .selectKey { _, decision -> decision!!.symbol }
            .peek { key, decision ->
                logger.info("Decision for {}: {} with confidence {}",
                    key, decision?.action, decision?.confidence)
            }
            .to(Topics.DECISIONS, Produced.with(Serdes.String(), JsonSerdes.decisionSerde()))

        return builder.build()
    }

    private fun processSignalStream(
        builder: StreamsBuilder,
        topic: String,
        timeframe: String
    ): KStream<String, Signal> {
        return builder
            .stream<String, String>(topic, Consumed.with(Serdes.String(), Serdes.String()))
            .flatMapValues { value ->
                try {
                    listOf(JsonMapper.signalFromJson(value).withProcessedAt(Instant.now()))
                } catch (e: Exception) {
                    logger.error("Failed to deserialize signal from {}", topic, e)
                    emptyList()
                }
            }
            .peek { _, signal ->
                logger.debug(
                    "Processing {} signal for {}: {}@{}",
                    timeframe,
                    signal.symbol,
                    signal.side,
                    signal.price
                )
            }
            .selectKey { _, signal -> signal.symbol }
    }

    private fun aggregatorSerde(): Serde<SignalAggregator> {
        return Serdes.serdeFrom(
            { _, data -> JsonMapper.toJsonBytes(data.toSerializable()) },
            { _, bytes ->
                if (bytes == null) null
                else SignalAggregator.fromSerializable(
                    JsonMapper.fromJsonBytes(bytes)
                )
            }
        )
    }
}
