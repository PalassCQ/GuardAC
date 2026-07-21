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
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.util

import org.bukkit.ChatColor

object Colors {

    private val HEX_PATTERN = Regex("[&<]#([0-9A-Fa-f]{6})>?")

    fun translate(input: String): String {
        val withHex = HEX_PATTERN.replace(input) { match ->
            val hex = match.groupValues[1]
            val sb = StringBuilder("§x")
            for (c in hex) {
                sb.append('§').append(c)
            }
            sb.toString()
        }
        return ChatColor.translateAlternateColorCodes('&', withHex)
    }
}
