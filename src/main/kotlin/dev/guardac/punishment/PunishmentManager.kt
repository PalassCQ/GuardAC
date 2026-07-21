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
 *   - SlothAC (© 2025 KaelusMC, https://github.com/KaelusMC/SlothAC)
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.punishment

import dev.guardac.GuardAC
import dev.guardac.event.GuardPunishmentEvent
import dev.guardac.player.GuardPlayer
import dev.guardac.util.Colors
import dev.guardac.util.Message
import dev.guardac.util.SafeName
import dev.guardac.violation.ViolationLog
import org.bukkit.Bukkit
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PunishmentManager(private val plugin: GuardAC) {

    private data class PunishGroup(
        val checks: List<String>,
        val actions: NavigableMap<Int, List<String>>,
    )

    private val groups = HashMap<String, PunishGroup>()

    val violationLog = ViolationLog()

    private val lastPunishTime = ConcurrentHashMap<UUID, Long>()

    init {
        reload()
    }

    fun reload() {
        groups.clear()

        try {
            plugin.configManager.reloadPunishments()
            for (groupName in plugin.configManager.getPunishmentGroups()) {
                val checks = plugin.configManager.getPunishmentChecks(groupName)
                val rawActions = plugin.configManager.getPunishmentActions(groupName)
                if (rawActions.isEmpty()) continue

                val sorted: NavigableMap<Int, List<String>> = TreeMap()
                rawActions.forEach { (vl, cmds) -> sorted[vl] = cmds }
                groups[groupName] = PunishGroup(checks, sorted)
            }
        } catch (e: Exception) {
            plugin.logger.warning(
                "[Punish] Could not read punishments.yml (${e.message}). " +
                "Using the built-in default - detection and alerts keep working."
            )
        }

        if (!hasGroupFor(AI_CHECK)) {
            groups[AI_CHECK] = defaultAiGroup()
            plugin.logger.info("[Punish] No punishment config for AI - built-in default enabled (alert+log).")
        }
    }

    fun maxVl(checkGroup: String): Int {
        val group = groups[checkGroup]
            ?: groups.values.firstOrNull { g -> g.checks.any { it.equals(checkGroup, ignoreCase = true) } }
            ?: return 1
        return group.actions.lastKey()
    }

    private fun hasGroupFor(check: String): Boolean =
        groups.containsKey(check) ||
            groups.values.any { g -> g.checks.any { it.equals(check, ignoreCase = true) } }

    private fun defaultAiGroup(): PunishGroup {
        val actions: NavigableMap<Int, List<String>> = TreeMap()
        actions[1] = listOf("[alert]", "[log]")
        return PunishGroup(listOf(AI_CHECK), actions)
    }

    fun handle(
        gp: GuardPlayer,
        checkGroup: String,
        vl: Int,
        verbose: String,
        bypassCooldown: Boolean = false,
        forceAnimation: Boolean = false,
    ) {
        val group = groups[checkGroup]
            ?: groups.values.firstOrNull { g ->
                g.checks.any { it.equals(checkGroup, ignoreCase = true) }
            }
            ?: return

        val entry = group.actions.floorEntry(vl) ?: return
        val actions = entry.value

        val cooldownMs = plugin.configManager.punishCooldownMs
        if (cooldownMs > 0) {
            val now = System.currentTimeMillis()
            if (!bypassCooldown) {
                val last = lastPunishTime[gp.uuid]
                if (last != null && (now - last) < cooldownMs) {
                    if (plugin.configManager.debugEnabled) {
                        plugin.logger.info("[Punish] Cooldown active for ${gp.player.name} - skipping (${now - last}ms < ${cooldownMs}ms)")
                    }
                    return
                }
            }
            lastPunishTime[gp.uuid] = now
        }

        plugin.scheduler.entity(gp.player, Runnable {
            val event = GuardPunishmentEvent(gp.player, checkGroup, vl, verbose, actions)
            Bukkit.getPluginManager().callEvent(event)
            if (event.isCancelled) return@Runnable

            violationLog.record(ViolationLog.Entry(
                uuid       = gp.uuid,
                playerName = gp.player.name,
                checkName  = checkGroup,
                vl         = vl,
                probability = gp.lastAiProbability,
                buffer     = gp.aiBuffer,
                verbose    = verbose,
                actions    = actions,
            ))

            plugin.punishmentHistory.record(
                uuid        = gp.uuid,
                name        = gp.player.name,
                check       = checkGroup,
                vl          = vl,
                probability = gp.lastAiProbability,
                action      = summarizeActions(actions),
            )

            val hasRealCommand   = actions.any { isPunishmentCommand(it) }
            val hasExplicitAnim  = actions.any { it.trim().lowercase(Locale.ROOT).startsWith("[animation]") }

            val autoAnim = plugin.configManager.animationAutoOnBan
            val willAnimate = plugin.configManager.animationsEnabled &&
                (autoAnim || forceAnimation || hasExplicitAnim)

            val chain = if (forceAnimation && !hasRealCommand) {
                actions + fallbackBanAction()
            } else {
                actions
            }
            val dropLoot = chain.any { it.trim().lowercase(Locale.ROOT).startsWith("[ban]") }
            if (willAnimate && !hasExplicitAnim) {
                plugin.banAnimationManager.playRandom(gp.player, dropLoot) {
                    executeChain(gp, checkGroup, vl, verbose, chain, 0)
                }
            } else {
                executeChain(gp, checkGroup, vl, verbose, chain, 0)
            }
        })
    }

    fun onPlayerQuit(uuid: UUID) {
        lastPunishTime.remove(uuid)
    }

    private fun fallbackBanAction(): String {
        val time   = plugin.configManager.animationFallbackBanTime
        val reason = plugin.configManager.animationFallbackBanReason
        return if (time.isEmpty()) "[ban] $reason" else "[ban] $time $reason"
    }

    private fun isPunishmentCommand(action: String): Boolean {
        val l = action.trim().lowercase(Locale.ROOT)
        return l.isNotEmpty() &&
            !l.startsWith("[alert]") && !l.startsWith("[log]") &&
            !l.startsWith("[reset]") && !l.startsWith("[wait]") &&
            !l.startsWith("[broadcast]") && !l.startsWith("[animation]")
    }

    private fun summarizeActions(actions: List<String>): String {
        val real = actions.map { it.trim() }.filter { isPunishmentCommand(it) }
        if (real.isEmpty()) return "alert"
        return real.joinToString("; ") {
            val l = it.lowercase(Locale.ROOT)
            when {
                l.startsWith("[ban]")  -> "ban"
                l.startsWith("[kick]") -> "kick"
                else -> it.substringBefore(' ').ifEmpty { it }
            }
        }
    }

    private fun executeChain(
        gp: GuardPlayer,
        checkGroup: String,
        vl: Int,
        verbose: String,
        commands: List<String>,
        index: Int,
    ) {
        if (index >= commands.size) return

        val command = commands[index]
        val lower = command.trim().lowercase(Locale.ROOT)

        if (lower.startsWith("[animation]")) {
            val type = command.trim().substring("[animation]".length).trim().lowercase(Locale.ROOT)
            val dropLoot = commands.any { it.trim().lowercase(Locale.ROOT).startsWith("[ban]") }
            val cont = { executeChain(gp, checkGroup, vl, verbose, commands, index + 1) }
            if (type.isEmpty() || type == "random") {
                plugin.banAnimationManager.playRandom(gp.player, dropLoot, cont)
            } else {
                plugin.banAnimationManager.play(gp.player, type, dropLoot, cont)
            }
            return
        }

        if (lower.startsWith("[wait]")) {
            val arg = command.trim().substring("[wait]".length).trim()
            val ticks = parseDurationTicks(arg)
            if (ticks > 0) {
                plugin.scheduler.entityDelayed(gp.player, ticks, Runnable {
                    executeChain(gp, checkGroup, vl, verbose, commands, index + 1)
                })
                return
            }
        } else {
            executeAction(gp, checkGroup, vl, verbose, command)
        }

        executeChain(gp, checkGroup, vl, verbose, commands, index + 1)
    }

    private fun executeAction(
        gp: GuardPlayer,
        checkGroup: String,
        vl: Int,
        verbose: String,
        action: String,
    ) {
        val name = gp.player.name
        val touchesPlayer = action.contains("<player>") || action.contains("{player}")
        val expanded = action
            .replace("<player>", name).replace("{player}", name)
            .replace("<vl>", vl.toString()).replace("{vl}", vl.toString())
            .replace("<check>", checkGroup).replace("{check}", checkGroup)
            .replace("<verbose>", verbose).replace("{verbose}", verbose)

        val lower = expanded.trim().lowercase(Locale.ROOT)

        when {
            lower == "[alert]" -> {  }

            lower == "[log]" ->
                plugin.logger.info("[$name] violation: $checkGroup | VL: $vl | $verbose")

            lower == "[reset]" -> {
                gp.resetVL(checkGroup)
                if (checkGroup.equals("AI", ignoreCase = true)) gp.resetAi()
            }

            lower.startsWith("[broadcast]") -> {
                val msg = Colors.translate(expanded.trim().substring("[broadcast]".length).trim())
                Bukkit.getServer().onlinePlayers.forEach { p ->
                    p.sendMessage(msg)
                }
            }

            lower.startsWith("[ban]") ->
                executeBridgeBan(gp, expanded.trim().substring("[ban]".length).trim())

            lower.startsWith("[kick]") -> {
                val reason = Colors.translate(
                    expanded.trim().substring("[kick]".length).trim()
                ).ifBlank { "GuardAC" }
                plugin.scheduler.entity(gp.player, Runnable {
                    if (gp.player.isOnline) runCatching { gp.player.kickPlayer(reason) }
                })
            }

            touchesPlayer && !SafeName.isSafe(name) -> {
                plugin.logger.warning(
                    "[Punish] Name '$name' is not safe for a console command - command skipped, player kicked directly."
                )
                if (gp.player.isOnline) {
                    runCatching { gp.player.kickPlayer(plugin.locale.get(Message.UNSAFE_NAME_KICK)) }
                }
            }

            else -> plugin.scheduler.global(Runnable {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded.trim())
            })
        }
    }

    private fun executeBridgeBan(gp: GuardPlayer, arg: String) {
        val name = gp.player.name
        val firstToken = arg.substringBefore(' ')
        val minutes = parseBanMinutes(firstToken)
        val permanent = minutes < 0
        val reason = (if (permanent) arg else arg.substringAfter(' ', "").trim())
            .ifBlank { plugin.configManager.animationFallbackBanReason }
        val mins = if (permanent) 0 else minutes

        plugin.scheduler.global(Runnable {
            if (SafeName.isSafe(name)) {
                BanBridge.ban(plugin, name, reason, mins, "GuardAC")
            } else {

                runCatching {
                    val expires = if (mins > 0) java.util.Date(System.currentTimeMillis() + mins * 60_000L) else null
                    Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(name, reason, expires, "GuardAC")
                }
            }
        })

        plugin.scheduler.entityDelayed(gp.player, 2L, Runnable {
            if (gp.player.isOnline) runCatching { gp.player.kickPlayer(Colors.translate("&c$reason")) }
        })
    }

    private fun parseBanMinutes(token: String): Int {
        val t = token.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return -1
        val unit = t.last()
        val num = if (unit.isDigit()) t else t.dropLast(1)
        val n = num.toIntOrNull() ?: return -1
        if (n < 0) return -1
        return when {
            unit.isDigit() -> n
            unit == 'm'    -> n
            unit == 'h'    -> n * 60
            unit == 'd'    -> n * 1440
            else           -> -1
        }
    }

    private fun parseDurationTicks(raw: String): Long {
        val value = raw.trim().lowercase(Locale.ROOT)
        return try {
            when {
                value.endsWith("ms") -> {
                    val ms = value.dropLast(2).toDouble()
                    kotlin.math.max(0L, kotlin.math.ceil(ms / 50.0).toLong())
                }
                value.endsWith("s") -> {
                    val s = value.dropLast(1).toDouble()
                    kotlin.math.max(0L, (s * 20.0).toLong())
                }
                value.endsWith("t") -> {
                    val t = value.dropLast(1).toDouble()
                    kotlin.math.max(0L, t.toLong())
                }
                else -> kotlin.math.max(0L, value.toLong())
            }
        } catch (_: NumberFormatException) {
            0L
        }
    }

    private companion object {
        const val AI_CHECK = "AI"
    }
}
