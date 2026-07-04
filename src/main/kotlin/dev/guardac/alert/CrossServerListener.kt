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

package dev.guardac.alert

import dev.guardac.GuardAC
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener

class CrossServerListener(private val plugin: GuardAC) : PluginMessageListener {

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != AlertManager.CROSS_CHANNEL) return

        val payload = try {
            String(message, Charsets.UTF_8)
        } catch (_: Exception) {
            return
        }

        val parts = payload.split("|", limit = 5)
        if (parts.size < 5) return

        val (sourceServer, playerName, checkName, vlStr, verbose) = parts
        val vl = vlStr.toIntOrNull() ?: return

        val currentServer = plugin.configManager.serverName.ifBlank { "main" }
        if (sourceServer == currentServer) return

        plugin.alertManager.deliverCrossServerAlert(sourceServer, playerName, checkName, vl, verbose)
    }
}

private operator fun <T> List<T>.component5(): T = this[4]
