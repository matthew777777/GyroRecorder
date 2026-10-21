package com.matthew.gyrorecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PoseMathTest {

    private fun quat(axis: Int, degrees: Double): FloatArray {
        val half = Math.toRadians(degrees) / 2
        val s = sin(half).toFloat(); val c = cos(half).toFloat()
        return when (axis) {
            0 -> floatArrayOf(s, 0f, 0f, c)
            1 -> floatArrayOf(0f, s, 0f, c)
            else -> floatArrayOf(0f, 0f, s, c)
        }
    }

    private fun euler(q: FloatArray) = PoseMath.eulerYXZDegrees(q[0], q[1], q[2], q[3])

    @Test fun identityIsZero() {
        val (yaw, pitch, roll) = euler(floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0.0, yaw, 1e-6); assertEquals(0.0, pitch, 1e-6); assertEquals(0.0, roll, 1e-6)
    }

    @Test fun pureYawAboutY() {
        val (yaw, pitch, roll) = euler(quat(1, 90.0))
        assertEquals(90.0, yaw, 1e-4); assertEquals(0.0, pitch, 1e-4); assertEquals(0.0, roll, 1e-4)
    }

    @Test fun purePitchAboutX() {
        val (yaw, pitch, roll) = euler(quat(0, 30.0))
        assertEquals(0.0, yaw, 1e-4); assertEquals(30.0, pitch, 1e-4); assertEquals(0.0, roll, 1e-4)
    }

    @Test fun pureRollAboutZ() {
        val (yaw, pitch, roll) = euler(quat(2, -45.0))
        assertEquals(0.0, yaw, 1e-4); assertEquals(0.0, pitch, 1e-4); assertEquals(-45.0, roll, 1e-4)
    }

    @Test fun fovNinetyWhenFocalEqualsHalfSize() {
        // fx = w/2 -> half-angle atan(1) = 45 deg -> full 90 deg, same vertically.
        val (h, v, _) = PoseMath.fovDegrees(320f, 240f, 640, 480)
        assertEquals(90.0, h, 1e-4); assertEquals(90.0, v, 1e-4)
    }

    @Test fun fovDiagonalIsWidest() {
        val (h, v, d) = PoseMath.fovDegrees(400f, 400f, 640, 480)
        assertTrue(d > h && h > v)
        // Known value: 2*atan(640/800) = 77.32 deg horizontal.
        assertEquals(77.32, h, 0.01)
    }

    @Test fun forwardIsLocalMinusZ() {
        // Identity pose: ARCore camera looks along world -Z, up is +Y.
        val f = PoseMath.forward(0f, 0f, 0f, 1f)
        assertEquals(0f, f.first, 1e-6f); assertEquals(0f, f.second, 1e-6f); assertEquals(-1f, f.third, 1e-6f)
        val u = PoseMath.up(0f, 0f, 0f, 1f)
        assertEquals(0f, u.first, 1e-6f); assertEquals(1f, u.second, 1e-6f); assertEquals(0f, u.third, 1e-6f)
        // Yaw +90 about Y turns the camera to look along world -X... via matrix Ry(90): -Z -> (-1,0,0) rotated?
        val q = quat(1, 90.0)
        val f2 = PoseMath.forward(q[0], q[1], q[2], q[3])
        assertEquals(-1f, f2.first, 1e-4f); assertEquals(0f, f2.third, 1e-4f)
    }
}
