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

package dev.guardac.packet

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerPositionAndRotation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import dev.guardac.GuardAC
import dev.guardac.sample.AimSample
import dev.guardac.player.GuardPlayer
import org.bukkit.entity.Player
import kotlin.math.abs

class PacketListener(private val plugin: GuardAC) :
    PacketListenerAbstract(PacketListenerPriority.LOW) {

    override fun onPacketSend(event: PacketSendEvent) {
        if (event.packetType == PacketType.Play.Server.SET_PASSENGERS) {
            val w = WrapperPlayServerSetPassengers(event)
            plugin.playerDataManager.updatePassengers(w.entityId, w.passengers)
            return
        }
        if (event.packetType != PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) return
        val player = event.player as? Player ?: return
        val gp     = plugin.playerDataManager.get(player) ?: return
        val w      = WrapperPlayServerPlayerPositionAndLook(event)
        gp.markTeleportSent(w.teleportId)
    }

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val player = event.player as? Player ?: return
        val gp     = plugin.playerDataManager.get(player) ?: return
        if (gp.isExempt) return

        when (event.packetType) {
            PacketType.Play.Client.TELEPORT_CONFIRM -> {
                val w = WrapperPlayClientTeleportConfirm(event)
                gp.confirmTeleport(w.teleportId)
            }
            PacketType.Play.Client.PLAYER_ROTATION -> {
                val w = WrapperPlayClientPlayerRotation(event)
                gp.combat.tickElapsed()
                gp.onServerTick()
                handleRotation(gp, w.yaw, w.pitch)
            }
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION -> {
                val w = WrapperPlayClientPlayerPositionAndRotation(event)
                gp.combat.tickElapsed()
                gp.onServerTick()
                handleRotation(gp, w.yaw, w.pitch)
            }
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_FLYING -> {
                gp.combat.tickElapsed()
                gp.onServerTick()
            }
            PacketType.Play.Client.INTERACT_ENTITY -> {
                val w = WrapperPlayClientInteractEntity(event)
                if (w.action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                    handleAttack(gp, w.entityId, player)
                }
            }
            else -> {}
        }
    }

    private fun handleRotation(gp: GuardPlayer, yaw: Float, pitch: Float) {
        if (!isSaneRotation(yaw) || !isSaneRotation(pitch)) return

        val now = System.nanoTime()
        gp.recordRotationTiming(now)
        gp.rotation.update(yaw, pitch)
        if (gp.consumeTeleportGate()) {

            gp.rotation.clearState()
            return
        }

        val dyaw   = gp.rotation.deltaYaw
        val dpitch = gp.rotation.deltaPitch

        if (dyaw == 0f && dpitch == 0f) return

        val active = abs(dyaw) + abs(dpitch) >= plugin.configManager.aiDeadZone
        if (gp.isStaleSample(now, active)) {
            gp.rotation.seedRest()
        }

        if (gp.rotation.consumeWarmup()) return

        plugin.checkRegistry.rotationChecks.forEach { it.onRotation(gp) }

        val cfg = plugin.configManager
        val tick = buildTick(gp)
        gp.onTick(tick, !gp.isRiding)

        if (!gp.isRiding && gp.combat.isInCombatWindow(cfg.aiSequence)) {
            plugin.recorder.offer(gp.uuid, tick)
        }

        if (cfg.aiContinuous || gp.combat.isInCombatWindow(cfg.aiSequence)) {
            gp.pollSequence()?.let { seq ->
                plugin.checkRegistry.sequenceChecks.forEach { it.onSequence(gp, seq) }
            }
        }
    }

    private fun handleAttack(gp: GuardPlayer, entityId: Int, player: Player) {
        val targetUuid = plugin.playerDataManager.uuidByEntityId(entityId)
        if (targetUuid == player.uniqueId) return

        if (targetUuid == null && plugin.recorder.captureOf(gp.uuid) == null) return

        if (!gp.combat.foughtWithinSeconds(COMBAT_EPISODE_SECONDS)) {
            gp.beginCombatEpisode()
        }
        gp.combat.recordAttack()

        if (!gp.isRiding) {
            gp.pollAttackSequence()?.let { seq ->
                plugin.checkRegistry.sequenceChecks.forEach { it.onSequence(gp, seq, true) }
            }
        }
    }

    private fun isSaneRotation(v: Float): Boolean =
        !v.isNaN() && !v.isInfinite() && abs(v) <= MAX_ROTATION_MAGNITUDE

    private fun buildTick(gp: GuardPlayer) = AimSample(
        deltaYaw      = gp.rotation.deltaYaw,
        deltaPitch    = gp.rotation.deltaPitch,
        accelYaw      = gp.rotation.accelYaw,
        accelPitch    = gp.rotation.accelPitch,
        jerkYaw       = gp.rotation.jerkYaw,
        jerkPitch     = gp.rotation.jerkPitch,
        gcdErrorYaw   = gp.rotation.gcdErrorYaw,
        gcdErrorPitch = gp.rotation.gcdErrorPitch,
    )

    private companion object {
        const val MAX_ROTATION_MAGNITUDE = 1.0e6f
        const val COMBAT_EPISODE_SECONDS = 10L
    }
}
