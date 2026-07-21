/*
 * This file is part of GuardAC - https://github.com/PalassCQ/GuardAC
 * Copyright (C) 2026 GuardAC
 *
 * GuardAC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GuardAC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 * This file contains code derived from:
 *   - Shard (© 2025 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.stats

import dev.guardac.GuardAC
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class DailyStats(private val plugin: GuardAC) {

    private val lock = Any()
    private var connection: Connection? = null
    private val todayDetections = AtomicInteger(0)
    private val todayRequests   = AtomicInteger(0)
    @Volatile private var currentDate: String = LocalDate.now().toString()

    fun initialize() {
        try {
            val dataFolder = plugin.dataFolder
            if (!dataFolder.exists()) dataFolder.mkdirs()
            val dbPath = File(dataFolder, "stats.db").absolutePath
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            createTables()
            loadTodayStats()
            plugin.logger.info("[Stats] Statistics database initialized.")
        } catch (e: SQLException) {
            plugin.logger.warning("[Stats] Failed to initialize stats database: ${e.message}")
        }
    }

    private fun createTables() {
        val sql = """
            CREATE TABLE IF NOT EXISTS daily_stats (
                date       TEXT PRIMARY KEY,
                detections INTEGER DEFAULT 0,
                requests   INTEGER DEFAULT 0
            )
        """.trimIndent()
        synchronized(lock) {
            connection?.createStatement()?.use { it.execute(sql) }
        }
    }

    private fun loadTodayStats() {
        val sql = "SELECT detections, requests FROM daily_stats WHERE date = ?"
        try {
            synchronized(lock) {
                connection?.prepareStatement(sql)?.use { ps ->
                    ps.setString(1, currentDate)
                    val rs = ps.executeQuery()
                    if (rs.next()) {
                        todayDetections.set(rs.getInt("detections"))
                        todayRequests.set(rs.getInt("requests"))
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[Stats] Failed to load today stats: ${e.message}")
        }
    }

    fun recordDetection() {
        checkDateRollover()
        todayDetections.incrementAndGet()
        saveAsync()
    }

    fun recordRequest() {
        checkDateRollover()
        todayRequests.incrementAndGet()
    }

    fun getTodayDetections(): Int = todayDetections.get()
    fun getTodayRequests(): Int   = todayRequests.get()

    private fun checkDateRollover() {
        val today = LocalDate.now().toString()
        if (today != currentDate) {
            currentDate = today
            todayDetections.set(0)
            todayRequests.set(0)
        }
    }

    private fun saveAsync() {
        plugin.scheduler.async(Runnable {
            val sql = """
                INSERT INTO daily_stats (date, detections, requests) VALUES (?, ?, ?)
                ON CONFLICT(date) DO UPDATE SET
                    detections = excluded.detections,
                    requests   = excluded.requests
            """.trimIndent()
            try {
                synchronized(lock) {
                    connection?.prepareStatement(sql)?.use { ps ->
                        ps.setString(1, currentDate)
                        ps.setInt(2, todayDetections.get())
                        ps.setInt(3, todayRequests.get())
                        ps.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.warning("[Stats] Failed to save stats: ${e.message}")
            }
        })
    }

    fun shutdown() {

        val sql = """
            INSERT INTO daily_stats (date, detections, requests) VALUES (?, ?, ?)
            ON CONFLICT(date) DO UPDATE SET
                detections = excluded.detections,
                requests   = excluded.requests
        """.trimIndent()
        synchronized(lock) {
            try {
                connection?.prepareStatement(sql)?.use { ps ->
                    ps.setString(1, currentDate)
                    ps.setInt(2, todayDetections.get())
                    ps.setInt(3, todayRequests.get())
                    ps.executeUpdate()
                }
            } catch (_: SQLException) {}
            try { connection?.close() } catch (_: SQLException) {}
            connection = null
        }
    }
}
