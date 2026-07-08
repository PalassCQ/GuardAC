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

        val done = AtomicBoolean(false)
        val complete: () -> Unit = {
            if (done.compareAndSet(false, true)) {
                animating.remove(player.uniqueId)
                onComplete()
            }
        }

        val resolved = (type?.trim()?.lowercase()?.ifBlank { null }) ?: cfg.animationDefault
        when (resolved) {
            "pig"       -> playPig(player, complete)
            "explode"   -> playExplode(player, complete)
            "particles" -> playParticles(player, complete)
            "lightning" -> playLightning(player, complete)
            "vortex"    -> playVortex(player, complete)
            else        -> complete()
        }
    }

    private fun playPig(player: Player, onComplete: () -> Unit) {
        val world = player.world
        if (player.isInsideVehicle) runCatching { player.leaveVehicle() }
        dropResources(player)
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
                try {
                    if (!player.isOnline || !pig.isValid) { cancel(); finish(pig, player, onComplete); return }

                    pig.velocity = if (pig.location.y < targetY) Vector(0.0, RISE_SPEED, 0.0) else Vector(0.0, 0.0, 0.0)
                    if (t % 3 == 0) world.spawnParticle(particle("CLOUD"), pig.location, 6, 0.3, 0.1, 0.3, 0.0)
                    if (++t >= duration) { cancel(); finish(pig, player, onComplete) }
                } catch (e: Exception) {
                    cancel(); finish(pig, player, onComplete)
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun finish(pig: Pig, player: Player, onComplete: () -> Unit) {
        val loc = if (pig.isValid) pig.location.clone() else player.location.clone()
        runCatching { pig.removePassenger(player) }
        runCatching { pig.remove() }
        explode(loc)
        onComplete()
    }

    private fun playExplode(player: Player, onComplete: () -> Unit) {
        dropResources(player)
        explode(player.location.clone())

        Bukkit.getScheduler().runTaskLater(plugin, Runnable { onComplete() }, 8L)
    }

    private fun playParticles(player: Player, onComplete: () -> Unit) {
        val world = player.world
        dropResources(player)
        val cfg = plugin.configManager
        val duration = cfg.animationDurationTicks
        val perTick  = cfg.animationParticleCount.coerceAtLeast(1)
        val particle = particle(cfg.animationParticle, "FLAME")

        player.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, duration + 20, 1, false, false))
        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                if (!player.isOnline) { cancel(); onComplete(); return }
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
                    player.removePotionEffect(PotionEffectType.LEVITATION)
                    explode(player.location.clone())
                    onComplete()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun playLightning(player: Player, onComplete: () -> Unit) {
        dropResources(player)
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
            explode(player.location.clone())
            onComplete()
        }, gap * 3)
    }

    private fun playVortex(player: Player, onComplete: () -> Unit) {
        val world = player.world
        dropResources(player)
        val duration = plugin.configManager.animationDurationTicks
        playSound(player.location, "ENTITY_PHANTOM_FLAP", 1f, 0.6f)

        object : BukkitRunnable() {
            var t = 0
            override fun run() {
                if (!player.isOnline) { cancel(); onComplete(); return }
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
                if (t == duration / 2) player.velocity = Vector(0.0, 0.9, 0.0)
                if (++t >= duration) { cancel(); explode(player.location.clone()); onComplete() }
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun dropResources(player: Player) {
        if (!plugin.configManager.animationDropInventory) return
        val inv = player.inventory
        val loc = player.location

        inv.contents.forEach { item ->
            if (item != null && item.type != Material.AIR) {
                runCatching { player.world.dropItemNaturally(loc, item.clone()) }
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
