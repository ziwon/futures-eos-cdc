package com.trading.kafka.serde

import com.trading.model.Signal
import com.trading.model.TradingDecision
import com.trading.model.serde.JsonMapper
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.slf4j.LoggerFactory

/**
 * Kafka Serdes for trading models
 */
object JsonSerdes {
    private val logger = LoggerFactory.getLogger(JsonSerdes::class.java)

    fun signalSerde(): Serde<Signal> = JsonSerde(Signal::class.java)
    fun decisionSerde(): Serde<TradingDecision> = JsonSerde(TradingDecision::class.java)

    private class JsonSerde<T : Any>(private val clazz: Class<T>) : Serde<T> {
        override fun serializer() = Serializer<T> { _, data ->
            try {
                JsonMapper.toJsonBytes(data)
            } catch (e: Exception) {
                logger.error("Serialization error for ${clazz.simpleName}", e)
                throw e
            }
        }

        override fun deserializer() = Deserializer<T> { _, data ->
            if (data == null) return@Deserializer null
            try {
                when (clazz) {
                    Signal::class.java -> JsonMapper.fromJsonBytes<Signal>(data) as T
                    TradingDecision::class.java -> JsonMapper.fromJsonBytes<TradingDecision>(data) as T
                    else -> throw IllegalArgumentException("Unknown type: $clazz")
                }
            } catch (e: Exception) {
                logger.error("Deserialization error for ${clazz.simpleName}", e)
                null
            }
        }
    }
}