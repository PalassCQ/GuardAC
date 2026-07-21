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

package dev.guardac.config

import dev.guardac.GuardAC
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class ConfigManager(private val plugin: GuardAC) {

    private lateinit var cfg: FileConfiguration
    lateinit var punishments: YamlConfiguration
        private set

    fun load() {
        plugin.saveDefaultConfig()
        plugin.reloadConfig()
        cfg = plugin.config
        mergeMissingKeys()
        loadPunishments()
        ensureFolders()
        if (aiServer.trim().startsWith("http://", ignoreCase = true)) {
            plugin.logger.warning(
                "[GuardAC] ai.server uses http:// - traffic to the backend is not encrypted " +
                        "(the API key and data are visible in transit). Use https://."
            )
        }
    }

    private fun mergeMissingKeys() {
        try {
            val stream = plugin.getResource("config.yml") ?: return
            val defaults = stream.bufferedReader(Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
            var added = 0
            for (path in defaults.getKeys(true)) {
                if (defaults.isConfigurationSection(path)) continue
                if (!cfg.contains(path, true)) {
                    cfg.set(path, defaults.get(path))
                    added++
                }
            }
            val shippedVersion = defaults.getInt("config-version", cfg.getInt("config-version", 0))
            if (cfg.getInt("config-version", 0) != shippedVersion) {
                cfg.set("config-version", shippedVersion)
                added++
            }
            if (added > 0) {
                plugin.saveConfig()
                plugin.logger.info(
                    "[GuardAC] config.yml: $added new option(s) added from this plugin version " +
                            "(your current values are untouched). See config.yml to tune them."
                )
            }
        } catch (e: Exception) {
            plugin.logger.warning("[GuardAC] Could not merge new options into config.yml: ${e.message}")
        }
    }

    private fun loadPunishments() {
        val file = File(plugin.dataFolder, "punishments.yml")
        if (!file.exists()) plugin.saveResource("punishments.yml", false)
        punishments = YamlConfiguration.loadConfiguration(file)
    }

    fun reloadPunishments() = loadPunishments()

    fun getPunishmentActions(group: String): Map<Int, List<String>> {
        val section = punishments.getConfigurationSection("Punishments.$group.actions") ?: return emptyMap()
        return section.getKeys(false)
            .mapNotNull { key -> key.toIntOrNull()?.let { vl -> vl to section.getStringList(key) } }
            .toMap()
    }

    fun getPunishmentChecks(group: String): List<String> =
        punishments.getStringList("Punishments.$group.checks")

    fun getPunishmentGroups(): Set<String> =
        punishments.getConfigurationSection("Punishments")?.getKeys(false) ?: emptySet()

    private fun ensureFolders() {
        val dir = File(plugin.dataFolder, "datacollection")
        if (!dir.exists()) {
            dir.mkdirs()
            plugin.logger.info("Created folder: datacollection/")
        }
    }

    val datacollectionFolder: File get() = File(plugin.dataFolder, "datacollection")

    val locale: String get() = cfg.getString("locale", "en")!!

    val aiEnabled: Boolean     get() = cfg.getBoolean("ai.enabled", true)

    val aiServer: String       get() = cfg.getString("ai.server", DEFAULT_AI_SERVER)!!
    val aiApiKey: String       get() = cfg.getString("ai.api-key", "PASTE-YOUR-GUARDAC-KEY")!!

    val aiInferUrl: String get() {
        val base = aiServer.trim().trimEnd('/')
        if (base.isEmpty()) return DEFAULT_AI_SERVER + INFER_PATH
        val hasPath = try {
            val p = java.net.URI(base).path
            p != null && p.isNotEmpty() && p != "/"
        } catch (e: Exception) {
            base.endsWith(INFER_PATH)
        }
        return if (hasPath) base else base + INFER_PATH
    }

    val aiBaseUrl: String get() {
        var b = aiServer.trim().trimEnd('/')
        if (b.endsWith(INFER_PATH)) b = b.substring(0, b.length - INFER_PATH.length).trimEnd('/')
        return b
    }

    val aiInferBatchUrl: String get() = aiBaseUrl + INFER_BATCH_PATH
    val aiSequence: Int        get() = cfg.getInt("ai.sequence", 40)
    val aiStep: Int            get() = cfg.getInt("ai.step", 10)
    val aiTimeoutSeconds: Long get() = cfg.getLong("ai.timeout-seconds", 5)
    val aiContinuous: Boolean  get() = cfg.getBoolean("ai.continuous", false)

    val aiBinaryWire: Boolean  get() = cfg.getBoolean("ai.binary-wire", true)

    val aiMinMovement: Double  get() = cfg.getDouble("ai.min-movement", 15.0)

    val aiMinTpsAnalyze: Double get() = cfg.getDouble("ai.min-tps-analyze", 15.0)

    val aiOnlyAlert: Boolean   get() = cfg.getBoolean("ai.only-alert", false)

    val aiBufferFlag: Double        get() = cfg.getDouble("ai.buffer.flag", 30.0)
    val aiBufferResetOnFlag: Double get() = cfg.getDouble("ai.buffer.reset-on-flag", 10.0)
    val aiJudgeEnabled: Boolean     get() = cfg.getBoolean("ai.judge", true)
    val aiBufferMultiplier: Double  get() = cfg.getDouble("ai.buffer.multiplier", 100.0)
    val aiBufferDecrease: Double    get() = cfg.getDouble("ai.buffer.decrease", 0.25)

    val alertsToConsole: Boolean get() = cfg.getBoolean("alerts.print-to-console", true)

    val alertMinConfidence: Double get() = cfg.getDouble("alerts.min-hit-confidence", 75.0)
    val alertMinHits: Int          get() = cfg.getInt("alerts.min-hits", 3)

    val suspiciousAlertsEnabled: Boolean get() = cfg.getBoolean("alerts.suspicious.enabled", true)
    val suspiciousAlertBuffer: Double   get() = cfg.getDouble("alerts.suspicious.buffer", 15.0)

    val alertSoundEnabled: Boolean get() = cfg.getBoolean("alerts.sound.enabled", true)
    val alertSoundType: String     get() = cfg.getString("alerts.sound.type", "BLOCK_NOTE_BLOCK_PLING")!!
    val alertSoundVolume: Float    get() = cfg.getDouble("alerts.sound.volume", 0.8).toFloat()
    val alertSoundPitch: Float     get() = cfg.getDouble("alerts.sound.pitch", 1.5).toFloat()

    val crossServerEnabled: Boolean get() = cfg.getBoolean("cross-server.enabled", false)
    val crossServerPollSeconds: Long get() = cfg.getLong("cross-server.poll-seconds", 10L).coerceIn(3L, 300L)

    val serverName: String
        get() = cfg.getString("server-name", "")!!.ifBlank {
            cfg.getString("cross-server.server-name", "")!!
        }

    val webCommandsEnabled: Boolean    get() = cfg.getBoolean("web-commands.enabled", true)
    val webCommandsPollSeconds: Long   get() = cfg.getLong("web-commands.poll-seconds", 15L).coerceIn(5L, 300L)
    val banBridge: String              get() = cfg.getString("web-commands.ban-bridge", "auto")!!.lowercase()
    val banBridgeBanCommand: String    get() = cfg.getString("web-commands.ban-command", "")!!
    val banBridgeTempbanCommand: String get() = cfg.getString("web-commands.tempban-command", "")!!
    val banBridgeUnbanCommand: String  get() = cfg.getString("web-commands.unban-command", "")!!

    val debugEnabled: Boolean        get() = cfg.getBoolean("debug.enabled", false)
    val debugLogProbability: Boolean get() = cfg.getBoolean("debug.log-probability", false)

    val aiRetryEnabled: Boolean     get() = cfg.getBoolean("ai.retry.enabled", true)
    val aiRetryMaxAttempts: Int     get() = cfg.getInt("ai.retry.max-attempts", 3)
    val aiRetryInitialDelayMs: Long get() = cfg.getLong("ai.retry.initial-delay-ms", 500L)
    val aiRetryMaxDelayMs: Long     get() = cfg.getLong("ai.retry.max-delay-ms", 5000L)

    val aiBatchingEnabled: Boolean  get() = cfg.getBoolean("ai.batching.enabled", false)
    val aiBatchMaxSize: Int         get() = cfg.getInt("ai.batching.max-size", 20)
    val aiBatchMaxDelayMs: Long     get() = cfg.getLong("ai.batching.max-delay-ms", 15L)

    val vlDecayEnabled: Boolean      get() = cfg.getBoolean("vl-decay.enabled", true)
    val vlDecayIntervalSeconds: Int  get() = cfg.getInt("vl-decay.interval-seconds", 60)
    val vlDecayAmount: Int           get() = cfg.getInt("vl-decay.amount", 1)
    val vlDecaySkipInCombat: Boolean get() = cfg.getBoolean("vl-decay.skip-in-combat", true)

    val punishCooldownMs: Long get() = cfg.getLong("punishment.cooldown-ms", 5000L)

    val punishMinTps: Double get() = cfg.getDouble("punishment.min-tps", 16.0)

    val animationsEnabled: Boolean   get() = cfg.getBoolean("animations.enabled", true)
    val animationDurationTicks: Int  get() = cfg.getInt("animations.duration-ticks", 60).coerceIn(1, 600)
    val animationParticle: String    get() = cfg.getString("animations.particle", "FLAME")!!
    val animationParticleCount: Int  get() = cfg.getInt("animations.particle-count", 30).coerceIn(1, 500)
    val animationDropInventory: Boolean get() = cfg.getBoolean("animations.drop-inventory", true)
    val animationSound: Boolean      get() = cfg.getBoolean("animations.sound", true)
    val animationPigHeight: Double   get() = cfg.getDouble("animations.pig-height", 10.0).coerceIn(1.0, 60.0)

    val animationAutoOnBan: Boolean  get() = cfg.getBoolean("animations.auto-on-ban", false)

    val animationFallbackBanTime: String get() =
        cfg.getString("animations.fallback-ban-time", "30d")!!.trim()
    val animationFallbackBanReason: String get() =
        cfg.getString("animations.fallback-ban-reason", "Cheating software detected (GuardAC)")!!.trim()

    val fingerprintEnabled: Boolean      get() = cfg.getBoolean("fingerprint.enabled", true)
    val fingerprintWarmup: Int           get() = cfg.getInt("fingerprint.warmup-hits", 30)
    val fingerprintDriftMargin: Double   get() = cfg.getDouble("fingerprint.drift-margin", 0.35)
    val fingerprintMinRecent: Double     get() = cfg.getDouble("fingerprint.min-recent", 0.55)

    val menuClickCommands: List<String> get() = cfg.getStringList("menu.click-commands")

    val geyserExemptBedrock: Boolean get() = cfg.getBoolean("geyser.exempt-bedrock", true)

    val clientBrandEnabled: Boolean get() = cfg.getBoolean("client-brand.enabled", true)

    val autoUpdateEnabled: Boolean     get() = cfg.getBoolean("auto-update.enabled", true)
    val autoUpdateIntervalHours: Int   get() = cfg.getInt("auto-update.check-interval-hours", 6)
    val autoUpdateNotifyStaff: Boolean get() = cfg.getBoolean("auto-update.notify-staff", true)

    val worldGuardEnabled: Boolean         get() = cfg.getBoolean("worldguard.enabled", false)
    val worldGuardDisabledRegions: List<String> get() = cfg.getStringList("worldguard.disabled-regions")

    val persistBufferEnabled: Boolean get() = cfg.getBoolean("persist-buffer.enabled", true)
    val persistBufferGraceMinutes: Double get() = cfg.getDouble("persist-buffer.grace-minutes", 5.0)
    val persistBufferDecayPerHour: Double get() = cfg.getDouble("persist-buffer.decay-per-hour", 5.0)
    val persistBufferCapOnRestore: Double get() = cfg.getDouble("persist-buffer.cap-on-restore", 20.0)

    val persistBufferTtlMinutes: Double get() =
        cfg.getDouble("persist-buffer.ttl-minutes", cfg.getDouble("persist-buffer.expiry-minutes", 1440.0))

    val combatResetEnabled: Boolean      get() = cfg.getBoolean("combat-reset.enabled", true)
    val combatResetAfterSeconds: Long    get() = cfg.getLong("combat-reset.after-seconds", 60L)

    val suppressionEnabled: Boolean              get() = cfg.getBoolean("combat-suppression.enabled", false)
    val suppressionStartProbability: Double      get() = cfg.getDouble("combat-suppression.penalty.start-probability", 0.75)
    val suppressionMaxAttackSpeedPercent: Double  get() = cfg.getDouble("combat-suppression.penalty.max-percent", 40.0)
    val suppressionResetSeconds: Int             get() = cfg.getInt("combat-suppression.penalty.reset-after-seconds", 10)
    val suppressionIsolateEnabled: Boolean       get() = cfg.getBoolean("combat-suppression.isolate.enabled", true)
    val suppressionIsolateWindowMs: Long         get() = cfg.getLong("combat-suppression.isolate.repeat-window-seconds", 45) * 1000L
    val suppressionIsolateDurationMs: Long       get() = cfg.getLong("combat-suppression.isolate.duration-seconds", 12) * 1000L
    val suppressionIsolateNotify: Boolean        get() = cfg.getBoolean("combat-suppression.isolate.notify-suspect", true)

    val scanWindowsDefault: Int  get() = cfg.getInt("deep-scan.windows", 8)
    val scanTimeoutSeconds: Int  get() = cfg.getInt("deep-scan.timeout-seconds", 120)

    val reputationEnabled: Boolean     get() = cfg.getBoolean("reputation.enabled", true)
    val reputationReport: Boolean      get() = cfg.getBoolean("reputation.report", true)
    val reputationCheckOnJoin: Boolean get() = cfg.getBoolean("reputation.check-on-join", true)
    val reputationAlertThreshold: Int  get() = cfg.getInt("reputation.alert-threshold", 1)

    companion object {

        const val INFER_PATH = "/v1/infer"
        const val INFER_BATCH_PATH = "/v1/infer/batch"
        const val DEFAULT_AI_SERVER = "https://guardac.net"
    }
}
