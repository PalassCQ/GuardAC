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

@file:Suppress("UNCHECKED_CAST", "RAW_TYPE_IN_GENERIC_TYPES", "RAW_TYPES")

package dev.guardac.hologram

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import dev.guardac.GuardAC
import dev.guardac.player.GuardPlayer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.ArrayList
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HologramManager(private val plugin: GuardAC) {

    private val viewers = ConcurrentHashMap<UUID, ViewerState>()
    private val entityIdCounter = AtomicInteger(ENTITY_ID_START)
    private var task: BukkitTask? = null

    fun start() {
        if (!plugin.hologramConfig.enabled) return
        task = plugin.server.scheduler.runTaskTimer(
            plugin, Runnable { tick() },
            TASK_DELAY, plugin.hologramConfig.updateIntervalTicks.toLong()
        )
    }

    fun stop() {
        task?.cancel()
        task = null
        viewers.keys.toList().forEach { viewerId ->
            val state = viewers.remove(viewerId) ?: return@forEach
            val viewer = Bukkit.getPlayer(viewerId) ?: return@forEach
            destroyAll(viewer, state)
        }
        viewers.clear()
    }

    fun reload() {
        stop()
        start()
    }

    fun onQuit(player: Player) {
        val state = viewers.remove(player.uniqueId)
        if (state != null) destroyAll(player, state)
        viewers.forEach { (viewerId, viewerState) ->
            val viewer = Bukkit.getPlayer(viewerId) ?: return@forEach
            removeTarget(viewer, player.uniqueId, viewerState)
        }
    }

    private fun tick() {
        val cfg    = plugin.hologramConfig
        val online = Bukkit.getOnlinePlayers().toList()
        if (online.isEmpty()) return

        val viewDistSq = cfg.viewDistance * cfg.viewDistance
        val staffList  = online.filter { it.hasPermission("guardac.alerts") }
        val staffIds   = staffList.map { it.uniqueId }.toHashSet()

        viewers.keys.filter { it !in staffIds }.forEach { id ->
            val state = viewers.remove(id) ?: return@forEach
            Bukkit.getPlayer(id)?.let { destroyAll(it, state) }
        }
        if (staffList.isEmpty()) return

        for (viewer in staffList) {
            val state       = viewers.getOrPut(viewer.uniqueId) { ViewerState(viewer.uniqueId) }
            val viewerLoc   = viewer.location
            val viewerWorld = viewerLoc.world?.name ?: continue
            val alive       = mutableSetOf<UUID>()

            for (target in online) {
                if (target == viewer) continue
                if (target.world?.name != viewerWorld) continue
                if (target.isDead) continue
                val gp = plugin.playerDataManager.get(target) ?: continue
                val targetLoc = target.location
                if (viewerLoc.distanceSquared(targetLoc) > viewDistSq) continue

                alive += target.uniqueId

                val holoLoc = targetLoc.clone().add(0.0, cfg.yOffset, 0.0)
                updateTarget(viewer, target.uniqueId, holoLoc, gp, state, cfg)
            }

            state.targets.keys.filter { it !in alive }.forEach { removeTarget(viewer, it, state) }
        }
    }

    private fun updateTarget(
        viewer: Player, targetId: UUID, loc: Location,
        gp: GuardPlayer, state: ViewerState, cfg: HologramConfig,
    ) {
        val text  = buildText(gp, cfg)
        val cache = state.targets[targetId]
        if (cache == null) {
            val entityId = state.entityIds.getOrPut(targetId) { entityIdCounter.incrementAndGet() }
            destroy(viewer, entityId)
            spawn(viewer, entityId, loc, text)
            state.targets[targetId] = EntityCache(entityId, text, loc)
        } else {
            teleport(viewer, cache.entityId, loc)
            if (text != cache.lastText) {
                updateText(viewer, cache.entityId, text)
                cache.lastText = text
            }
            cache.lastLoc = loc
        }
    }

    private fun removeTarget(viewer: Player, targetId: UUID, state: ViewerState) {
        val cache = state.targets.remove(targetId) ?: return
        destroy(viewer, cache.entityId)
    }

    private fun destroyAll(viewer: Player, state: ViewerState) {
        state.targets.values.forEach { destroy(viewer, it.entityId) }
        state.targets.clear()
    }

    private fun buildText(gp: GuardPlayer, cfg: HologramConfig): String {
        val avg      = gp.avgProbability
        val avgColor = cfg.colorFor(avg)

        val avgStr   = "$avgColor${"%.0f".format(avg * 100.0)}%"

        val hitHistory = gp.getHitProbHistory()
        val histPart = if (hitHistory.isEmpty()) {
            "&8---"
        } else {
            hitHistory.joinToString(" ") { prob ->
                val color = cfg.colorFor(prob)
                "$color${"%.0f".format(prob * 100.0)}%"
            }
        }

        val formatted = cfg.format
            .replace("{AVG}",  avgStr)
            .replace("{PROB}", "${"${cfg.colorFor(gp.lastAiProbability)}"}${"%.0f".format(gp.lastAiProbability * 100.0)}%")
            .replace("{VL}",   gp.aiViolationLevel.toString())
            .replace("{HIST}", histPart)

        return GSON.serialize(LEGACY.deserialize(formatted))
    }

    private fun spawn(viewer: Player, entityId: Int, loc: Location, text: String) {
        PacketEvents.getAPI().playerManager.sendPacket(
            viewer,
            WrapperPlayServerSpawnEntity(
                entityId, Optional.of(UUID.randomUUID()), EntityTypes.ARMOR_STAND,
                Vector3d(loc.x, loc.y, loc.z), 0f, 0f, 0f, 0,
                Optional.of(Vector3d(0.0, 0.0, 0.0))
            )
        )
        sendMeta(viewer, entityId, text)
    }

    @Suppress("RAW_TYPES")
    private fun sendMeta(viewer: Player, entityId: Int, text: String) {
        val meta = ArrayList<EntityData>()
        meta.add(EntityData(0, EntityDataTypes.BYTE, ENTITY_FLAG_INVISIBLE))
        meta.add(EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(text)))
        meta.add(EntityData(3, EntityDataTypes.BOOLEAN, true))
        meta.add(EntityData(armorStandFlagsIndex(), EntityDataTypes.BYTE, ARMOR_STAND_MARKER))
        try {
            PacketEvents.getAPI().playerManager.sendPacket(viewer, WrapperPlayServerEntityMetadata(entityId, meta))
        } catch (e: Exception) {
            plugin.logger.warning("[Hologram] sendMeta failed for entity $entityId: ${e.message}")
        }
    }

    private fun teleport(viewer: Player, entityId: Int, loc: Location) {
        PacketEvents.getAPI().playerManager.sendPacket(
            viewer,
            WrapperPlayServerEntityTeleport(entityId, Vector3d(loc.x, loc.y, loc.z), 0f, 0f, true)
        )
    }

    @Suppress("RAW_TYPES")
    private fun updateText(viewer: Player, entityId: Int, text: String) {
        val meta = ArrayList<EntityData>()
        meta.add(EntityData(2, EntityDataTypes.OPTIONAL_COMPONENT, Optional.of(text)))
        try {
            PacketEvents.getAPI().playerManager.sendPacket(viewer, WrapperPlayServerEntityMetadata(entityId, meta))
        } catch (e: Exception) {
            plugin.logger.warning("[Hologram] updateText failed for entity $entityId: ${e.message}")
        }
    }

    private fun destroy(viewer: Player, entityId: Int) {
        if (!viewer.isOnline) return
        PacketEvents.getAPI().playerManager.sendPacket(viewer, WrapperPlayServerDestroyEntities(entityId))
    }

    private fun armorStandFlagsIndex(): Int {
        val v = PacketEvents.getAPI().serverManager.version ?: return 15
        return when {
            v.isNewerThanOrEquals(ServerVersion.V_1_17) -> 15
            v.isNewerThanOrEquals(ServerVersion.V_1_15) -> 14
            v.isNewerThanOrEquals(ServerVersion.V_1_14) -> 13
            else                                         -> 11
        }
    }

    private class ViewerState(val viewerId: UUID) {
        val targets   = ConcurrentHashMap<UUID, EntityCache>()
        val entityIds = ConcurrentHashMap<UUID, Int>()
    }

    private class EntityCache(val entityId: Int, var lastText: String, var lastLoc: Location?)

    private companion object {
        val LEGACY = LegacyComponentSerializer.legacyAmpersand()
        val GSON   = GsonComponentSerializer.gson()

        const val ENTITY_ID_START             = 43_000_000
        const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        const val ARMOR_STAND_MARKER: Byte    = 0x10
        const val TASK_DELAY                  = 10L
    }
}
