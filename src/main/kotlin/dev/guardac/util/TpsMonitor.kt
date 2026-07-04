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

package dev.guardac.util

import dev.guardac.GuardAC
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

class TpsMonitor(private val plugin: GuardAC) {

    @Volatile var tps: Double = 20.0
        private set

    private var task: BukkitTask? = null
    private var lastSampleNanos = 0L

    fun start() {
        stop()
        lastSampleNanos = System.nanoTime()
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val now = System.nanoTime()
            val elapsedMs = (now - lastSampleNanos) / 1_000_000.0
            lastSampleNanos = now
            if (elapsedMs <= 0.0) return@Runnable
            val measured = (SAMPLE_TICKS * 1000.0 / elapsedMs).coerceIn(0.0, 20.0)
            tps = tps * (1.0 - EMA_ALPHA) + measured * EMA_ALPHA
        }, SAMPLE_TICKS, SAMPLE_TICKS)
    }

    fun stop() {
        task?.cancel()
        task = null
        tps = 20.0
    }

    private companion object {
        const val SAMPLE_TICKS = 100L
        const val EMA_ALPHA    = 0.4
    }
}
