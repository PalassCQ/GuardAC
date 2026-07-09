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
import dev.guardac.combat.SuppressionStage
import dev.guardac.player.GuardPlayer
import dev.guardac.util.Colors
import dev.guardac.util.Message
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
class AlertManager(private val plugin: GuardAC) {
    private val alertsMuted      = CopyOnWriteArraySet<UUID>()
    private val monitorReceivers = CopyOnWriteArraySet<UUID>()
    private val probSessions = ConcurrentHashMap<UUID, UUID>()
    private val probTasks    = ConcurrentHashMap<UUID, BukkitTask>()
    private val crossServerEnabled = CopyOnWriteArraySet<UUID>()
    fun reload() {}
    fun toggleAlerts(uuid: UUID): Boolean =
        if (alertsMuted.remove(uuid)) true else { alertsMuted.add(uuid); false }
    fun hasAlerts(uuid: UUID): Boolean = !alertsMuted.contains(uuid)
    fun toggleMonitor(uuid: UUID): Boolean =
        if (monitorReceivers.remove(uuid)) false else monitorReceivers.add(uuid)
    fun hasMonitor(uuid: UUID): Boolean = monitorReceivers.contains(uuid)
    fun toggleCrossServer(uuid: UUID): Boolean =
        if (crossServerEnabled.remove(uuid)) false else crossServerEnabled.add(uuid)
    fun hasCrossServer(uuid: UUID): Boolean = crossServerEnabled.contains(uuid)
    fun sendAlert(gp: GuardPlayer, checkName: String, vl: Int, verbose: String, model: String = "[AI]") {
        val now = System.currentTimeMillis()
        val last = gp.lastAlertMs.get()
        if (now - last < ALERT_THROTTLE_MS) return
        if (!gp.lastAlertMs.compareAndSet(last, now)) return
        val playerName = gp.player.name
        val ping = gp.player.ping
        val msg = plugin.locale.get(
            Message.ALERTS_FORMAT,
            "player",     playerName,
            "check",      checkName,
            "model",      model,
            "vl",         vl.toString(),
            "verbose",    verbose,
            "avg",        formatPercent(gp.avgProbability),
            "peak",       formatPercent(gp.peakProbability),
            "ping",       ping.toString(),
            "ping_color", pingColor(ping),
            "lag",        lagTag(gp),
        )
        val consoleLine = plugin.locale.get(
            Message.ALERTS_CONSOLE_FORMAT,
            "player",  playerName,
            "check",   checkName,
            "model",   model,
            "vl",      vl.toString(),
            "verbose", verbose,
            "ping",    ping.toString(),
            "lag",     if (gp.isLagging) " [LAG]" else "",
        )
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!gp.player.isOnline) return@Runnable
            if (plugin.configManager.alertsToConsole) {
                plugin.logger.info(consoleLine)
            }
            val recipients = Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") }
                .filter { !alertsMuted.contains(it.uniqueId) }

