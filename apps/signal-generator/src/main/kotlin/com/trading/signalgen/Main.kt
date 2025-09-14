package com.trading.signalgen

import com.trading.kafka.config.KafkaConfig
import com.trading.kafka.topics.Topics
import com.trading.model.Signal
import com.trading.model.Side
import com.trading.model.serde.JsonMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("SignalGenerator")

class SignalGenerator {
    private val producer: KafkaProducer<String, String>
    private val virtualThreadDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Configuration
    private val symbols = (System.getenv("SYMBOLS") ?: "BTCUSDT,ETHUSDT,SOLUSDT,XRPUSDT,NAS100")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private val timeframes = when (val tf = System.getenv("TF") ?: "all") {
        "all" -> listOf("1m", "5m", "15m")
        else -> listOf(tf)
    }

    private val ratePerSec = System.getenv("RATE_PER_SEC")?.toIntOrNull() ?: 5
    private val durationSec = System.getenv("DURATION_SEC")?.toIntOrNull() ?: 60

    // Base prices for realistic simulation
    private val basePrices = mapOf(
        "BTCUSDT" to 65000.0,
        "ETHUSDT" to 3000.0,
        "SOLUSDT" to 160.0,
        "XRPUSDT" to 0.6,
        "NAS100" to 20000.0
    )

    init {
        val props = KafkaConfig.producerProperties(enableIdempotence = true)
        producer = KafkaProducer(props)
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    }

    fun start() {
        logger.info("Starting Signal Generator - Timeframes: {}, Symbols: {}, Rate: {}/s, Duration: {}s",
            timeframes, symbols, ratePerSec, durationSec)

        runBlocking {
            generateSignals()
        }
    }

    private suspend fun generateSignals() = coroutineScope {
        val signalChannel = Channel<Pair<Signal, String>>(Channel.BUFFERED)
        val startTime = System.currentTimeMillis()
        var totalSent = 0

        // Producer coroutine
        val producerJob = launch {
            while ((System.currentTimeMillis() - startTime) / 1000 < durationSec) {
                repeat(ratePerSec) {
                    val symbol = symbols.random()
                    val timeframe = timeframes.random()
                    val signal = generateSignal(symbol, timeframe)
                    signalChannel.send(signal to timeframe)
                }
                delay(1000)
            }
            signalChannel.close()
        }

        // Consumer coroutines for parallel sending
        val consumers = (1..4).map {
            launch(virtualThreadDispatcher) {
                for ((signal, timeframe) in signalChannel) {
                    sendSignal(signal, timeframe)
                    totalSent++
                }
            }
        }

        producerJob.join()
        consumers.joinAll()

        logger.info("Signal generation completed: {} signals sent in {} seconds",
            totalSent, (System.currentTimeMillis() - startTime) / 1000)
    }

    private fun generateSignal(symbol: String, timeframe: String): Signal {
        val basePrice = basePrices[symbol] ?: 100.0
        val price = basePrice * (1 + Random.nextDouble(-0.003, 0.003)) // ±0.3% jitter
        val side = if (Random.nextBoolean()) Side.BUY else Side.SELL
        val qty = Random.nextDouble(0.01, 0.5)

        return Signal(
            symbol = symbol,
            side = side,
            qty = qty,
            price = price,
            timeframe = timeframe,
            ts = System.currentTimeMillis(),
            processedAt = Instant.now()
        )
    }

    private suspend fun sendSignal(signal: Signal, timeframe: String) {
        val topic = Topics.getSignalTopic(timeframe)
        val json = JsonMapper.signalToJson(signal)
        val record = ProducerRecord(topic, signal.symbol, json)

        withContext(Dispatchers.IO) {
            producer.send(record) { metadata, exception ->
                if (exception != null) {
                    logger.error("Failed to send signal to {}", topic, exception)
                } else {
                    logger.trace("Signal sent to {} partition {} offset {}",
                        metadata.topic(), metadata.partition(), metadata.offset())
                }
            }
        }
    }

    fun shutdown() {
        logger.info("Shutting down Signal Generator")
        runBlocking {
            scope.cancel()
        }
        producer.close(Duration.ofSeconds(10))
    }
}

fun main() {
    try {
        val generator = SignalGenerator()
        generator.start()
    } catch (e: Exception) {
        logger.error("Failed to run Signal Generator", e)
        exitProcess(1)
    }
}

