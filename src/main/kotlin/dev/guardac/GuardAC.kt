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

package dev.guardac

import com.github.retrooper.packetevents.PacketEvents
import dev.guardac.ai.AiTransport
import dev.guardac.ai.BatchingAiTransport
import dev.guardac.ai.HttpAiTransport
import dev.guardac.ai.RetryingAiTransport
import dev.guardac.alert.AlertManager
import dev.guardac.animation.BanAnimationManager
import dev.guardac.brand.ClientBrandListener
import dev.guardac.checks.CheckRegistry
import dev.guardac.combat.DamageListener
import dev.guardac.combat.SuppressionManager
import dev.guardac.compat.WorldGuardCompat
import dev.guardac.command.DataCollectCommand
import dev.guardac.command.GuardCommand
import dev.guardac.config.ConfigManager
import dev.guardac.config.LocaleManager
import dev.guardac.config.MonitorConfig
import dev.guardac.dataset.DataCollectorManager
import dev.guardac.history.PunishmentHistory
import dev.guardac.hologram.HologramConfig
import dev.guardac.hologram.HologramManager
import dev.guardac.packet.PacketListener
import dev.guardac.player.ExemptManager
import dev.guardac.player.PlayerDataManager
import dev.guardac.punishment.PunishmentManager
import dev.guardac.reputation.ReputationClient
import dev.guardac.scan.ScanManager
import dev.guardac.stats.DailyStats
import dev.guardac.update.UpdateManager
import dev.guardac.util.Scheduler
import dev.guardac.util.TaskHandle
import dev.guardac.util.TpsMonitor
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

class GuardAC : JavaPlugin() {

    lateinit var configManager: ConfigManager               private set
    lateinit var monitorConfig: MonitorConfig               private set
    lateinit var hologramConfig: HologramConfig             private set
    lateinit var hologramManager: HologramManager           private set
    lateinit var locale: LocaleManager                      private set
    lateinit var playerDataManager: PlayerDataManager       private set
    lateinit var alertManager: AlertManager                 private set
    lateinit var punishmentManager: PunishmentManager       private set
    lateinit var banAnimationManager: BanAnimationManager    private set
    lateinit var aiTransport: AiTransport                   private set
    lateinit var reputationClient: ReputationClient         private set
    lateinit var checkRegistry: CheckRegistry               private set
    lateinit var dataCollectorManager: DataCollectorManager private set
    lateinit var exemptManager: ExemptManager               private set
    lateinit var dailyStats: DailyStats                     private set
    lateinit var punishmentHistory: PunishmentHistory       private set
    lateinit var worldGuardCompat: WorldGuardCompat         private set
    lateinit var suppressionManager: SuppressionManager     private set
    lateinit var scanManager: ScanManager                   private set
    lateinit var tpsMonitor: TpsMonitor                     private set
    lateinit var updateManager: UpdateManager               private set

    lateinit var scheduler: Scheduler                       private set

    var startTime: Long = 0L
        private set

    private var runtimeStarted = false
    private var vlDecayTask: TaskHandle? = null

    override fun onEnable() {
        instance = this
        runCatching(::enableRuntime).onFailure(::handleEnableFailure)
    }

    override fun onDisable() {
        shutdownRuntime()
    }

    fun reload() {
        runCatching {
            configManager.load()
            monitorConfig.load()
            hologramConfig.load()
            locale.reload()
            alertManager.reload()
            aiTransport.reload()
            reputationClient.reload()
            playerDataManager.reloadAll()
            hologramManager.reload()
            worldGuardCompat = WorldGuardCompat(logger, configManager.worldGuardEnabled, configManager.worldGuardDisabledRegions)
        }.onSuccess {
            logger.info("GuardAC reloaded successfully.")
        }.onFailure {
            logger.log(Level.SEVERE, "Error during reload.", it)
        }
    }

