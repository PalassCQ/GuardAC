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

/**
 * A name is "safe" when it can be substituted into a console command without
 * changing the command's argument structure. Java Edition names always match;
 * Bedrock/Geyser names can carry spaces, dots and other characters that would
 * shift command arguments - a punishment could land on the wrong player.
 */
object SafeName {
    private val SAFE = Regex("^\\w{1,16}$")

    fun isSafe(name: String): Boolean = SAFE.matches(name)
}
