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
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.dataset

import dev.guardac.GuardAC
import dev.guardac.data.DataSession
import dev.guardac.data.TickData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class DataCollectorManager(private val plugin: GuardAC) {

    val activeSessions: MutableMap<UUID, DataSession> = ConcurrentHashMap()

    private val saveExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "guardac-dc-save").also { it.isDaemon = true }
        }

    fun startCollecting(uuid: UUID, playerName: String, status: String): Boolean {
        var isNew          = true
        var alreadyRunning = false

        activeSessions.compute(uuid) { _, existing ->
            when {
                existing == null          -> DataSession(uuid, playerName, status)
                existing.status == status -> { alreadyRunning = true; existing }
                else                      -> { saveAsync(uuid, existing); isNew = false; DataSession(uuid, playerName, status) }
            }
        }
        return !alreadyRunning && isNew
    }

    fun stopCollecting(uuid: UUID): Boolean {
        val session = activeSessions.remove(uuid) ?: return false
        saveAsync(uuid, session)
        return true
    }

    fun cancelCollecting(uuid: UUID): Boolean =
        activeSessions.remove(uuid) != null

    fun getSession(uuid: UUID): DataSession? =
        activeSessions[uuid]

    fun addTick(uuid: UUID, tick: TickData) {
        activeSessions[uuid]?.addTick(tick)
    }

    fun flushAll() {
        activeSessions.keys.toList().forEach { uuid ->
            activeSessions.remove(uuid)?.let { saveAsync(uuid, it) }
        }
        saveExecutor.shutdown()
        runCatching { saveExecutor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    private fun saveAsync(uuid: UUID, session: DataSession) {
        saveExecutor.submit {
            runCatching { session.saveAndClose(plugin) }
                .onFailure { plugin.logger.log(Level.SEVERE, "[DataCollect] Save error for $uuid", it) }
        }
    }
}
