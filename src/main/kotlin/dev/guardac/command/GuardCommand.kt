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

package dev.guardac.command

import dev.guardac.GuardAC
import dev.guardac.combat.SuppressionStage
import dev.guardac.menu.ResultsMenu
import dev.guardac.menu.SuspectsMenu
import dev.guardac.util.Colors
import dev.guardac.util.Message
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class GuardCommand(private val plugin: GuardAC) : CommandExecutor, TabCompleter {

    override fun onCommand(
        sender: CommandSender, command: Command, label: String, args: Array<out String>,
    ): Boolean {
        val sub = args.getOrNull(0)?.lowercase() ?: "help"

        val node = "guardac.command." + (if (sub in SUBCOMMANDS) sub else "help")
        if (!sender.hasPermission(node)) {
            sender.sendMessage(plugin.locale.get(Message.NO_PERMISSION))
            return true
        }
        when (sub) {
            "help"        -> sendHelp(sender)
            "reload"      -> handleReload(sender)
            "alerts"      -> handleAlerts(sender)
            "monitor"     -> handleMonitor(sender)
            "profile"     -> handleProfile(sender, args)
            "suspicious"  -> handleSuspicious(sender)
            "menu"        -> handleMenu(sender)
            "debug"       -> handleDebug(sender, args)
            "prob"        -> handleProb(sender, args)
            "exempt"      -> handleExempt(sender, args)
            "reset"       -> handleReset(sender, args)
            "punish"      -> handlePunish(sender, args)
            "scan"        -> handleScan(sender, args)
            "stats"       -> handleStats(sender, args)
            "crossserver" -> handleCrossServer(sender)
            "log"         -> handleLog(sender, args)
            "history"     -> handleHistory(sender, args)
            "results"     -> handleResults(sender, args)
            else          -> sendHelp(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<out String>,
    ): List<String> {
        val online = Bukkit.getOnlinePlayers().map { it.name }
        return when (args.size) {

            1 -> SUBCOMMANDS
                .filter { sender.hasPermission("guardac.command.$it") }
                .filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "profile", "debug", "prob", "reset", "punish", "scan", "log", "history", "results"
                    -> online.filter { it.startsWith(args[1], ignoreCase = true) }
                "exempt"
                    -> (listOf("remove", "status") + online).filter { it.startsWith(args[1], ignoreCase = true) }
                "stats"
                    -> listOf("1h", "6h", "24h", "7d").filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "exempt" -> when (args[1].lowercase()) {
                    "remove", "status" -> online.filter { it.startsWith(args[2], ignoreCase = true) }
                    else -> emptyList()
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun handleReload(sender: CommandSender) {
        plugin.reload()
        sender.sendMessage(plugin.locale.get(Message.RELOAD_SUCCESS))
    }

    private fun handleAlerts(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage(plugin.locale.get(Message.RUN_AS_PLAYER)); return }
        val enabled = plugin.alertManager.toggleAlerts(sender.uniqueId)
        sender.sendMessage(
            if (enabled) plugin.locale.get(Message.ALERTS_ENABLED)
            else         plugin.locale.get(Message.ALERTS_DISABLED)
        )
    }

    private fun handleMonitor(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage(plugin.locale.get(Message.RUN_AS_PLAYER)); return }
        val enabled = plugin.alertManager.toggleMonitor(sender.uniqueId)
        sender.sendMessage(
            if (enabled) plugin.locale.get(Message.MONITOR_ENABLED)
            else         plugin.locale.get(Message.MONITOR_DISABLED)
        )
    }

    private fun handleCrossServer(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage(plugin.locale.get(Message.RUN_AS_PLAYER)); return }
        val enabled = plugin.alertManager.toggleCrossServer(sender.uniqueId)
        sender.sendMessage(
            if (enabled) plugin.locale.get(Message.CROSSSERVER_ENABLED)
            else         plugin.locale.get(Message.CROSSSERVER_DISABLED)
        )
    }

    private fun handleMenu(sender: CommandSender) {
        if (sender !is Player) { sender.sendMessage(plugin.locale.get(Message.RUN_AS_PLAYER)); return }
        sender.sendMessage(plugin.locale.get(Message.SUSPECTS_MENU_OPEN))
        SuspectsMenu(plugin, sender).open()
    }

    private fun handleProfile(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_PROFILE))
        val target = Bukkit.getPlayerExact(name)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
        val gp = plugin.playerDataManager.get(target)
            ?: return sender.sendMessage(plugin.locale.get(Message.PROFILE_NO_DATA))

        val sessionMs = System.currentTimeMillis() - gp.joinTime
        sender.sendMessage(plugin.locale.get(Message.PROFILE_HEADER, "player", target.name))
        sender.sendMessage(plugin.locale.get(Message.PROFILE_PING, "ping", target.ping.toString()))
        sender.sendMessage(plugin.locale.get(Message.PROFILE_SESSION, "session", formatDuration(sessionMs)))
        sender.sendMessage(plugin.locale.get(
            Message.PROFILE_AI_BUFFER,
            "buffer", "%.2f".format(gp.aiBuffer),
            "flag",   plugin.configManager.aiBufferFlag.toString(),
        ))
        sender.sendMessage(plugin.locale.get(
            Message.PROFILE_AI_VL,
            "vl",    gp.aiViolationLevel.toString(),
            "total", gp.totalAiFlags.get().toString(),
        ))
        sender.sendMessage(plugin.locale.get(
            Message.PROFILE_AVG_PROB,
            "avg",  "%.1f".format(gp.avgProbability * 100.0),
            "peak", "%.1f".format(gp.peakProbability * 100.0),
        ))
        sender.sendMessage(plugin.locale.get(
            Message.PROFILE_SENSITIVITY,
            "sens_x", "%.2f".format(gp.rotation.sensitivityX),
            "sens_y", "%.2f".format(gp.rotation.sensitivityY),
        ))
        sender.sendMessage(plugin.locale.get(Message.PROFILE_RIDING, "riding", if (gp.isRiding) plugin.locale.get(Message.COMMON_YES) else plugin.locale.get(Message.COMMON_NO)))
        gp.clientBrand?.let { brand ->
            sender.sendMessage(plugin.locale.get(Message.PROFILE_BRAND, "brand", brand))
        }
        if (plugin.configManager.suppressionEnabled) {
            sender.sendMessage(plugin.locale.get(
                Message.PROFILE_SUPPRESSION,
                "stage",   suppressionStageTag(gp.suppressionStage),
                "penalty", "%.0f".format(gp.currentAttackSpeedPenalty() * 100.0),
            ))
        }
    }

    private fun handleScan(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_SCAN))
        val target = Bukkit.getPlayerExact(name)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
        val windows = args.getOrNull(2)?.toIntOrNull() ?: plugin.configManager.scanWindowsDefault
        if (!plugin.scanManager.start(target, sender, windows)) {
            sender.sendMessage(plugin.locale.get(Message.SCAN_ALREADY, "player", target.name))
            return
        }
        sender.sendMessage(plugin.locale.get(
            Message.SCAN_STARTED,
            "player", target.name,
            "windows", windows.coerceIn(1, dev.guardac.scan.ScanManager.MAX_WINDOWS).toString(),
        ))
    }

    private fun suppressionStageTag(stage: SuppressionStage): String = when (stage) {
        SuppressionStage.NONE    -> "&8-"
        SuppressionStage.DAMPEN  -> "&#FFC857Dampen"
        SuppressionStage.ISOLATE -> "&#FF4D6DIsolate"
    }

    private fun handleSuspicious(sender: CommandSender) {
        val list = plugin.playerDataManager.getAll()
            .filter { it.player.isOnline }
            .filter { it.aiBuffer > SUSPICIOUS_BUFFER_THRESHOLD }
            .sortedByDescending { it.aiBuffer }
            .take(10)

        if (list.isEmpty()) { sender.sendMessage(plugin.locale.get(Message.SUSPICIOUS_EMPTY)); return }
        sender.sendMessage(plugin.locale.get(Message.SUSPICIOUS_HEADER, "count", list.size.toString()))
        list.forEach { gp ->
            sender.sendMessage(plugin.locale.get(
                Message.SUSPICIOUS_ENTRY,
                "player", gp.player.name,
                "buffer", "%.1f".format(gp.aiBuffer),
                "ping",   gp.player.ping.toString(),
            ))
        }
    }

    private fun handleDebug(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
        if (name == null) {
            val statusMsg = if (plugin.configManager.debugEnabled)
                plugin.locale.get(Message.DEBUG_MODE_ON) else plugin.locale.get(Message.DEBUG_MODE_OFF)
            sender.sendMessage(plugin.locale.get(Message.DEBUG_MODE_STATUS, "status", statusMsg))
            return
        }
        val target = Bukkit.getPlayerExact(name)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
        val gp  = plugin.playerDataManager.get(target)
            ?: return sender.sendMessage(plugin.locale.get(Message.PROFILE_NO_DATA))
        val rot  = gp.rotation

        sender.sendMessage(plugin.locale.get(Message.DEBUG_HEADER, "player", target.name))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_ROTATION, "yaw", "%.2f".format(rot.yaw), "pitch", "%.2f".format(rot.pitch)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_DELTA, "dyaw", "%.2f".format(rot.deltaYaw), "dpitch", "%.2f".format(rot.deltaPitch)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_ACCEL, "accel", "%.3f".format(rot.accelYaw)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_JERK,  "jerk",  "%.3f".format(rot.jerkYaw)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_GCD,   "gcd_yaw", "%.4f".format(rot.gcdErrorYaw), "gcd_pitch", "%.4f".format(rot.gcdErrorPitch)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_MODE_XY, "mx", "%.4f".format(rot.modeX), "my", "%.4f".format(rot.modeY)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_AI_BUFFER, "buffer", "%.4f".format(gp.aiBuffer)))
        sender.sendMessage(plugin.locale.get(Message.DEBUG_RIDING, "riding", if (gp.isRiding) plugin.locale.get(Message.COMMON_YES) else plugin.locale.get(Message.COMMON_NO)))
        sender.sendMessage(plugin.locale.get(
            Message.DEBUG_IDLE,
            "idle",  gp.idleTickCount.toString(),
            "total", gp.totalTickCount.toString(),
        ))
        if (plugin.configManager.suppressionEnabled) {
            sender.sendMessage(plugin.locale.get(
                Message.DEBUG_SUPPRESSION,
                "stage",    suppressionStageTag(gp.suppressionStage),
                "penalty",  "%.0f".format(gp.currentAttackSpeedPenalty() * 100.0),
                "isolated", if (gp.isIsolated) plugin.locale.get(Message.COMMON_YES) else plugin.locale.get(Message.COMMON_NO),
            ))
        }
    }

    private fun handleProb(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage(plugin.locale.get(Message.RUN_AS_PLAYER)); return }
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            if (plugin.alertManager.hasProbSession(sender.uniqueId)) {
                plugin.alertManager.stopProbSession(sender.uniqueId)
                sender.sendMessage(plugin.locale.get(Message.PROB_STOPPED))
            } else {
                sender.sendMessage(plugin.locale.get(Message.USAGE_PROB))
            }
            return
        }
        val target = Bukkit.getPlayerExact(targetName)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", targetName))
        val started = plugin.alertManager.startProbSession(sender, target)
        if (started) sender.sendMessage(plugin.locale.get(Message.PROB_STARTED, "player", target.name))
        else         sender.sendMessage(plugin.locale.get(Message.PROB_STOPPED))
    }

    private fun handleExempt(sender: CommandSender, args: Array<out String>) {
        val sub = args.getOrNull(1)?.lowercase()
        when (sub) {
            "remove" -> {
                val name = args.getOrNull(2)
                    ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_EXEMPT_REMOVE))
                val target = Bukkit.getPlayerExact(name)
                    ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
                if (plugin.exemptManager.removeExempt(target.uniqueId))
                    sender.sendMessage(plugin.locale.get(Message.EXEMPT_REMOVED, "player", target.name))
                else
                    sender.sendMessage(plugin.locale.get(Message.EXEMPT_NOT_FOUND, "player", target.name))
            }
            "status" -> {
                val name = args.getOrNull(2)
                    ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_EXEMPT_STATUS))
                val target = Bukkit.getPlayerExact(name)
                    ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
                if (plugin.exemptManager.isExempt(target.uniqueId) || target.hasPermission("guardac.bypass"))
                    sender.sendMessage(plugin.locale.get(Message.EXEMPT_STATUS_EXEMPT, "player", target.name))
                else
                    sender.sendMessage(plugin.locale.get(Message.EXEMPT_STATUS_NOT_EXEMPT, "player", target.name))
            }
            null -> sender.sendMessage(plugin.locale.get(Message.USAGE_EXEMPT))
            else -> {
                val target = Bukkit.getPlayerExact(sub)
                    ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", sub))
                if (plugin.exemptManager.isExempt(target.uniqueId)) {
                    sender.sendMessage(plugin.locale.get(Message.EXEMPT_ALREADY, "player", target.name))
                    return
                }
                plugin.exemptManager.addExempt(target.uniqueId)
                sender.sendMessage(plugin.locale.get(Message.EXEMPT_ADDED, "player", target.name))
            }
        }
    }

    private fun handleReset(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_RESET))
        val target = Bukkit.getPlayerExact(name)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
        val gp = plugin.playerDataManager.get(target)
            ?: return sender.sendMessage(plugin.locale.get(Message.PROFILE_NO_DATA))
        gp.resetAllVL()
        sender.sendMessage(plugin.locale.get(Message.RESET_ALL_SUCCESS, "player", target.name))
    }

    private fun handlePunish(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_PUNISH))
        val target = Bukkit.getPlayerExact(name)
            ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", name))
        val gp = plugin.playerDataManager.get(target)
            ?: return sender.sendMessage(plugin.locale.get(Message.PUNISH_NO_DATA))
        plugin.logger.info(
            "[Punish] Manual punish by ${sender.name} on ${target.name} " +
            "(vl=${gp.aiViolationLevel}, buffer=${"%.1f".format(gp.aiBuffer)}, " +
            "detections=${gp.totalAiFlags.get()})"
        )
        val verbose = "manual-punish by ${sender.name}"

        val maxVl = plugin.punishmentManager.maxVl("AI")
        plugin.alertManager.sendAlert(gp, "Manual", maxVl, verbose, "[Manual]")

        plugin.punishmentManager.handle(gp, "AI", maxVl, verbose, bypassCooldown = true, forceAnimation = true)
        sender.sendMessage(plugin.locale.get(Message.PUNISH_SUCCESS, "player", target.name))
    }

    private fun handleStats(sender: CommandSender, args: Array<out String>) {
        val periodLabel = args.getOrNull(1)?.lowercase(Locale.ROOT) ?: "24h"
        val periodHours = when (periodLabel) {
            "1h"         -> 1L
            "6h"         -> 6L
            "24h", "1d" -> 24L
            "7d", "1w"  -> 168L
            else -> { sender.sendMessage(plugin.locale.get(Message.USAGE_STATS)); return }
        }

        val all        = plugin.playerDataManager.getAll()
        val online     = all.size
        val suspicious = all.count { it.aiBuffer > SUSPICIOUS_BUFFER_THRESHOLD }
        val totalFlags = all.sumOf { it.totalAiFlags.get() }
        val dcSessions = plugin.dataCollectorManager.activeSessions.size
        val aiEnabled  = plugin.configManager.aiEnabled
        val uptime     = System.currentTimeMillis() - plugin.startTime
        val flagsPerHour = if (periodHours > 0) "%.1f".format(totalFlags.toDouble() / periodHours) else "0"
        val suspPct      = if (online > 0) "%.1f".format(suspicious.toDouble() / online * 100) else "0.0"
        val todayDetections = plugin.dailyStats.getTodayDetections()
        val todayRequests   = plugin.dailyStats.getTodayRequests()

        sender.sendMessage(plugin.locale.get(Message.STATS_HEADER, "period", periodLabel))
        sender.sendMessage(plugin.locale.get(Message.STATS_ONLINE,      "online",     online.toString()))
        sender.sendMessage(plugin.locale.get(Message.STATS_SUSPICIOUS,  "suspicious", suspicious.toString(), "pct", suspPct))
        sender.sendMessage(plugin.locale.get(Message.STATS_TOTAL_FLAGS, "flags",      totalFlags.toString(), "per_hour", flagsPerHour))
        sender.sendMessage(plugin.locale.get(Message.STATS_AI_STATUS,   "status",     if (aiEnabled) plugin.locale.get(Message.COMMON_ON) else plugin.locale.get(Message.COMMON_OFF)))
        sender.sendMessage(plugin.locale.get(Message.STATS_DC_SESSIONS, "sessions",   dcSessions.toString()))
        sender.sendMessage(plugin.locale.get(Message.STATS_UPTIME,      "uptime",     formatDuration(uptime)))
        sender.sendMessage(plugin.locale.get(Message.STATS_DETECTIONS, "detections", todayDetections.toString(), "requests", todayRequests.toString()))
    }

    private fun handleLog(sender: CommandSender, args: Array<out String>) {
        val log = plugin.punishmentManager.violationLog
        val entries = if (args.size >= 2) {
            val target = Bukkit.getPlayerExact(args[1])
                ?: return sender.sendMessage(plugin.locale.get(Message.PLAYER_NOT_FOUND, "player", args[1]))
            log.forPlayer(target.uniqueId, 20)
        } else {
            log.recent(20)
        }

        if (entries.isEmpty()) {
            sender.sendMessage(plugin.locale.get(Message.LOG_EMPTY))
            return
        }
        sender.sendMessage(plugin.locale.get(Message.LOG_HEADER, "count", entries.size.toString()))
        entries.forEach { sender.sendMessage(it.format()) }
    }

    private fun handleHistory(sender: CommandSender, args: Array<out String>) {

        val entries = if (args.size >= 2) {
            plugin.punishmentHistory.forPlayer(args[1], 20)
        } else {
            plugin.punishmentHistory.recent(20)
        }
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.locale.get(Message.HISTORY_EMPTY))
            return
        }
        sender.sendMessage(plugin.locale.get(Message.HISTORY_HEADER, "count", entries.size.toString()))
        entries.forEach { e ->
            sender.sendMessage(plugin.locale.get(
                Message.HISTORY_ENTRY,
                "time",   HISTORY_TIME_FMT.format(Instant.ofEpochMilli(e.epochMillis)),
                "player", e.playerName,
                "check",  e.check,
                "vl",     e.vl.toString(),
                "prob",   "%.0f".format(e.probability * 100.0),
                "action", e.action,
            ))
        }
    }

    private fun handleResults(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) { sender.sendMessage(plugin.locale.get(Message.RUN_AS_PLAYER)); return }
        val name = args.getOrNull(1)
            ?: return sender.sendMessage(plugin.locale.get(Message.USAGE_RESULTS))

        val ourServer = plugin.reputationClient.displayName
        val local = plugin.punishmentHistory.resultsFor(name, ResultsMenu.CAPACITY)
            .map { ResultsMenu.Row(it.uuid, it.playerName, it.model, ourServer, it.probability, it.epochMillis) }

        if (!plugin.configManager.crossServerEnabled) {
            openResultsMenu(sender, name, local, showServer = false)
            return
        }

        plugin.reputationClient.queryNetworkResults(name, ResultsMenu.CAPACITY)
            .thenAccept { remote ->
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (!sender.isOnline) return@Runnable
                    val merged = ArrayList<ResultsMenu.Row>(local.size + (remote?.size ?: 0))
                    merged.addAll(local)
                    remote.orEmpty()
                        .filter { it.server != ourServer }
                        .forEach { merged.add(ResultsMenu.Row(it.uuid, it.name, it.model, it.server, it.probability, it.epochMillis)) }
                    merged.sortByDescending { it.epochMillis }
                    openResultsMenu(sender, name, merged.take(ResultsMenu.CAPACITY), showServer = true)
                })
            }
    }

    private fun openResultsMenu(viewer: Player, requestedName: String, rows: List<ResultsMenu.Row>, showServer: Boolean) {
        if (rows.isEmpty()) {
            viewer.sendMessage(plugin.locale.get(Message.RESULTS_EMPTY, "player", requestedName))
            return
        }
        ResultsMenu(plugin, viewer, rows.first().name, rows, showServer).open()
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage(plugin.locale.get(Message.HELP_HEADER))
        sender.sendMessage(plugin.locale.get(Message.HELP_RELOAD))
        sender.sendMessage(plugin.locale.get(Message.HELP_ALERTS))
        sender.sendMessage(plugin.locale.get(Message.HELP_MONITOR))
        sender.sendMessage(plugin.locale.get(Message.HELP_PROFILE))
        sender.sendMessage(plugin.locale.get(Message.HELP_SUSPICIOUS))
        sender.sendMessage(plugin.locale.get(Message.HELP_MENU))
        sender.sendMessage(plugin.locale.get(Message.HELP_DEBUG))
        sender.sendMessage(plugin.locale.get(Message.HELP_PROB))
        sender.sendMessage(plugin.locale.get(Message.HELP_EXEMPT))
        sender.sendMessage(plugin.locale.get(Message.HELP_RESET))
        sender.sendMessage(plugin.locale.get(Message.HELP_PUNISH))
        sender.sendMessage(plugin.locale.get(Message.HELP_SCAN))
        sender.sendMessage(plugin.locale.get(Message.HELP_STATS))
        sender.sendMessage(plugin.locale.get(Message.HELP_LOG))
        sender.sendMessage(plugin.locale.get(Message.HELP_HISTORY))
        sender.sendMessage(plugin.locale.get(Message.HELP_RESULTS))
        sender.sendMessage(plugin.locale.get(Message.HELP_CROSSSERVER))
        sender.sendMessage(plugin.locale.get(Message.HELP_DATACOLLECT))
        sender.sendMessage(plugin.locale.get(Message.HELP_FOOTER))
    }

    private fun formatDuration(ms: Long): String {
        val hours   = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        val h = plugin.locale.get(Message.UNIT_HOURS)
        val m = plugin.locale.get(Message.UNIT_MINUTES)
        val s = plugin.locale.get(Message.UNIT_SECONDS)
        return when {
            hours > 0   -> "$hours$h $minutes$m"
            minutes > 0 -> "$minutes$m $seconds$s"
            else        -> "$seconds$s"
        }
    }

    private companion object {
        const val SUSPICIOUS_BUFFER_THRESHOLD = 10.0
        val HISTORY_TIME_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

        val SUBCOMMANDS = listOf(
            "help", "reload", "alerts", "monitor", "profile", "suspicious", "menu",
            "debug", "prob", "exempt", "reset", "punish", "scan", "stats",
            "crossserver", "log", "history", "results",
        )
    }
}
