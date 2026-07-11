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
import dev.guardac.util.Message
import dev.guardac.util.SafeName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.bukkit.BanList
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ReputationResult(
    val detections: Int,
    val servers: Int,
    val maxProb: Double,
)

data class NetworkResult(
    val uuid: String,
    val name: String,
    val server: String,
    val model: String,
    val probability: Double,
    val epochMillis: Long,
)

class ReputationClient(private val plugin: GuardAC) {

    // Identifies THIS server inside the key's network for the whole session,
    // even when two servers share a blank server-name. Random per boot.
    val instanceId: String = UUID.randomUUID().toString()

    val displayName: String
        get() = plugin.configManager.serverName.ifBlank { "srv-${plugin.server.port}" }

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
        stopNetworkAlertPolling()
        executor.shutdownNow()
    }

    fun report(uuid: UUID, name: String, probability: Double, vl: Int = 0, verbose: String = "") {
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
                    // Cross-server relay context: which server of this key's
                    // network flagged, so the others can show it to their staff.
                    "server" to displayName,
                    "instance" to instanceId,
                    "vl" to vl,
                    "verbose" to verbose,
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

    // ------------------------------------------------------------------
    // Cross-server alert relay: poll the backend for alerts reported by the
    // key's OTHER servers. No proxy needed - the backend is the bridge.
    // ------------------------------------------------------------------
    @Volatile private var pollTask: BukkitTask? = null
    @Volatile private var pollSince: Double = 0.0
    // The timer fires on schedule even if the previous run is still waiting on a
    // slow request - without this guard two overlapping polls would use the same
    // `since` cursor and deliver every alert twice.
    private val pollInFlight = AtomicBoolean(false)

    fun startNetworkAlertPolling() {
        pollTask?.cancel()
        val period = plugin.configManager.crossServerPollSeconds * 20L
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
            if (shuttingDown) return@Runnable
            if (!pollInFlight.compareAndSet(false, true)) return@Runnable
            try {
                // Result history + web commands work for every customer; only
                // the staff-chat alert relay is behind cross-server.enabled.
                flushPendingResults()
                if (plugin.configManager.crossServerEnabled) pollNetworkAlerts()
                pollWebCommands()
            } finally {
                pollInFlight.set(false)
            }
        }, period, period)
    }

    fun stopNetworkAlertPolling() {
        pollTask?.cancel()
        pollTask = null
    }

    private fun pollNetworkAlerts() {
        val cfg = plugin.configManager
        val url = cfg.aiBaseUrl + NETWORK_ALERTS_PATH +
            "?since=" + pollSince + "&instance=" + instanceId
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .GET()
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) return
            val dto = mapper.readValue(resp.body(), NetworkAlertsDto::class.java)
            dto.alerts.orEmpty().forEach { a ->
                // One malformed alert must not eat the rest of the page.
                runCatching {
                    val server  = sanitize(a.server.ifBlank { "network" }, 24)
                    val player  = sanitize(a.player, 20)
                    val verbose = sanitize(a.verbose, 64)
                    if (player.isNotBlank()) {
                        plugin.alertManager.deliverCrossServerAlert(
                            server, player, "AI", a.vl.coerceIn(0, 1_000_000), verbose,
                        )
                    }
                }
            }
            // Advance the cursor only after the page is handled - an exception
            // above would otherwise skip alerts that were never delivered.
            pollSince = dto.now
        } catch (_: Exception) {
            // Network hiccup: keep the old `since`, the next poll catches up.
        }
    }

    // ------------------------------------------------------------------
    // Cross-server result history: every AI window result is queued here and
    // flushed to the backend in one small batch per poll interval, so /guard
    // results on ANY server of the key can show the player's hits from all of
    // them. Deliberately decoupled from the inference hot path.
    // ------------------------------------------------------------------
    private data class PendingResult(
        val uuid: String, val name: String, val prob: Double, val model: String, val tsMs: Long,
    )

    private val pendingResults = ConcurrentLinkedQueue<PendingResult>()
    private val pendingCount = AtomicInteger(0)

    fun queueResult(uuid: UUID, name: String, probability: Double, model: String) {
        // Not gated by cross-server.enabled: this history also powers the
        // player lookup in the web dashboard, which every customer has.
        if (shuttingDown) return
        // Bounded queue: under a long backend outage the freshest history wins.
        while (pendingCount.get() >= MAX_PENDING_RESULTS) {
            if (pendingResults.poll() == null) break
            pendingCount.decrementAndGet()
        }
        pendingResults.add(PendingResult(uuid.toString(), name, probability, model, System.currentTimeMillis()))
        pendingCount.incrementAndGet()
    }

    private fun flushPendingResults() {
        if (pendingCount.get() == 0) return
        val batch = ArrayList<PendingResult>(MAX_PUSH_BATCH)
        while (batch.size < MAX_PUSH_BATCH) {
            val r = pendingResults.poll() ?: break
            pendingCount.decrementAndGet()
            batch.add(r)
        }
        if (batch.isEmpty()) return

        val cfg = plugin.configManager
        val body = try {
            mapper.writeValueAsString(
                mapOf(
                    "server" to displayName,
                    "results" to batch.map {
                        mapOf(
                            "uuid" to it.uuid,
                            "name" to it.name,
                            "model" to it.model,
                            "prob" to it.prob,
                            "ts" to it.tsMs / 1000.0,
                        )
                    },
                )
            )
        } catch (e: Exception) {
            return
        }
        val req = HttpRequest.newBuilder(URI.create(cfg.aiBaseUrl + RESULTS_PUSH_PATH))
            .header("Content-Type", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        try {
            // Best effort: a failed batch is not retried - the local history
            // database still holds every row, only the network copy is thinner.
            http.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (_: Exception) {
        }
    }

    fun queryNetworkResults(name: String, limit: Int): CompletableFuture<List<NetworkResult>?> {
        val cfg = plugin.configManager
        // Results are pushed regardless of cross-server.enabled (see queueResult),
        // so the query side must stay open too or /guard results goes dark.
        if (shuttingDown) {
            return CompletableFuture.completedFuture(null)
        }
        val url = cfg.aiBaseUrl + RESULTS_QUERY_PATH +
            "?name=" + URLEncoder.encode(name, Charsets.UTF_8) + "&limit=" + limit
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .GET()
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .thenApply { resp ->
                if (resp.statusCode() !in 200..299) return@thenApply null
                try {
                    val dto = mapper.readValue(resp.body(), NetworkResultsDto::class.java)
                    dto.results.orEmpty().mapNotNull { r ->
                        val player = sanitize(r.name, 20)
                        if (player.isBlank() || r.uuid.isBlank()) null
                        else NetworkResult(
                            uuid        = r.uuid.take(64),
                            name        = player,
                            server      = sanitize(r.server.ifBlank { "network" }, 24),
                            model       = sanitize(r.model.ifBlank { "Def" }, 24),
                            probability = r.prob.coerceIn(0.0, 1.0),
                            epochMillis = (r.ts * 1000.0).toLong(),
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .exceptionally { null }
    }

    // ------------------------------------------------------------------
    // Web moderation commands: bans issued from the customer's dashboard.
    // The backend keeps a command visible for a short TTL so every server
    // of the key's network applies it; ids are de-duped here in memory
    // (bans are idempotent, so a re-execution after a restart is harmless).
    // ------------------------------------------------------------------
    @Volatile private var lastWebCommandsPollMs = 0L
    private val executedWebCommandIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private fun pollWebCommands() {
        val cfg = plugin.configManager
        if (!cfg.webCommandsEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastWebCommandsPollMs < cfg.webCommandsPollSeconds * 1000L) return
        lastWebCommandsPollMs = now

        val req = HttpRequest.newBuilder(URI.create(cfg.aiBaseUrl + COMMANDS_POLL_PATH))
            .header("Accept", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .GET()
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        try {
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) return
            val dto = mapper.readValue(resp.body(), WebCommandsDto::class.java)
            dto.commands.orEmpty().forEach { c ->
                runCatching { handleWebCommand(c) }
            }
        } catch (_: Exception) {
            // Network hiccup - the command stays queued, next poll retries.
        }
    }

    private fun handleWebCommand(c: WebCommandDto) {
        if (c.id <= 0 || !executedWebCommandIds.add(c.id)) return
        if (executedWebCommandIds.size > 1_000) executedWebCommandIds.clear()
        if (c.type != "ban" && c.type != "unban") return
        // The name ends up in the ban list and staff chat - same strict rule as
        // console commands. An unsafe name is refused, not "cleaned".
        val name = c.player.take(16)
        if (!SafeName.isSafe(name)) {
            plugin.logger.warning("[GuardAC] Web command ${c.id}: unsafe player name, skipped.")
            ackWebCommand(c.id)
            return
        }
        val staff = sanitize(c.requestedBy, 32).ifBlank { "dashboard" }
        if (c.type == "unban") {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                Bukkit.getBanList(BanList.Type.NAME).pardon(name)
                val msg = plugin.locale.get(Message.WEB_UNBAN_EXECUTED, "player", name, "staff", staff)
                Bukkit.getOnlinePlayers()
                    .filter { it.hasPermission("guardac.alerts") }
                    .forEach { it.sendMessage(msg) }
                plugin.logger.info("[GuardAC] Web unban applied: $name by $staff")
            })
            ackWebCommand(c.id)
            return
        }
        val reason  = sanitize(c.reason, 120).ifBlank { "Banned by server staff" }
        val minutes = c.durationMinutes.coerceIn(0, 527_040)
        val durationLabel = when {
            minutes <= 0          -> "\u221E"
            minutes % 1440 == 0   -> "${minutes / 1440}d"
            minutes % 60 == 0     -> "${minutes / 60}h"
            else                  -> "${minutes}m"
        }
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val expires = if (minutes > 0) Date(System.currentTimeMillis() + minutes * 60_000L) else null
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expires, "GuardAC Web ($staff)")
            Bukkit.getPlayerExact(name)?.kickPlayer(
                plugin.locale.get(Message.WEB_BAN_KICK, "reason", reason)
            )
            val msg = plugin.locale.get(
                Message.WEB_BAN_EXECUTED,
                "player",   name,
                "staff",    staff,
                "reason",   reason,
                "duration", durationLabel,
            )
            Bukkit.getOnlinePlayers()
                .filter { it.hasPermission("guardac.alerts") }
                .forEach { it.sendMessage(msg) }
            plugin.logger.info("[GuardAC] Web ban applied: $name by $staff ($reason, $durationLabel)")
        })
        ackWebCommand(c.id)
    }

    private fun ackWebCommand(id: Long) {
        val cfg = plugin.configManager
        val body = try {
            mapper.writeValueAsString(mapOf("id" to id, "server" to displayName))
        } catch (e: Exception) {
            return
        }
        val req = HttpRequest.newBuilder(URI.create(cfg.aiBaseUrl + COMMANDS_ACK_PATH))
            .header("Content-Type", "application/json")
            .header("X-API-Key", cfg.aiApiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(cfg.aiTimeoutSeconds))
            .build()
        runCatching {
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally { null }
        }
    }

    private fun sanitize(v: String, max: Int): String =
        v.filter { it.code >= 0x20 && it != '\u00A7' && it != '&' }.take(max)

    private companion object {
        const val REPORT_PATH = "/v1/report"
        const val REPUTATION_PATH = "/v1/reputation/"
        const val NETWORK_ALERTS_PATH = "/v1/network/alerts"
        const val RESULTS_PUSH_PATH = "/v1/results/push"
        const val RESULTS_QUERY_PATH = "/v1/results/query"
        const val COMMANDS_POLL_PATH = "/v1/commands/poll"
        const val COMMANDS_ACK_PATH = "/v1/commands/ack"
        // Backend caps a push at 200 results; the queue keeps a little headroom
        // so a short outage doesn't throw history away immediately.
        const val MAX_PUSH_BATCH = 200
        const val MAX_PENDING_RESULTS = 600
    }
}

