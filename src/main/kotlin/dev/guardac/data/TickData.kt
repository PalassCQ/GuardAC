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

package dev.guardac.data

import kotlin.math.roundToLong

data class TickData(
    val deltaYaw: Float,
    val deltaPitch: Float,
    val accelYaw: Float,
    val accelPitch: Float,
    val jerkYaw: Float,
    val jerkPitch: Float,
    val gcdErrorYaw: Float,
    val gcdErrorPitch: Float,
    val pingBucket: Int = 0,
) {
    fun appendCsv(out: Appendable, status: String) {
        val label = if (status.equals("CHEAT", ignoreCase = true)) '1' else '0'
        out.append(label)
        out.append(','); appendFixed6(out, deltaYaw)
        out.append(','); appendFixed6(out, deltaPitch)
        out.append(','); appendFixed6(out, accelYaw)
        out.append(','); appendFixed6(out, accelPitch)
        out.append(','); appendFixed6(out, jerkYaw)
        out.append(','); appendFixed6(out, jerkPitch)
        out.append(','); appendFixed6(out, gcdErrorYaw)
        out.append(','); appendFixed6(out, gcdErrorPitch)
        out.append(','); out.append(pingBucket.toString())
    }

    companion object {
        fun csvHeader() =
            "is_cheating,delta_yaw,delta_pitch,accel_yaw,accel_pitch,jerk_yaw,jerk_pitch,gcd_error_yaw,gcd_error_pitch,ping_bucket"

        fun pingToBucket(pingMs: Int): Int = when {
            pingMs < 30  -> 0
            pingMs < 80  -> 1
            pingMs < 150 -> 2
            pingMs < 300 -> 3
            else         -> 4
        }

        private const val SCALE = 1_000_000L

        private fun appendFixed6(out: Appendable, value: Float) {
            if (!value.isFinite()) { out.append("0.000000"); return }
            var v = value.toDouble()
            val negative = v < 0.0
            if (negative) v = -v
            val scaled   = (v * SCALE).roundToLong()
            val intPart  = scaled / SCALE
            val fracPart = (scaled % SCALE).toInt()
            if (negative) out.append('-')
            out.append(intPart.toString())
            out.append('.')
            val frac = fracPart.toString()
            repeat(6 - frac.length) { out.append('0') }
            out.append(frac)
        }
    }
}

data class TickDataDto(
    val delta_yaw: Float,
    val delta_pitch: Float,
    val accel_yaw: Float,
    val accel_pitch: Float,
    val jerk_yaw: Float,
    val jerk_pitch: Float,
    val gcd_error_yaw: Float,
    val gcd_error_pitch: Float,
    val ping_bucket: Int,
) {
    companion object {
        fun from(t: TickData) = TickDataDto(
            delta_yaw       = t.deltaYaw,
            delta_pitch     = t.deltaPitch,
            accel_yaw       = t.accelYaw,
            accel_pitch     = t.accelPitch,
            jerk_yaw        = t.jerkYaw,
            jerk_pitch      = t.jerkPitch,
            gcd_error_yaw   = t.gcdErrorYaw,
            gcd_error_pitch = t.gcdErrorPitch,
            ping_bucket     = t.pingBucket,
        )
    }
}

data class InferenceRequest(
    val ticks: List<TickDataDto>,
    val count: Int,

    val priority: Boolean = false,
)
