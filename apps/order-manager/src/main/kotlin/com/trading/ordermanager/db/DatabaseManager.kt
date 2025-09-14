package com.trading.ordermanager.db

import com.trading.ordermanager.model.Order
import com.trading.ordermanager.model.OutboxEvent
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.Timestamp
import java.util.UUID

class DatabaseManager {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val dataSource: HikariDataSource

    init {
        val config = HikariConfig().apply {
            jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://pg.trading.svc.cluster.local:5432/trading"
            username = System.getenv("DB_USER") ?: "trader"
            password = System.getenv("DB_PASSWORD") ?: "trading123"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
            minimumIdle = 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
            isAutoCommit = false  // Important for transactions
        }
        dataSource = HikariDataSource(config)
        logger.info("Database connection pool initialized")
    }

    fun saveOrderWithOutbox(order: Order, event: OutboxEvent): Boolean {
        var connection: Connection? = null
        return try {
            connection = dataSource.connection
            connection.autoCommit = false

            // Insert order
            val orderSql = """
                INSERT INTO app.orders (
                    id, client_order_id, symbol, side, qty, price, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            connection.prepareStatement(orderSql).use { stmt ->
                stmt.setObject(1, order.id)
                stmt.setString(2, order.clientOrderId)
                stmt.setString(3, order.symbol)
                stmt.setString(4, order.side)
                stmt.setDouble(5, order.qty)
                stmt.setDouble(6, order.price)
                stmt.setString(7, order.status)
                stmt.setTimestamp(8, Timestamp.from(order.createdAt))
                stmt.setTimestamp(9, Timestamp.from(order.updatedAt))
                stmt.executeUpdate()
            }

            // Insert outbox event (occurred_at_ms is a generated column in DB)
            val outboxSql = """
                INSERT INTO app.outbox (
                    event_id, aggregate_type, aggregate_id, type, payload, occurred_at
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?)
            """.trimIndent()

            connection.prepareStatement(outboxSql).use { stmt ->
                stmt.setObject(1, event.eventId)
                stmt.setString(2, event.aggregateType)
                stmt.setObject(3, event.aggregateId)
                stmt.setString(4, event.type)
                stmt.setString(5, event.payload)
                stmt.setTimestamp(6, Timestamp.from(event.occurredAt))
                stmt.executeUpdate()
            }

            // Commit transaction - atomic operation
            connection.commit()
            logger.debug("Order ${order.clientOrderId} saved with outbox event")
            true

        } catch (e: Exception) {
            logger.error("Failed to save order with outbox", e)
            connection?.rollback()
            false
        } finally {
            connection?.close()
        }
    }

    fun getRecentOrders(limit: Int = 10): List<Order> {
        val sql = """
            SELECT id, client_order_id, symbol, side, qty, price, status, created_at, updated_at
            FROM app.orders
            ORDER BY created_at DESC
            LIMIT ?
        """.trimIndent()

        return dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                val orders = mutableListOf<Order>()
                while (rs.next()) {
                    orders.add(
                        Order(
                            id = rs.getObject("id") as UUID,
                            clientOrderId = rs.getString("client_order_id"),
                            symbol = rs.getString("symbol"),
                            side = rs.getString("side"),
                            qty = rs.getDouble("qty"),
                            price = rs.getDouble("price"),
                            status = rs.getString("status"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                            updatedAt = rs.getTimestamp("updated_at").toInstant()
                        )
                    )
                }
                orders
            }
        }
    }

    fun close() {
        dataSource.close()
        logger.info("Database connection pool closed")
    }
}
