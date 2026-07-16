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

package dev.guardac.player.state

class CombatState {

    var ticksSinceAttack: Int = Int.MAX_VALUE / 2
        private set

    @Volatile var totalAttacks: Int = 0
        private set

    @Volatile var lastAttackMs: Long = 0L
        private set

    fun recordAttack() {
        ticksSinceAttack = 0
        totalAttacks++
        lastAttackMs = System.currentTimeMillis()
    }

    fun tickElapsed() {
        if (ticksSinceAttack < Int.MAX_VALUE / 2) ticksSinceAttack++
    }

    fun isInCombatWindow(windowTicks: Int): Boolean =
        ticksSinceAttack <= windowTicks

    fun reset() {
        ticksSinceAttack = Int.MAX_VALUE / 2
        totalAttacks     = 0
        lastAttackMs     = 0L
    }
}
