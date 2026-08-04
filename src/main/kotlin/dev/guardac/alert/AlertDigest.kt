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

package dev.guardac.alert

class AlertDigest {

    class Batch(val count: Int, val max: Double, val firstOfEpisode: Boolean)

    private var lastHitMs     = 0L
    private var lastCountedMs = 0L
    private var hits          = 0
    private var batchMax      = 0.0

    @Synchronized
    fun record(
        nowMs: Long,
        probability: Double,
        minHits: Int,
        idleMs: Long,
        momentMs: Long,
        independent: Boolean = false,
    ): Batch? {
        val step = minHits.coerceAtLeast(1)
        decay(nowMs, step, idleMs)

        lastHitMs = nowMs
        if (probability > batchMax) batchMax = probability

        if (!independent && lastCountedMs != 0L && nowMs - lastCountedMs < momentMs) return null
        if (!independent) lastCountedMs = nowMs

        hits += 1
        if (hits % step != 0) return null

        val batch = Batch(hits, batchMax, hits == step)
        batchMax = 0.0
        return batch
    }

    private fun decay(nowMs: Long, step: Int, idleMs: Long) {
        if (hits <= 0 || lastHitMs == 0L || idleMs <= 0L) return
        val idle = nowMs - lastHitMs
        if (idle < idleMs) return

        val steps = idle / idleMs
        lastHitMs += steps * idleMs

        val dropped = steps * step
        hits = if (dropped >= hits) 0 else hits - dropped.toInt()
        batchMax = 0.0
        lastCountedMs = 0L
        if (hits == 0) lastHitMs = 0L
    }
}
