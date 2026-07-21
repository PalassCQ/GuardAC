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

package dev.guardac.util

import java.util.UUID

object GeyserUtil {

    private var floodgateApiInstance: Any? = null
    private var isFloodgatePlayerMethod: java.lang.reflect.Method? = null
    private var geyserApiClass: Class<*>? = null

    init {

        runCatching {
            val cls = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            floodgateApiInstance = cls.getMethod("getInstance").invoke(null)
            isFloodgatePlayerMethod = cls.getMethod("isFloodgatePlayer", UUID::class.java)
        }

        runCatching { geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi") }
    }

    val available: Boolean
        get() = floodgateApiInstance != null || geyserApiClass != null

    fun isBedrock(uuid: UUID): Boolean {

        val api = floodgateApiInstance
        val method = isFloodgatePlayerMethod
        if (api != null && method != null) {
            runCatching {
                val r = method.invoke(api, uuid)
                if (r is Boolean && r) return true
            }
        }

        val gcls = geyserApiClass
        if (gcls != null) {
            runCatching {
                val apiObj = gcls.getMethod("api").invoke(null)
                if (apiObj != null) {
                    val r = gcls.getMethod("isBedrockPlayer", UUID::class.java).invoke(apiObj, uuid)
                    if (r is Boolean && r) return true
                }
            }
        }

        return uuid.version() == 0
    }
}