            val component = clickableAlert(msg, playerName)
            recipients.forEach { it.spigot().sendMessage(component) }
            if (plugin.configManager.alertSoundEnabled) {
                playAlertSound(recipients)
            }
        })
    }

    fun sendFingerprintAlert(gp: GuardPlayer) {
        val base = "%.0f".format(gp.fingerprintBaseline * 100.0)
        val now  = "%.0f".format(gp.fingerprintRecent * 100.0)
        if (plugin.configManager.alertsToConsole) {
            plugin.logger.info("[Fingerprint] ${gp.player.name} drift: base=$base% now=$now%")
        }
        val msg = plugin.locale.get(
            Message.FINGERPRINT_ALERT,
            "player", gp.player.name,
            "base",   base,
            "now",    now,
        )
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!gp.player.isOnline) return@Runnable
            val component = clickableAlert(msg, gp.player.name)
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") && !alertsMuted.contains(it.uniqueId) }
                .forEach { it.spigot().sendMessage(component) }
        })
    }

    fun sendSuspiciousAlert(gp: GuardPlayer, buffer: Double) {
        val now = System.currentTimeMillis()
        val last = gp.lastSuspiciousMs.get()
        if (now - last < SUSPICIOUS_THROTTLE_MS) return
        if (!gp.lastSuspiciousMs.compareAndSet(last, now)) return
        if (plugin.configManager.alertsToConsole) {
            plugin.logger.info("[Suspicious] ${gp.player.name} buffer=${"%.1f".format(buffer)}")
        }
        val msg = plugin.locale.get(
            Message.SUSPICIOUS_ALERT,
            "player", gp.player.name,
            "buffer", "%.1f".format(buffer),
            "flag",   "%.0f".format(plugin.configManager.aiBufferFlag),
            "ping",       gp.player.ping.toString(),
            "ping_color", pingColor(gp.player.ping),
            "lag",        lagTag(gp),
        )
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val component = clickableAlert(msg, gp.player.name)
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") && !alertsMuted.contains(it.uniqueId) }
                .forEach { it.spigot().sendMessage(component) }
        })
    }

    fun sendReputationNotice(playerName: String, detections: Int, servers: Int) {
        if (plugin.configManager.alertsToConsole) {
            plugin.logger.info("[Reputation] $playerName - $detections detections on $servers other server(s)")
        }
        val msg = plugin.locale.get(
            Message.REPUTATION_NOTICE,
            "player", playerName,
            "servers", servers.toString(),
            "detections", detections.toString(),
        )
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") && !alertsMuted.contains(it.uniqueId) }
                .forEach { it.sendMessage(msg) }
        })
    }

    fun dispatchMonitorHit(gp: GuardPlayer, probability: Double, model: String = "Def") {
        if (monitorReceivers.isEmpty()) return
        val now = System.currentTimeMillis()
        val last = gp.lastMonitorHitMs.get()
        if (now - last < MONITOR_THROTTLE_MS) return
        if (!gp.lastMonitorHitMs.compareAndSet(last, now)) return
        val pct   = probability * 100.0
        val avg   = gp.avgProbability * 100.0
        val color = plugin.monitorConfig.colorForProbability(pct)
        val avgColor = plugin.monitorConfig.colorForProbability(avg)
        val msg = plugin.locale.get(
            Message.MONITOR_HIT,
            "player",      gp.player.name,
            "model",       "[$model]",
            "color",       color,
            "prob",        "%.1f".format(pct),
            "avg",         "%.1f".format(avg),
            "avg_color",   avgColor,
            "vl",          gp.aiViolationLevel.toString(),
            "ping",        gp.player.ping.toString(),
            "ping_color",  pingColor(gp.player.ping),
            "lag",         lagTag(gp),
            "peak",        "%.1f".format(gp.peakProbability * 100.0),
            "high_count",  gp.highProbCount.toString(),
            "suppression", suppressionTag(gp),
        )
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!gp.player.isOnline) return@Runnable
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") && monitorReceivers.contains(it.uniqueId) }
                .forEach { it.sendMessage(msg) }
        })
    }
    fun deliverCrossServerAlert(sourceServer: String, playerName: String, checkName: String, vl: Int, verbose: String) {
        val msg = plugin.locale.get(
            Message.CROSSSERVER_ALERT,
            "server",  sourceServer,
            "player",  playerName,
            "check",   checkName,
            "vl",      vl.toString(),
            "verbose", verbose,
        )
        Bukkit.getScheduler().runTask(plugin, Runnable {
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") && crossServerEnabled.contains(it.uniqueId) }
                .forEach { it.sendMessage(msg) }
        })
    }
    fun startProbSession(viewer: Player, target: Player): Boolean {
        val viewerId = viewer.uniqueId
        val targetId = target.uniqueId
        if (probSessions[viewerId] == targetId) {
            stopProbSession(viewerId)
            return false
        }
        stopProbSession(viewerId)
        probSessions[viewerId] = targetId

        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val onlineViewer = Bukkit.getPlayer(viewerId)

            if (onlineViewer == null || !onlineViewer.isOnline) {
                stopProbSessionSilent(viewerId)
                return@Runnable
            }
            val onlineTarget = Bukkit.getPlayer(targetId)
            if (onlineTarget == null || !onlineTarget.isOnline) {

                stopProbSession(viewerId)
                return@Runnable
            }
            val gp = plugin.playerDataManager.get(onlineTarget) ?: return@Runnable

            val prob    = gp.lastAiProbability * 100
            val avg     = gp.avgProbability * 100
            val peak    = gp.peakProbability * 100
            val probColor = when {
                prob >= 90.0 -> "&c"
                prob >= 60.0 -> "&e"
                else         -> "&a"
            }
            val avgColor = when {
                avg >= 90.0 -> "&c"
                avg >= 60.0 -> "&e"
                else        -> "&a"
            }
            val msg = plugin.locale.get(
                Message.PROB_ACTIONBAR,
                "player",     onlineTarget.name,
                "prob",       "%.1f".format(prob),
                "prob_color", probColor,
                "avg",        "%.1f".format(avg),
                "avg_color",  avgColor,
                "peak",       "%.1f".format(peak),
                "buffer",     "%.1f".format(gp.aiBuffer),
                "vl",         gp.aiViolationLevel.toString(),
                "ping",       onlineTarget.ping.toString(),
                "ping_color", pingColor(onlineTarget.ping),
                "lag",        lagTag(gp),
            )
            sendActionBar(onlineViewer, msg)
        }, 0L, PROB_UPDATE_TICKS)
        probTasks[viewerId] = task
        return true
    }

    fun stopProbSession(viewerId: UUID) {
        probSessions.remove(viewerId)
        probTasks.remove(viewerId)?.cancel()

        Bukkit.getPlayer(viewerId)?.let { sendActionBar(it, "") }
    }

    private fun stopProbSessionSilent(viewerId: UUID) {
        probSessions.remove(viewerId)
        probTasks.remove(viewerId)?.cancel()
    }

    fun removeProbSession(uuid: UUID) = stopProbSession(uuid)
    fun hasProbSession(uuid: UUID): Boolean = probSessions.containsKey(uuid)
    private fun sendActionBar(player: Player, msg: String) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent(msg))
    }

    private fun clickableAlert(legacyMsg: String, playerName: String): TextComponent {
        val component = TextComponent(*TextComponent.fromLegacyText(legacyMsg))
        component.clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/guardac profile $playerName")
        component.hoverEvent = HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            Text(plugin.locale.get(Message.ALERT_CLICK_HINT, "player", playerName)),
        )
        return component
    }
    private fun playAlertSound(recipients: List<Player>) {
        val soundName = plugin.configManager.alertSoundType
        val volume    = plugin.configManager.alertSoundVolume
        val pitch     = plugin.configManager.alertSoundPitch
        val sound = try {
            Sound.valueOf(soundName)
        } catch (_: IllegalArgumentException) {
            plugin.logger.warning("Invalid alert sound: $soundName")
            return
        }
        recipients.forEach { p ->
            if (p.isOnline) p.playSound(p.location, sound, volume, pitch)
        }
    }
    private fun suppressionTag(gp: GuardPlayer): String {
        if (!plugin.configManager.suppressionEnabled) return ""
        return when (gp.suppressionStage) {
            SuppressionStage.NONE    -> ""
            SuppressionStage.DAMPEN  -> " &8• &#FFC857[Dampen]"
            SuppressionStage.ISOLATE -> " &8• &#FF4D6D[Isolate]"
        }
    }

    private fun formatPercent(v: Double): String = "%.1f".format(v * 100.0)

    fun pingColor(ping: Int): String = when {
        ping >= PING_RED    -> "&c"
        ping >= PING_YELLOW -> "&e"
        else                -> "&a"
    }

    fun lagTag(gp: GuardPlayer): String =
        if (gp.isLagging) plugin.locale.get(Message.LAG_TAG) else ""

    companion object {
        const val MONITOR_THROTTLE_MS = 1_000L
        const val ALERT_THROTTLE_MS   = 1_000L
        const val SUSPICIOUS_THROTTLE_MS = 15_000L

        const val PROB_UPDATE_TICKS   = 10L

        const val PING_YELLOW = 100
        const val PING_RED    = 200
    }
}
