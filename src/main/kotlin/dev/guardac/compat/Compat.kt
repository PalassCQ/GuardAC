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

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import java.util.concurrent.ConcurrentHashMap

object Compat {

    data class Version(val minor: Int, val patch: Int) {

        fun atLeast(minor: Int, patch: Int = 0): Boolean =
            this.minor > minor || (this.minor == minor && this.patch >= patch)
    }

    val version: Version by lazy {
        runCatching {
            val raw = Bukkit.getBukkitVersion().substringBefore('-')
            val parts = raw.split('.')
            Version(parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }.getOrDefault(Version(0, 0))
    }

    private val PARTICLE_ALIASES: Map<String, String> = mapOf(
        "EXPLOSION_HUGE" to "EXPLOSION_EMITTER",
        "EXPLOSION_LARGE" to "EXPLOSION",
        "EXPLOSION_NORMAL" to "POOF",
        "REDSTONE" to "DUST",
        "SPELL_MOB" to "ENTITY_EFFECT",
        "SPELL_WITCH" to "WITCH",
        "SMOKE_LARGE" to "LARGE_SMOKE",
        "SMOKE_NORMAL" to "SMOKE",
        "FIREWORKS_SPARK" to "FIREWORK",
        "ENCHANTMENT_TABLE" to "ENCHANT",
        "CRIT_MAGIC" to "ENCHANTED_HIT",
        "VILLAGER_HAPPY" to "HAPPY_VILLAGER",
        "WATER_BUBBLE" to "BUBBLE",
        "WATER_SPLASH" to "SPLASH",
        "WATER_WAKE" to "FISHING",
    )

    private val particleCache = ConcurrentHashMap<String, Particle>()

    fun particle(vararg names: String): Particle {
        for (n in names) resolveParticle(n)?.let { return it }
        return Particle.FLAME
    }

    private fun resolveParticle(name: String): Particle? {
        particleCache[name]?.let { return it }
        val resolved = tryParticle(name)
            ?: PARTICLE_ALIASES[name]?.let { tryParticle(it) }
            ?: PARTICLE_ALIASES.entries.firstOrNull { it.value == name }?.let { tryParticle(it.key) }
        if (resolved != null) particleCache[name] = resolved
        return resolved
    }

    private fun tryParticle(name: String): Particle? =
        runCatching { Particle.valueOf(name) }.getOrNull()

    private val soundCache = ConcurrentHashMap<String, Sound>()

    fun sound(vararg names: String): Sound? {
        for (n in names) resolveSound(n)?.let { return it }
        return null
    }

    private fun resolveSound(name: String): Sound? {
        soundCache[name]?.let { return it }
        val resolved = runCatching { Sound.valueOf(name) }.getOrNull() ?: registrySound(name)
        if (resolved != null) soundCache[name] = resolved
        return resolved
    }

    private fun registrySound(enumName: String): Sound? = runCatching {
        val key = NamespacedKey.minecraft(enumName.lowercase().replace('_', '.'))
        registryGet("SOUNDS", key) as? Sound
    }.getOrNull()

    private val attackSpeed: Attribute? by lazy { resolveAttackSpeed() }

    fun attackSpeedAttribute(): Attribute? = attackSpeed

    private fun resolveAttackSpeed(): Attribute? {
        for (path in arrayOf("attack_speed", "generic.attack_speed")) {
            runCatching {
                (registryGet("ATTRIBUTE", NamespacedKey.minecraft(path)) as? Attribute)?.let { return it }
            }
        }
        for (name in arrayOf("GENERIC_ATTACK_SPEED", "ATTACK_SPEED")) {
            runCatching {
                val m = Attribute::class.java.getMethod("valueOf", String::class.java)
                (m.invoke(null, name) as? Attribute)?.let { return it }
            }
        }
        return null
    }

    private fun registryGet(field: String, key: NamespacedKey): Any? = runCatching {
        val registry = Class.forName("org.bukkit.Registry").getField(field).get(null) ?: return null
        val get = registry.javaClass.methods.firstOrNull {
            it.name == "get" && it.parameterCount == 1
        } ?: return null
        get.isAccessible = true
        get.invoke(registry, key)
    }.getOrNull()
}
