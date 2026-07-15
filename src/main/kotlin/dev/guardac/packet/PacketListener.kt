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
import dev.guardac.GuardAC
import dev.guardac.data.TickData
import dev.guardac.player.GuardPlayer
import org.bukkit.entity.Player

class PacketListener(private val plugin: GuardAC) :
    PacketListenerAbstract(PacketListenerPriority.LOW) {

    override fun onPacketSend(event: PacketSendEvent) {
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
                handleRotation(gp, w.yaw, w.pitch)
            }
            PacketType.Play.Client.PLAYER_POSITION -> {
                gp.noteMovement()
            }
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION -> {
                gp.noteMovement()
                val w = WrapperPlayClientPlayerPositionAndRotation(event)
                handleRotation(gp, w.yaw, w.pitch)
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
        gp.recordRotationTiming(System.nanoTime())
        gp.rotation.update(yaw, pitch)
        if (gp.consumeTeleportGate()) {

            gp.rotation.clearState()
            return
        }

        val dyaw   = gp.rotation.deltaYaw
        val dpitch = gp.rotation.deltaPitch

        gp.noteAimActivity(dyaw, dpitch)
        if (dyaw == 0f && dpitch == 0f) return

        plugin.checkRegistry.rotationChecks.forEach { it.onRotation(gp) }

        gp.combat.tickElapsed()
        val recordForAi = !gp.isRiding && gp.combat.isInCombatWindow(plugin.configManager.aiSequence)

        val tick = buildTick(gp)
        gp.onTick(tick, recordForAi)

        if (recordForAi) {
            plugin.dataCollectorManager.addTick(gp.uuid, tick)
        }

        gp.pollSequence()?.let { seq ->
            plugin.checkRegistry.sequenceChecks.forEach { it.onSequence(gp, seq) }
        }
    }

    private fun handleAttack(gp: GuardPlayer, entityId: Int, player: Player) {

        val targetUuid = plugin.playerDataManager.uuidByEntityId(entityId) ?: return
        if (targetUuid == player.uniqueId) return

        val minRotation = if (gp.isMovingRecently) MIN_HIT_ROTATION_MOVING else MIN_HIT_ROTATION_STILL
        if (gp.recentAimSum() < minRotation) return

        gp.combat.recordAttack()
    }

    private companion object {

        const val MIN_HIT_ROTATION_STILL  = 8.0
        const val MIN_HIT_ROTATION_MOVING = 16.0
    }

    private fun buildTick(gp: GuardPlayer) = TickData(
        deltaYaw      = gp.rotation.deltaYaw,
        deltaPitch    = gp.rotation.deltaPitch,
        accelYaw      = gp.rotation.accelYaw,
        accelPitch    = gp.rotation.accelPitch,
        jerkYaw       = gp.rotation.jerkYaw,
        jerkPitch     = gp.rotation.jerkPitch,
        gcdErrorYaw   = gp.rotation.gcdErrorYaw,
        gcdErrorPitch = gp.rotation.gcdErrorPitch,
    )
}
