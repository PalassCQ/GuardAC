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

package dev.guardac.compat

import dev.guardac.GuardAC
import dev.guardac.player.GuardPlayer
import dev.guardac.util.BuildInfo
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.Locale

class GuardacExpansion(private val plugin: GuardAC) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "guardac"

    override fun getAuthor(): String = plugin.description.authors.joinToString(", ")

    override fun getVersion(): String = plugin.description.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val id = params.lowercase(Locale.ROOT)

        serverValue(id)?.let { return it }

        val online = player as? Player ?: return null
        if (!online.isOnline) return null
        val gp = plugin.playerDataManager.get(online) ?: return null
        return playerValue(id, gp)
    }

    private fun serverValue(id: String): String? = when (id) {
        "version"          -> plugin.description.version
        "build"            -> BuildInfo.stamp
        "backend"          -> backendStatus()
        "mode"             -> if (plugin.configManager.aiOnlyAlert) "alert-only" else "enforcing"
        "tracked"          -> plugin.playerDataManager.getAll().size.toString()
        "suspicious"       -> plugin.playerDataManager.getAll()
            .count { it.aiBuffer > SUSPICIOUS_BUFFER }.toString()
        "detections_today" -> plugin.dailyStats.getTodayDetections().toString()
        "checks_today"     -> plugin.dailyStats.getTodayRequests().toString()
        else               -> null
    }

    private fun playerValue(id: String, gp: GuardPlayer): String? = when (id) {
        "vl"          -> gp.aiViolationLevel.toString()
        "buffer"      -> decimal(gp.aiBuffer)
        "probability" -> percent(gp.lastAiProbability)
        "average"     -> percent(gp.avgProbability)
        "peak"        -> percent(gp.peakProbability)
        "detections"  -> gp.totalAiFlags.get().toString()
        "exempt"      -> bool(gp.isExempt)
        "status"      -> status(gp)
        else          -> null
    }

    private fun status(gp: GuardPlayer): String = when {
        gp.isExempt                        -> "exempt"
        gp.aiViolationLevel > 0            -> "flagged"
        gp.aiBuffer > SUSPICIOUS_BUFFER    -> "watched"
        else                               -> "clean"
    }

    private fun backendStatus(): String = when {
        !plugin.configManager.aiEnabled  -> "off"
        plugin.aiTransport.circuitOpen   -> "degraded"
        else                             -> "online"
    }

    private fun percent(value: Double): String =
        String.format(Locale.ROOT, "%.0f", value * 100.0)

    private fun decimal(value: Double): String =
        String.format(Locale.ROOT, "%.1f", value)

    private fun bool(value: Boolean): String = if (value) "yes" else "no"

    private companion object {
        const val SUSPICIOUS_BUFFER = 10.0
    }
}
