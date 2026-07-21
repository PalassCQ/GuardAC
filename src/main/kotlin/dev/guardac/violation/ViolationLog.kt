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

package dev.guardac.violation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque

class ViolationLog {

    data class Entry(
        val uuid: UUID,
        val playerName: String,
        val checkName: String,
        val vl: Int,
        val probability: Double,
        val buffer: Double,
        val verbose: String,
        val actions: List<String>,
        val time: Instant = Instant.now(),
    ) {
        fun format(): String {
            val timeStr = FORMATTER.format(time)
            val prob = "%.2f".format(probability * 100)
            return "§8[$timeStr] §e${playerName} §8| §7${checkName} §8| §7VL:${vl} §8| §7${prob}% §8| §7${verbose}"
        }

        private companion object {
            val FORMATTER: DateTimeFormatter =
                DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        }
    }

    private val entries = ConcurrentLinkedDeque<Entry>()

    fun record(entry: Entry) {
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.pollLast()
    }

    fun recent(limit: Int = MAX_ENTRIES): List<Entry> =
        entries.take(limit)

    fun forPlayer(uuid: UUID, limit: Int = 20): List<Entry> =
        entries.filter { it.uuid == uuid }.take(limit)

    fun clear() = entries.clear()

    fun size(): Int = entries.size

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
