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

package dev.guardac.player

import dev.guardac.GuardAC
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PlayerDataManager(private val plugin: GuardAC) : Listener {

    private val players = ConcurrentHashMap<UUID, GuardPlayer>()

    private val entityIdToUuid = ConcurrentHashMap<Int, UUID>()

    private val playerVehicle = ConcurrentHashMap<UUID, Int>()

    private class Carry(val buffer: Double, val vl: Int, val epochMillis: Long)

    private val carryOver = ConcurrentHashMap<UUID, Carry>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLogin(event: PlayerLoginEvent) {
        plugin.reputationClient.recordJoinHost(event.hostname)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val gp = add(event.player)
        plugin.banAnimationManager.onJoin(event.player)
        plugin.alertManager.restorePrefs(event.player)
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

            if (plugin.configManager.persistBufferEnabled &&
                (gp.aiBuffer > 0.0 || gp.aiViolationLevel > 0)
            ) {
                carryOver[uuid] = Carry(gp.aiBuffer, gp.aiViolationLevel, System.currentTimeMillis())
                pruneCarryOver()
                plugin.punishmentHistory.saveBuffer(uuid, gp.aiBuffer, gp.aiViolationLevel)
            }
            plugin.suppressionManager.onQuit(gp)
        }
        plugin.hologramManager.onQuit(player)
        plugin.alertManager.removeProbSession(uuid)
        plugin.alertManager.onPlayerQuit(uuid)
        plugin.punishmentManager.onPlayerQuit(uuid)
        plugin.banAnimationManager.onQuit(uuid)
        entityIdToUuid.remove(player.entityId)
        playerVehicle.remove(uuid)
        remove(uuid)
    }

    fun updatePassengers(vehicleId: Int, passengers: IntArray) {
        val riders = ArrayList<UUID>(passengers.size)
        for (id in passengers) {
            entityIdToUuid[id]?.let { riders.add(it) }
        }

        playerVehicle.entries.removeIf { entry ->
            if (entry.value == vehicleId && !riders.contains(entry.key)) {
                players[entry.key]?.setRiding(false)
                true
            } else {
                false
            }
        }
        for (uuid in riders) {
            playerVehicle[uuid] = vehicleId
            players[uuid]?.setRiding(true)
        }
    }

    private fun restorePersistedBuffer(gp: GuardPlayer) {
        val cfg = plugin.configManager
        if (!cfg.persistBufferEnabled) return

        carryOver.remove(gp.uuid)?.let { carry ->
            applyPersisted(gp, carry.buffer, carry.vl, carry.epochMillis)
            plugin.scheduler.async(Runnable { plugin.punishmentHistory.clearBuffer(gp.uuid) })
            return
        }

        plugin.scheduler.async(Runnable {
            val rec = plugin.punishmentHistory.loadBuffer(gp.uuid) ?: return@Runnable
            plugin.punishmentHistory.clearBuffer(gp.uuid)
            applyPersisted(gp, rec.buffer, rec.vl, rec.epochMillis)
        })
    }

    private fun applyPersisted(gp: GuardPlayer, buffer: Double, vl: Int, savedAtMs: Long) {
        val cfg = plugin.configManager
        val ageMinutes = (System.currentTimeMillis() - savedAtMs) / 60000.0
        if (ageMinutes < 0.0) return
        if (ageMinutes > cfg.persistBufferTtlMinutes) return

        val hoursBeyondGrace =
            ((ageMinutes - cfg.persistBufferGraceMinutes) / 60.0).coerceAtLeast(0.0)

        val decayedBuffer =
            (buffer - hoursBeyondGrace * cfg.persistBufferDecayPerHour).coerceAtLeast(0.0)
        val cappedBuffer = decayedBuffer.coerceAtMost(cfg.persistBufferCapOnRestore)

        val decayedVl = (vl - hoursBeyondGrace.toInt()).coerceAtLeast(0)

        if (cappedBuffer <= 0.0 && decayedVl <= 0) return
        gp.restorePersisted(cappedBuffer, decayedVl)
    }

    private fun pruneCarryOver() {
        if (carryOver.size <= CARRY_OVER_MAX) return
        val cutoff = System.currentTimeMillis() -
            (plugin.configManager.persistBufferTtlMinutes * 60_000.0).toLong()
        carryOver.entries.removeIf { it.value.epochMillis < cutoff }
        if (carryOver.size > CARRY_OVER_MAX) carryOver.clear()
    }

    fun add(player: Player): GuardPlayer {
        val gp = GuardPlayer(player.uniqueId, player, plugin)
        players[player.uniqueId] = gp
        entityIdToUuid[player.entityId] = player.uniqueId
        gp.setRiding(player.isInsideVehicle)
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

    fun adoptOnline() {
        plugin.server.onlinePlayers.forEach { player ->
            if (players.containsKey(player.uniqueId)) return@forEach
            val gp = add(player)
            restorePersistedBuffer(gp)
        }
    }

    private companion object {
        const val CARRY_OVER_MAX = 512
    }
}