private data class RepDto @JsonCreator constructor(
    @JsonProperty("found")      val found: Boolean = false,
    @JsonProperty("detections") val detections: Int = 0,
    @JsonProperty("servers")    val servers: Int = 0,
    @JsonProperty("max_prob")   val max_prob: Double = 0.0,
)

private data class NetworkAlertsDto @JsonCreator constructor(
    @JsonProperty("now")    val now: Double = 0.0,
    @JsonProperty("alerts") val alerts: List<NetworkAlertDto>? = null,
)

private data class NetworkAlertDto @JsonCreator constructor(
    @JsonProperty("server")      val server: String = "",
    @JsonProperty("player")      val player: String = "",
    @JsonProperty("vl")          val vl: Int = 0,
    @JsonProperty("verbose")     val verbose: String = "",
    @JsonProperty("probability") val probability: Double = 0.0,
    @JsonProperty("ts")          val ts: Double = 0.0,
)

private data class NetworkResultsDto @JsonCreator constructor(
    @JsonProperty("results") val results: List<NetworkResultDto>? = null,
)

private data class WebCommandsDto @JsonCreator constructor(
    @JsonProperty("commands") val commands: List<WebCommandDto>? = null,
)

private data class WebCommandDto @JsonCreator constructor(
    @JsonProperty("id")               val id: Long = 0,
    @JsonProperty("type")             val type: String = "",
    @JsonProperty("player")           val player: String = "",
    @JsonProperty("reason")           val reason: String = "",
    @JsonProperty("duration_minutes") val durationMinutes: Int = 0,
    @JsonProperty("requested_by")     val requestedBy: String = "",
    @JsonProperty("ts")               val ts: Double = 0.0,
)

private data class NetworkResultDto @JsonCreator constructor(
    @JsonProperty("uuid")   val uuid: String = "",
    @JsonProperty("name")   val name: String = "",
    @JsonProperty("server") val server: String = "",
    @JsonProperty("model")  val model: String = "",
    @JsonProperty("prob")   val prob: Double = 0.0,
    @JsonProperty("ts")     val ts: Double = 0.0,
)
