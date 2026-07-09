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

package dev.guardac.checks.impl

import dev.guardac.GuardAC
import dev.guardac.ai.InferenceResult
import dev.guardac.checks.type.SequenceCheck
import dev.guardac.data.TickData
import dev.guardac.event.GuardAiPredictionEvent
import dev.guardac.player.GuardPlayer
import org.bukkit.Bukkit
import kotlin.math.abs

class AiCheck(private val plugin: GuardAC) : SequenceCheck {

    override fun onSequence(gp: GuardPlayer, ticks: Array<TickData>) {
        val cfg = plugin.configManager
        if (!cfg.aiEnabled) return
        if (gp.isRiding) return

        if (cfg.geyserExemptBedrock && gp.isBedrock) return

        if (plugin.worldGuardCompat.shouldBypass(gp.player)) return

        val scanning = plugin.scanManager.isScanning(gp.uuid)

        // Client-side lag guard: a window built from bursty packet timings has
        // distorted deltas - a lagging but honest player reads as inhuman aim.
        // Requires BOTH bad timings AND real measured latency, so a cheat can't
        // fake jitter for a free skip while keeping a clean low ping.
        val unstable = gp.consumeUnstableTicks()
        if (!scanning && cfg.aiLagProtection &&
            unstable >= UNSTABLE_TICKS_MIN && gp.player.ping >= UNSTABLE_PING_MIN
        ) return

        // The window must contain enough real aim movement before we judge it: a
        // near-static camera carries no signal and only adds noise. Raising this
        // means the model reacts to a hit only after clear camera work.
        if (isBelowMovementThreshold(ticks, cfg.aiMinMovement)) return

        // Lag guard: when the server TPS drops, tick timing is stretched and the
        // movement features look unnatural - that is exactly when a laggy but
        // legit player can read as a cheat. Skip analysis during a drop so lag
        // never becomes a detection. A Deep Scan (explicit staff request) overrides.
        val minTps = cfg.aiMinTpsAnalyze
        if (!scanning && minTps > 0.0 && plugin.tpsMonitor.tps < minTps) return

        plugin.aiTransport.infer(ticks, scanning)
            .thenAccept { result -> handleResult(gp, result) }
    }

    private fun isBelowMovementThreshold(ticks: Array<TickData>, minMovement: Double): Boolean {
        var sum = 0.0
        for (t in ticks) {
            sum += abs(t.deltaYaw) + abs(t.deltaPitch)
            if (sum >= minMovement) return false
        }
        return true
    }

    private fun handleResult(gp: GuardPlayer, result: InferenceResult) {
        when (result) {
            is InferenceResult.Disabled -> return
            is InferenceResult.Failure  -> {
                if (plugin.configManager.debugEnabled) {
                    plugin.logger.warning("[AI] Inference error for ${gp.player.name}: ${result.cause.message}")
                }
                return
            }
            is InferenceResult.Success  -> {
                val prob  = result.probability
                val label = result.label ?: "AI"

                plugin.dailyStats.recordRequest()

                if (plugin.configManager.debugLogProbability) {
                    plugin.logger.info(
                        "[AI] ${gp.player.name} | prob=${"%.3f".format(prob)}" +
                        " | avg=${"%.3f".format(gp.avgProbability)}" +
                        " | buf=${"%.2f".format(gp.aiBuffer)}" +
                        " | label=$label" +
                        " | ping=${gp.player.ping}ms"
                    )
                }

                val bufferBefore   = gp.aiBuffer
                val isolatedBefore = gp.isIsolated
                gp.addAiProbability(prob)
                val bufferAfter  = gp.aiBuffer

                plugin.scanManager.onResult(gp.uuid, prob, result.sources)

                if (gp.checkFingerprintDrift()) {
                    plugin.alertManager.sendFingerprintAlert(gp)
                }

                val cfgSuspicious = plugin.configManager
                if (cfgSuspicious.suspiciousAlertsEnabled) {
                    val threshold = cfgSuspicious.suspiciousAlertBuffer
                    if (bufferBefore <= threshold && bufferAfter > threshold) {
                        plugin.alertManager.sendSuspiciousAlert(gp, bufferAfter)
                    }
                }
                val flagged      = gp.flagAi()
                val isolatedAfter = gp.isIsolated

                plugin.alertManager.dispatchMonitorHit(gp, prob, result.model)

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    if (!gp.player.isOnline) return@Runnable

                    if (!isolatedBefore && isolatedAfter) {
                        plugin.suppressionManager.notifyIsolate(gp)
                    }
                    val event = GuardAiPredictionEvent(
                        player         = gp.player,
                        probability    = prob,
                        bufferBefore   = bufferBefore,
                        bufferAfter    = bufferAfter,
                        violationLevel = gp.aiViolationLevel,
                        flagged        = flagged,
                        label          = label,
                    )
                    Bukkit.getPluginManager().callEvent(event)
                    if (flagged && !event.isCancelled) {
                        plugin.dailyStats.recordDetection()

                        plugin.reputationClient.report(gp.uuid, gp.player.name, prob)
                        val verbose = buildVerbose(prob)

                        if (prob * 100.0 >= plugin.configManager.alertMinConfidence) {
                            plugin.alertManager.sendAlert(gp, CHECK_NAME, gp.aiViolationLevel, verbose, modelTag(result.sources))
                        }

                        val tps = plugin.tpsMonitor.tps
                        val minTps = plugin.configManager.punishMinTps
                        if (minTps > 0.0 && tps < minTps) {
                            plugin.logger.info(
                                "[GuardAC] Наказание для ${gp.player.name} отложено: TPS ${"%.1f".format(tps)} < $minTps (лаг-гейт)."
                            )
                        } else if (!plugin.configManager.aiOnlyAlert) {
                            plugin.punishmentManager.handle(gp, CHECK_NAME, gp.aiViolationLevel, verbose)
                        }
                    }
                })
            }
        }
    }

    private fun buildVerbose(prob: Double): String = "${"%.0f".format(prob * 100.0)}%"

    private fun modelTag(sources: List<String>): String =
        if (sources.isEmpty()) "[AI]" else sources.joinToString("") { "[$it]" }

    companion object {
        private const val CHECK_NAME = "AI"
        private const val UNSTABLE_TICKS_MIN = 5
        private const val UNSTABLE_PING_MIN  = 100
    }
}
