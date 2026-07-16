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

package dev.guardac.scan

import dev.guardac.GuardAC
import dev.guardac.util.Message
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScanManager(private val plugin: GuardAC) {

    class Scan(
        val targetName: String,
        val initiator: UUID?,
        val wanted: Int,
        val startMs: Long = System.currentTimeMillis(),
        val probs: MutableList<Double> = mutableListOf(),
        val votedSources: MutableSet<String> = mutableSetOf(),
    )

    private val scans = ConcurrentHashMap<UUID, Scan>()

    fun isScanning(uuid: UUID): Boolean = scans.containsKey(uuid)

    fun start(target: Player, sender: CommandSender, windows: Int): Boolean {
        val wanted = windows.coerceIn(1, MAX_WINDOWS)
        val initiator = (sender as? Player)?.uniqueId
        val scan = Scan(target.name, initiator, wanted)
        if (scans.putIfAbsent(target.uniqueId, scan) != null) return false

        val timeoutTicks = plugin.configManager.scanTimeoutSeconds * 20L
        plugin.scheduler.globalDelayed(timeoutTicks, Runnable {
            val active = scans.remove(target.uniqueId)
            if (active != null && active === scan) report(target.uniqueId, active, timedOut = true)
        })
        return true
    }

    fun onResult(uuid: UUID, probability: Double, sources: List<String>) {
        val scan = scans[uuid] ?: return
        synchronized(scan) {
            scan.probs.add(probability)
            scan.votedSources.addAll(sources)
            if (scan.probs.size < scan.wanted) return
        }
        if (scans.remove(uuid, scan)) report(uuid, scan, timedOut = false)
    }

    fun onQuit(uuid: UUID) {
        val scan = scans.remove(uuid) ?: return
        report(uuid, scan, timedOut = true)
    }

    private fun report(uuid: UUID, scan: Scan, timedOut: Boolean) {
        val probs = synchronized(scan) { scan.probs.toList() }
        val sources = synchronized(scan) { scan.votedSources.toList() }
        plugin.scheduler.global(Runnable {
            val out: CommandSender =
                scan.initiator?.let { Bukkit.getPlayer(it) } ?: Bukkit.getConsoleSender()

            if (probs.isEmpty()) {
                out.sendMessage(plugin.locale.get(Message.SCAN_NO_DATA, "player", scan.targetName))
                return@Runnable
            }
            val avg  = probs.average()
            val peak = probs.max()
            val verdictKey = when {
                avg >= VERDICT_CHEAT_AVG || (peak >= VERDICT_CHEAT_PEAK && avg >= VERDICT_CHEAT_AVG_SOFT) ->
                    Message.SCAN_VERDICT_CHEATING
                avg >= VERDICT_SUSPICIOUS_AVG -> Message.SCAN_VERDICT_SUSPICIOUS
                else                          -> Message.SCAN_VERDICT_CLEAN
            }
            out.sendMessage(plugin.locale.get(Message.SCAN_REPORT_HEADER, "player", scan.targetName))
            out.sendMessage(plugin.locale.get(
                Message.SCAN_REPORT_STATS,
                "windows", probs.size.toString(),
                "avg", "%.0f".format(avg * 100.0),
                "peak", "%.0f".format(peak * 100.0),
            ))
            if (sources.isNotEmpty()) {
                out.sendMessage(plugin.locale.get(
                    Message.SCAN_REPORT_MODELS, "models", sources.joinToString(", ")
                ))
            }
            out.sendMessage(plugin.locale.get(verdictKey))
            if (timedOut && probs.size < scan.wanted) {
                out.sendMessage(plugin.locale.get(
                    Message.SCAN_PARTIAL_NOTE,
                    "collected", probs.size.toString(),
                    "wanted", scan.wanted.toString(),
                ))
            }
        })
    }

    companion object {
        const val MAX_WINDOWS = 20

        private const val VERDICT_CHEAT_AVG       = 0.75
        private const val VERDICT_CHEAT_PEAK      = 0.90
        private const val VERDICT_CHEAT_AVG_SOFT  = 0.50
        private const val VERDICT_SUSPICIOUS_AVG  = 0.40
    }
}
