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

package dev.guardac.sample

import dev.guardac.GuardAC
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class RecordingSession(val uuid: UUID, val player: String, val status: String) {

    private val guard = Any()
    private val samples = ArrayList<AimSample>(INITIAL_CAPACITY)

    val startTime: Instant = Instant.now()

    val size: Int
        get() = synchronized(guard) { samples.size }

    fun offer(sample: AimSample) {
        synchronized(guard) { samples.add(sample) }
    }

    fun outputName(): String {
        val label = fileToken(status.replace(' ', '#'))
        val nick  = fileToken(player).ifBlank { "player" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(startTime)
        return "${label}_${nick}_${stamp}.csv"
    }

    @Throws(IOException::class)
    fun persist(plugin: GuardAC) {
        val rows = synchronized(guard) { samples.toList() }
        if (rows.isEmpty()) {
            plugin.logger.warning("[DataCollect] Session for $player has 0 ticks - skipping save.")
            return
        }
        val cheating = status.startsWith("CHEAT")
        val folder = plugin.configManager.datacollectionFolder.also { it.mkdirs() }
        val target = File(folder, outputName())
        Files.newBufferedWriter(target.toPath(), StandardCharsets.UTF_8).use { w ->
            w.append(AimSample.CSV_HEADER).append('\n')
            for (row in rows) {
                w.append(row.csvRow(cheating)).append('\n')
            }
        }
        plugin.logger.info("[DataCollect] Saved ${rows.size} ticks for $player ($status)")
    }

    private fun fileToken(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            append(if (ch.isWhitespace() || ch in ILLEGAL_CHARS) '-' else ch)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 4096
        const val ILLEGAL_CHARS = "/\\?%*:|\"<>'"
    }
}
