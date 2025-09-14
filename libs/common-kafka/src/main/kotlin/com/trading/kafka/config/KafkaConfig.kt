package com.trading.kafka.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import java.util.Properties

/**
 * Centralized Kafka configuration builder
 */
object KafkaConfig {

    fun streamsProperties(
        applicationId: String,
        bootstrapServers: String = System.getenv("KAFKA_BOOTSTRAP") ?: "trading-kafka-kafka-bootstrap:9092",
        enableEOS: Boolean = true,
        numThreads: Int = 4
    ): Properties = Properties().apply {
        // Basic configs
        put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId)
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)

        // EOS configuration
        if (enableEOS) {
            put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
            put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3)
            put(StreamsConfig.producerPrefix(ProducerConfig.ACKS_CONFIG), "all")
            put(StreamsConfig.producerPrefix(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG), true)
        }

        // Performance tuning
        put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, numThreads)
        put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000)
        put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024L)

        // Consumer configs
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500)

        // State store configs
        put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams")
        put(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, StreamsConfig.OPTIMIZE)

        // Default serdes
        put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().javaClass)
        put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().javaClass)
    }

    fun producerProperties(
        bootstrapServers: String = System.getenv("KAFKA_BOOTSTRAP") ?: "trading-kafka-kafka-bootstrap:9092",
        enableIdempotence: Boolean = true
    ): Properties = Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.RETRIES_CONFIG, 3)
        put(ProducerConfig.LINGER_MS_CONFIG, 10)
        put(ProducerConfig.BATCH_SIZE_CONFIG, 16384)
        put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy")

        if (enableIdempotence) {
            put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        }
    }

    fun consumerProperties(
        groupId: String,
        bootstrapServers: String = System.getenv("KAFKA_BOOTSTRAP") ?: "trading-kafka-kafka-bootstrap:9092"
    ): Properties = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true)
        put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000)
    }
}