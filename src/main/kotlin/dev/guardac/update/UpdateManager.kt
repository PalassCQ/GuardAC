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
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.update

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import dev.guardac.GuardAC
import dev.guardac.util.Message
import org.bukkit.Bukkit
import dev.guardac.util.TaskHandle
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipFile

class UpdateManager(private val plugin: GuardAC, private val pluginJar: File) {

    private val mapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val stateFile = File(plugin.dataFolder, ".update-state")
    private var task: TaskHandle? = null
    @Volatile private var lastFailureLogMs = 0L

    fun start() {
        val intervalTicks = plugin.configManager.autoUpdateIntervalHours
            .coerceIn(1, 168) * 3600L * 20L
        task = plugin.scheduler.asyncTimer(FIRST_CHECK_DELAY_TICKS, intervalTicks) {
            runCatching(::check).onFailure(::logFailure)
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun check() {
        if (!plugin.configManager.autoUpdateEnabled) return

        val release = fetchLatestRelease() ?: return
        val tag = release.tag_name.trim()
        if (tag.isEmpty() || !TAG_PATTERN.matches(tag)) return

        val known = readState()
        if (known.isEmpty()) {

            writeState(tag)
            return
        }
        if (known == tag) return

        val asset = pickAsset(release.assets.orEmpty()) ?: return
        val bytes = download(asset) ?: return
        if (!looksLikeOurPlugin(bytes)) {
            plugin.logger.warning("[GuardAC] Auto-update: downloaded file for $tag failed validation, skipping.")
            return
        }

        val updateDir = plugin.server.updateFolderFile
        updateDir.mkdirs()
        val staged = File(updateDir, pluginJar.name)
        val tmp = File(updateDir, pluginJar.name + ".download")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), staged.toPath(), StandardCopyOption.REPLACE_EXISTING)
        writeState(tag)

        plugin.logger.info("[GuardAC] Update $tag downloaded (${bytes.size / 1024} KB) - it will be applied on the next server restart.")
        if (plugin.configManager.autoUpdateNotifyStaff) {
            val msg = plugin.locale.get(Message.UPDATE_DOWNLOADED, "tag", tag)
            plugin.scheduler.global(Runnable {
                Bukkit.getOnlinePlayers()
                    .filter { it.hasPermission("guardac.alerts") }
                    .forEach { it.sendMessage(msg) }
            })
        }
    }

    private fun fetchLatestRelease(): ReleaseDto? {
        val req = HttpRequest.newBuilder(URI.create(RELEASES_URL))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GuardAC-Updater")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return null
        return mapper.readValue(res.body(), ReleaseDto::class.java)
    }

    private fun pickAsset(assets: List<AssetDto>): AssetDto? {
        val jars = assets.filter { it.name.endsWith(".jar") && it.browser_download_url.startsWith("https://") }
        if (jars.isEmpty()) return null
        val wanted = if (Runtime.version().feature() >= 21) "java21" else "java17"
        return jars.firstOrNull { it.name.contains(wanted) } ?: jars.first()
    }

    private fun download(asset: AssetDto): ByteArray? {
        val req = HttpRequest.newBuilder(URI.create(asset.browser_download_url))
            .header("User-Agent", "GuardAC-Updater")
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() != 200) return null
        val bytes = res.body()
        if (bytes.size < MIN_JAR_BYTES || bytes.size > MAX_JAR_BYTES) return null
        if (asset.size > 0 && bytes.size != asset.size) return null
        return bytes
    }

    private fun looksLikeOurPlugin(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return false
        val tmp = File.createTempFile("guardac-update", ".jar")
        return try {
            tmp.writeBytes(bytes)
            ZipFile(tmp).use { zip ->
                val entry = zip.getEntry("plugin.yml") ?: return false
                val text = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                text.contains("name: GuardAC")
            }
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    private fun readState(): String =
        runCatching { stateFile.readText().trim() }.getOrDefault("")

    private fun writeState(tag: String) {
        runCatching {
            plugin.dataFolder.mkdirs()
            stateFile.writeText(tag)
        }
    }

    private fun logFailure(cause: Throwable) {

        val now = System.currentTimeMillis()
        if (now - lastFailureLogMs < FAILURE_LOG_THROTTLE_MS) return
        lastFailureLogMs = now
        plugin.logger.info("[GuardAC] Update check failed: ${cause.message}")
    }

    private companion object {
        const val RELEASES_URL = "https://api.github.com/repos/PalassCQ/GuardAC/releases/latest"
        val TAG_PATTERN = Regex("^v[0-9][0-9A-Za-z_.-]{0,30}$")
        const val FIRST_CHECK_DELAY_TICKS = 60L * 20L
        const val MIN_JAR_BYTES = 200 * 1024
        const val MAX_JAR_BYTES = 64 * 1024 * 1024
        const val FAILURE_LOG_THROTTLE_MS = 6L * 3600L * 1000L
    }
}

private data class ReleaseDto @JsonCreator constructor(
    @JsonProperty("tag_name") val tag_name: String = "",
    @JsonProperty("assets")   val assets: List<AssetDto>? = null,
)

private data class AssetDto @JsonCreator constructor(
    @JsonProperty("name")                 val name: String = "",
    @JsonProperty("size")                 val size: Int = 0,
    @JsonProperty("browser_download_url") val browser_download_url: String = "",
)
