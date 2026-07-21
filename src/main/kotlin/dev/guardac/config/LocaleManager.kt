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
import dev.guardac.util.Colors
import dev.guardac.util.Message
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class LocaleManager(private val plugin: GuardAC) {

    private var messages: YamlConfiguration = YamlConfiguration()
    private var fallback: YamlConfiguration = YamlConfiguration()

    fun reload() {
        val locale      = plugin.configManager.locale
        val messagesDir = File(plugin.dataFolder, "messages")
        messagesDir.mkdirs()

        saveDefault("en")
        saveDefault("ru")
        if (!locale.equals("en", ignoreCase = true) && !locale.equals("ru", ignoreCase = true)) {
            saveDefault(locale)
        }
        mergeMissingKeys("en")
        mergeMissingKeys("ru")
        if (!locale.equals("en", ignoreCase = true) && !locale.equals("ru", ignoreCase = true)) {
            mergeMissingKeys(locale)
        }

        val localeFile = File(messagesDir, "messages_$locale.yml")
        val targetFile = if (localeFile.exists()) localeFile
                         else File(messagesDir, "messages_en.yml")

        messages = YamlConfiguration.loadConfiguration(targetFile)

        plugin.getResource("messages/messages_en.yml")?.bufferedReader()?.let {
            fallback = YamlConfiguration.loadConfiguration(it)
        }
    }

    private fun saveDefault(locale: String) {
        val resourcePath = "messages/messages_$locale.yml"
        plugin.getResource(resourcePath) ?: return
        val file = File(File(plugin.dataFolder, "messages"), "messages_$locale.yml")
        if (!file.exists()) plugin.saveResource(resourcePath, false)
    }

    private fun mergeMissingKeys(locale: String) {
        val resourcePath = "messages/messages_$locale.yml"
        val file = File(File(plugin.dataFolder, "messages"), "messages_$locale.yml")
        if (!file.exists()) return
        try {
            val stream = plugin.getResource(resourcePath) ?: return
            val defaults = stream.bufferedReader(Charsets.UTF_8).use { YamlConfiguration.loadConfiguration(it) }
            val onDisk = YamlConfiguration.loadConfiguration(file)
            var added = 0
            for (key in defaults.getKeys(false)) {
                if (!onDisk.contains(key, true)) {
                    onDisk.set(key, defaults.get(key))
                    added++
                }
            }
            if (added > 0) {
                onDisk.save(file)
                plugin.logger.info("[GuardAC] messages_$locale.yml: $added new message(s) added.")
            }
        } catch (e: Exception) {
            plugin.logger.warning("[GuardAC] Could not merge new messages into messages_$locale.yml: ${e.message}")
        }
    }

    fun get(message: Message, vararg placeholders: String): String {
        require(placeholders.size % 2 == 0) { "Placeholders must come in key-value pairs" }

        val raw = messages.getString(message.key)
            ?: fallback.getString(message.key)
            ?: "§c[Missing: ${message.key}]"

        var result = raw
        if (message != Message.PREFIX) {
            result = result.replace("{prefix}", get(Message.PREFIX))
        }
        var i = 0
        while (i < placeholders.size) {
            result = result.replace("{${placeholders[i]}}", placeholders[i + 1])
            i += 2
        }
        return Colors.translate(result)
    }
}
