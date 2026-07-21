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

package dev.guardac.sample

import java.util.Locale

data class AimSample(
    val deltaYaw: Float,
    val deltaPitch: Float,
    val accelYaw: Float,
    val accelPitch: Float,
    val jerkYaw: Float,
    val jerkPitch: Float,
    val gcdErrorYaw: Float,
    val gcdErrorPitch: Float,
) {
    fun channels(): FloatArray = floatArrayOf(
        deltaYaw, deltaPitch, accelYaw, accelPitch,
        jerkYaw, jerkPitch, gcdErrorYaw, gcdErrorPitch,
    )

    fun csvRow(cheating: Boolean): String = buildString(104) {
        append(if (cheating) '1' else '0')
        for (v in channels()) {
            append(',')
            append(if (v.isFinite()) String.format(Locale.ROOT, "%.6f", v) else "0.000000")
        }
    }

    companion object {
        const val CSV_HEADER =
            "is_cheating,delta_yaw,delta_pitch,accel_yaw,accel_pitch,jerk_yaw,jerk_pitch,gcd_error_yaw,gcd_error_pitch"
    }
}

data class AimSampleDto(
    val delta_yaw: Float,
    val delta_pitch: Float,
    val accel_yaw: Float,
    val accel_pitch: Float,
    val jerk_yaw: Float,
    val jerk_pitch: Float,
    val gcd_error_yaw: Float,
    val gcd_error_pitch: Float,
) {
    companion object {
        fun from(s: AimSample) = AimSampleDto(
            delta_yaw       = s.deltaYaw,
            delta_pitch     = s.deltaPitch,
            accel_yaw       = s.accelYaw,
            accel_pitch     = s.accelPitch,
            jerk_yaw        = s.jerkYaw,
            jerk_pitch      = s.jerkPitch,
            gcd_error_yaw   = s.gcdErrorYaw,
            gcd_error_pitch = s.gcdErrorPitch,
        )
    }
}

data class InferenceRequest(
    val ticks: List<AimSampleDto>,
    val count: Int,

    val priority: Boolean = false,
)
