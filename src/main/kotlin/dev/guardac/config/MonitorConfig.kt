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
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.config

import dev.guardac.GuardAC
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class MonitorConfig(private val plugin: GuardAC) {

    private lateinit var cfg: YamlConfiguration

    fun load() {
        val file = File(plugin.dataFolder, "monitor.yml")
        if (!file.exists()) plugin.saveResource("monitor.yml", false)
        cfg = YamlConfiguration.loadConfiguration(file)
    }

    val colorLow: String      get() = cfg.getString("monitor.colors.low", "&a")!!
    val colorMedium: String   get() = cfg.getString("monitor.colors.medium", "&e")!!
    val colorHigh: String     get() = cfg.getString("monitor.colors.high", "&6")!!
    val colorCritical: String get() = cfg.getString("monitor.colors.critical", "&c")!!

    val thresholdLow: Double    get() = cfg.getDouble("monitor.thresholds.low", 35.0)
    val thresholdMedium: Double get() = cfg.getDouble("monitor.thresholds.medium", 55.0)
    val thresholdHigh: Double   get() = cfg.getDouble("monitor.thresholds.high", 85.0)

    fun colorForProbability(pct: Double): String = when {
        pct < thresholdLow    -> colorLow
        pct < thresholdMedium -> colorMedium
        pct < thresholdHigh   -> colorHigh
        else                   -> colorCritical
    }
}
