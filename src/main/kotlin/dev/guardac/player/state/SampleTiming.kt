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

package dev.guardac.player.state

class SampleTiming {

    @Volatile private var lastActiveNanos: Long = 0L
    @Volatile private var lastSampleActive: Boolean = false
    @Volatile private var ticksObserved: Int = 0
    @Volatile private var lastSlotNanos: Long = 0L

    fun onPacket(nowNanos: Long) {
        if (lastSlotNanos != 0L && nowNanos - lastSlotNanos < TICK_NANOS) return
        lastSlotNanos = nowNanos
        if (ticksObserved < Int.MAX_VALUE) ticksObserved++
    }

    fun isStale(nowNanos: Long, active: Boolean): Boolean {
        val hadMotion = lastSampleActive
        val last = lastActiveNanos
        val observed = ticksObserved
        lastSampleActive = active
        if (active) {
            lastActiveNanos = nowNanos
            ticksObserved = 0
        }

        if (!active || !hadMotion || last == 0L) return false
        val gapMs = (nowNanos - last) / 1_000_000
        if (gapMs < STALE_GAP_MIN_MS) return false
        return observed * 2 * MS_PER_TICK < gapMs
    }

    fun reset() {
        lastActiveNanos = 0L
        lastSampleActive = false
        ticksObserved = 0
        lastSlotNanos = 0L
    }

    companion object {
        const val STALE_GAP_MIN_MS = 120L
        const val MS_PER_TICK = 50L
        const val TICK_NANOS = MS_PER_TICK * 1_000_000L
    }
}
