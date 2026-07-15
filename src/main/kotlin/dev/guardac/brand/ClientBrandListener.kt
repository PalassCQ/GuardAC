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

package dev.guardac.brand

import dev.guardac.GuardAC
import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener

class ClientBrandListener(private val plugin: GuardAC) : PluginMessageListener {

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != BRAND_CHANNEL) return
        val brand = decodeBrand(message) ?: return
        plugin.playerDataManager.get(player)?.clientBrand = brand.take(48)
    }

    private fun decodeBrand(data: ByteArray): String? {
        if (data.isEmpty()) return null
        var index = 0
        var numRead = 0
        var length = 0
        while (index < data.size) {
            val b = data[index].toInt()
            length = length or ((b and 0x7f) shl (7 * numRead))
            index++
            numRead++
            if (numRead > 5) return null
            if (b and 0x80 == 0) break
        }
        val text = if (length in 1..(data.size - index)) {
            String(data, index, length, Charsets.UTF_8)
        } else {
            String(data, index, data.size - index, Charsets.UTF_8)
        }
        return text.filter { it >= ' ' }.trim().ifBlank { null }
    }

    companion object {
        const val BRAND_CHANNEL = "minecraft:brand"
    }
}
