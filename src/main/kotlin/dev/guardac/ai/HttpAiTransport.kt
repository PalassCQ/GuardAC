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

package dev.guardac.ai

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.guardac.GuardAC
import dev.guardac.data.InferenceRequest
import dev.guardac.data.TickData
import dev.guardac.data.TickDataDto
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class HttpAiTransport(private val plugin: GuardAC) : AiTransport {

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "guardac-ai").also { it.isDaemon = true }
    }

    @Volatile private var httpClient: HttpClient = buildClient()
    @Volatile private var shuttingDown = false

    override val isEnabled: Boolean
        get() = plugin.configManager.aiEnabled

    override fun infer(ticks: Array<TickData>, priority: Boolean): CompletableFuture<InferenceResult> {
        if (!isEnabled || shuttingDown) return CompletableFuture.completedFuture(InferenceResult.Disabled)

        val cfg  = plugin.configManager
        val binary = cfg.aiBinaryWire
        val (publisher, contentType) = try {
            if (binary) {
                HttpRequest.BodyPublishers.ofByteArray(encodeSingle(ticks, priority)) to WIRE_BINARY
            } else {
                val json = mapper.writeValueAsString(
                    InferenceRequest(
                        ticks = ticks.map { TickDataDto.from(it) },
                        count = ticks.size,
                        priority = priority,
                    )
                )
                HttpRequest.BodyPublishers.ofString(json) to WIRE_JSON
            }
        } catch (e: Exception) {
            plugin.logger.warning("[AI] Failed to serialize request: ${e.message}")
            return CompletableFuture.completedFuture(InferenceResult.Failure(e))
        }

        val request = HttpRequest.newBuilder(URI.create(cfg.aiInferUrl))
            .header("Content-Type", contentType)
            .header("Accept",       "application/json")
            .header("X-API-Key",    cfg.aiApiKey)
            .header("User-Agent",   "GuardAC/${plugin.description.version}")
            .POST(publisher)
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (shuttingDown) InferenceResult.Disabled else parseResponse(it) }
            .exceptionally { throwable ->
                val cause = throwable.cause ?: throwable
                InferenceResult.Failure(cause)
            }
    }

    fun inferBatch(
        items: List<Array<TickData>>,
        priorities: List<Boolean> = emptyList(),
    ): CompletableFuture<List<InferenceResult>> {
        if (!isEnabled || shuttingDown) {
            return CompletableFuture.completedFuture(items.map { InferenceResult.Disabled })
        }
        val cfg = plugin.configManager
        val binary = cfg.aiBinaryWire
        val (publisher, contentType) = try {
            if (binary) {
                HttpRequest.BodyPublishers.ofByteArray(encodeBatch(items, priorities)) to WIRE_BINARY
            } else {
                val json = mapper.writeValueAsString(
                    BatchInferenceRequest(
                        windows = items.mapIndexed { i, arr ->
                            InferenceRequest(
                                ticks = arr.map { TickDataDto.from(it) },
                                count = arr.size,
                                priority = priorities.getOrElse(i) { false },
                            )
                        }
                    )
                )
                HttpRequest.BodyPublishers.ofString(json) to WIRE_JSON
            }
        } catch (e: Exception) {
            plugin.logger.warning("[AI] Failed to serialize batch request: ${e.message}")
            return CompletableFuture.completedFuture(items.map { InferenceResult.Failure(e) })
        }

        val request = HttpRequest.newBuilder(URI.create(cfg.aiInferBatchUrl))
            .header("Content-Type", contentType)
            .header("Accept",       "application/json")
            .header("X-API-Key",    cfg.aiApiKey)
            .header("User-Agent",   "GuardAC/${plugin.description.version}")
            .POST(publisher)
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { if (shuttingDown) items.map { InferenceResult.Disabled } else parseBatchResponse(it, items.size) }
            .exceptionally { throwable ->
                val cause = throwable.cause ?: throwable
                items.map { InferenceResult.Failure(cause) }
            }
    }

    override fun reload() {
        httpClient = buildClient()
    }

    override fun shutdown() {
        shuttingDown = true
        executor.shutdownNow()
    }

    private fun buildClient(): HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(plugin.configManager.aiTimeoutSeconds))
        .executor(executor)
        .build()

    private fun parseBatchResponse(response: HttpResponse<String>, expected: Int): List<InferenceResult> {
        if (response.statusCode() !in 200..299) {
            if (plugin.configManager.debugEnabled) {
                plugin.logger.warning("[AI] Batch server returned ${response.statusCode()}: ${response.body()}")
            }
            val failure = InferenceResult.Failure(RuntimeException("HTTP ${response.statusCode()}"))
            return List(expected) { failure }
        }
        return try {
            val dto = mapper.readValue(response.body(), BatchInferenceResponseDto::class.java)
            val results = dto.results.map { toResult(it) }
            if (results.size == expected) results
            else {

                plugin.logger.warning("[AI] Batch response size mismatch: expected $expected, got ${results.size}")
                val failure = InferenceResult.Failure(RuntimeException("batch size mismatch"))
                List(expected) { failure }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[AI] Failed to parse batch response: ${e.message}")
            val failure = InferenceResult.Failure(e)
            List(expected) { failure }
        }
    }

    private fun parseResponse(response: HttpResponse<String>): InferenceResult {
        return if (response.statusCode() in 200..299) {
            try {
                val dto = mapper.readValue(response.body(), InferenceResponseDto::class.java)
                toResult(dto)
            } catch (e: Exception) {
                plugin.logger.warning("[AI] Failed to parse response: ${e.message}")
                InferenceResult.Failure(e)
            }
        } else {
            if (plugin.configManager.debugEnabled) {
                plugin.logger.warning("[AI] Server returned ${response.statusCode()}: ${response.body()}")
            }
            InferenceResult.Failure(RuntimeException("HTTP ${response.statusCode()}"))
        }
    }

    private fun toResult(dto: InferenceResponseDto): InferenceResult {
        val p = dto.probability
        if (p !in 0.0..1.0) {
            plugin.logger.warning("[AI] Rejected malformed response: probability=$p (expected 0..1)")
            return InferenceResult.Failure(IllegalArgumentException("probability out of range: $p"))
        }
        val sources = (dto.sources ?: emptyList()).take(8).map { it.take(24) }
        val model   = (dto.model ?: "Def").take(24)
        return InferenceResult.Success(p, dto.label?.take(32), sources, model, dto.deep == true)
    }

    private fun encodeSingle(ticks: Array<TickData>, priority: Boolean): ByteArray {
        val t = ticks.size
        val buf = ByteBuffer.allocate(6 + t * FEATURES * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(BIN_MAGIC).put(BIN_VERSION)
            .put(if (priority) 1.toByte() else 0.toByte()).put(FEATURES.toByte())
        buf.putShort(t.toShort())
        for (tk in ticks) putTick(buf, tk)
        return buf.array()
    }

    private fun encodeBatch(items: List<Array<TickData>>, priorities: List<Boolean>): ByteArray {
        var size = 6
        for (arr in items) size += 3 + arr.size * FEATURES * 4
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(BIN_MAGIC).put(BIN_VERSION).put(0.toByte()).put(FEATURES.toByte())
        buf.putShort(items.size.toShort())
        items.forEachIndexed { i, arr ->
            buf.put(if (priorities.getOrElse(i) { false }) 1.toByte() else 0.toByte())
            buf.putShort(arr.size.toShort())
            for (tk in arr) putTick(buf, tk)
        }
        return buf.array()
    }

    private fun putTick(buf: ByteBuffer, tk: TickData) {
        buf.putFloat(tk.deltaYaw);    buf.putFloat(tk.deltaPitch)
        buf.putFloat(tk.accelYaw);    buf.putFloat(tk.accelPitch)
        buf.putFloat(tk.jerkYaw);     buf.putFloat(tk.jerkPitch)
        buf.putFloat(tk.gcdErrorYaw); buf.putFloat(tk.gcdErrorPitch)
    }

    private companion object {
        const val WIRE_JSON = "application/json"
        const val WIRE_BINARY = "application/octet-stream"
        const val FEATURES = 8
        const val BIN_MAGIC: Byte = 0x47
        const val BIN_VERSION: Byte = 1
    }
}

private data class InferenceResponseDto @JsonCreator constructor(
    @JsonProperty("probability") val probability: Double,
    @JsonProperty("label")       val label: String? = null,

    @JsonProperty("sources")     val sources: List<String>? = null,

    @JsonProperty("model")       val model: String? = null,

    @JsonProperty("deep")        val deep: Boolean? = null,
)

private data class BatchInferenceRequest(
    val windows: List<InferenceRequest>,
)

private data class BatchInferenceResponseDto @JsonCreator constructor(
    @JsonProperty("results") val results: List<InferenceResponseDto>,
)
