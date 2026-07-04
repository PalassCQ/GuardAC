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

package dev.guardac.reputation

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.guardac.GuardAC
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

data class ReputationResult(
    val detections: Int,
    val servers: Int,
    val maxProb: Double,
)

class ReputationClient(private val plugin: GuardAC) {

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "guardac-rep").also { it.isDaemon = true }
    }

    @Volatile private var http: HttpClient = build()
    @Volatile private var shuttingDown = false

    private fun build(): HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(plugin.configManager.aiTimeoutSeconds))
        .executor(executor)
        .build()

    fun reload() { http = build() }

    fun shutdown() {
        shuttingDown = true
        executor.shutdownNow()
    }

    fun report(uuid: UUID, name: String, probability: Double) {
        val cfg = plugin.configManager
        if (shuttingDown) return

        val shareReputation = cfg.reputationEnabled && cfg.reputationReport
        val body = try {
            mapper.writeValueAsString(
                mapOf(
                    "uuid" to uuid.toString(),
                    "name" to name,
                    "probability" to probability,
                    "share_reputation" to shareReputation,
                )
            )
        } catch (e: Exception) {
            return
        }
        val req = HttpRequest.newBuilder(URI.create(cfg.aiBaseUrl + REPORT_PATH))
            .header("Content-Type", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        runCatching {
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally { null }
        }
    }

    fun query(uuid: UUID): CompletableFuture<ReputationResult?> {
        val cfg = plugin.configManager
        if (shuttingDown || !cfg.reputationEnabled) {
            return CompletableFuture.completedFuture(null)
        }
        val req = HttpRequest.newBuilder(URI.create(cfg.aiBaseUrl + REPUTATION_PATH + uuid))
            .header("Accept", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .GET()
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply { resp ->
                if (resp.statusCode() !in 200..299) return@thenApply null
                try {
                    val dto = mapper.readValue(resp.body(), RepDto::class.java)
                    if (!dto.found) null
                    else ReputationResult(dto.detections, dto.servers, dto.max_prob)
                } catch (e: Exception) {
                    null
                }
            }
            .exceptionally { null }
    }

    private companion object {
        const val REPORT_PATH = "/v1/report"
        const val REPUTATION_PATH = "/v1/reputation/"
    }
}

private data class RepDto @JsonCreator constructor(
    @JsonProperty("found")      val found: Boolean = false,
    @JsonProperty("detections") val detections: Int = 0,
    @JsonProperty("servers")    val servers: Int = 0,
    @JsonProperty("max_prob")   val max_prob: Double = 0.0,
)
