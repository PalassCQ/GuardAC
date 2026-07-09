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
        if (channel != CrossServerCodec.PROXY_CHANNEL) return
        // The proxy routes many plugins' traffic on this channel; the codec only
        // returns a payload for OUR subchannel. Messages still arrive over player
        // connections and can be forged by a modified client, so everything below
        // is strict field validation - anything a real GuardAC instance would
        // never send is dropped silently.
        val payload = CrossServerCodec.decode(message) ?: return
        if (payload.length > MAX_PAYLOAD_BYTES) return

        val parts = payload.split("|", limit = 5)
        if (parts.size < 5) return

        val (sourceServer, playerName, checkName, vlStr, verbose) = parts
        val vl = vlStr.toIntOrNull() ?: return
        if (vl < 0 || vl > MAX_VL) return
        if (!SERVER_PATTERN.matches(sourceServer)) return
        if (!NAME_PATTERN.matches(playerName)) return
        if (!CHECK_PATTERN.matches(checkName)) return

        val currentServer = plugin.configManager.serverName.ifBlank { "main" }
        if (sourceServer == currentServer) return

        // The verbose part goes verbatim into a staff chat message; strip color/
        // control characters so a forged payload can't restyle or garble the alert.
        val cleanVerbose = verbose.take(MAX_VERBOSE_LENGTH)
            .filter { it.code >= 0x20 && it != '§' && it != '&' }

        plugin.alertManager.deliverCrossServerAlert(sourceServer, playerName, checkName, vl, cleanVerbose)
    }

    private companion object {
        const val MAX_PAYLOAD_BYTES  = 512
        const val MAX_VL             = 1_000_000
        const val MAX_VERBOSE_LENGTH = 64
        val SERVER_PATTERN = Regex("^[\\w .-]{1,32}$")
        val NAME_PATTERN   = Regex("^[\\w .-]{1,20}$")
        val CHECK_PATTERN  = Regex("^[\\w-]{1,24}$")
    }
}

private operator fun <T> List<T>.component5(): T = this[4]
