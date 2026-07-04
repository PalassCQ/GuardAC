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

package dev.guardac.compat

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class WorldGuardCompat(
    private val logger: Logger,
    private val enabled: Boolean,
    disabledRegionsList: List<String>,
) {

    private val disabledRegions: MutableMap<String, MutableSet<String>> = HashMap()
    private val cache = ConcurrentHashMap<UUID, RegionCheckCache>()
    private var available = false

    init {
        for (entry in disabledRegionsList) {
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                disabledRegions.getOrPut(parts[0].lowercase()) { HashSet() }.add(parts[1].lowercase())
            } else {
                disabledRegions.getOrPut("*") { HashSet() }.add(entry.lowercase())
            }
        }
        available = try {
            Class.forName("com.sk89q.worldguard.WorldGuard")
            Bukkit.getPluginManager().getPlugin("WorldGuard") != null
        } catch (_: Throwable) {
            false
        }
        if (enabled && available) logger.info("[WorldGuard] Интеграция включена.")
        else if (enabled) logger.warning("[WorldGuard] Плагин не найден — проверка регионов отключена.")
    }

    fun shouldBypass(player: Player): Boolean {
        if (!enabled || !available) return false
        if (disabledRegions.isEmpty()) return false

        val location = try { player.location } catch (_: Throwable) { return false }
        val world = location.world ?: return false
        val now = System.currentTimeMillis()
        val id = player.uniqueId

        val cached = cache[id]
        if (cached != null && cached.matches(world.name, location) && now - cached.checkedAt <= CACHE_TTL_MS) {
            return cached.bypass
        }
        val bypass = bypassAt(location)
        cache[id] = RegionCheckCache(world.name, location, now, bypass)
        return bypass
    }

    private fun bypassAt(location: Location): Boolean {
        val world = location.world ?: return false
        val worldDisabled = disabledRegions[world.name.lowercase()] ?: emptySet()
        val globalDisabled = disabledRegions["*"] ?: emptySet()
        if (worldDisabled.isEmpty() && globalDisabled.isEmpty()) return false
        return try {
            val container = WorldGuard.getInstance().platform.regionContainer
            val manager = container.get(BukkitAdapter.adapt(world)) ?: return false
            val regions = manager.getApplicableRegions(BukkitAdapter.asBlockVector(location))
            var highest: ProtectedRegion? = null
            var highestPriority = Int.MIN_VALUE
            for (region in regions) {
                if (region.priority > highestPriority) {
                    highestPriority = region.priority
                    highest = region
                }
            }
            val regionId = highest?.id?.lowercase() ?: return false
            worldDisabled.contains(regionId) || globalDisabled.contains(regionId)
        } catch (e: Exception) {
            logger.warning("[WorldGuard] Ошибка проверки регионов: ${e.message}")
            false
        }
    }

    fun clearCache(id: UUID) {
        cache.remove(id)
    }

    private class RegionCheckCache(
        private val worldName: String,
        location: Location,
        val checkedAt: Long,
        val bypass: Boolean,
    ) {
        private val bx = location.blockX
        private val by = location.blockY
        private val bz = location.blockZ
        fun matches(world: String, loc: Location): Boolean =
            worldName == world && bx == loc.blockX && by == loc.blockY && bz == loc.blockZ
    }

    private companion object {
        const val CACHE_TTL_MS = 500L
    }
}
