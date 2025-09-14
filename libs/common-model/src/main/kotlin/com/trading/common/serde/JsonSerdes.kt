package com.trading.common.serde

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.Serdes
import java.nio.charset.StandardCharsets

object ObjectMappers {
    val default: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}

class JsonSerializer<T>(private val mapper: ObjectMapper = ObjectMappers.default) : Serializer<T> {
    override fun serialize(topic: String?, data: T?): ByteArray? =
        data?.let { mapper.writeValueAsBytes(it) }
}

class JsonDeserializer<T>(
    private val clazz: Class<T>,
    private val mapper: ObjectMapper = ObjectMappers.default
) : Deserializer<T> {
    override fun deserialize(topic: String?, data: ByteArray?): T? =
        data?.let { mapper.readValue(String(it, StandardCharsets.UTF_8), clazz) }
}

class JsonSerde<T>(clazz: Class<T>) : Serde<T> {
    private val serializer = JsonSerializer<T>()
    private val deserializer = JsonDeserializer(clazz)
    override fun serializer(): Serializer<T> = serializer
    override fun deserializer(): Deserializer<T> = deserializer
}

object JsonSerdes {
    inline fun <reified T> of(): Serde<T> = JsonSerde(T::class.java)
    fun string(): Serde<String> = Serdes.String()
}

