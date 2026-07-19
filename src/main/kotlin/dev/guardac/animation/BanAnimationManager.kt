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

package dev.guardac.animation

import dev.guardac.GuardAC
import dev.guardac.compat.Compat
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BanAnimationManager(private val plugin: GuardAC) : Listener {

    private data class MovementState(
        val walk: Float, val fly: Float, val allowFlight: Boolean, val flying: Boolean,
    )

    private val animating: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val anchors = ConcurrentHashMap<UUID, Location>()
    private val pendingCompletions = ConcurrentHashMap<UUID, MutableList<() -> Unit>>()

    private val frozen = ConcurrentHashMap<UUID, MovementState>()

    private val spawned: MutableSet<Entity> = ConcurrentHashMap.newKeySet()
    private val missingWarned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun <T : Entity> track(entity: T): T {
        runCatching { entity.addScoreboardTag(ANIM_TAG) }
        spawned.add(entity)
        return entity
    }

    fun removeAnimationEntities() {
        spawned.toList().forEach { e ->
            spawned.remove(e)
            runCatching { e.remove() }
        }
    }

    fun isAnimating(uuid: UUID): Boolean = uuid in animating

    fun onQuit(uuid: UUID) {
        animating.remove(uuid)
        anchors.remove(uuid)

    }

    fun onJoin(player: Player) {
        frozen.remove(player.uniqueId)?.let { applyState(player, it) }
    }

    fun restoreAllFrozen() {
        frozen.keys.toList().forEach { id ->
            val state = frozen.remove(id) ?: return@forEach
            Bukkit.getPlayer(id)?.let { applyState(it, state) }
        }
    }

    private fun applyState(player: Player, s: MovementState) {
        runCatching {
            player.walkSpeed   = s.walk
            player.flySpeed    = s.fly
            player.allowFlight = s.allowFlight
            player.isFlying    = s.flying && s.allowFlight
        }
        runCatching { Compat.potion("LEVITATION")?.let { player.removePotionEffect(it) } }

        runCatching {
            Compat.potion("SLOW_FALLING")?.let {
                player.addPotionEffect(PotionEffect(it, 100, 0, false, false))
            }
        }
    }

    fun playRandom(player: Player, dropLoot: Boolean, onComplete: () -> Unit) =
        play(player, TYPES.random(), dropLoot, onComplete)

    fun play(player: Player, type: String?, dropLoot: Boolean, onComplete: () -> Unit) {
        val cfg = plugin.configManager
        if (!cfg.animationsEnabled || !player.isOnline) { onComplete(); return }

        if (!animating.add(player.uniqueId)) {
            pendingCompletions.computeIfAbsent(player.uniqueId) {
                java.util.Collections.synchronizedList(mutableListOf())
            }.add(onComplete)
            return
        }

        val lifted = freeze(player)
        val restore = lifted.restore
        val done = AtomicBoolean(false)

        val finishWith: (Location) -> Unit = { loc ->
            if (done.compareAndSet(false, true)) {
                animating.remove(player.uniqueId)
                anchors.remove(player.uniqueId)
                restore()
                if (dropLoot) dropResources(player, loc)
                explode(loc)
                playKillSound(player, loc)
                onComplete()
                pendingCompletions.remove(player.uniqueId)?.forEach { queued ->
                    runCatching { queued() }
                }
            }
        }

        val resolved = (type?.trim()?.lowercase()?.ifBlank { null }) ?: TYPES.random()
        when (resolved) {
            "pig"       -> playPig(player, finishWith)
            "explode"   -> playExplode(player, finishWith)
            "particles" -> playParticles(player, finishWith)
            "lightning" -> playLightning(player, finishWith)
            "vortex"    -> playVortex(player, finishWith)
            "meteor"    -> playMeteor(player, finishWith)
            "cage"      -> playCage(player, finishWith)
            else        -> finishWith(player.location.clone())
        }
    }

    private class Freeze(val restore: () -> Unit)

    private fun freeze(player: Player): Freeze {
        anchors[player.uniqueId] = player.location.clone()
        frozen[player.uniqueId] = MovementState(
            player.walkSpeed, player.flySpeed, player.allowFlight, player.isFlying,
        )
        runCatching {
            player.isFlying = false
            player.allowFlight = false
            player.walkSpeed = 0f
            player.flySpeed = 0f
        }

        if (!player.isInsideVehicle) {
            runCatching {
                val levitation = Compat.potion("LEVITATION")
                if (levitation == null) {
                    warnUnavailable("effect", "LEVITATION")
                } else {
                    player.addPotionEffect(PotionEffect(
                        levitation,
                        plugin.configManager.animationDurationTicks + 20, 1, false, false,
                    ))
                }
                player.velocity = Vector(0.0, 0.3, 0.0)
            }
        }
        return Freeze {
            if (player.isOnline) {
                frozen.remove(player.uniqueId)?.let { applyState(player, it) }
            }

        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val anchor = anchors[event.player.uniqueId] ?: return

        if (event.player.isInsideVehicle) return
        val to = event.to

        if (anchor.world == to.world && anchor.x == to.x && anchor.z == to.z) return
        event.setTo(to.clone().apply {
            x = anchor.x
            z = anchor.z
        })
    }

    private fun playPig(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }
        playSound(player.location, "ENTITY_PIG_AMBIENT", 1f, 1f)

        val targetY = player.location.y + plugin.configManager.animationPigHeight

        val pig = track(world.spawn(player.location, Pig::class.java).apply {
            setGravity(false)
            isSilent = false
            isInvulnerable = true

            removeWhenFarAway = true
        })

        val seat = runCatching {
            track(world.spawn(player.location, ArmorStand::class.java).apply {
                isVisible = false
                isMarker = true
                setGravity(false)
                isInvulnerable = true
                isSilent = true
            })
        }.getOrNull()
        seat?.let { runCatching { pig.addPassenger(it) } }
        runCatching { pig.addPassenger(player) }

        val duration = plugin.configManager.animationDurationTicks
        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable { cleanupPig(pig, seat, player); finishWith(pig.location.clone()) },
        ) { handle ->
            try {
                if (!player.isOnline || !pig.isValid) {
                    handle.cancel()
                    val loc = if (pig.isValid) pig.location.clone() else player.location.clone()
                    cleanupPig(pig, seat, player)
                    finishWith(loc)
                    return@entityTimer
                }

                anchors[player.uniqueId] = pig.location.clone()
                if (!pig.passengers.contains(player)) {
                    plugin.scheduler.teleport(player, pig.location)
                    runCatching { pig.addPassenger(player) }
                }
                pig.velocity = if (pig.location.y < targetY) Vector(0.0, RISE_SPEED, 0.0) else Vector(0.0, 0.0, 0.0)
                if (t % 3 == 0) world.spawnParticle(particle("CLOUD"), pig.location, 6, 0.3, 0.1, 0.3, 0.0)
                if (++t >= duration) {
                    handle.cancel()
                    val loc = pig.location.clone()
                    cleanupPig(pig, seat, player)
                    finishWith(loc)
                }
            } catch (e: Exception) {
                handle.cancel()
                cleanupPig(pig, seat, player)
                finishWith(player.location.clone())
            }
        }
    }

    private fun cleanupPig(pig: Pig, seat: ArmorStand?, player: Player) {
        runCatching { pig.removePassenger(player) }
        seat?.let { spawned.remove(it); runCatching { it.remove() } }
        spawned.remove(pig)
        runCatching { pig.remove() }
    }

    private fun playExplode(player: Player, finishWith: (Location) -> Unit) {

        plugin.scheduler.entityDelayed(
            player, 6L, Runnable { finishWith(player.location.clone()) },
            retired = Runnable { finishWith(player.location.clone()) },
        )
    }

    private fun playParticles(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val cfg = plugin.configManager
        val duration = cfg.animationDurationTicks
        val perTick  = cfg.animationParticleCount.coerceAtLeast(1)
        val particle = particle(cfg.animationParticle, "FLAME")

        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable { finishWith(player.location.clone()) },
        ) { handle ->
            try {
                if (!player.isOnline) { handle.cancel(); finishWith(player.location.clone()); return@entityTimer }
                val center = player.location.clone().add(0.0, 1.0, 0.0)
                val points = 14
                val each = (perTick / points).coerceAtLeast(1)
                for (i in 0 until points) {
                    val ang = t * 0.25 + Math.PI * 2 * i / points
                    val x = Math.cos(ang) * 1.2
                    val z = Math.sin(ang) * 1.2
                    world.spawnParticle(particle, center.clone().add(x, 0.0, z), each, 0.0, 0.0, 0.0, 0.0)
                }
                if (++t >= duration) {
                    handle.cancel()
                    runCatching { Compat.potion("LEVITATION")?.let { player.removePotionEffect(it) } }
                    finishWith(player.location.clone())
                }
            } catch (e: Exception) {
                handle.cancel()
                runCatching { Compat.potion("LEVITATION")?.let { player.removePotionEffect(it) } }
                finishWith(player.location.clone())
            }
        }
    }

    private fun playLightning(player: Player, finishWith: (Location) -> Unit) {
        val duration = plugin.configManager.animationDurationTicks
        val gap = (duration / 4).coerceAtLeast(1).toLong()

        for (i in 0..2) {
            plugin.scheduler.entityDelayed(player, gap * i, Runnable {
                if (!player.isOnline) return@Runnable
                runCatching { player.world.strikeLightningEffect(player.location) }
                player.world.spawnParticle(
                    particle("ELECTRIC_SPARK", "CRIT"),
                    player.location.clone().add(0.0, 1.0, 0.0), 25, 0.5, 0.8, 0.5, 0.05,
                )
            })
        }
        plugin.scheduler.entityDelayed(
            player, gap * 3, Runnable { finishWith(player.location.clone()) },
            retired = Runnable { finishWith(player.location.clone()) },
        )
    }

    private fun playVortex(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        playSound(player.location, "ENTITY_PHANTOM_FLAP", 1f, 0.6f)

        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable { finishWith(player.location.clone()) },
        ) { handle ->
            try {
                if (!player.isOnline) { handle.cancel(); finishWith(player.location.clone()); return@entityTimer }
                val base = player.location
                for (arm in 0..1) {
                    val ang = t * 0.5 + arm * Math.PI
                    val r = 1.6 - (t.toDouble() / duration) * 0.7
                    val y = (t.toDouble() / duration) * 2.8
                    world.spawnParticle(
                        particle("CLOUD"),
                        base.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r),
                        3, 0.05, 0.05, 0.05, 0.0,
                    )
                    world.spawnParticle(
                        particle("END_ROD", "CRIT"),
                        base.clone().add(Math.cos(ang + 0.7) * r, y * 0.6, Math.sin(ang + 0.7) * r),
                        1, 0.0, 0.0, 0.0, 0.0,
                    )
                }
                if (++t >= duration) { handle.cancel(); finishWith(player.location.clone()) }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun playMeteor(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        val fall = (duration * 2 / 3).coerceAtLeast(15)
        playSound(player.location, "ENTITY_GHAST_SHOOT", 1f, 0.5f)

        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable { finishWith(player.location.clone()) },
        ) { handle ->
            try {
                if (!player.isOnline) { handle.cancel(); finishWith(player.location.clone()); return@entityTimer }
                val remaining = 1.0 - t.toDouble() / fall
                val pos = player.location.clone().add(
                    remaining * 7.0,
                    remaining * 15.0 + 1.0,
                    remaining * 5.0,
                )
                world.spawnParticle(particle("FLAME"), pos, 12, 0.25, 0.25, 0.25, 0.01)
                world.spawnParticle(particle("LAVA"), pos, 2, 0.1, 0.1, 0.1, 0.0)
                world.spawnParticle(particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"), pos, 5, 0.2, 0.2, 0.2, 0.01)
                if (t % 5 == 0) playSound(pos, "BLOCK_FIRE_AMBIENT", 1f, 0.6f)
                if (++t >= fall) {
                    handle.cancel()
                    val impact = player.location.clone()
                    world.spawnParticle(particle("FLAME"), impact, 70, 1.4, 0.5, 1.4, 0.08)
                    world.spawnParticle(particle("LAVA"), impact, 12, 1.0, 0.4, 1.0, 0.0)
                    finishWith(impact)
                }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun playCage(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        playSound(player.location, "BLOCK_ANVIL_LAND", 0.6f, 0.5f)

        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable { finishWith(player.location.clone()) },
        ) { handle ->
            try {
                if (!player.isOnline) { handle.cancel(); finishWith(player.location.clone()); return@entityTimer }
                val base = player.location
                val radius = 2.4 - (t.toDouble() / duration) * 1.7
                val bars = 8
                for (i in 0 until bars) {
                    val ang = Math.PI * 2 * i / bars + t * 0.05
                    val x = Math.cos(ang) * radius
                    val z = Math.sin(ang) * radius
                    var y = 0.0
                    while (y <= 2.4) {
                        world.spawnParticle(particle("END_ROD", "CRIT"), base.clone().add(x, y, z), 1, 0.0, 0.0, 0.0, 0.0)
                        y += 0.5
                    }
                }

                world.spawnParticle(
                    particle("END_ROD", "CRIT"),
                    base.clone().add(0.0, 2.6, 0.0), 4, radius * 0.4, 0.05, radius * 0.4, 0.0,
                )
                if (t % 12 == 0) playSound(base, "BLOCK_AMETHYST_BLOCK_CHIME", 1f, 0.6f)
                if (++t >= duration) { handle.cancel(); finishWith(base.clone()) }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun dropResources(player: Player, loc: Location) {
        if (!plugin.configManager.animationDropInventory) return
        if (!player.isOnline) return
        val inv = player.inventory
        inv.contents.forEach { item ->
            if (item != null && item.type != Material.AIR) {
                runCatching { loc.world?.dropItemNaturally(loc, item.clone()) }
            }
        }
        inv.clear()
        playSound(loc, "ENTITY_ITEM_PICKUP", 0.8f, 0.6f)
    }

    private fun explode(loc: Location) {
        val w = loc.world ?: return
        w.spawnParticle(particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE", "EXPLOSION"), loc, 1)
        playSound(loc, "ENTITY_GENERIC_EXPLODE", 1f, 1f)
    }

    private fun playSound(loc: Location, name: String, volume: Float, pitch: Float) {
        if (!plugin.configManager.animationSound) return
        val sound = Compat.sound(name) ?: return
        loc.world?.playSound(loc, sound, volume, pitch)
    }

    private fun playKillSound(player: Player, loc: Location) {
        if (!plugin.configManager.animationSound) return
        val sound = Compat.sound("ENTITY_WITHER_DEATH")
        if (sound == null) {
            warnUnavailable("sound", "ENTITY_WITHER_DEATH")
            return
        }
        runCatching { player.playSound(player.location, sound, 1f, WITHER_PITCH) }
        loc.world?.playSound(loc, sound, 4f, WITHER_PITCH)
    }

    private fun warnUnavailable(kind: String, name: String) {
        if (missingWarned.add(name)) {
            plugin.logger.warning(
                "[Animation] $kind \"$name\" is not available on this server version - that part of the show is skipped."
            )
        }
    }

    private fun particle(vararg names: String): Particle = Compat.particle(*names)

    private companion object {
        val TYPES = listOf("pig", "explode", "particles", "lightning", "vortex", "meteor", "cage")

        const val ANIM_TAG = "guardac_anim"
        const val RISE_SPEED = 0.35

        const val WITHER_PITCH = 1.8f
    }
}
