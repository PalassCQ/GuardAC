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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Pig
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.scheduler.BukkitRunnable
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
    // Original movement abilities of currently-frozen players. Walk/fly speed are
    // saved into the player's NBT on quit, so a player who leaves (or is banned)
    // mid-animation would come back permanently frozen - onJoin() unfreezes them.
    private val frozen = ConcurrentHashMap<UUID, MovementState>()

    fun isAnimating(uuid: UUID): Boolean = uuid in animating

    fun onQuit(uuid: UUID) {
        animating.remove(uuid)
        anchors.remove(uuid)
        // `frozen` is kept on purpose: the entry is what lets onJoin() restore
        // movement for a player who disconnected before the animation finished.
    }

    fun onJoin(player: Player) {
        frozen.remove(player.uniqueId)?.let { applyState(player, it) }
    }

    /** Plugin shutdown mid-animation: give every frozen player their movement back. */
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
    }

    fun playRandom(player: Player, onComplete: () -> Unit) = play(player, TYPES.random(), onComplete)

    fun play(player: Player, type: String?, onComplete: () -> Unit) {
        val cfg = plugin.configManager
        if (!cfg.animationsEnabled || !player.isOnline) { onComplete(); return }

        // A show is already mid-flight for this player: don't start a second one,
        // but NEVER swallow the punishment chain behind it - queue it to run the
        // moment the current animation finishes (an escalated ban that lands
        // during a 3s animation must still execute).
        if (!animating.add(player.uniqueId)) {
            pendingCompletions.computeIfAbsent(player.uniqueId) {
                java.util.Collections.synchronizedList(mutableListOf())
            }.add(onComplete)
            return
        }

        val restore = freeze(player)
        val done = AtomicBoolean(false)
        // The ONE terminal step, shared by every animation: exactly one explosion
        // and one inventory drop, no matter how many code paths race to finish
        // (timer end, player quit, error). Whatever the punishment does next runs
        // right after via onComplete().
        val finishWith: (Location) -> Unit = { loc ->
            if (done.compareAndSet(false, true)) {
                animating.remove(player.uniqueId)
                anchors.remove(player.uniqueId)
                restore()
                dropResources(player, loc)
                explode(loc)
                onComplete()
                pendingCompletions.remove(player.uniqueId)?.forEach { queued ->
                    runCatching { queued() }
                }
            }
        }

        val resolved = (type?.trim()?.lowercase()?.ifBlank { null }) ?: cfg.animationDefault
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

    /**
     * Locks the player in place for the animation: no walking, no flight, no
     * flying away to escape the show. Returns a restorer that MUST run when the
     * animation ends - a player who survives it (alert-only tiers, /guard punish
     * on a non-ban level) has to get their movement back.
     */
    private fun freeze(player: Player): () -> Unit {
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
        return {
            if (player.isOnline) {
                frozen.remove(player.uniqueId)?.let { applyState(player, it) }
            }
            // Offline: keep the entry - onJoin() restores movement on their next login.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerMove(event: PlayerMoveEvent) {
        val anchor = anchors[event.player.uniqueId] ?: return
        // While riding the animation pig the position is vehicle-driven; a setTo
        // teleport here would eject the passenger. The pig task re-seats instead.
        if (event.player.isInsideVehicle) return
        val to = event.to
        if (sameBlockPosition(anchor, to)) return
        event.setTo(anchor.clone().apply {
            yaw = to.yaw
            pitch = to.pitch
        })
        event.player.velocity = Vector(0.0, 0.0, 0.0)
    }

    private fun sameBlockPosition(a: Location, b: Location): Boolean =
        a.world == b.world && a.x == b.x && a.y == b.y && a.z == b.z

    private fun playPig(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }
        playSound(player.location, "ENTITY_PIG_AMBIENT", 1f, 1f)

        val targetY = player.location.y + plugin.configManager.animationPigHeight
        val pig = world.spawn(player.location, Pig::class.java).apply {
            setGravity(false)
            setAI(false)
            isSilent = false
            isInvulnerable = true
            addPassenger(player)
        }

        val duration = plugin.configManager.animationDurationTicks
        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                // A throw inside a repeating task kills the task silently - the
                // animation lock would never release and every later punishment
                // for this player would queue forever. Fail into finishWith.
                try {
                    if (!player.isOnline || !pig.isValid) {
                        cancel()
                        val loc = if (pig.isValid) pig.location.clone() else player.location.clone()
                        cleanupPig(pig, player)
                        finishWith(loc)
                        return
                    }
                    // Re-seat if the player shift-dismounted, so they ride up and can't
                    // step off to walk/run away before the explosion.
                    anchors[player.uniqueId] = pig.location.clone()
                    if (!pig.passengers.contains(player)) {
                        runCatching { player.teleport(pig.location) }
                        runCatching { pig.addPassenger(player) }
                    }
                    pig.velocity = if (pig.location.y < targetY) Vector(0.0, RISE_SPEED, 0.0) else Vector(0.0, 0.0, 0.0)
                    if (t % 3 == 0) world.spawnParticle(particle("CLOUD"), pig.location, 6, 0.3, 0.1, 0.3, 0.0)
                    if (++t >= duration) {
                        cancel()
                        val loc = pig.location.clone()
                        cleanupPig(pig, player)
                        finishWith(loc)   // explosion + resources scatter happen HERE, at the top
                    }
                } catch (e: Exception) {
                    cancel()
                    cleanupPig(pig, player)
                    finishWith(player.location.clone())
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun cleanupPig(pig: Pig, player: Player) {
        runCatching { pig.removePassenger(player) }
        runCatching { pig.remove() }
    }

    private fun playExplode(player: Player, finishWith: (Location) -> Unit) {
        // A short beat so the boom lands a moment before the ban message.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { finishWith(player.location.clone()) }, 6L)
    }

    private fun playParticles(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val cfg = plugin.configManager
        val duration = cfg.animationDurationTicks
        val perTick  = cfg.animationParticleCount.coerceAtLeast(1)
        val particle = particle(cfg.animationParticle, "FLAME")

        player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, duration + 20, 1, false, false))
        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                try {
                    if (!player.isOnline) { cancel(); finishWith(player.location.clone()); return }
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
                        cancel()
                        runCatching { player.removePotionEffect(PotionEffectType.LEVITATION) }
                        finishWith(player.location.clone())
                    }
                } catch (e: Exception) {
                    cancel()
                    runCatching { player.removePotionEffect(PotionEffectType.LEVITATION) }
                    finishWith(player.location.clone())
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playLightning(player: Player, finishWith: (Location) -> Unit) {
        val duration = plugin.configManager.animationDurationTicks
        val gap = (duration / 4).coerceAtLeast(1).toLong()

        for (i in 0..2) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                runCatching { player.world.strikeLightningEffect(player.location) }
                player.world.spawnParticle(
                    particle("ELECTRIC_SPARK", "CRIT"),
                    player.location.clone().add(0.0, 1.0, 0.0), 25, 0.5, 0.8, 0.5, 0.05,
                )
            }, gap * i)
        }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            finishWith(player.location.clone())
        }, gap * 3)
    }

    private fun playVortex(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        playSound(player.location, "ENTITY_PHANTOM_FLAP", 1f, 0.6f)

        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                try {
                    if (!player.isOnline) { cancel(); finishWith(player.location.clone()); return }
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
                    if (++t >= duration) { cancel(); finishWith(player.location.clone()) }
                } catch (e: Exception) {
                    cancel()
                    finishWith(player.location.clone())
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    /**
     * A burning comet streaks down from the sky at an angle and detonates on
     * the frozen player. The impact IS finishWith - one boom, loot scattered.
     */
    private fun playMeteor(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        val fall = (duration * 2 / 3).coerceAtLeast(15)
        playSound(player.location, "ENTITY_GHAST_SHOOT", 1f, 0.5f)

        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                try {
                    if (!player.isOnline) { cancel(); finishWith(player.location.clone()); return }
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
                        cancel()
                        val impact = player.location.clone()
                        world.spawnParticle(particle("FLAME"), impact, 70, 1.4, 0.5, 1.4, 0.08)
                        world.spawnParticle(particle("LAVA"), impact, 12, 1.0, 0.4, 1.0, 0.0)
                        finishWith(impact)
                    }
                } catch (e: Exception) {
                    cancel()
                    finishWith(player.location.clone())
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    /**
     * A glowing cage of particle bars closes in around the player, chiming as
     * it shrinks, then slams shut into the explosion.
     */
    private fun playCage(player: Player, finishWith: (Location) -> Unit) {
        val world = player.world
        val duration = plugin.configManager.animationDurationTicks
        playSound(player.location, "BLOCK_ANVIL_LAND", 0.6f, 0.5f)

        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                try {
                    if (!player.isOnline) { cancel(); finishWith(player.location.clone()); return }
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
                    // Closing ring overhead.
                    world.spawnParticle(
                        particle("END_ROD", "CRIT"),
                        base.clone().add(0.0, 2.6, 0.0), 4, radius * 0.4, 0.05, radius * 0.4, 0.0,
                    )
                    if (t % 12 == 0) playSound(base, "BLOCK_AMETHYST_BLOCK_CHIME", 1f, 0.6f)
                    if (++t >= duration) { cancel(); finishWith(base.clone()) }
                } catch (e: Exception) {
                    cancel()
                    finishWith(player.location.clone())
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
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
        val sound = runCatching { Sound.valueOf(name) }.getOrNull() ?: return
        loc.world?.playSound(loc, sound, volume, pitch)
    }

    private fun particle(vararg names: String): Particle {
        for (n in names) runCatching { return Particle.valueOf(n) }
        return Particle.FLAME
    }

    private companion object {
        val TYPES = listOf("pig", "explode", "particles", "lightning", "vortex", "meteor", "cage")
        const val RISE_SPEED = 0.35
    }
}
