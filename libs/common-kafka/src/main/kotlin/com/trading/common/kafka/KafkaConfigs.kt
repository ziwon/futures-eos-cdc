package com.trading.common.kafka

import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.streams.StreamsConfig
import java.util.Properties

object KafkaConfigs {
    fun producer(
        bootstrap: String = env("KAFKA_BOOTSTRAP", "trading-kafka-bootstrap:9092"),
        clientId: String = env("CLIENT_ID", "signal-gen"),
        acks: String = env("ACKS", "all"),
        lingerMs: Long = env("LINGER_MS", "10").toLong(),
        batchSize: Int = env("BATCH_SIZE", "65536").toInt(),
        enableIdempotence: Boolean = env("IDEMPOTENCE", "true").toBoolean(),
    ): Properties = Properties().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(ProducerConfig.CLIENT_ID_CONFIG, clientId)
        put(ProducerConfig.ACKS_CONFIG, acks)
        put(ProducerConfig.LINGER_MS_CONFIG, lingerMs)
        put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize)
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, enableIdempotence)
        put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1)
        put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE)
        put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000)
    }

    fun consumer(
        bootstrap: String = env("KAFKA_BOOTSTRAP", "trading-kafka-bootstrap:9092"),
        groupId: String,
        enableAutoCommit: Boolean = false
    ): Properties = Properties().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    }

    fun streams(
        bootstrap: String = env("KAFKA_BOOTSTRAP", "trading-kafka-bootstrap:9092"),
        appId: String,
        threads: Int = env("STREAM_THREADS", "1").toInt()
    ): Properties = Properties().apply {
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(StreamsConfig.APPLICATION_ID_CONFIG, appId)
        put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, threads)
        put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)
        put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100)
    }

    private fun env(key: String, default: String): String = System.getenv(key) ?: default
}

