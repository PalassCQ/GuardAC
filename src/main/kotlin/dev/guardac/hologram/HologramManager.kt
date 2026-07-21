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
import dev.guardac.util.TaskHandle
import java.util.ArrayList
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HologramManager(private val plugin: GuardAC) {

    private val viewers = ConcurrentHashMap<UUID, ViewerState>()
    private val entityIdCounter = AtomicInteger(ENTITY_ID_START)
    private var task: TaskHandle? = null

    fun start() {
        if (!plugin.hologramConfig.enabled) return

        task = plugin.scheduler.globalTimer(
            TASK_DELAY, plugin.hologramConfig.updateIntervalTicks.toLong(),
        ) { tick() }
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

            runCatching {
                val state       = viewers.getOrPut(viewer.uniqueId) { ViewerState(viewer.uniqueId) }
                val viewerLoc   = viewer.location
                val viewerWorld = viewerLoc.world?.name ?: return@runCatching
                val alive       = mutableSetOf<UUID>()

                for (target in online) {
                    if (target == viewer) continue
                    if (target.world?.name != viewerWorld) continue
                    if (target.isDead) continue
                    val gp = plugin.playerDataManager.get(target) ?: continue
                    val targetLoc = target.location
                    if (viewerLoc.distanceSquared(targetLoc) > viewDistSq) continue

                    if (gp.getHitProbHistory().isEmpty()) continue

                    alive += target.uniqueId

                    val holoLoc = targetLoc.clone().add(0.0, cfg.yOffset, 0.0)
                    updateTarget(viewer, target.uniqueId, holoLoc, gp, state, cfg)
                }

                state.targets.keys.filter { it !in alive }.forEach { removeTarget(viewer, it, state) }
            }
        }
    }

    private fun updateTarget(
        viewer: Player, targetId: UUID, loc: Location,
        gp: GuardPlayer, state: ViewerState, cfg: HologramConfig,
    ) {
        val texts = buildLines(gp, cfg)
        val cache = state.targets.getOrPut(targetId) { EntityCache() }
        val lh    = cfg.lineHeight

        while (cache.lines.size > texts.size) {
            destroy(viewer, cache.lines.removeAt(cache.lines.size - 1).entityId)
        }

        for (i in texts.indices) {
            val lineLoc = loc.clone().add(0.0, (texts.size - 1 - i) * lh, 0.0)
            val line    = cache.lines.getOrNull(i)
            if (line == null) {
                val entityId = entityIdCounter.incrementAndGet()
                spawn(viewer, entityId, lineLoc, texts[i])
                cache.lines.add(LineEntity(entityId, texts[i]))
            } else {
                teleport(viewer, line.entityId, lineLoc)
                if (texts[i] != line.lastText) {
                    updateText(viewer, line.entityId, texts[i])
                    line.lastText = texts[i]
                }
            }
        }
    }

    private fun removeTarget(viewer: Player, targetId: UUID, state: ViewerState) {
        val cache = state.targets.remove(targetId) ?: return
        cache.lines.forEach { destroy(viewer, it.entityId) }
    }

    private fun destroyAll(viewer: Player, state: ViewerState) {
        state.targets.values.forEach { c -> c.lines.forEach { destroy(viewer, it.entityId) } }
        state.targets.clear()
    }

    private fun buildLines(gp: GuardPlayer, cfg: HologramConfig): List<String> {
        val lines = ArrayList<String>(cfg.maxHits + 1)

        val hits = gp.getHitProbHistory()
        for (prob in hits.asReversed().take(cfg.maxHits)) {
            val probStr = "${cfg.colorFor(prob)}${"%.4f".format(java.util.Locale.ROOT, prob)}"
            lines.add(cfg.hitFormat.replace("{PROB}", probStr))
        }

        val avg    = gp.avgProbability
        val avgStr = "${cfg.colorFor(avg)}${"%.0f".format(avg * 100.0)}%"
        lines.add(cfg.header.replace("{AVG}", avgStr))

        return lines.map { GSON.serialize(LEGACY.deserialize(it)) }
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
        val targets = ConcurrentHashMap<UUID, EntityCache>()
    }

    private class EntityCache {
        val lines = ArrayList<LineEntity>(6)
    }

    private class LineEntity(val entityId: Int, var lastText: String)

    private companion object {
        val LEGACY = LegacyComponentSerializer.legacyAmpersand()
        val GSON   = GsonComponentSerializer.gson()

        const val ENTITY_ID_START             = 43_000_000
        const val ENTITY_FLAG_INVISIBLE: Byte = 0x20
        const val ARMOR_STAND_MARKER: Byte    = 0x10
        const val TASK_DELAY                  = 10L
    }
}
