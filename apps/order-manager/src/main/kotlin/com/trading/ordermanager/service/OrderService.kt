package com.trading.ordermanager.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.ordermanager.db.DatabaseManager
import com.trading.ordermanager.model.Order
import com.trading.ordermanager.model.OutboxEvent
import com.trading.ordermanager.model.TradingDecision
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import kotlin.math.roundToInt

class OrderService {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(JavaTimeModule())
        // Decisions include extra snake_case fields (suggested_price, suggested_qty, reason, etc.).
        // Ignore unknown props so we can parse only what we need.
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    }

    private val consumer: KafkaConsumer<String, String>
    private val db = DatabaseManager()
    private var running = true
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Configuration
    private val confidenceThreshold = System.getenv("CONFIDENCE_THRESHOLD")?.toDoubleOrNull() ?: 0.65
    private val baseQuantity = System.getenv("BASE_QUANTITY")?.toDoubleOrNull() ?: 1.0
    private val maxQuantity = System.getenv("MAX_QUANTITY")?.toDoubleOrNull() ?: 10.0

    // Simulated prices (in production, would fetch from market data)
    private val marketPrices = mapOf(
        "BTCUSDT" to 65000.0,
        "ETHUSDT" to 3500.0,
        "SOLUSDT" to 150.0,
        "NAS100" to 18500.0
    )

    private var ordersCreated = 0L
    private var decisionsProcessed = 0L

    init {
        val props = Properties().apply {
            put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getenv("KAFKA_BOOTSTRAP") ?: "trading-kafka-bootstrap:9092")
            put(ConsumerConfig.GROUP_ID_CONFIG, System.getenv("ORDER_MANAGER_GROUP_ID") ?: "order-manager")
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false") // Manual commit for EOS
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100")
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
        }
        consumer = KafkaConsumer(props)
        consumer.subscribe(listOf("trading.decisions"))
        logger.info("Order Manager initialized - Confidence threshold: $confidenceThreshold")
    }

    suspend fun start() {
        logger.info("Starting Order Manager service")

        // Start metrics reporter
        scope.launch {
            while (running) {
                delay(30000) // Report every 30 seconds
                logger.info("Metrics - Decisions: $decisionsProcessed, Orders: $ordersCreated, Ratio: ${
                    if (decisionsProcessed > 0) "%.2f%%".format(ordersCreated * 100.0 / decisionsProcessed)
                    else "N/A"
                }")
            }
        }

        // Main processing loop
        withContext(Dispatchers.IO) {
            while (running) {
                try {
                    val records = consumer.poll(Duration.ofMillis(1000))

                    if (!records.isEmpty) {
                        for (record in records) {
                            try {
                                processDecision(record.value())
                                decisionsProcessed++
                            } catch (e: Exception) {
                                logger.error("Error processing decision from offset ${record.offset()}", e)
                            }
                        }

                        // Commit after successful processing (at-least-once)
                        consumer.commitSync()
                        logger.debug("Committed ${records.count()} decisions")
                    }
                } catch (e: Exception) {
                    logger.error("Error in main processing loop", e)
                    delay(5000) // Back off on error
                }
            }
        }
    }

    private fun processDecision(decisionJson: String) {
        val decision = objectMapper.readValue<TradingDecision>(decisionJson)
        logger.debug("Processing decision: ${decision.symbol} - ${decision.action} (confidence: ${decision.confidence})")

        // Only create orders for actionable decisions with sufficient confidence
        if (decision.action in listOf("BUY", "SELL", "STRONG_BUY", "STRONG_SELL")) {
            val adjustedConfidence = if (decision.action.startsWith("STRONG")) {
                decision.confidence * 1.2 // Boost confidence for strong signals
            } else {
                decision.confidence
            }

            if (adjustedConfidence >= confidenceThreshold) {
                createOrder(decision)
            } else {
                logger.debug("Skipping order - confidence ${decision.confidence} below threshold $confidenceThreshold")
            }
        }
    }

    private fun createOrder(decision: TradingDecision) {
        val orderId = UUID.randomUUID()
        val clientOrderId = "ORD-${System.currentTimeMillis()}-${orderId.toString().substring(0, 8)}"

        // Calculate quantity based on confidence
        val quantity = calculateQuantity(decision.confidence)

        // Get simulated market price
        val price = marketPrices[decision.symbol] ?: run {
            logger.warn("No price available for ${decision.symbol}, using default")
            100.0
        }

        // Determine side from action
        val side = when (decision.action) {
            "BUY", "STRONG_BUY" -> "BUY"
            "SELL", "STRONG_SELL" -> "SELL"
            else -> return // Should not happen due to filter above
        }

        val order = Order(
            id = orderId,
            clientOrderId = clientOrderId,
            symbol = decision.symbol,
            side = side,
            qty = quantity,
            price = price,
            status = "PENDING"
        )

        // Create outbox event
        val orderPayload = objectMapper.writeValueAsString(mapOf(
            "orderId" to order.id.toString(),
            "clientOrderId" to order.clientOrderId,
            "symbol" to order.symbol,
            "side" to order.side,
            "qty" to order.qty,
            "price" to order.price,
            "status" to order.status,
            "confidence" to decision.confidence,
            "signals" to (decision.signals?.size ?: 0)
        ))

        val outboxEvent = OutboxEvent(
            aggregateType = "ORDER",
            aggregateId = order.id,
            type = "ORDER_CREATED",
            payload = orderPayload
        )

        // Save atomically with outbox pattern
        if (db.saveOrderWithOutbox(order, outboxEvent)) {
            ordersCreated++
            logger.info("✅ Order created: $clientOrderId - ${order.symbol} ${order.side} ${order.qty} @ ${order.price}")
        } else {
            logger.error("Failed to save order $clientOrderId")
        }
    }

    private fun calculateQuantity(confidence: Double): Double {
        // Linear scaling: confidence 0.65 -> 1.0 maps to quantity baseQuantity -> maxQuantity
        val normalizedConfidence = ((confidence - confidenceThreshold) / (1.0 - confidenceThreshold))
            .coerceIn(0.0, 1.0)

        val quantity = baseQuantity + (maxQuantity - baseQuantity) * normalizedConfidence

        // Round to 2 decimal places
        return (quantity * 100).roundToInt() / 100.0
    }

    suspend fun shutdown() {
        logger.info("Shutting down Order Manager...")
        running = false
        scope.cancel()
        consumer.close()
        db.close()
        logger.info("Order Manager shut down successfully")
    }
}
