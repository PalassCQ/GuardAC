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
 *   - Shard (© 2026 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
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

    private class PendingResult(
        val uuid: String, val name: String, val model: String,
        val probability: Double, val ts: Long,
    )

    private val pendingResults = java.util.concurrent.ConcurrentLinkedQueue<PendingResult>()
    private val pendingCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var flushTask: dev.guardac.util.TaskHandle? = null

    fun initialize() {
        try {
            val dataFolder = plugin.dataFolder.also { if (!it.exists()) it.mkdirs() }
            val dbPath = File(dataFolder, "history.db").absolutePath
            connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            synchronized(lock) {
                connection?.createStatement()?.use { st ->
                    runCatching {
                        st.execute("PRAGMA journal_mode=WAL")
                        st.execute("PRAGMA synchronous=NORMAL")
                        st.execute("PRAGMA busy_timeout=5000")
                    }
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
                        CREATE TABLE IF NOT EXISTS staff_prefs (
                            uuid        TEXT    PRIMARY KEY,
                            alerts      INTEGER NOT NULL,
                            monitor     INTEGER NOT NULL,
                            overhead    INTEGER NOT NULL,
                            crossserver INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

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
            startResultFlusher()
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to initialize history database: ${e.message}")
        }
    }

    private fun startResultFlusher() {
        flushTask?.cancel()
        flushTask = plugin.scheduler.asyncTimer(RESULT_FLUSH_TICKS, RESULT_FLUSH_TICKS) { flushResults() }
    }

    fun record(uuid: UUID, name: String, check: String, vl: Int, probability: Double, action: String) {
        if (connection == null) return
        val ts = Instant.now().toEpochMilli()
        plugin.scheduler.async(Runnable {
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
        plugin.scheduler.async(Runnable {
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

    val isAvailable: Boolean get() = connection != null

    class StaffPrefs(
        val alerts: Boolean,
        val monitor: Boolean,
        val overhead: Boolean,
        val crossServer: Boolean,
    )

    fun saveStaffPrefs(uuid: UUID, prefs: StaffPrefs) {
        if (connection == null) return
        val write = Runnable {
            try {
                synchronized(lock) {
                    val conn = connection ?: return@Runnable
                    conn.prepareStatement(
                        "INSERT INTO staff_prefs (uuid, alerts, monitor, overhead, crossserver) " +
                            "VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET " +
                            "alerts=excluded.alerts, monitor=excluded.monitor, " +
                            "overhead=excluded.overhead, crossserver=excluded.crossserver"
                    ).use { ps ->
                        ps.setString(1, uuid.toString())
                        ps.setInt(2, if (prefs.alerts) 1 else 0)
                        ps.setInt(3, if (prefs.monitor) 1 else 0)
                        ps.setInt(4, if (prefs.overhead) 1 else 0)
                        ps.setInt(5, if (prefs.crossServer) 1 else 0)
                        ps.executeUpdate()
                    }
                }
            } catch (e: SQLException) {
                plugin.logger.warning("[History] Failed to save staff preferences: ${e.message}")
            }
        }
        if (!plugin.isEnabled) write.run() else plugin.scheduler.async(write)
    }

    fun loadStaffPrefs(uuid: UUID): StaffPrefs? {
        try {
            synchronized(lock) {
                val conn = connection ?: return null
                conn.prepareStatement(
                    "SELECT alerts, monitor, overhead, crossserver FROM staff_prefs WHERE uuid = ?"
                ).use { ps ->
                    ps.setString(1, uuid.toString())
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            return StaffPrefs(
                                alerts      = rs.getInt("alerts") != 0,
                                monitor     = rs.getInt("monitor") != 0,
                                overhead    = rs.getInt("overhead") != 0,
                                crossServer = rs.getInt("crossserver") != 0,
                            )
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to load staff preferences: ${e.message}")
        }
        return null
    }

    fun recordResult(uuid: UUID, name: String, model: String, probability: Double) {
        if (connection == null) return
        if (pendingCount.get() >= RESULTS_QUEUE_MAX) {
            if (pendingResults.poll() != null) pendingCount.decrementAndGet()
        }
        pendingResults.add(
            PendingResult(uuid.toString(), name, model, probability, Instant.now().toEpochMilli())
        )
        pendingCount.incrementAndGet()
    }

    private fun flushResults() {
        if (pendingCount.get() == 0) return
        val batch = ArrayList<PendingResult>(RESULTS_FLUSH_MAX)
        while (batch.size < RESULTS_FLUSH_MAX) {
            val r = pendingResults.poll() ?: break
            pendingCount.decrementAndGet()
            batch.add(r)
        }
        if (batch.isEmpty()) return

        try {
            synchronized(lock) {
                val conn = connection ?: return
                val autoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        "INSERT INTO results (uuid, name, model, prob, ts) VALUES (?, ?, ?, ?, ?)"
                    ).use { ps ->
                        for (r in batch) {
                            ps.setString(1, r.uuid)
                            ps.setString(2, r.name)
                            ps.setString(3, r.model)
                            ps.setDouble(4, r.probability)
                            ps.setLong(5, r.ts)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                    conn.commit()
                } catch (e: SQLException) {
                    runCatching { conn.rollback() }
                    throw e
                } finally {
                    conn.autoCommit = autoCommit
                }

                if (resultInserts.addAndGet(batch.size.toLong()) >= RESULTS_TRIM_EVERY) {
                    resultInserts.set(0)
                    trimResults(conn, batch)
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Failed to record AI results: ${e.message}")
        }
    }

    private fun trimResults(conn: Connection, batch: List<PendingResult>) {
        val uuids = HashSet<String>(batch.size)
        for (r in batch) uuids.add(r.uuid)
        conn.prepareStatement(
            "DELETE FROM results WHERE uuid = ? AND id NOT IN " +
                "(SELECT id FROM results WHERE uuid = ? ORDER BY id DESC LIMIT ?)"
        ).use { ps ->
            for (uuid in uuids) {
                ps.setString(1, uuid)
                ps.setString(2, uuid)
                ps.setInt(3, RESULTS_CAP_PER_PLAYER)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    data class TopEntry(
        val playerName: String,
        val hits: Int,
        val total: Int,
        val avgProbability: Double,
        val maxProbability: Double,
    )

    fun topSuspects(sinceMs: Long, minProbability: Double, limit: Int): List<TopEntry> {
        val out = ArrayList<TopEntry>()
        try {
            synchronized(lock) {
                val conn = connection ?: return emptyList()
                conn.prepareStatement(
                    "SELECT name, " +
                        "SUM(CASE WHEN prob >= ? THEN 1 ELSE 0 END) AS hits, " +
                        "COUNT(*) AS total, AVG(prob) AS avg_prob, MAX(prob) AS max_prob " +
                        "FROM results WHERE ts >= ? GROUP BY name COLLATE NOCASE " +
                        "HAVING hits > 0 ORDER BY hits DESC, max_prob DESC LIMIT ?"
                ).use { ps ->
                    ps.setDouble(1, minProbability)
                    ps.setLong(2, sinceMs)
                    ps.setInt(3, limit)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            out.add(
                                TopEntry(
                                    playerName     = rs.getString("name"),
                                    hits           = rs.getInt("hits"),
                                    total          = rs.getInt("total"),
                                    avgProbability = rs.getDouble("avg_prob"),
                                    maxProbability = rs.getDouble("max_prob"),
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: SQLException) {
            plugin.logger.warning("[History] Top query failed: ${e.message}")
        }
        return out
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
        flushTask?.cancel()
        flushTask = null
        runCatching {
            var rounds = 0
            while (pendingCount.get() > 0 && rounds++ < SHUTDOWN_FLUSH_ROUNDS) {
                if (pendingResults.peek() == null) break
                flushResults()
            }
        }
        synchronized(lock) {
            try { connection?.close() } catch (_: SQLException) {}
            connection = null
        }
    }

    private companion object {

        const val RESULTS_CAP_PER_PLAYER = 450
        const val RESULTS_TRIM_EVERY     = 512L
        const val RESULTS_TTL_MS         = 7L * 24 * 60 * 60 * 1000

        const val RESULT_FLUSH_TICKS = 40L
        const val RESULTS_FLUSH_MAX  = 512
        const val RESULTS_QUEUE_MAX  = 20_000
        const val SHUTDOWN_FLUSH_ROUNDS = 64
    }
}
