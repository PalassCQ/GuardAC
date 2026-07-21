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

package dev.guardac.ai

import dev.guardac.GuardAC
import dev.guardac.sample.AimSample
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.random.Random

class RetryingAiTransport(
    private val plugin: GuardAC,
    private val delegate: AiTransport,
) : AiTransport by delegate {

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "guardac-ai-retry").also { it.isDaemon = true }
    }

    private val consecutiveFailures = AtomicInteger(0)
    @Volatile private var circuitOpenUntil: Long = 0L

    private val halfOpenProbeInFlight = AtomicBoolean(false)

    override val isEnabled: Boolean get() = delegate.isEnabled

    override fun infer(ticks: Array<AimSample>, priority: Boolean): CompletableFuture<InferenceResult> {
        if (!isEnabled) return CompletableFuture.completedFuture(InferenceResult.Disabled)

        val now = System.currentTimeMillis()
        val openUntil = circuitOpenUntil
        if (openUntil > now) {
            return CompletableFuture.completedFuture(InferenceResult.Disabled)
        }
        if (openUntil > 0L && openUntil <= now) {
            if (!halfOpenProbeInFlight.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(InferenceResult.Disabled)
            }
            plugin.logger.info("[GuardAC] AI circuit breaker HALF-OPEN - sending a probe request.")
        }

        return attempt(ticks, priority, attempt = 1)
    }

    private fun attempt(ticks: Array<AimSample>, priority: Boolean, attempt: Int): CompletableFuture<InferenceResult> {
        return delegate.infer(ticks, priority).thenCompose { result ->
            when (result) {
                is InferenceResult.Success -> {
                    onSuccess()
                    CompletableFuture.completedFuture(result)
                }
                is InferenceResult.Failure -> {
                    if (shouldRetry(result.cause) && attempt < maxAttempts()) {
                        scheduleRetry(ticks, priority, attempt)
                    } else {
                        onFailure(result.cause)
                        CompletableFuture.completedFuture<InferenceResult>(result)
                    }
                }
                is InferenceResult.Disabled -> CompletableFuture.completedFuture(result)
            }
        }
    }

    private fun scheduleRetry(ticks: Array<AimSample>, priority: Boolean, attempt: Int): CompletableFuture<InferenceResult> {
        val result = CompletableFuture<InferenceResult>()
        val delayMs = computeDelayMs(attempt)
        scheduler.schedule({
            attempt(ticks, priority, attempt + 1).whenComplete { value, ex ->
                if (ex != null) result.completeExceptionally(ex)
                else result.complete(value)
            }
        }, delayMs, TimeUnit.MILLISECONDS)
        return result
    }

    private fun onSuccess() {
        val wasOpen = circuitOpenUntil > 0L
        consecutiveFailures.set(0)
        halfOpenProbeInFlight.set(false)
        circuitOpenUntil = 0L
        if (wasOpen) {
            plugin.logger.info("[GuardAC] AI circuit breaker CLOSED - connection restored.")
        }
    }

    private fun onFailure(cause: Throwable) {
        halfOpenProbeInFlight.set(false)
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= CIRCUIT_OPEN_THRESHOLD) {
            val until = System.currentTimeMillis() + CIRCUIT_HALF_OPEN_DELAY_MS
            circuitOpenUntil = until
            plugin.logger.warning(
                "[GuardAC] AI circuit breaker OPEN - $failures consecutive failures. " +
                "Retrying in ${CIRCUIT_HALF_OPEN_DELAY_MS / 1000}s. Cause: ${cause.message}"
            )
        } else if (plugin.configManager.debugEnabled) {
            plugin.logger.warning("[AI] Inference error (attempt $failures): ${cause.message}")
        }
    }

    private fun shouldRetry(cause: Throwable): Boolean = when (cause) {
        is IOException                     -> true
        is java.net.ConnectException       -> true
        is java.net.SocketTimeoutException -> true
        is RuntimeException -> cause.message?.startsWith("HTTP 5") == true ||
                               cause.message?.startsWith("HTTP 429") == true
        else -> false
    }

    private fun maxAttempts(): Int = plugin.configManager.aiRetryMaxAttempts

    private fun computeDelayMs(attempt: Int): Long {
        val cfg = plugin.configManager
        val base = (cfg.aiRetryInitialDelayMs * BACKOFF_MULTIPLIER.pow(attempt - 1).toLong())
            .coerceAtMost(cfg.aiRetryMaxDelayMs)
        val jitter = (base * 0.2).toLong()
        return (base + Random.nextLong(-jitter, jitter + 1)).coerceAtLeast(0L)
    }

    override fun reload() = delegate.reload()

    override fun shutdown() {
        scheduler.shutdownNow()
        delegate.shutdown()
    }

    private companion object {
        const val BACKOFF_MULTIPLIER = 2.0

        const val CIRCUIT_OPEN_THRESHOLD = 5

        const val CIRCUIT_HALF_OPEN_DELAY_MS = 30_000L
    }
}
