// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Device-frame gyroscope rates -> stored-image camera frame
 * (X right along columns, Y down along rows, Z along sensor normal into scene).
 *
 * Same convention as RawLens `GyroCameraFrameMapper` (MAPPER_VERSION 1): the
 * downstream alignment seed assumes content shift ~ (+fx * ry, -fy * rx) and
 * +rz = clockwise on screen. Anchor mount (back camera, SENSOR_ORIENTATION=90)
 * is a proper rotation (det +1); other quarter-turn mounts rotate rigidly with
 * the sensor; front cameras additionally negate the x rate (provisional).
 * Raw device-frame rates are always recorded alongside, so any capture can be
 * re-mapped offline if this convention is ever revised.
 * Gyro->rotation->homography background: GyroFlow+ (arXiv:2301.10018), see README "References".
 */
object GyroCameraFrameMapper {
    const val MAPPER_VERSION = 1

    fun map(
        wx: Float,
        wy: Float,
        wz: Float,
        sensorOrientationDeg: Int,
        frontFacing: Boolean
    ): Triple<Float, Float, Float> {
        val m = matrix(sensorOrientationDeg, frontFacing)
        return Triple(
            (m[0] * wx + m[1] * wy + m[2] * wz).toFloat(),
            (m[3] * wx + m[4] * wy + m[5] * wz).toFloat(),
            (m[6] * wx + m[7] * wy + m[8] * wz).toFloat()
        )
    }

    /** Row-major 3x3, Double build. Throws on non-quarter-turn orientation. */
    fun matrix(sensorOrientationDeg: Int, frontFacing: Boolean): DoubleArray {
        val theta = ((sensorOrientationDeg % 360) + 360) % 360
        require(theta % 90 == 0) { "sensor orientation must be a quarter turn, got $sensorOrientationDeg" }
        val anchor = doubleArrayOf(
            0.0, 1.0, 0.0,
            1.0, 0.0, 0.0,
            0.0, 0.0, -1.0
        )
        val rel = Math.toRadians((90 - theta).toDouble())
        fun snap(v: Double): Double = when {
            abs(v) < 1e-12 -> 0.0
            abs(v - 1.0) < 1e-12 -> 1.0
            abs(v + 1.0) < 1e-12 -> -1.0
            else -> v
        }
        val c = snap(cos(rel))
        val s = snap(sin(rel))
        // Q = Rz(rel) composed on the left of the anchor rows.
        val q = doubleArrayOf(
            c, -s, 0.0,
            s, c, 0.0,
            0.0, 0.0, 1.0
        )
        val m = DoubleArray(9)
        for (r in 0 until 3) {
            for (col in 0 until 3) {
                m[r * 3 + col] =
                    q[r * 3] * anchor[col] + q[r * 3 + 1] * anchor[3 + col] + q[r * 3 + 2] * anchor[6 + col]
            }
        }
        if (frontFacing) {
            // Mirrored mount: negate the stored-x rate (provisional, as in RawLens).
            m[0] = -m[0]; m[1] = -m[1]; m[2] = -m[2]
        }
        return m
    }
}
