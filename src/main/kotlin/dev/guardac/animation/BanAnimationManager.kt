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

    fun availableTypes(): List<String> = TYPES.filter { plugin.configManager.animationTypeEnabled(it) }

    fun playRandom(player: Player, dropLoot: Boolean, onComplete: () -> Unit) =
        play(player, null, dropLoot, onComplete)

    fun play(player: Player, type: String?, dropLoot: Boolean, onComplete: () -> Unit) {
        val cfg = plugin.configManager
        if (!cfg.animationsEnabled || !player.isOnline) { onComplete(); return }

        val resolved = pickType(type) ?: run { onComplete(); return }

        if (!animating.add(player.uniqueId)) {
            pendingCompletions.computeIfAbsent(player.uniqueId) {
                java.util.Collections.synchronizedList(mutableListOf())
            }.add(onComplete)
            return
        }

        val fx = Effects(resolved)
        val lifted = freeze(player)
        val restore = lifted.restore
        val done = AtomicBoolean(false)

        val lift = if (resolved == "pig") null
        else beginLift(player, cfg.animationDurationTicks, resolved == "endrod")

        val finishWith: (Location) -> Unit = { loc ->
            if (done.compareAndSet(false, true)) {
                lift?.stop?.invoke()
                animating.remove(player.uniqueId)
                anchors.remove(player.uniqueId)
                restore()
                if (dropLoot) dropResources(player, fx, loc)
                fx.explode(loc)
                fx.killSound(player, loc)
                onComplete()
                pendingCompletions.remove(player.uniqueId)?.forEach { queued ->
                    runCatching { queued() }
                }
            }
        }

        when (resolved) {
            "pig"       -> playPig(player, fx, finishWith)
            "explode"   -> playExplode(player, fx, finishWith)
            "particles" -> playParticles(player, fx, finishWith)
            "lightning" -> playLightning(player, fx, finishWith)
            "vortex"    -> playVortex(player, fx, finishWith)
            "meteor"    -> playMeteor(player, fx, finishWith)
            "cage"      -> playCage(player, fx, finishWith)
            "endrod"    -> playEndRod(player, fx, finishWith)
            else        -> finishWith(player.location.clone())
        }
    }

    private fun pickType(requested: String?): String? {
        val pool = availableTypes()
        val canonical = resolveType(requested)
        if (canonical != null && canonical != RANDOM) {
            if (canonical in pool) return canonical
            warnDisabledType(canonical)
        } else if (canonical == null && !requested.isNullOrBlank()) {
            warnUnknownType(requested)
        }
        return pool.randomOrNull()
    }

    private fun warnUnknownType(type: String?) {
        val raw = type?.trim().orEmpty()
        if (raw.isEmpty() || !missingWarned.add("type:$raw")) return
        plugin.logger.warning(
            "[Animation] Unknown animation \"$raw\" - playing a random one instead. " +
                    "Available: ${TYPES.joinToString(" | ")}"
        )
    }

    private fun warnDisabledType(type: String) {
        if (!missingWarned.add("disabled:$type")) return
        plugin.logger.warning(
            "[Animation] Animation \"$type\" is switched off in config.yml " +
                    "(animations.types.$type.enabled) - playing another one instead."
        )
    }

    private class Lift(val stop: () -> Unit)

    private fun beginLift(player: Player, duration: Int, piston: Boolean): Lift {
        val height  = plugin.configManager.animationPigHeight
        val targetY = player.location.y + height

        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }

        var t = 0
        var liftedY = player.location.y
        val task = plugin.scheduler.entityTimer(player, 1L, 1L) { handle ->
            try {
                if (!player.isOnline || t >= duration) {
                    handle.cancel()
                    return@entityTimer
                }
                val speed = if (piston && t < PISTON_TICKS) {
                    PISTON_SPEED
                } else {
                    val left = targetY - liftedY
                    if (left <= 0.0) 0.0
                    else (left / (duration - t).coerceAtLeast(1)).coerceIn(0.0, PISTON_SPEED)
                }
                liftedY = (liftedY + speed).coerceAtMost(targetY)

                val here = player.location
                if (kotlin.math.abs(liftedY - here.y) > 1.0e-4) {
                    plugin.scheduler.teleport(player, here.clone().apply { y = liftedY })
                }
                anchors[player.uniqueId] = player.location.clone()
                t++
            } catch (e: Exception) {
                handle.cancel()
            }
        }

        return Lift { runCatching { task.cancel() } }
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

    private fun playPig(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val world = player.world
        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }
        fx.sound(player.location, "ENTITY_PIG_AMBIENT", 1f, 1f)

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
                if (t % 3 == 0) fx.burst(world, particle("CLOUD"), pig.location, 6, 0.3, 0.1, 0.3)
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

    private fun playExplode(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        fx.sound(player.location, "BLOCK_FIRE_AMBIENT", 1f, 0.5f)

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
                    fx.burst(
                        world, particle("FLAME"),
                        base.clone().add(Math.cos(ang) * radius, 0.4 + progress * 1.4, Math.sin(ang) * radius),
                        2, 0.05, 0.05, 0.05,
                    )
                }
                fx.burst(
                    world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"),
                    base.clone().add(0.0, 1.0, 0.0), 3, 0.3, 0.5, 0.3, 0.01,
                )
                if (t % 10 == 0) fx.sound(base, "BLOCK_FIRE_AMBIENT", 1f, 0.5f + progress.toFloat())
                if (++t >= duration) { handle.cancel(); finishWith(base.clone()) }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun playParticles(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
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
                    fx.burst(world, particle, center.clone().add(x, 0.0, z), each)
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

    private fun playLightning(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val duration = plugin.configManager.animationDurationTicks
        val strikes = 5
        val gap = (duration / strikes).coerceAtLeast(1).toLong()

        for (i in 0 until strikes) {
            plugin.scheduler.entityDelayed(player, gap * i, Runnable {
                if (!player.isOnline) return@Runnable
                runCatching { player.world.strikeLightningEffect(player.location) }
                fx.burst(
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

    private fun playVortex(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        fx.sound(player.location, "ENTITY_PHANTOM_FLAP", 1f, 0.6f)

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
                    fx.burst(
                        world, particle("CLOUD"),
                        base.clone().add(Math.cos(ang) * r, y, Math.sin(ang) * r),
                        3, 0.05, 0.05, 0.05,
                    )
                    fx.burst(
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

    private fun playMeteor(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        val fall = duration.coerceAtLeast(15)
        fx.sound(player.location, "ENTITY_GHAST_SHOOT", 1f, 0.5f)

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
                fx.burst(world, particle("FLAME"), pos, 12, 0.25, 0.25, 0.25, 0.01)
                fx.burst(world, particle("LAVA"), pos, 2, 0.1, 0.1, 0.1)
                fx.burst(world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"), pos, 5, 0.2, 0.2, 0.2, 0.01)
                if (t % 5 == 0) fx.sound(pos, "BLOCK_FIRE_AMBIENT", 1f, 0.6f)
                if (++t >= fall) {
                    handle.cancel()
                    val impact = player.location.clone()
                    fx.burst(world, particle("FLAME"), impact, 70, 1.4, 0.5, 1.4, 0.08)
                    fx.burst(world, particle("LAVA"), impact, 12, 1.0, 0.4, 1.0)
                    finishWith(impact)
                }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun playCage(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        fx.sound(player.location, "BLOCK_ANVIL_LAND", 0.6f, 0.5f)

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
                        fx.burst(world, particle("END_ROD", "CRIT"), base.clone().add(x, y, z), 1)
                        y += 0.5
                    }
                }

                fx.burst(
                    world, particle("END_ROD", "CRIT"),
                    base.clone().add(0.0, 2.6, 0.0), 4, radius * 0.4, 0.05, radius * 0.4,
                )
                if (t % 12 == 0) fx.sound(base, "BLOCK_AMETHYST_BLOCK_CHIME", 1f, 0.6f)
                if (++t >= duration) { handle.cancel(); finishWith(base.clone()) }
            } catch (e: Exception) {
                handle.cancel()
                finishWith(player.location.clone())
            }
        }
    }

    private fun playEndRod(player: Player, fx: Effects, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        val material = rodMaterial()

        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }
        runCatching { player.isSneaking = true }
        fx.anySound(player.location, 0.9f, 0.7f, "ENTITY_ENDERMAN_TELEPORT", "ENTITY_ENDERMEN_TELEPORT")

        var rod: Entity? = null
        val solid = material != null
        val cleanup = {
            rod?.let { seg -> spawned.remove(seg); runCatching { seg.remove() } }
            rod = null
        }

        fx.burst(world, particle("END_ROD", "CRIT"), rodLocation(player, player.location), 24, 0.25, 0.3, 0.25, 0.05)

        var lastY = player.location.y
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

                runCatching { player.isSneaking = true }

                val lift = base.y - lastY
                lastY = base.y

                val seat = rodLocation(player, base, lift)

                if (rod == null && material != null && t == 0) {

                    rod = spawnRodSegment(rodLocation(player, base), material)
                    fx.anySound(seat, 1f, 0.55f, "BLOCK_PISTON_EXTEND", "BLOCK_PISTON_OUT")
                    fx.burst(
                        world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"),
                        seat.clone().add(0.0, -0.1, 0.0), 30, 0.35, 0.1, 0.35, 0.09,
                    )
                    fx.burst(world, particle("CRIT"), seat, 20, 0.3, 0.15, 0.3, 0.25)
                }
                if (t == PISTON_TICKS) {
                    fx.anySound(base, 0.8f, 1.4f, "BLOCK_PISTON_CONTRACT", "BLOCK_PISTON_IN")
                }

                val current = rod
                if (current != null) {

                    val bob = Math.sin(t * BOB_SPEED) * BOB_AMPLITUDE
                    val bobbedSeat = seat.clone().add(0.0, bob, 0.0)

                    val delta = bobbedSeat.toVector().subtract(current.location.toVector())
                    if (delta.lengthSquared() > ROD_JUMP_DIST_SQ) {
                        plugin.scheduler.teleport(current, bobbedSeat)
                        current.velocity = Vector(0.0, 0.0, 0.0)
                    } else {
                        current.velocity = delta
                    }
                } else {

                    var y = 0.0
                    while (y <= 0.9) {
                        fx.burst(world, particle("END_ROD", "CRIT"), seat.clone().add(0.0, y, 0.0), 2)
                        y += 0.25
                    }
                }

                val thrust = if (t < PISTON_TICKS) 3 else 1
                fx.burst(
                    world, particle("LARGE_SMOKE", "SMOKE_LARGE", "SMOKE"),
                    seat.clone().add(0.0, -0.15, 0.0), 6 * thrust, 0.14, 0.06, 0.14, 0.02 + lift * 0.15,
                )
                fx.burst(world, particle("END_ROD", "CRIT"), seat, 5, 0.1, 0.06, 0.1, 0.03)

                for (arm in 0 until 3) {
                    val ang = t * 0.3 + arm * (Math.PI * 2.0 / 3.0)
                    val y   = ((t * 0.09 + arm * 0.75) % 2.3) - 0.6
                    fx.burst(
                        world, particle("END_ROD", "CRIT"),
                        base.clone().add(Math.cos(ang) * 0.85, y, Math.sin(ang) * 0.85), 2, 0.03, 0.03, 0.03, 0.0,
                    )
                }

                for (i in 0 until 6) {
                    val ang = -t * 0.22 + i * (Math.PI / 3.0)
                    fx.burst(
                        world, particle("PORTAL"),
                        base.clone().add(Math.cos(ang) * 1.05, 1.1, Math.sin(ang) * 1.05), 2, 0.05, 0.45, 0.05, 0.04,
                    )
                }
                if (t % 4 == 0) {
                    fx.burst(
                        world, particle("CRIT"),
                        base.clone().add(0.0, 0.9, 0.0), 8, 0.45, 0.5, 0.45, 0.05,
                    )
                }
                if (t % 25 == 0) {
                    fx.anySound(
                        base, 0.4f, 1.3f,
                        "BLOCK_AMETHYST_BLOCK_CHIME", "BLOCK_NOTE_BLOCK_CHIME", "BLOCK_BEACON_AMBIENT",
                    )
                }

                if (++t >= duration) {
                    handle.cancel()
                    val loc = base.clone()
                    val burstAt = seat.clone()
                    cleanup()
                    shatterRod(fx, world, burstAt, if (solid) material else null)
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

    private fun rodLocation(player: Player, base: Location, lift: Double = 0.0): Location {

        val ride = if (player.isInsideVehicle) MOUNT_RIDE_OFFSET else 0.0
        return base.clone().add(0.0, ride + ROD_OFFSET_Y + lift, 0.0).apply { yaw = 0f; pitch = 0f }
    }

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

    private fun shatterRod(fx: Effects, world: org.bukkit.World, seat: Location, material: Material?) {
        val at = seat.clone().add(0.0, 0.5, 0.0)
        fx.anySound(seat, 1f, 0.8f, "BLOCK_GLASS_BREAK")
        if (material != null) fx.blockBurst(world, at, material, 24)
        fx.burst(world, particle("END_ROD", "CRIT"), at, 20, 0.28, 0.25, 0.28, 0.12)
        fx.burst(world, particle("PORTAL"), at, 24, 0.3, 0.5, 0.3, 0.2)
    }

    private fun dropResources(player: Player, fx: Effects, loc: Location) {
        if (!plugin.configManager.animationDropInventory) return
        if (!player.isOnline) return
        val inv = player.inventory
        inv.contents.forEach { item ->
            if (item != null && item.type != Material.AIR) {
                runCatching { loc.world?.dropItemNaturally(loc, item.clone()) }
            }
        }
        inv.clear()
        fx.sound(loc, "ENTITY_ITEM_PICKUP", 0.8f, 0.6f)
    }

    private fun warnUnavailable(kind: String, name: String) {
        if (missingWarned.add(name)) {
            plugin.logger.warning(
                "[Animation] $kind \"$name\" is not available on this server version - that part of the show is skipped."
            )
        }
    }

    private fun particle(vararg names: String): Particle = Compat.particle(*names)

    private inner class Effects(val type: String) {

        private val particlesOn = plugin.configManager.animationTypeParticles(type)
        private val soundsOn    = plugin.configManager.animationTypeSound(type)

        fun burst(
            world: org.bukkit.World, particle: Particle, loc: Location,
            count: Int, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0, speed: Double = 0.0,
        ) {
            if (!particlesOn) return
            runCatching { world.spawnParticle(particle, loc, count, dx, dy, dz, speed, null, true) }
                .onFailure { world.spawnParticle(particle, loc, count, dx, dy, dz, speed) }
        }

        fun blockBurst(world: org.bukkit.World, loc: Location, material: Material, count: Int) {
            if (!particlesOn) return
            val dust = Compat.particle("BLOCK_CRACK", "BLOCK")
            runCatching {
                world.spawnParticle(dust, loc, count, 0.22, 0.4, 0.22, 0.06, material.createBlockData(), true)
            }.onFailure {
                burst(world, particle("END_ROD", "CRIT"), loc, count, 0.22, 0.4, 0.22, 0.06)
            }
        }

        fun explode(loc: Location) {
            val world = loc.world ?: return
            burst(world, particle("EXPLOSION_EMITTER", "EXPLOSION_HUGE", "EXPLOSION"), loc, 1)
            sound(loc, "ENTITY_GENERIC_EXPLODE", 1f, 1f)
        }

        fun sound(loc: Location, name: String, volume: Float, pitch: Float) {
            if (!soundsOn) return
            val sound = Compat.sound(name) ?: return
            loc.world?.playSound(loc, sound, volume, pitch)
        }

        fun anySound(loc: Location, volume: Float, pitch: Float, vararg names: String) {
            if (!soundsOn) return
            val sound = Compat.sound(*names) ?: return
            loc.world?.playSound(loc, sound, volume, pitch)
        }

        fun killSound(player: Player, loc: Location) {
            if (!soundsOn || !plugin.configManager.animationKillSound) return
            val sound = Compat.sound("ENTITY_WITHER_DEATH")
            if (sound == null) {
                warnUnavailable("sound", "ENTITY_WITHER_DEATH")
                return
            }
            runCatching { player.playSound(player.location, sound, 1f, WITHER_PITCH) }
            loc.world?.playSound(loc, sound, 4f, WITHER_PITCH)
        }
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
            if (key == RANDOM) return RANDOM
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

        private const val RISE_FRACTION = 1.0

        private const val ROD_JUMP_DIST_SQ = 9.0

        private const val PISTON_TICKS = 10
        private const val PISTON_SPEED = 0.35

        private const val ROD_OFFSET_Y = -1.0

        private const val MOUNT_RIDE_OFFSET = 0.9

        private const val WITHER_PITCH = 1.8f

        private const val BOB_SPEED = 1.0
        private const val BOB_AMPLITUDE = 0.42
    }
}