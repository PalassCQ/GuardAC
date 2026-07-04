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

package dev.guardac.player

import dev.guardac.GuardAC
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerDataManager(private val plugin: GuardAC) : Listener {

    private val players = ConcurrentHashMap<UUID, GuardPlayer>()

    private val entityIdToUuid = ConcurrentHashMap<Int, UUID>()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val gp = add(event.player)
        restorePersistedBuffer(gp)
        checkReputation(gp)
    }

    private fun checkReputation(gp: GuardPlayer) {
        val cfg = plugin.configManager
        if (!cfg.reputationEnabled || !cfg.reputationCheckOnJoin) return
        val name = gp.player.name
        plugin.reputationClient.query(gp.uuid).thenAccept { result ->
            if (result != null && result.detections >= cfg.reputationAlertThreshold) {
                plugin.alertManager.sendReputationNotice(name, result.detections, result.servers)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val uuid = player.uniqueId

        get(uuid)?.let { gp ->
            if (plugin.configManager.persistBufferEnabled && gp.aiBuffer > 0.0) {
                plugin.punishmentHistory.saveBuffer(uuid, gp.aiBuffer, gp.aiViolationLevel)
            }
            plugin.suppressionManager.onQuit(gp)
        }
        plugin.scanManager.onQuit(uuid)
        plugin.hologramManager.onQuit(player)
        plugin.alertManager.removeProbSession(uuid)
        plugin.punishmentManager.onPlayerQuit(uuid)
        entityIdToUuid.remove(player.entityId)
        remove(uuid)
    }

    private fun restorePersistedBuffer(gp: GuardPlayer) {
        val cfg = plugin.configManager
        if (!cfg.persistBufferEnabled) return

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val rec = plugin.punishmentHistory.loadBuffer(gp.uuid) ?: return@Runnable
            plugin.punishmentHistory.clearBuffer(gp.uuid)

            val ageMinutes = (System.currentTimeMillis() - rec.epochMillis) / 60000.0
            if (ageMinutes > cfg.persistBufferTtlMinutes || rec.buffer <= 0.0) return@Runnable

            val decayedBuffer = if (ageMinutes <= cfg.persistBufferGraceMinutes) {
                rec.buffer
            } else {
                val hoursBeyondGrace = (ageMinutes - cfg.persistBufferGraceMinutes) / 60.0
                (rec.buffer - hoursBeyondGrace * cfg.persistBufferDecayPerHour).coerceAtLeast(0.0)
            }
            if (decayedBuffer <= 0.0) return@Runnable

            val cappedBuffer = decayedBuffer.coerceAtMost(cfg.persistBufferCapOnRestore)
            val retainedFraction = cappedBuffer / rec.buffer
            val decayedVl = (rec.vl * retainedFraction).toInt()
            gp.restorePersisted(cappedBuffer, decayedVl)
        })
    }

    fun add(player: Player): GuardPlayer {
        val gp = GuardPlayer(player.uniqueId, player, plugin)
        players[player.uniqueId] = gp
        entityIdToUuid[player.entityId] = player.uniqueId
        return gp
    }

    fun remove(uuid: UUID) = players.remove(uuid)

    fun uuidByEntityId(entityId: Int): UUID? = entityIdToUuid[entityId]

    fun get(player: Player): GuardPlayer? = players[player.uniqueId]

    fun get(uuid: UUID): GuardPlayer? = players[uuid]

    fun getAll(): Collection<GuardPlayer> = players.values

    fun reloadAll() {
        plugin.server.onlinePlayers.forEach { player ->
            players.computeIfAbsent(player.uniqueId) {
                GuardPlayer(player.uniqueId, player, plugin)
            }
            entityIdToUuid[player.entityId] = player.uniqueId
        }
    }
}
