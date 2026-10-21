// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import kotlin.math.asin
import kotlin.math.atan2

/**
 * Pure quaternion helpers for the ARCore pose HUD (no Android deps, unit-tested).
 *
 * ARCore Pose rotation quaternion order is (x, y, z, w) — see README "References"
 * (ARCore Pose reference). Euler output uses the YXZ convention
 * R = Ry(yaw) * Rx(pitch) * Rz(roll), degrees, right-handed, ARCore world
 * frame (Y up, camera looks along its local -Z).
 */
object PoseMath {

    /** Row-major 3x3 rotation matrix for a unit quaternion (x, y, z, w). */
    fun matrix(qx: Float, qy: Float, qz: Float, qw: Float): FloatArray {
        val x = qx.toDouble(); val y = qy.toDouble(); val z = qz.toDouble(); val w = qw.toDouble()
        return floatArrayOf(
            (1 - 2 * (y * y + z * z)).toFloat(), (2 * (x * y - z * w)).toFloat(), (2 * (x * z + y * w)).toFloat(),
            (2 * (x * y + z * w)).toFloat(), (1 - 2 * (x * x + z * z)).toFloat(), (2 * (y * z - x * w)).toFloat(),
            (2 * (x * z - y * w)).toFloat(), (2 * (y * z + x * w)).toFloat(), (1 - 2 * (x * x + y * y)).toFloat()
        )
    }

    /** Yaw/pitch/roll in degrees, YXZ convention (see class doc). */
    fun eulerYXZDegrees(qx: Float, qy: Float, qz: Float, qw: Float): Triple<Double, Double, Double> {
        val m = matrix(qx, qy, qz, qw)
        val pitch = Math.toDegrees(asin((-m[5]).toDouble().coerceIn(-1.0, 1.0)))
        val yaw = Math.toDegrees(atan2(m[2].toDouble(), m[8].toDouble()))
        val roll = Math.toDegrees(atan2(m[3].toDouble(), m[4].toDouble()))
        return Triple(yaw, pitch, roll)
    }

    /** Camera forward in world coords: local -Z axis transformed by the pose. */
    fun forward(qx: Float, qy: Float, qz: Float, qw: Float): Triple<Float, Float, Float> {
        val m = matrix(qx, qy, qz, qw)
        return Triple(-m[2], -m[5], -m[8])
    }

    /** Camera up in world coords: local +Y axis transformed by the pose. */
    fun up(qx: Float, qy: Float, qz: Float, qw: Float): Triple<Float, Float, Float> {
        val m = matrix(qx, qy, qz, qw)
        return Triple(m[1], m[4], m[7])
    }

    /**
     * Horizontal / vertical / diagonal field of view in degrees for a pinhole
     * model: fov = 2*atan(size / (2*f)). Diagonal uses the exact corner-ray
     * angle atan(sqrt((w/2fx)^2 + (h/2fy)^2)), so fx != fy is handled.
     */
    fun fovDegrees(fx: Float, fy: Float, width: Int, height: Int): Triple<Double, Double, Double> {
        val h = Math.toDegrees(2 * kotlin.math.atan(width / (2.0 * fx)))
        val v = Math.toDegrees(2 * kotlin.math.atan(height / (2.0 * fy)))
        val cx = width / (2.0 * fx); val cy = height / (2.0 * fy)
        val d = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.sqrt(cx * cx + cy * cy)))
        return Triple(h, v, d)
    }
}
