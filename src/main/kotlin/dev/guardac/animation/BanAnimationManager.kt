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
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BanAnimationManager(private val plugin: GuardAC) {

    private val animating: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun isAnimating(uuid: UUID): Boolean = uuid in animating

    fun onQuit(uuid: UUID) {
        animating.remove(uuid)
    }

    fun playRandom(player: Player, onComplete: () -> Unit) = play(player, TYPES.random(), onComplete)

    fun play(player: Player, type: String?, onComplete: () -> Unit) {
        val cfg = plugin.configManager
        if (!cfg.animationsEnabled || !player.isOnline) { onComplete(); return }

        if (!animating.add(player.uniqueId)) { onComplete(); return }

        val restore = freeze(player)
        val done = AtomicBoolean(false)
        // The ONE terminal step, shared by every animation: exactly one explosion
        // and one inventory drop, no matter how many code paths race to finish
        // (timer end, player quit, error). Whatever the punishment does next runs
        // right after via onComplete().
        val finishWith: (Location) -> Unit = { loc ->
            if (done.compareAndSet(false, true)) {
                animating.remove(player.uniqueId)
                restore()
                dropResources(player, loc)
                explode(loc)
                onComplete()
            }
        }

        val resolved = (type?.trim()?.lowercase()?.ifBlank { null }) ?: cfg.animationDefault
        when (resolved) {
            "pig"       -> playPig(player, finishWith)
            "explode"   -> playExplode(player, finishWith)
            "particles" -> playParticles(player, finishWith)
            "lightning" -> playLightning(player, finishWith)
            "vortex"    -> playVortex(player, finishWith)
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
        val walk = player.walkSpeed
        val fly = player.flySpeed
        val allowFlight = player.allowFlight
        val flying = player.isFlying
        runCatching {
            player.isFlying = false
            player.allowFlight = false
            player.walkSpeed = 0f
            player.flySpeed = 0f
        }
        return {
            runCatching {
                if (player.isOnline) {
                    player.walkSpeed = walk
                    player.flySpeed = fly
                    player.allowFlight = allowFlight
                    player.isFlying = flying
                }
            }
        }
    }

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
                if (!player.isOnline || !pig.isValid) {
                    cancel()
                    val loc = if (pig.isValid) pig.location.clone() else player.location.clone()
                    cleanupPig(pig, player)
                    finishWith(loc)
                    return
                }
                // Re-seat if the player shift-dismounted, so they ride up and can't
                // step off to walk/run away before the explosion.
                if (!pig.passengers.contains(player)) runCatching { pig.addPassenger(player) }
                pig.velocity = if (pig.location.y < targetY) Vector(0.0, RISE_SPEED, 0.0) else Vector(0.0, 0.0, 0.0)
                if (t % 3 == 0) world.spawnParticle(particle("CLOUD"), pig.location, 6, 0.3, 0.1, 0.3, 0.0)
                if (++t >= duration) {
                    cancel()
                    val loc = pig.location.clone()
                    cleanupPig(pig, player)
                    finishWith(loc)   // explosion + resources scatter happen HERE, at the top
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
        val TYPES = listOf("pig", "explode", "particles", "lightning", "vortex")
        const val RISE_SPEED = 0.35
    }
}
