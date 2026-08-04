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

package dev.guardac.player

import kotlin.math.max

class HitFeed(private val capacity: Int) {

    data class Sample(val probability: Double, val epochMillis: Long)

    private val samples = ArrayDeque<Sample>(capacity)
    private var lastAttacks = -1
    private var lastMs      = 0L

    fun record(nowMs: Long, probability: Double, attacks: Int, windowMs: Long) {
        val fresh = samples.isEmpty() ||
            (if (attacks > 0) attacks != lastAttacks else nowMs - lastMs >= windowMs)

        if (fresh) {
            samples.addLast(Sample(probability, nowMs))
            lastMs = nowMs
        } else {
            val last = samples.removeLast()
            samples.addLast(Sample(max(last.probability, probability), nowMs))
        }
        lastAttacks = attacks
        while (samples.size > capacity) samples.removeFirst()
    }

    fun probabilities(): List<Double> = samples.map { it.probability }

    fun samples(): List<Sample> = samples.toList()

    fun isEmpty(): Boolean = samples.isEmpty()

    fun lastEpochMillis(): Long = samples.lastOrNull()?.epochMillis ?: 0L

    fun mean(window: Int): Double {
        if (samples.isEmpty()) return 0.0
        val from = (samples.size - window).coerceAtLeast(0)
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].probability
        return sum / (samples.size - from)
    }

    fun clear() {
        samples.clear()
        lastAttacks = -1
        lastMs      = 0L
    }
}
