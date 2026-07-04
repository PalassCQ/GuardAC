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

package dev.guardac.hologram

import dev.guardac.GuardAC
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class HologramConfig(private val plugin: GuardAC) {

    private lateinit var cfg: YamlConfiguration

    fun load() {
        val file = File(plugin.dataFolder, "hologram.yml")
        if (!file.exists()) plugin.saveResource("hologram.yml", false)
        cfg = YamlConfiguration.loadConfiguration(file)
        migrateOldThresholds()
    }

    private fun migrateOldThresholds() {
        val oldMedium = cfg.getDouble("hologram.colors.thresholds.medium", -1.0)
        if (oldMedium == 0.5) {

            cfg.set("hologram.colors.thresholds.medium", 0.35)
            cfg.set("hologram.colors.thresholds.high", 0.50)
            cfg.set("hologram.colors.thresholds.critical", 0.80)
        }
    }

    val enabled: Boolean
        get() = cfg.getBoolean("hologram.enabled", true)

    val updateIntervalTicks: Int
        get() = cfg.getInt("hologram.update-interval-ticks", 5).coerceAtLeast(1)

    val viewDistance: Double
        get() = cfg.getDouble("hologram.view-distance", 40.0)

    val yOffset: Double
        get() = cfg.getDouble("hologram.y-offset", 2.3)

    val format: String
        get() = cfg.getString("hologram.format", "&7AVG {AVG}% &8| {HIST}")!!

    val colorLow: String      get() = cfg.getString("hologram.colors.low", "&a")!!
    val colorMedium: String   get() = cfg.getString("hologram.colors.medium", "&2")!!
    val colorHigh: String     get() = cfg.getString("hologram.colors.high", "&6")!!
    val colorCritical: String get() = cfg.getString("hologram.colors.critical", "&c")!!

    val thresholdMedium: Double   get() = cfg.getDouble("hologram.colors.thresholds.medium", 0.35)
    val thresholdHigh: Double     get() = cfg.getDouble("hologram.colors.thresholds.high", 0.50)
    val thresholdCritical: Double get() = cfg.getDouble("hologram.colors.thresholds.critical", 0.80)

    fun colorFor(probability: Double): String = when {
        probability >= thresholdCritical -> colorCritical
        probability >= thresholdHigh     -> colorHigh
        probability >= thresholdMedium   -> colorMedium
        else                             -> colorLow
    }
}
