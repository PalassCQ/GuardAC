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

package dev.guardac.combat

import dev.guardac.GuardAC
import dev.guardac.player.GuardPlayer
import dev.guardac.util.Message
import org.bukkit.Bukkit
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import dev.guardac.util.TaskHandle
import java.util.UUID

class SuppressionManager(private val plugin: GuardAC) {

    private var task: TaskHandle? = null

    fun start() {
        stop()
        task = plugin.scheduler.globalTimer(SYNC_INTERVAL_TICKS, SYNC_INTERVAL_TICKS) { syncAll() }
    }

    fun stop() {
        task?.cancel()
        task = null
        plugin.playerDataManager.getAll().forEach { gp ->
            if (gp.player.isOnline) plugin.scheduler.entity(gp.player, Runnable { clearModifier(gp) })
        }
    }

    private fun syncAll() {
        val enabled = plugin.configManager.suppressionEnabled
        plugin.playerDataManager.getAll().forEach { gp ->
            if (!gp.player.isOnline) return@forEach

            plugin.scheduler.entity(gp.player, Runnable {
                if (!enabled) clearModifier(gp) else syncOne(gp)
            })
        }
    }

    private fun syncOne(gp: GuardPlayer) {
        val attr = gp.player.getAttribute(Attribute.GENERIC_ATTACK_SPEED) ?: return
        attr.modifiers.filter { it.uniqueId == MODIFIER_ID }.forEach { attr.removeModifier(it) }
        val penalty = gp.currentAttackSpeedPenalty()
        if (penalty > 0.0 && !gp.isExempt) {
            attr.addModifier(AttributeModifier(MODIFIER_ID, MODIFIER_NAME, -penalty, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
        }
    }

    private fun clearModifier(gp: GuardPlayer) {
        if (!gp.player.isOnline) return
        val attr = gp.player.getAttribute(Attribute.GENERIC_ATTACK_SPEED) ?: return
        attr.modifiers.filter { it.uniqueId == MODIFIER_ID }.forEach { attr.removeModifier(it) }
    }

    fun onQuit(gp: GuardPlayer) = clearModifier(gp)

    fun notifyIsolate(gp: GuardPlayer) {
        if (!plugin.configManager.suppressionIsolateNotify) return
        if (!gp.player.isOnline) return
        gp.player.sendMessage(plugin.locale.get(Message.SUPPRESSION_ISOLATE_NOTICE))
    }

    private companion object {
        const val SYNC_INTERVAL_TICKS = 10L
        val MODIFIER_ID: UUID = UUID.fromString("7d9f6b1e-3f2a-4c9e-8f3a-1c2d3e4f5a6b")
        const val MODIFIER_NAME = "guardac-suppression"
    }
}
