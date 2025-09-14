package com.trading.ordermanager

import com.trading.ordermanager.service.OrderService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("OrderManager")

fun main() {
    logger.info("Starting Order Manager with Outbox Pattern")

    val latch = CountDownLatch(1)
    val orderService = OrderService()

    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info("Shutting down Order Manager...")
        runBlocking {
            orderService.shutdown()
        }
        latch.countDown()
    })

    try {
        runBlocking {
            orderService.start()
        }
        latch.await()
    } catch (e: Exception) {
        logger.error("Fatal error in Order Manager", e)
        exitProcess(1)
    }
}