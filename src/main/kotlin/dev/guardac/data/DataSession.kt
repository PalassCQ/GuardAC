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

package dev.guardac.data

import dev.guardac.GuardAC
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Queue
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class DataSession(
    val uuid: UUID,
    val player: String,
    val status: String,
) {
    val recordedTicks: Queue<TickData> = ConcurrentLinkedQueue()
    val startTime: Instant = Instant.now()

    fun addTick(tick: TickData) = recordedTicks.add(tick)

    fun generateFileName(): String {
        val timestamp  = TIMESTAMP_FORMAT.format(startTime)
        val safeStatus = status.replace(' ', '#').replace(UNSAFE, "-")
        // Sanitize the player too: Bedrock/Geyser nicks can carry spaces and other
        // characters that are illegal in a filename (normal Java nicks are unchanged).
        val safePlayer = player.replace(UNSAFE, "-").ifBlank { "player" }
        return "${safeStatus}_${safePlayer}_${timestamp}.csv"
    }

    fun writeCsv(writer: Appendable) {
        if (recordedTicks.isEmpty()) return
        writer.append(TickData.csvHeader()).append('\n')
        val label = if (status.startsWith("CHEAT")) "CHEAT" else "LEGIT"
        for (tick in recordedTicks) {
            tick.appendCsv(writer, label)
            writer.append('\n')
        }
    }

    @Throws(IOException::class)
    fun saveAndClose(plugin: GuardAC) {
        if (recordedTicks.isEmpty()) {
            plugin.logger.warning("[DataCollect] Session for $player has 0 ticks - skipping save.")
            return
        }
        val dir = plugin.configManager.datacollectionFolder.also { it.mkdirs() }
        Files.newBufferedWriter(File(dir, generateFileName()).toPath(), StandardCharsets.UTF_8)
            .use { writeCsv(it) }
        plugin.logger.info("[DataCollect] Saved ${recordedTicks.size} ticks for $player ($status)")
    }

    private companion object {
        val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        val UNSAFE = Regex("[/\\\\?%*:|\"<>'\\s]")
    }
}
