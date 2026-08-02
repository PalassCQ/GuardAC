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

package dev.guardac.animation

import dev.guardac.GuardAC
import dev.guardac.compat.Compat
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
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
        val sneaking: Boolean = false,
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
            player.isSneaking  = s.sneaking
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

        val resolved = resolveType(type) ?: run {

            warnUnknownType(type)
            TYPES.random()
        }
        when (resolved) {
            "pig"       -> playPig(player, finishWith)
            "explode"   -> playExplode(player, finishWith)
            "particles" -> playParticles(player, finishWith)
            "lightning" -> playLightning(player, finishWith)
            "vortex"    -> playVortex(player, finishWith)
            "meteor"    -> playMeteor(player, finishWith)
            "cage"      -> playCage(player, finishWith)
            "endrod"    -> playEndRod(player, finishWith)
            else        -> finishWith(player.location.clone())
        }
    }

    private fun warnUnknownType(type: String?) {
        val raw = type?.trim().orEmpty()
        if (raw.isEmpty() || !missingWarned.add("type:$raw")) return
        plugin.logger.warning(
            "[Animation] Unknown animation \"$raw\" - playing a random one instead. " +
            "Available: ${TYPES.joinToString(" | ")}"
        )
    }

    private class Freeze(val restore: () -> Unit)

    private fun freeze(player: Player): Freeze {
        anchors[player.uniqueId] = player.location.clone()
        frozen[player.uniqueId] = MovementState(
            player.walkSpeed, player.flySpeed, player.allowFlight, player.isFlying, player.isSneaking,
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
        val riseSpeed = riseSpeedFor(plugin.configManager.animationPigHeight, duration)
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
                pig.velocity = if (pig.location.y < targetY) Vector(0.0, riseSpeed, 0.0) else Vector(0.0, 0.0, 0.0)
                if (t % 3 == 0) burst(world, particle("CLOUD"), pig.location, 6, 0.3, 0.1, 0.3)
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
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        playSound(player.location, "BLOCK_FIRE_AMBIENT", 1f, 0.5f)

        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable { finishWith(player.location.clone()) },
        ) { handle ->
            try {
                if (!player.isOnline) { handle.cancel(); finishWith(player.location.clone()); return@entityTimer }
                val base = player.location
                val progress = t.toDouble() / duration

                val radius = 2.6 * (1.0 - progress) + 0.2
                val points = 10
                for (i in 0 until points) {
                    val ang = t * 0.35 + Math.PI * 2 * i / points
                    burst(
                        world, particle("FLAME"),
                        base.clone().add(Math.cos(ang) * radius, 0.4 + progress * 1.4, Math.sin(ang) * radius),
                        2, 0.05, 0.05, 0.05,
                    )
                }
                burst(
                    world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"),
                    base.clone().add(0.0, 1.0, 0.0), 3, 0.3, 0.5, 0.3, 0.01,
                )
                if (t % 10 == 0) playSound(base, "BLOCK_FIRE_AMBIENT", 1f, 0.5f + progress.toFloat())
                if (++t >= duration) { handle.cancel(); finishWith(base.clone()) }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
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
                    burst(world, particle, center.clone().add(x, 0.0, z), each)
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
        val strikes = 5
        val gap = (duration / strikes).coerceAtLeast(1).toLong()

        for (i in 0 until strikes) {
            plugin.scheduler.entityDelayed(player, gap * i, Runnable {
                if (!player.isOnline) return@Runnable
                runCatching { player.world.strikeLightningEffect(player.location) }
                burst(
                    player.world, particle("ELECTRIC_SPARK", "CRIT"),
                    player.location.clone().add(0.0, 1.0, 0.0), 25, 0.5, 0.8, 0.5, 0.05,
                )
            })
        }
        plugin.scheduler.entityDelayed(
            player, duration.toLong(), Runnable { finishWith(player.location.clone()) },
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
                    burst(
                        world, particle("CLOUD"),
                        base.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r),
                        3, 0.05, 0.05, 0.05,
                    )
                    burst(
                        world, particle("END_ROD", "CRIT"),
                        base.clone().add(Math.cos(ang + 0.7) * r, y * 0.6, Math.sin(ang + 0.7) * r),
                        1,
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
        val fall = duration.coerceAtLeast(15)
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
                burst(world, particle("FLAME"), pos, 12, 0.25, 0.25, 0.25, 0.01)
                burst(world, particle("LAVA"), pos, 2, 0.1, 0.1, 0.1)
                burst(world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"), pos, 5, 0.2, 0.2, 0.2, 0.01)
                if (t % 5 == 0) playSound(pos, "BLOCK_FIRE_AMBIENT", 1f, 0.6f)
                if (++t >= fall) {
                    handle.cancel()
                    val impact = player.location.clone()
                    burst(world, particle("FLAME"), impact, 70, 1.4, 0.5, 1.4, 0.08)
                    burst(world, particle("LAVA"), impact, 12, 1.0, 0.4, 1.0)
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
                        burst(world, particle("END_ROD", "CRIT"), base.clone().add(x, y, z), 1)
                        y += 0.5
                    }
                }

                burst(
                    world, particle("END_ROD", "CRIT"),
                    base.clone().add(0.0, 2.6, 0.0), 4, radius * 0.4, 0.05, radius * 0.4,
                )
                if (t % 12 == 0) playSound(base, "BLOCK_AMETHYST_BLOCK_CHIME", 1f, 0.6f)
                if (++t >= duration) { handle.cancel(); finishWith(base.clone()) }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun playEndRod(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        val material = rodMaterial()

        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }
        runCatching { player.isSneaking = true }
        playAnySound(player.location, 0.9f, 0.7f, "ENTITY_ENDERMAN_TELEPORT", "ENTITY_ENDERMEN_TELEPORT")

        val mount = runCatching {
            track(world.spawn(player.location, Pig::class.java).apply {
                setGravity(false)
                isSilent = true
                isInvulnerable = true
                removeWhenFarAway = true
                Compat.potion("INVISIBILITY")?.let {
                    addPotionEffect(PotionEffect(it, duration + 40, 0, false, false))
                }
            })
        }.getOrNull()
        mount?.let { runCatching { it.addPassenger(player) } }

        var rod: Entity? = material?.let { spawnRodSegment(rodLocation(player.location), it) }
        val solid = rod != null
        val cleanup = {
            rod?.let { seg -> spawned.remove(seg); runCatching { seg.remove() } }
            rod = null
            mount?.let { m ->
                runCatching { m.removePassenger(player) }
                spawned.remove(m); runCatching { m.remove() }
            }
        }

        val height    = plugin.configManager.animationPigHeight
        val targetY   = player.location.y + height
        val riseSpeed = riseSpeedFor(height, duration)
        burst(world, particle("END_ROD", "CRIT"), rodLocation(player.location), 14, 0.18, 0.2, 0.18, 0.04)

        var t = 0
        plugin.scheduler.entityTimer(
            player, 1L, 1L,
            retired = Runnable {
                val loc = player.location.clone()
                cleanup()
                finishWith(loc)
            },
        ) { handle ->
            try {
                if (!player.isOnline) {
                    handle.cancel()
                    val loc = player.location.clone()
                    cleanup()
                    finishWith(loc)
                    return@entityTimer
                }

                val base = player.location
                val seat = rodLocation(base)

                runCatching { player.isSneaking = true }

                var lift = 0.0
                if (mount != null && mount.isValid) {
                    if (!mount.passengers.contains(player)) {
                        plugin.scheduler.teleport(player, mount.location)
                        runCatching { mount.addPassenger(player) }
                    }
                    if (mount.location.y < targetY) lift = riseSpeed
                    mount.velocity = Vector(0.0, lift, 0.0)
                    anchors[player.uniqueId] = mount.location.clone()
                }

                val current = rod
                if (current != null) {

                    current.velocity = Vector(0.0, lift, 0.0)
                    if (current.location.distanceSquared(seat) > ROD_RESYNC_DIST_SQ) {
                        plugin.scheduler.teleport(current, seat)
                    }
                } else {

                    var y = 0.0
                    while (y <= 0.9) {
                        burst(world, particle("END_ROD", "CRIT"), seat.clone().add(0.0, y, 0.0), 1)
                        y += 0.3
                    }
                }

                burst(world, particle("END_ROD", "CRIT"), seat, 2, 0.07, 0.04, 0.07, 0.02)
                burst(
                    world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"),
                    seat.clone().add(0.0, -0.2, 0.0), 2, 0.1, 0.05, 0.1, 0.01,
                )

                for (arm in 0 until 2) {
                    val ang = t * 0.28 + arm * Math.PI
                    val y = 0.2 + ((t * 0.08 + arm * 0.9) % 1.9)
                    burst(
                        world, particle("END_ROD", "CRIT"),
                        base.clone().add(Math.cos(ang) * 0.65, y, Math.sin(ang) * 0.65), 1,
                    )
                }
                val swirl = -t * 0.19
                burst(
                    world, particle("PORTAL"),
                    base.clone().add(Math.cos(swirl) * 0.85, 1.0, Math.sin(swirl) * 0.85), 3, 0.05, 0.3, 0.05, 0.02,
                )
                if (t % 25 == 0) {
                    playAnySound(
                        base, 0.4f, 1.3f,
                        "BLOCK_AMETHYST_BLOCK_CHIME", "BLOCK_NOTE_BLOCK_CHIME", "BLOCK_BEACON_AMBIENT",
                    )
                }

                if (++t >= duration) {
                    handle.cancel()
                    val loc = base.clone()
                    cleanup()
                    shatterRod(world, loc, if (solid) material else null)
                    finishWith(loc)
                }
            } catch (e: Exception) {
                handle.cancel()
                val loc = player.location.clone()
                cleanup()
                finishWith(loc)
            }
        }
    }

    private fun riseSpeedFor(height: Double, durationTicks: Int): Double {
        val riseTicks = (durationTicks * RISE_FRACTION).toInt().coerceAtLeast(1)
        return (height / riseTicks).coerceIn(MIN_RISE_SPEED, RISE_SPEED)
    }

    private fun rodLocation(base: Location): Location =
        base.clone().add(0.0, ROD_OFFSET_Y, 0.0).apply { yaw = 0f; pitch = 0f }

    private fun rodMaterial(): Material? = runCatching { Material.valueOf("END_ROD") }.getOrNull()

    private fun spawnRodSegment(at: Location, material: Material): Entity? {
        val world = at.world ?: return null
        val block = runCatching { world.spawnFallingBlock(at, material.createBlockData()) }.getOrNull()
            ?: run { warnUnavailable("block", "END_ROD"); return null }
        runCatching { block.setGravity(false) }
        runCatching { block.dropItem = false }
        runCatching { block.setHurtEntities(false) }
        runCatching { block.isSilent = true }
        runCatching { block.velocity = Vector(0.0, 0.0, 0.0) }
        return track<FallingBlock>(block)
    }

    private fun shatterRod(world: org.bukkit.World, base: Location, material: Material?) {
        val at = rodLocation(base).add(0.0, 0.5, 0.0)
        playAnySound(base, 1f, 0.8f, "BLOCK_GLASS_BREAK")
        if (material != null) blockBurst(world, at, material, 24)
        burst(world, particle("END_ROD", "CRIT"), at, 20, 0.28, 0.25, 0.28, 0.12)
        burst(world, particle("PORTAL"), at, 24, 0.3, 0.5, 0.3, 0.2)
    }

    private fun blockBurst(world: org.bukkit.World, loc: Location, material: Material, count: Int) {
        val dust = Compat.particle("BLOCK_CRACK", "BLOCK")
        runCatching {
            world.spawnParticle(dust, loc, count, 0.22, 0.4, 0.22, 0.06, material.createBlockData(), true)
        }.onFailure {
            burst(world, particle("END_ROD", "CRIT"), loc, count, 0.22, 0.4, 0.22, 0.06)
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
        burst(w, particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE", "EXPLOSION"), loc, 1)
        playSound(loc, "ENTITY_GENERIC_EXPLODE", 1f, 1f)
    }

    private fun playSound(loc: Location, name: String, volume: Float, pitch: Float) {
        if (!plugin.configManager.animationSound) return
        val sound = Compat.sound(name) ?: return
        loc.world?.playSound(loc, sound, volume, pitch)
    }

    private fun playAnySound(loc: Location, volume: Float, pitch: Float, vararg names: String) {
        if (!plugin.configManager.animationSound) return
        val sound = Compat.sound(*names) ?: return
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

    private fun burst(
        world: org.bukkit.World, particle: Particle, loc: Location,
        count: Int, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0, speed: Double = 0.0,
    ) {
        runCatching { world.spawnParticle(particle, loc, count, dx, dy, dz, speed, null, true) }
            .onFailure { world.spawnParticle(particle, loc, count, dx, dy, dz, speed) }
    }

    companion object {

        val TYPES = listOf("pig", "explode", "particles", "lightning", "vortex", "meteor", "cage", "endrod")

        const val RANDOM = "random"

        private val ALIASES = mapOf(
            "end_rod" to "endrod",
            "rod"     to "endrod",
            "endrod"  to "endrod",
        )

        fun resolveType(raw: String?): String? {
            val key = raw?.trim()?.lowercase(java.util.Locale.ROOT)?.ifBlank { null } ?: return null
            if (key == RANDOM) return TYPES.random()
            ALIASES[key]?.let { return it }
            return if (key in TYPES) key else null
        }

        fun isKnownType(raw: String?): Boolean {
            val key = raw?.trim()?.lowercase(java.util.Locale.ROOT)?.ifBlank { null } ?: return false
            return key == RANDOM || key in ALIASES || key in TYPES
        }

        private const val ANIM_TAG = "guardac_anim"
        private const val RISE_SPEED = 0.35
        private const val MIN_RISE_SPEED = 0.02

        private const val RISE_FRACTION = 0.85

        private const val ROD_RESYNC_DIST_SQ = 0.25

        private const val ROD_OFFSET_Y = -0.75

        private const val WITHER_PITCH = 1.8f
    }
}
