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

package dev.guardac.checks.impl

import dev.guardac.GuardAC
import dev.guardac.ai.InferenceResult
import dev.guardac.checks.type.SequenceCheck
import dev.guardac.sample.AimSample
import dev.guardac.event.GuardAiPredictionEvent
import dev.guardac.player.GuardPlayer
import org.bukkit.Bukkit
import kotlin.math.abs

class AiCheck(private val plugin: GuardAC) : SequenceCheck {

    override fun onSequence(gp: GuardPlayer, ticks: Array<AimSample>) {
        val cfg = plugin.configManager
        if (!cfg.aiEnabled) return
        if (gp.isRiding) return

        if (cfg.geyserExemptBedrock && gp.isBedrock) return

        if (plugin.worldGuardCompat.shouldBypass(gp.player)) return

        val unstable = gp.consumeUnstableTicks()
        val lagDistorted = unstable >= UNSTABLE_TICKS_MIN && gp.player.ping >= UNSTABLE_PING_MIN

        if (isDeadWindow(ticks, cfg.aiDeadZone, cfg.aiMinActiveTicks)) return

        val minTps = cfg.aiMinTpsAnalyze
        if (minTps > 0.0 && plugin.tpsMonitor.tps < minTps) return

        plugin.aiTransport.infer(ticks, false)
            .thenAccept { result -> handleResult(gp, result, lagDistorted, ticks) }
    }

    private fun isDeadWindow(ticks: Array<AimSample>, deadZone: Double, minActive: Int): Boolean {
        var active = 0
        for (t in ticks) {
            if (abs(t.deltaYaw) + abs(t.deltaPitch) >= deadZone) {
                active++
                if (active >= minActive) return false
            }
        }
        return true
    }

    private fun handleResult(
        gp: GuardPlayer, result: InferenceResult, lagDistorted: Boolean, ticks: Array<AimSample>,
    ) {
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
                plugin.punishmentHistory.recordResult(gp.uuid, gp.player.name, result.model, prob)
                plugin.reputationClient.queueResult(gp.uuid, gp.player.name, prob, result.model)

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
                gp.addAiProbability(prob, lagDistorted)
                val bufferAfter  = gp.aiBuffer

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
                plugin.alertManager.dispatchMonitorHit(gp, prob, result.model)

                val batchAnnounced = plugin.alertManager.recordVerdict(gp, prob, modelTag(result.sources))
                val flagged        = batchAnnounced && gp.creditAiViolation()
                val isolatedAfter  = gp.isIsolated

                plugin.scheduler.entity(gp.player, Runnable {
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

                        val confidence = gp.flagConfidence()
                        val verbose = buildVerbose(confidence)

                        plugin.reputationClient.report(
                            gp.uuid, gp.player.name, confidence, gp.aiViolationLevel, verbose, ticks,
                        )

                        val tps = plugin.tpsMonitor.tps
                        val minTps = plugin.configManager.punishMinTps
                        if (minTps > 0.0 && tps < minTps) {
                            plugin.logger.info(
                                "[GuardAC] Punishment for ${gp.player.name} delayed: TPS ${"%.1f".format(tps)} < $minTps (lag gate)."
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
        private const val UNSTABLE_TICKS_MIN = 3
        private const val UNSTABLE_PING_MIN  = 100
    }
}
