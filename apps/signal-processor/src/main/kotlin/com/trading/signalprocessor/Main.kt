package com.trading.signalprocessor

import com.trading.kafka.config.KafkaConfig
import com.trading.signalprocessor.topology.SignalTopology
import kotlinx.coroutines.*
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("SignalProcessor")

class SignalProcessor {
    private val streams: KafkaStreams
    private val latch = CountDownLatch(1)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisorJob)

    init {
        val appId = System.getenv("APP_ID") ?: "signal-processor"
        val numThreads = System.getenv("STREAM_THREADS")?.toIntOrNull() ?: 4

        val props = KafkaConfig.streamsProperties(
            applicationId = appId,
            enableEOS = true,
            numThreads = numThreads
        )

        val topology = SignalTopology.build()
        logger.info("Starting Signal Processor with topology:\n{}", topology.describe())

        streams = KafkaStreams(topology, props).apply {
            setUncaughtExceptionHandler { exception ->
                logger.error("Uncaught exception in Kafka Streams", exception)
                StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse.SHUTDOWN_CLIENT
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    }

    fun start() {
        logger.info("Starting Signal Processor with EOS enabled")

        // State listener on virtual thread
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            executor.submit {
                streams.setStateListener { newState, oldState ->
                    logger.info("State transition: {} -> {}", oldState, newState)
                    if (newState == KafkaStreams.State.ERROR) {
                        latch.countDown()
                    }
                }
            }
        }

        streams.start()

        // Start metrics reporter with coroutines
        scope.launch {
            reportMetrics()
        }
    }

    private suspend fun reportMetrics() = coroutineScope {
        while (isActive) {
            delay(30_000)
            withContext(Dispatchers.IO) {
                try {
                    val metrics = streams.metrics()
                    val activeTaskRatio = (metrics.values
                        .find { it.metricName().name() == "task-active-ratio" }
                        ?.metricValue() as? Number)?.toDouble() ?: 0.0

                    val recordsProcessed = (metrics.values
                        .find { it.metricName().name() == "process-total" }
                        ?.metricValue() as? Number)?.toDouble() ?: 0.0

                    logger.info(
                        "Metrics - Active tasks: {}, Records processed: {}",
                        activeTaskRatio,
                        recordsProcessed
                    )
                } catch (e: Exception) {
                    logger.error("Failed to collect metrics", e)
                }
            }
        }
    }

    fun shutdown() {
        logger.info("Shutting down Signal Processor")
        runBlocking {
            supervisorJob.cancelAndJoin()
        }
        streams.close(Duration.ofSeconds(30))
        latch.countDown()
    }

    fun await() {
        latch.await()
    }
}

fun main() = runBlocking {
    try {
        logger.info("Signal Processor starting...")
        val processor = SignalProcessor()
        processor.start()
        processor.await()
    } catch (e: Exception) {
        logger.error("Failed to run Signal Processor", e)
        exitProcess(1)
    }
}
