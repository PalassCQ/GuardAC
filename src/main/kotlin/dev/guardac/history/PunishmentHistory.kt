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
 */

package dev.guardac.history

import dev.guardac.GuardAC
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class PunishmentHistory(private val plugin: GuardAC) {

    data class Entry(
        val playerName: String,
        val check: String,
        val vl: Int,
        val probability: Double,
        val action: String,
        val epochMillis: Long,
    )

    data class AiResult(
        val uuid: String,
        val playerName: String,
        val model: String,
        val probability: Double,
        val epochMillis: Long,
    )

    private val lock = Any()
    private var connection: Connection? = null
    private val resultInserts = AtomicLong(0)

    fun initialize() {
        try {
            val dataFolder = plugin.dataFolder.also { if (!it.exists()) it.mkdirs() }
            val dbPath = File(dataFolder, "history.db").absolutePath
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            synchronized(lock) {
                connection?.createStatement()?.use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS punishments (
                            id          INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid        TEXT    NOT NULL,
                            name        TEXT    NOT NULL,
                            check_name  TEXT    NOT NULL,
                            vl          INTEGER NOT NULL,
                            probability REAL    NOT NULL,
                            action      TEXT    NOT NULL,
                            ts          INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_punish_uuid ON punishments(uuid)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_punish_name ON punishments(name)")

                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS buffers (
                            uuid   TEXT    PRIMARY KEY,
                            buffer REAL    NOT NULL,
                            vl     INTEGER NOT NULL,
                            ts     INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    val ttlMs = (plugin.configManager.persistBufferTtlMinutes * 60_000.0).toLong()
                    if (ttlMs > 0) {
                        st.executeUpdate(
                            "DELETE FROM buffers WHERE ts < ${Instant.now().toEpochMilli() - ttlMs}"
                        )
                    }

                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS results (
                            id    INTEGER PRIMARY KEY AUTOINCREMENT,
                            uuid  TEXT    NOT NULL,
                            name  TEXT    NOT NULL,
                            model TEXT    NOT NULL,
                            prob  REAL    NOT NULL,
                            ts    INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_results_uuid ON results(uuid)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_results_name ON results(name)")
                    st.executeUpdate(
                        "DELETE FROM results WHERE ts < ${Instant.now().toEpochMilli() - RESULTS_TTL_MS}"
                    )
                }
            }
            plugin.logger.info("[History] Punishment history database initialized.")
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to initialize history database: ${e.message}")
        }
    }

    fun record(uuid: UUID, name: String, check: String, vl: Int, probability: Double, action: String) {
        if (connection == null) return
        val ts = Instant.now().toEpochMilli()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                synchronized(lock) {
                    val conn = connection ?: return@Runnable
                    conn.prepareStatement(
                        "INSERT INTO punishments (uuid, name, check_name, vl, probability, action, ts) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)"
                    ).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.setString(2, name)
                        ps.setString(3, check)
                        ps.setInt(4, vl)
                        ps.setDouble(5, probability)
                        ps.setString(6, action)
                        ps.setLong(7, ts)
                        ps.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.warning("[History] Failed to record punishment: ${e.message}")
            }
        })
    }

    fun forPlayer(name: String, limit: Int): List<Entry> =
        query("SELECT name, check_name, vl, probability, action, ts FROM punishments " +
              "WHERE name = ? COLLATE NOCASE ORDER BY ts DESC LIMIT ?") { ps ->
            ps.setString(1, name)
            ps.setInt(2, limit)
        }

    fun recent(limit: Int): List<Entry> =
        query("SELECT name, check_name, vl, probability, action, ts FROM punishments " +
              "ORDER BY ts DESC LIMIT ?") { ps ->
            ps.setInt(1, limit)
        }

    private inline fun query(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<Entry> {
        val result = ArrayList<Entry>()
        try {
            synchronized(lock) {
                val conn = connection ?: return emptyList()
                conn.prepareStatement(sql).use { ps ->
                    bind(ps)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            result.add(
                                Entry(
                                    playerName  = rs.getString("name"),
                                    check       = rs.getString("check_name"),
                                    vl          = rs.getInt("vl"),
                                    probability = rs.getDouble("probability"),
                                    action      = rs.getString("action"),
                                    epochMillis = rs.getLong("ts"),
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Query failed: ${e.message}")
        }
        return result
    }

    data class BufferRecord(val buffer: Double, val vl: Int, val epochMillis: Long)

    fun saveBuffer(uuid: UUID, buffer: Double, vl: Int) {
        if (connection == null) return
        if (!plugin.isEnabled) {

            saveBufferNow(uuid, buffer, vl)
            return
        }
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            saveBufferNow(uuid, buffer, vl)
        })
    }

    fun saveBufferNow(uuid: UUID, buffer: Double, vl: Int) {
        try {
            synchronized(lock) {
                val conn = connection ?: return
                conn.prepareStatement(
                    "INSERT INTO buffers (uuid, buffer, vl, ts) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET buffer=excluded.buffer, vl=excluded.vl, ts=excluded.ts"
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.setDouble(2, buffer)
                    ps.setInt(3, vl)
                    ps.setLong(4, Instant.now().toEpochMilli())
                    ps.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to save buffer: ${e.message}")
        }
    }

    fun loadBuffer(uuid: UUID): BufferRecord? {
        try {
            synchronized(lock) {
                val conn = connection ?: return null
                conn.prepareStatement("SELECT buffer, vl, ts FROM buffers WHERE uuid = ?").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            return BufferRecord(rs.getDouble("buffer"), rs.getInt("vl"), rs.getLong("ts"))
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to load buffer: ${e.message}")
        }
        return null
    }

    fun clearBuffer(uuid: UUID) {
        try {
            synchronized(lock) {
                val conn = connection ?: return
                conn.prepareStatement("DELETE FROM buffers WHERE uuid = ?").use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to clear buffer: ${e.message}")
        }
    }

    fun recordResult(uuid: UUID, name: String, model: String, probability: Double) {
        if (connection == null) return
        val ts = Instant.now().toEpochMilli()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                synchronized(lock) {
                    val conn = connection ?: return@Runnable
                    conn.prepareStatement(
                        "INSERT INTO results (uuid, name, model, prob, ts) VALUES (?, ?, ?, ?, ?)"
                    ).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.setString(2, name)
                        ps.setString(3, model)
                        ps.setDouble(4, probability)
                        ps.setLong(5, ts)
                        ps.executeUpdate()
                    }
                    // Results arrive every few seconds per fighting player, so the
                    // per-player tail is trimmed periodically, not on every insert.
                    if (resultInserts.incrementAndGet() % RESULTS_TRIM_EVERY == 0L) {
                        conn.prepareStatement(
                            "DELETE FROM results WHERE uuid = ? AND id NOT IN " +
                                "(SELECT id FROM results WHERE uuid = ? ORDER BY id DESC LIMIT ?)"
                        ).use { ps ->
                            ps.setString(1, uuid.toString())
                            ps.setString(2, uuid.toString())
                            ps.setInt(3, RESULTS_CAP_PER_PLAYER)
                            ps.executeUpdate()
                        }
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.warning("[History] Failed to record AI result: ${e.message}")
            }
        })
    }

    fun resultsFor(name: String, limit: Int): List<AiResult> {
        val out = ArrayList<AiResult>()
        try {
            synchronized(lock) {
                val conn = connection ?: return emptyList()
                conn.prepareStatement(
                    "SELECT uuid, name, model, prob, ts FROM results " +
                        "WHERE name = ? COLLATE NOCASE ORDER BY ts DESC LIMIT ?"
                ).use { ps ->
                    ps.setString(1, name)
                    ps.setInt(2, limit)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            out.add(
                                AiResult(
                                    uuid        = rs.getString("uuid"),
                                    playerName  = rs.getString("name"),
                                    model       = rs.getString("model"),
                                    probability = rs.getDouble("prob"),
                                    epochMillis = rs.getLong("ts"),
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Results query failed: ${e.message}")
        }
        return out
    }

    fun shutdown() {
        synchronized(lock) {
            try { connection?.close() } catch (_: SQLException) {}
            connection = null
        }
    }

    private companion object {
        // AI window results: per-player cap, periodic trim, and a hard age limit
        // so the table never grows unbounded on a busy server.
        const val RESULTS_CAP_PER_PLAYER = 450
        const val RESULTS_TRIM_EVERY     = 64L
        const val RESULTS_TTL_MS         = 7L * 24 * 60 * 60 * 1000
    }
}
