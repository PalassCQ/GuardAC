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

package dev.guardac.util

import java.util.Properties

object BuildInfo {

    private const val UNKNOWN = "unknown"

    val version: String
    val commit: String
    val date: String

    init {
        val props = Properties()
        runCatching {
            BuildInfo::class.java.getResourceAsStream("/build-info.properties")?.use {
                props.load(it)
            }
        }
        version = props.getProperty("version")?.takeIf { it.isNotBlank() } ?: UNKNOWN
        commit  = props.getProperty("commit")?.takeIf { it.isNotBlank() } ?: UNKNOWN
        date    = props.getProperty("date")?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }

    val stamp: String
        get() = if (commit == UNKNOWN) UNKNOWN else "$commit ($date)"

    val full: String
        get() = "v$version build $stamp"
}