    private fun enableRuntime() {
        startTime             = System.currentTimeMillis()

        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        java.security.Security.setProperty("networkaddress.cache.ttl", "300")
        scheduler             = Scheduler(this)
        configManager         = ConfigManager(this).also { it.load() }
        monitorConfig         = MonitorConfig(this).also { it.load() }
        hologramConfig        = HologramConfig(this).also { it.load() }
        locale                = LocaleManager(this).also { it.reload() }
        playerDataManager     = PlayerDataManager(this)
        alertManager          = AlertManager(this)
        punishmentManager     = PunishmentManager(this)
        banAnimationManager   = BanAnimationManager(this)
        exemptManager         = ExemptManager()
        dataCollectorManager  = DataCollectorManager(this)
        hologramManager       = HologramManager(this)
        dailyStats            = DailyStats(this).also { it.initialize() }
        punishmentHistory     = PunishmentHistory(this).also { it.initialize() }
        worldGuardCompat      = WorldGuardCompat(logger, configManager.worldGuardEnabled, configManager.worldGuardDisabledRegions)
        suppressionManager    = SuppressionManager(this).also { it.start() }
        scanManager           = ScanManager(this)
        tpsMonitor            = TpsMonitor(this).also { it.start() }
        updateManager         = UpdateManager(this, file).also { it.start() }
        val httpTransport     = HttpAiTransport(this)
        val batchedTransport: AiTransport =
            if (configManager.aiBatchingEnabled) BatchingAiTransport(this, httpTransport) else httpTransport
        aiTransport           = if (configManager.aiRetryEnabled)
                                    RetryingAiTransport(this, batchedTransport)
                                else batchedTransport
        reputationClient      = ReputationClient(this)
        checkRegistry         = CheckRegistry(this)

        PacketEvents.getAPI().eventManager.registerListener(PacketListener(this))

        server.pluginManager.registerEvents(playerDataManager, this)
        server.pluginManager.registerEvents(banAnimationManager, this)
        server.pluginManager.registerEvents(DamageListener(this), this)
        startVlDecayTask()
        hologramManager.start()

        reputationClient.startNetworkAlertPolling()

        if (configManager.clientBrandEnabled) {
            runCatching {
                server.messenger.registerIncomingPluginChannel(this, ClientBrandListener.BRAND_CHANNEL, ClientBrandListener(this))
            }.onFailure { logger.warning("[GuardAC] Could not enable client-brand detection: ${it.message}") }
        }

        val guardCommand = GuardCommand(this)
        getCommand("guard")?.setExecutor(guardCommand)
        getCommand("guard")?.tabCompleter = guardCommand

        val dcCommand = DataCollectCommand(this)
        getCommand("guarddc")?.setExecutor(dcCommand)
        getCommand("guarddc")?.tabCompleter = dcCommand

        runtimeStarted = true
        printBanner()
    }

    fun startVlDecayTask() {
        vlDecayTask?.cancel()
        vlDecayTask = null
        if (!configManager.vlDecayEnabled) return
        val intervalTicks = configManager.vlDecayIntervalSeconds * 20L

        vlDecayTask = scheduler.globalTimer(intervalTicks, intervalTicks) {
            val decayAmount  = configManager.vlDecayAmount
            val skipInCombat = configManager.vlDecaySkipInCombat
            playerDataManager.getAll().forEach { gp ->
                if (!gp.player.isOnline) return@forEach
                if (skipInCombat && gp.combat.isInCombatWindow(configManager.aiSequence)) return@forEach
                gp.decayVl(decayAmount)
            }
        }
    }

    private fun handleEnableFailure(cause: Throwable) {
        logger.log(Level.SEVERE, "GuardAC failed to start and will be disabled.", cause)
        shutdownRuntime()
        server.pluginManager.disablePlugin(this)
    }

    private fun shutdownRuntime() {
        if (!runtimeStarted) return
        runtimeStarted = false
        vlDecayTask?.cancel()
        vlDecayTask = null
        runCatching { banAnimationManager.restoreAllFrozen() }
        runCatching { banAnimationManager.removeAnimationEntities() }
        runCatching { hologramManager.stop() }
        runCatching { suppressionManager.stop() }
        runCatching { tpsMonitor.stop() }
        runCatching { updateManager.stop() }
        runCatching { dataCollectorManager.flushAll() }
        runCatching { dailyStats.shutdown() }

        runCatching {
            if (configManager.persistBufferEnabled) {
                playerDataManager.getAll().forEach { gp ->
                    if (gp.aiBuffer > 0.0) {
                        punishmentHistory.saveBufferNow(gp.uuid, gp.aiBuffer, gp.aiViolationLevel)
                    }
                }
            }
        }
        runCatching { punishmentHistory.shutdown() }
        runCatching { aiTransport.shutdown() }
        runCatching { reputationClient.shutdown() }
        runCatching {
            if (configManager.crossServerEnabled) {
                server.messenger.unregisterOutgoingPluginChannel(this)
            }
            server.messenger.unregisterIncomingPluginChannel(this)
        }
        logger.info("GuardAC disabled.")
    }

    private fun printBanner() {
        val version   = description.version
        val mode      = if (configManager.aiOnlyAlert) "only-alert" else "enforcing"
        val aiStatus  = if (configManager.aiEnabled) "online  →  ${configManager.aiServer}  [$mode]" else "disabled"
        val csStatus  = if (configManager.crossServerEnabled) "enabled" else "disabled"
        val sndStatus = if (configManager.alertSoundEnabled) "enabled" else "disabled"
        val hlStatus  = if (hologramConfig.enabled) "enabled" else "disabled"

        logger.info("")
        logger.info("   ▍ GuardAC  ·  v$version")
        logger.info("   ▏")
        logger.info("   ▏  protection  : $aiStatus")
        logger.info("   ▏  cross-server: $csStatus")
        logger.info("   ▏  alert sound : $sndStatus")
        logger.info("   ▏  holograms   : $hlStatus")
        logger.info("   ▍ ready.")
        logger.info("")

        if (configManager.aiEnabled) {
            val key = configManager.aiApiKey
            if (key.isBlank() || key == "PASTE-YOUR-GUARDAC-KEY" || key == "changeme") {
                logger.warning("[GuardAC] API key is not set (ai.api-key). Get a key at " +
                    "${configManager.aiServer} and paste it into config.yml - " +
                    "otherwise the backend will reject requests (401).")
            }
        }
    }

    companion object {
        lateinit var instance: GuardAC
            private set
    }
}
