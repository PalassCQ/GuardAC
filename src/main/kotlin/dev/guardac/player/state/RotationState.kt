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
 *   - Grim (© 2025 GrimAnticheat, https://github.com/GrimAnticheat/Grim)
 * All derived code is licensed under GPL-3.0.
 */

package dev.guardac.player.state

import dev.guardac.util.RunningMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

class RotationState {

    var yaw: Float = 0f
        private set
    var pitch: Float = 0f
        private set

    private var prevYaw: Float = 0f
    private var prevPitch: Float = 0f

    private var prevDeltaYaw: Float = 0f
    private var prevDeltaPitch: Float = 0f

    private var prevAccelYaw: Float = 0f
    private var prevAccelPitch: Float = 0f

    private val xRotMode = RunningMode(TOTAL_SAMPLES)
    private val yRotMode = RunningMode(TOTAL_SAMPLES)

    private var lastAbsDeltaYaw: Double = 0.0
    private var lastAbsDeltaPitch: Double = 0.0

    var modeX: Double = 0.0
        private set
    var modeY: Double = 0.0
        private set

    val deltaYaw: Float   get() = angleDiff(yaw, prevYaw)
    val deltaPitch: Float get() = pitch - prevPitch

    val accelYaw: Float   get() = deltaYaw - prevDeltaYaw
    val accelPitch: Float get() = deltaPitch - prevDeltaPitch

    val jerkYaw: Float   get() = accelYaw - prevAccelYaw
    val jerkPitch: Float get() = accelPitch - prevAccelPitch

    val gcdErrorYaw: Float
        get() = if (modeX > 0) {
            val err = abs(deltaYaw.toDouble() % modeX)
            minOf(err, modeX - err).toFloat()
        } else 0f

    val gcdErrorPitch: Float
        get() = if (modeY > 0) {
            val err = abs(deltaPitch.toDouble() % modeY)
            minOf(err, modeY - err).toFloat()
        } else 0f

    val sensitivityX: Double
        get() = if (modeX > 0) convertToSensitivity(modeX) else 0.5

    val sensitivityY: Double
        get() = if (modeY > 0) convertToSensitivity(modeY) else 0.5

    fun update(newYaw: Float, newPitch: Float) {
        prevAccelYaw   = accelYaw
        prevAccelPitch = accelPitch
        prevDeltaYaw   = deltaYaw
        prevDeltaPitch = deltaPitch
        prevYaw        = yaw
        prevPitch      = pitch
        yaw            = newYaw
        pitch          = newPitch

        updateGcd()
    }

    fun clearState() {
        prevYaw        = yaw
        prevPitch      = pitch
        prevDeltaYaw   = 0f
        prevDeltaPitch = 0f
        prevAccelYaw   = 0f
        prevAccelPitch = 0f
        lastAbsDeltaYaw   = 0.0
        lastAbsDeltaPitch = 0.0
    }

    private fun updateGcd() {
        val dYaw   = abs(deltaYaw.toDouble())
        val dPitch = abs(deltaPitch.toDouble())

        if (lastAbsDeltaYaw > 0.0) {
            val gx = gcd(dYaw, lastAbsDeltaYaw)
            if (dYaw > 0 && dYaw < MAX_DELTA_FOR_GCD && gx > MINIMUM_DIVISOR) {
                xRotMode.add(gx)
                if (xRotMode.size() > SIGNIFICANT_SAMPLES) {
                    xRotMode.updateMode()
                    if (xRotMode.modeCount > SIGNIFICANT_SAMPLES) modeX = xRotMode.modeValue
                }
            }
        }
        if (dYaw > 0) lastAbsDeltaYaw = dYaw

        if (lastAbsDeltaPitch > 0.0) {
            val gy = gcd(dPitch, lastAbsDeltaPitch)
            if (dPitch > 0 && dPitch < MAX_DELTA_FOR_GCD && gy > MINIMUM_DIVISOR) {
                yRotMode.add(gy)
                if (yRotMode.size() > SIGNIFICANT_SAMPLES) {
                    yRotMode.updateMode()
                    if (yRotMode.modeCount > SIGNIFICANT_SAMPLES) modeY = yRotMode.modeValue
                }
            }
        }
        if (dPitch > 0) lastAbsDeltaPitch = dPitch
    }

    private fun angleDiff(a: Float, b: Float): Float {
        var diff = a - b
        while (diff > 180f)  diff -= 360f
        while (diff < -180f) diff += 360f
        return diff
    }

    private companion object {
        val MINIMUM_DIVISOR: Double = 0.2f.pow(3) * 8 * 0.15 - 1e-3
        const val MAX_DELTA_FOR_GCD     = 5.0
        const val SIGNIFICANT_SAMPLES   = 15
        const val TOTAL_SAMPLES         = 80

        fun gcd(a: Double, b: Double): Double {
            if (a == 0.0) return 0.0
            var x = if (a >= b) a else b
            var y = if (a < b) a else b
            while (y > MINIMUM_DIVISOR) {
                val temp = x - floor(x / y) * y
                x = y
                y = temp
            }
            return x
        }

        fun convertToSensitivity(value: Double): Double {
            val normalized = value / 0.15 / 8.0
            val cubic = Math.cbrt(normalized)
            return (cubic - 0.2) / 0.6
        }
    }
}
