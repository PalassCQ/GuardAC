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

package dev.guardac.dataset

import dev.guardac.GuardAC
import dev.guardac.sample.AimSample
import dev.guardac.sample.RecordingSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level

class RecorderService(private val plugin: GuardAC) {

    val captures: MutableMap<UUID, RecordingSession> = ConcurrentHashMap()

    private val ioPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "guardac-recorder-io").also { it.isDaemon = true }
    }

    fun beginCapture(uuid: UUID, playerName: String, status: String): Boolean {
        var fresh    = true
        var occupied = false

        captures.compute(uuid) { _, current ->
            when {
                current == null          -> RecordingSession(uuid, playerName, status)
                current.status == status -> { occupied = true; current }
                else                     -> {
                    persistAsync(uuid, current)
                    fresh = false
                    RecordingSession(uuid, playerName, status)
                }
            }
        }
        return fresh && !occupied
    }

    fun finishCapture(uuid: UUID): Boolean {
        val session = captures.remove(uuid) ?: return false
        persistAsync(uuid, session)
        return true
    }

    fun discardCapture(uuid: UUID): Boolean = captures.remove(uuid) != null

    fun captureOf(uuid: UUID): RecordingSession? = captures[uuid]

    fun offer(uuid: UUID, sample: AimSample) {
        captures[uuid]?.offer(sample)
    }

    fun drainAndShutdown() {
        captures.keys.toList().forEach { uuid ->
            captures.remove(uuid)?.let { persistAsync(uuid, it) }
        }
        ioPool.shutdown()
        runCatching { ioPool.awaitTermination(10, TimeUnit.SECONDS) }
    }

    private fun persistAsync(uuid: UUID, session: RecordingSession) {
        ioPool.submit {
            runCatching { session.persist(plugin) }
                .onFailure { plugin.logger.log(Level.SEVERE, "[DataCollect] Save error for $uuid", it) }
        }
    }
}
