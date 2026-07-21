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

package dev.guardac.command

import dev.guardac.GuardAC
import dev.guardac.data.DataSession
import dev.guardac.util.Message
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.time.Duration
import java.time.Instant
import java.util.Locale

class DataCollectCommand(private val plugin: GuardAC) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender, command: Command, label: String, args: Array<out String>,
    ): Boolean {
        if (!sender.hasPermission("guardac.datacollect")) {
            sender.sendMessage(plugin.locale.get(Message.NO_PERMISSION))
            return true
        }
        when (args.getOrNull(0)?.lowercase()) {
            "start"  -> handleStart(sender, args)
            "stop"   -> handleStop(sender, args)
            "cancel" -> handleCancel(sender, args)
            "status" -> handleStatus(sender, args)
            else     -> sendHelp(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<out String>,
    ): List<String> {
        if (!sender.hasPermission("guardac.datacollect")) return emptyList()
        val online = Bukkit.getOnlinePlayers().map { it.name }
        val sessionNames = plugin.dataCollectorManager.activeSessions.values.map { it.player }
        return when (args.size) {
            1 -> listOf("start", "stop", "cancel", "status", "help")
                .filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "start"          -> online.filter { it.startsWith(args[1], ignoreCase = true) }
                "stop", "cancel" -> (online + sessionNames).distinct().filter { it.startsWith(args[1], ignoreCase = true) }
                "status"         -> (listOf("") + online + sessionNames).distinct().filter { it.startsWith(args[1], ignoreCase = true) }
                else             -> emptyList()
            }
            3 -> if (args[0].equals("start", ignoreCase = true))
                listOf("LEGIT", "CHEAT").filter { it.startsWith(args[2].uppercase()) }
            else emptyList()
            4 -> if (args[0].equals("start", ignoreCase = true)) listOf("<comment>") else emptyList()
            else -> emptyList()
        }
    }

    private fun handleStart(sender: CommandSender, args: Array<out String>) {
        if (args.size < 4) {
            sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_DETAILS_REQUIRED))
            return
        }
        val playerName = args[1]
        val type       = args[2].uppercase(Locale.ROOT)
        if (type != "LEGIT" && type != "CHEAT") {
            sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_INVALID_TYPE))
            return
        }
        val details = args.drop(3).joinToString(" ")
        val target  = Bukkit.getPlayerExact(playerName)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", playerName))

        val status   = "$type $details"
        val existing = plugin.dataCollectorManager.getSession(target.uniqueId)
        val isNew    = plugin.dataCollectorManager.startCollecting(target.uniqueId, target.name, status)

        val msgKey = if (isNew && existing == null) Message.DATACOLLECT_START_SUCCESS
                     else Message.DATACOLLECT_START_RESTARTED
        sender.sendMessage(plugin.locale.get(msgKey, "player", target.name, "status", status))
    }

    private fun handleStop(sender: CommandSender, args: Array<out String>) {
        val playerName = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_DC_STOP))

        val uuid = resolveSessionUuid(playerName)
            ?: return sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_STOP_FAIL, "player", playerName))

        val session = plugin.dataCollectorManager.getSession(uuid)
        val ticks   = session?.recordedTicks?.size ?: 0
        val name    = session?.player ?: playerName

        if (plugin.dataCollectorManager.stopCollecting(uuid)) {
            sender.sendMessage(plugin.locale.get(
                Message.DATACOLLECT_STOP_SUCCESS,
                "player", name,
                "ticks",  ticks.toString(),
            ))
        } else {
            sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_STOP_FAIL, "player", playerName))
        }
    }

    private fun handleCancel(sender: CommandSender, args: Array<out String>) {
        val playerName = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_DC_CANCEL))

        val uuid = resolveSessionUuid(playerName)
            ?: return sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_STOP_FAIL, "player", playerName))

        val name = plugin.dataCollectorManager.getSession(uuid)?.player ?: playerName
        if (plugin.dataCollectorManager.cancelCollecting(uuid)) {
            sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_CANCEL_SUCCESS, "player", name))
        } else {
            sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_STOP_FAIL, "player", playerName))
        }
    }

    private fun handleStatus(sender: CommandSender, args: Array<out String>) {
        val playerName = args.getOrNull(1)
        if (playerName != null) {
            val uuid = resolveSessionUuid(playerName)
            val session = uuid?.let { plugin.dataCollectorManager.getSession(it) }
                ?: return sender.sendMessage(
                    plugin.locale.get(Message.DATACOLLECT_STATUS_NO_SESSION, "player", playerName)
                )
            sender.sendMessage(formatSession(session))
            return
        }
        sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_STATUS_HEADER))
        val sessions = plugin.dataCollectorManager.activeSessions
        if (sessions.isEmpty()) {
            sender.sendMessage(plugin.locale.get(Message.DATACOLLECT_STATUS_EMPTY))
            return
        }
        sessions.values.forEach { sender.sendMessage(formatSession(it)) }
    }

    private fun resolveSessionUuid(playerName: String): java.util.UUID? {
        val online = Bukkit.getPlayerExact(playerName)
        if (online != null) {
            return if (plugin.dataCollectorManager.getSession(online.uniqueId) != null) online.uniqueId else null
        }
        return plugin.dataCollectorManager.activeSessions.entries
            .firstOrNull { it.value.player.equals(playerName, ignoreCase = true) }
            ?.key
    }

    private fun formatSession(session: DataSession): String {
        val seconds = Duration.between(session.startTime, Instant.now()).toSeconds()
        return plugin.locale.get(
            Message.DATACOLLECT_STATUS_ENTRY,
            "player", session.player,
            "status", session.status,
            "time",   seconds.toString(),
            "ticks",  session.recordedTicks.size.toString(),
        )
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(plugin.locale.get(Message.DC_HELP_HEADER))
        sender.sendMessage(plugin.locale.get(Message.DC_HELP_START))
        sender.sendMessage(plugin.locale.get(Message.DC_HELP_STOP))
        sender.sendMessage(plugin.locale.get(Message.DC_HELP_CANCEL))
        sender.sendMessage(plugin.locale.get(Message.DC_HELP_STATUS))
        sender.sendMessage(plugin.locale.get(Message.DC_HELP_FOOTER))
    }
}
