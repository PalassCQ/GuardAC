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

package dev.guardac.ai

import dev.guardac.GuardAC
import dev.guardac.data.TickData
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BatchingAiTransport(
    private val plugin: GuardAC,
    private val delegate: HttpAiTransport,
) : AiTransport by delegate {

    private data class Job(
        val ticks: Array<TickData>,
        val priority: Boolean,
        val future: CompletableFuture<InferenceResult>,
    )

    private val queue = ConcurrentLinkedQueue<Job>()
    private val queueSize = AtomicInteger(0)
    private val flushLock = Any()
    private var flushTask: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "guardac-ai-batch").also { it.isDaemon = true }
    }

    override fun infer(ticks: Array<TickData>, priority: Boolean): CompletableFuture<InferenceResult> {
        if (!plugin.configManager.aiBatchingEnabled) return delegate.infer(ticks, priority)

        val future = CompletableFuture<InferenceResult>()
        queue.add(Job(ticks, priority, future))
        val size = queueSize.incrementAndGet()
        if (size >= plugin.configManager.aiBatchMaxSize) {
            flushNow()
        } else {
            scheduleFlush()
        }
        return future
    }

    private fun scheduleFlush() {
        synchronized(flushLock) {
            if (flushTask != null) return
            flushTask = scheduler.schedule({ flushNow() }, plugin.configManager.aiBatchMaxDelayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun flushNow() {
        val jobs = ArrayList<Job>()
        synchronized(flushLock) {
            flushTask?.cancel(false)
            flushTask = null
            while (true) {
                val job = queue.poll() ?: break
                queueSize.decrementAndGet()
                jobs.add(job)
            }
        }
        if (jobs.isEmpty()) return

        if (jobs.size == 1) {
            val job = jobs[0]
            delegate.infer(job.ticks, job.priority).whenComplete { result, ex -> complete(job, result, ex) }
            return
        }

        delegate.inferBatch(jobs.map { it.ticks }, jobs.map { it.priority }).whenComplete { results, ex ->
            if (ex != null) {
                val failure = InferenceResult.Failure(ex.cause ?: ex)
                jobs.forEach { it.future.complete(failure) }
                return@whenComplete
            }
            jobs.forEachIndexed { i, job ->
                job.future.complete(results.getOrElse(i) { InferenceResult.Failure(RuntimeException("missing batch result")) })
            }
        }
    }

    private fun complete(job: Job, result: InferenceResult?, ex: Throwable?) {
        if (ex != null) job.future.complete(InferenceResult.Failure(ex.cause ?: ex))
        else job.future.complete(result ?: InferenceResult.Disabled)
    }

    private fun drain(reason: Throwable) {
        synchronized(flushLock) {
            flushTask?.cancel(false)
            flushTask = null
        }
        while (true) {
            val job = queue.poll() ?: break
            queueSize.decrementAndGet()
            job.future.complete(InferenceResult.Failure(reason))
        }
    }

    override fun reload() {
        delegate.reload()
    }

    override fun shutdown() {
        drain(IllegalStateException("GuardAC AI transport shut down"))
        scheduler.shutdownNow()
        delegate.shutdown()
    }
}
