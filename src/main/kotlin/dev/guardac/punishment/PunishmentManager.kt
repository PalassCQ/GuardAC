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
                "[Punish] Не удалось прочитать punishments.yml (${e.message}). " +
                "Использую встроенный дефолт - детект и алерты продолжают работать."
            )
        }

        if (!hasGroupFor(AI_CHECK)) {
            groups[AI_CHECK] = defaultAiGroup()
            plugin.logger.info("[Punish] Для AI нет настроек наказаний - включён встроенный дефолт (alert+log).")
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

        // The cooldown exists to stop auto-flag storms from stacking bans; a manual
        // staff punish is a deliberate decision and must never be silently eaten by
        // a flag that happened a few seconds earlier.
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

        Bukkit.getScheduler().runTask(plugin, Runnable {
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
            // Auto-animation plays before a real ban/kick; a MANUAL punish plays it
            // unconditionally - staff asked for the show, even on alert-only tiers.
            val autoAnim = plugin.configManager.animationAutoOnBan && hasRealCommand
            if (plugin.configManager.animationsEnabled &&
                (autoAnim || forceAnimation) && !hasExplicitAnim
            ) {
                plugin.banAnimationManager.playRandom(gp.player) {
                    executeChain(gp, checkGroup, vl, verbose, actions, 0)
                }
            } else {
                executeChain(gp, checkGroup, vl, verbose, actions, 0)
            }
        })
    }

    fun onPlayerQuit(uuid: UUID) {
        lastPunishTime.remove(uuid)
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
        return real.joinToString("; ") { it.substringBefore(' ').ifEmpty { it } }
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

            val type = command.trim().substring("[animation]".length).trim().ifBlank { null }
            plugin.banAnimationManager.play(gp.player, type) {
                executeChain(gp, checkGroup, vl, verbose, commands, index + 1)
            }
            return
        }

        if (lower.startsWith("[wait]")) {
            val arg = command.trim().substring("[wait]".length).trim()
            val ticks = parseDurationTicks(arg)
            if (ticks > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    executeChain(gp, checkGroup, vl, verbose, commands, index + 1)
                }, ticks)
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
                plugin.logger.info("[$name] нарушение: $checkGroup | VL: $vl | $verbose")

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

            // A Bedrock/Geyser name with spaces or special characters substituted
            // into a console command shifts the arguments - the punishment could
            // land on a different player entirely. Such players are removed
            // directly through the API instead of through the command.
            touchesPlayer && !SafeName.isSafe(name) -> {
                plugin.logger.warning(
                    "[Punish] Ник '$name' небезопасен для консольной команды - команда пропущена, игрок кикнут напрямую."
                )
                if (gp.player.isOnline) {
                    runCatching { gp.player.kickPlayer(plugin.locale.get(Message.UNSAFE_NAME_KICK)) }
                }
            }

            else -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded.trim())
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
