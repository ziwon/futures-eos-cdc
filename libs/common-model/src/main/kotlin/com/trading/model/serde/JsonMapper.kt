package com.trading.model.serde

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.trading.model.Signal
import com.trading.model.TradingDecision

/**
 * Centralized JSON mapper for all DTOs
 */
object JsonMapper {
    val mapper: ObjectMapper = jacksonObjectMapper().apply {
        registerModule(JavaTimeModule())
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }

    fun toJson(value: Any): String = mapper.writeValueAsString(value)

    fun toJsonBytes(value: Any): ByteArray = mapper.writeValueAsBytes(value)

    inline fun <reified T> fromJson(json: String): T = mapper.readValue(json)

    inline fun <reified T> fromJsonBytes(bytes: ByteArray): T = mapper.readValue(bytes)

    // Specific methods for common types
    fun signalFromJson(json: String): Signal = fromJson(json)

    fun decisionFromJson(json: String): TradingDecision = fromJson(json)

    fun signalToJson(signal: Signal): String = toJson(signal)

    fun decisionToJson(decision: TradingDecision): String = toJson(decision)
}