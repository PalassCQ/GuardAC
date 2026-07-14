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
        // Same color scale as /guard monitor (monitor.yml thresholds), so the
        // nick and the number read identically in both streams.
        val probColor = plugin.monitorConfig.colorForProbability(gp.lastAiProbability * 100.0)
        val buffer = "%.1f".format(gp.aiBuffer)
        val msg = plugin.locale.get(
            Message.ALERTS_FORMAT,
            "player",     playerName,
            "check",      checkName,
            "model",      model,
            "vl",         vl.toString(),
            "verbose",    verbose,
            "prob_color", probColor,
            "buffer",     buffer,
            "avg",        formatPercent(gp.avgProbability),
            "peak",       formatPercent(gp.peakProbability),
        )
        val consoleLine = plugin.locale.get(
            Message.ALERTS_CONSOLE_FORMAT,
            "player",  playerName,
            "check",   checkName,
            "model",   model,
            "vl",      vl.toString(),
            "verbose", verbose,
            "buffer",  buffer,
        )
        deliverAlert(msg, consoleLine, playerName, withSound = true)
    }

    // ------------------------------------------------------------------
    // Per-hit alerts, batched by count: every alerts.min-hits confident hits
    // of a fight produce one line with the running total - x3, then x6, x9...
    // A lone spike below the bar stays silent. An episode closes after
    // EPISODE_IDLE_MS without confident hits and the count starts over.
    // ------------------------------------------------------------------
    private class HitDigest {
        var lastHitMs = 0L
        var episodeHits = 0
        var batchMax = 0.0
        var model = "[AI]"
    }

    private val digests = ConcurrentHashMap<UUID, HitDigest>()

    fun sendHitAlert(gp: GuardPlayer, probability: Double, model: String) {
        val minHits = plugin.configManager.alertMinHits.coerceAtLeast(1)
        val d = digests.computeIfAbsent(gp.uuid) { HitDigest() }
        var announceCount = 0
        var announceMax = 0.0
        var firstOfEpisode = false
        synchronized(d) {
            val now = System.currentTimeMillis()
            if (now - d.lastHitMs > EPISODE_IDLE_MS) {
                // New fight: the counter starts over.
                d.episodeHits = 0
                d.batchMax = 0.0
            }
            d.lastHitMs = now
            d.episodeHits++
            if (probability > d.batchMax) d.batchMax = probability
            d.model = model
            if (d.episodeHits % minHits == 0) {
                announceCount = d.episodeHits
                announceMax = d.batchMax
                firstOfEpisode = d.episodeHits == minHits
                // The next line reports the peak of ITS OWN batch of hits.
                d.batchMax = 0.0
            }
        }
        if (announceCount > 0) {
            sendCountAlert(gp, announceCount, announceMax, model, withSound = firstOfEpisode)
        }
    }

    private fun sendCountAlert(gp: GuardPlayer, count: Int, maxProb: Double, model: String, withSound: Boolean) {
        val playerName = gp.player.name
        val probColor = plugin.monitorConfig.colorForProbability(maxProb * 100.0)
        val buffer = "%.1f".format(gp.aiBuffer)
        val max = detailed(maxProb)
        val msg = plugin.locale.get(
            Message.ALERTS_DIGEST_FORMAT,
            "player",     playerName,
            "model",      model,
            "count",      count.toString(),
            "max",        max,
            "prob_color", probColor,
            "buffer",     buffer,
        )
        val consoleLine = plugin.locale.get(
            Message.ALERTS_CONSOLE_FORMAT,
            "player",  playerName,
            "check",   "AI",
            "model",   model,
            "vl",      gp.aiViolationLevel.toString(),
            "verbose", "x$count max $max",
            "buffer",  buffer,
        )
        // The chime plays once per fight (the x3 line); follow-ups are quiet.
        deliverAlert(msg, consoleLine, playerName, withSound)
    }

    fun onPlayerQuit(uuid: UUID) {
        digests.remove(uuid)
    }

    // Dot decimal separator regardless of the server's system locale.
    private fun detailed(p: Double): String = "%.12f".format(java.util.Locale.ROOT, p)

    private fun deliverAlert(msg: String, consoleLine: String, playerName: String, withSound: Boolean) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (plugin.configManager.alertsToConsole) {
                plugin.logger.info(consoleLine)
            }
            val recipients = Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") }
                .filter { !alertsMuted.contains(it.uniqueId) }

            val component = clickableAlert(msg, playerName)
            recipients.forEach { it.spigot().sendMessage(component) }
            if (withSound && plugin.configManager.alertSoundEnabled) {
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

    fun sendClientArtifactAlert(gp: GuardPlayer, count: Int) {
        if (plugin.configManager.alertsToConsole) {
            plugin.logger.info("[Client] ${gp.player.name} non-physical yaw snaps: x$count")
        }
        val msg = plugin.locale.get(
            Message.CLIENT_ARTIFACT_ALERT,
            "player", gp.player.name,
            "count",  count.toString(),
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
            "detailed",    "%.12f".format(java.util.Locale.ROOT, probability),
            "buffer",      "%.1f".format(gp.aiBuffer),
            "prob",        "%.1f".format(pct),
            "avg",         "%.1f".format(avg),
            "avg_color",   avgColor,
            "vl",          gp.aiViolationLevel.toString(),
            "ping",        gp.player.ping.toString(),
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
            // Same color scale as alerts and /guard monitor (monitor.yml), so
            // the HUD reads identically to every other staff surface.
            val probColor = plugin.monitorConfig.colorForProbability(prob)
            val avgColor  = plugin.monitorConfig.colorForProbability(avg)
            val pingColor = when {
                onlineTarget.ping > 200 -> "&c"
                onlineTarget.ping > 100 -> "&e"
                else                    -> "&#AEB8C4"
            }
            val msg = plugin.locale.get(
                Message.PROB_ACTIONBAR,
                "player",     onlineTarget.name,
                "bar",        probBar(prob),
                "prob",       "%.1f".format(prob),
                "prob_color", probColor,
                "avg",        "%.1f".format(avg),
                "avg_color",  avgColor,
                "peak",       "%.1f".format(peak),
                "buffer",     "%.1f".format(gp.aiBuffer),
                "vl",         gp.aiViolationLevel.toString(),
                "ping",       onlineTarget.ping.toString(),
                "ping_color", pingColor,
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

    // 10-segment probability meter for the /guard prob HUD. Filled segments are
    // colored by THEIR OWN level on the shared monitor.yml scale, so the bar
    // reads as a green->red gradient as suspicion grows.
    private fun probBar(pct: Double): String {
        val filled = (pct / 10.0).toInt().coerceIn(0, 10)
        val sb = StringBuilder()
        for (i in 1..10) {
            if (i <= filled) {
                sb.append(plugin.monitorConfig.colorForProbability(i * 10.0 - 5.0)).append('▰')
            } else {
                sb.append("&8▱")
            }
        }
        return sb.toString()
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
    companion object {
        const val MONITOR_THROTTLE_MS = 1_000L
        const val ALERT_THROTTLE_MS   = 1_000L
        const val SUSPICIOUS_THROTTLE_MS = 15_000L
        // A pause this long without confident hits closes the digest episode,
        // so the next fight starts counting from zero again.
        const val EPISODE_IDLE_MS     = 30_000L

        const val PROB_UPDATE_TICKS   = 10L
    }
}
