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

package dev.guardac.util

import java.util.ArrayDeque

class RunningMode(val maxSize: Int) {

    private val addList: ArrayDeque<Double> = ArrayDeque(maxSize)
    private val popularityMap: HashMap<Double, Int> = HashMap()

    var modeValue: Double = 0.0
        private set
    var modeCount: Int = 0
        private set

    fun size(): Int = addList.size

    fun add(value: Double) {
        pop()
        for ((key, _) in popularityMap) {
            if (kotlin.math.abs(key - value) < THRESHOLD) {
                popularityMap[key] = popularityMap[key]!! + 1
                addList.addLast(key)
                return
            }
        }
        popularityMap[value] = 1
        addList.addLast(value)
    }

    private fun pop() {
        if (addList.size >= maxSize) {
            val removed = addList.pollFirst() ?: return
            val count = popularityMap[removed] ?: return
            if (count <= 1) popularityMap.remove(removed)
            else popularityMap[removed] = count - 1
        }
    }

    fun updateMode() {
        var max = 0
        var mostPopular = 0.0
        for ((key, count) in popularityMap) {
            if (count > max) {
                max = count
                mostPopular = key
            }
        }
        modeValue = mostPopular
        modeCount = max
    }

    fun clear() {
        addList.clear()
        popularityMap.clear()
        modeValue = 0.0
        modeCount = 0
    }

    private companion object {
        const val THRESHOLD = 1e-3
    }
}
