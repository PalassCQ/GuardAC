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
 *   - Shard (© 2026 KaelusAI, https://github.com/KaelusAI/Shard)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */
package dev.guardac.punishment

import dev.guardac.GuardAC
import java.util.Date
import org.bukkit.BanList
import org.bukkit.Bukkit

object BanBridge {

    fun ban(plugin: GuardAC, name: String, reason: String, minutes: Int, source: String) {
        val cfg = plugin.configManager
        val duration = if (minutes <= 0) "permanent" else durationLabel(minutes)
        when (resolveProvider(plugin)) {
            Provider.LITEBANS -> {

                val cmd = if (minutes <= 0) "ban $name $reason"
                          else "ban $name ${durationLabel(minutes)} $reason"
                dispatch(plugin, cmd)
            }
            Provider.ADVANCEDBAN -> {
                val cmd = if (minutes <= 0) "ban $name $reason"
                          else "tempban $name ${durationLabel(minutes)} $reason"
                dispatch(plugin, cmd)
            }
            Provider.COMMAND -> {
                val template = if (minutes <= 0 || cfg.banBridgeTempbanCommand.isBlank())
                    cfg.banBridgeBanCommand else cfg.banBridgeTempbanCommand
                if (template.isBlank()) {
                    vanillaBan(name, reason, minutes, source)
                } else {
                    dispatch(plugin, fill(template, name, reason, minutes, duration))
                }
            }
            Provider.VANILLA -> vanillaBan(name, reason, minutes, source)
        }
    }

    fun unban(plugin: GuardAC, name: String) {
        val cfg = plugin.configManager
        when (resolveProvider(plugin)) {
            Provider.LITEBANS, Provider.ADVANCEDBAN -> dispatch(plugin, "unban $name")
            Provider.COMMAND -> {
                val template = cfg.banBridgeUnbanCommand
                if (template.isBlank()) Bukkit.getBanList(BanList.Type.NAME).pardon(name)
                else dispatch(plugin, fill(template, name, "", 0, "permanent"))
            }
            Provider.VANILLA -> Bukkit.getBanList(BanList.Type.NAME).pardon(name)
        }
    }

    private enum class Provider { VANILLA, LITEBANS, ADVANCEDBAN, COMMAND }

    private fun resolveProvider(plugin: GuardAC): Provider {
        val pm = Bukkit.getPluginManager()
        return when (plugin.configManager.banBridge) {
            "vanilla"     -> Provider.VANILLA
            "litebans"    -> Provider.LITEBANS
            "advancedban" -> Provider.ADVANCEDBAN
            "command"     -> Provider.COMMAND
            else -> when {
                pm.getPlugin("LiteBans") != null    -> Provider.LITEBANS
                pm.getPlugin("AdvancedBan") != null -> Provider.ADVANCEDBAN
                else                                -> Provider.VANILLA
            }
        }
    }

    private fun vanillaBan(name: String, reason: String, minutes: Int, source: String) {
        val expires = if (minutes > 0) Date(System.currentTimeMillis() + minutes * 60_000L) else null
        Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expires, source)
    }

    private fun dispatch(plugin: GuardAC, command: String) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
        plugin.logger.info("[GuardAC] Ban bridge dispatched: /$command")
    }

    private fun fill(template: String, name: String, reason: String, minutes: Int, duration: String) =
        template
            .replace("{player}", name)
            .replace("{reason}", reason)
            .replace("{minutes}", minutes.toString())
            .replace("{duration}", duration)
            .removePrefix("/")

    fun durationLabel(minutes: Int): String = when {
        minutes <= 0        -> "∞"
        minutes % 1440 == 0 -> "${minutes / 1440}d"
        minutes % 60 == 0   -> "${minutes / 60}h"
        else                -> "${minutes}m"
    }
}
