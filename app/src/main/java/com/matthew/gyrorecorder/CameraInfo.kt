// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.Session
import org.json.JSONArray
import org.json.JSONObject

/**
 * Camera provenance for scene replication, stored under "camera" in session.json.
 *
 * Two layers, both from public APIs (see README "References"):
 *  - ARCore `Session.getCameraConfig()`: camera id, facing, CPU/GPU stream
 *    sizes, fps range, depth/stereo usage.
 *  - Camera2 `CameraCharacteristics` for that camera id: physical focal
 *    length(s) in mm, sensor physical size, pixel array, orientation.
 * Per-frame intrinsics + FOV are added by SessionRecorder on the first frame.
 */
object CameraInfo {

    fun build(context: Context, session: Session?): JSONObject {
        val root = JSONObject()
        var cameraId: String? = null
        try {
            val cfg = session?.cameraConfig
            if (cfg != null) {
                cameraId = cfg.cameraId
                root.put("arcore", JSONObject().apply {
                    put("cameraId", cfg.cameraId ?: "unknown")
                    put("facing", cfg.facingDirection.name)
                    put("imageSizeCpu", "${cfg.imageSize.width}x${cfg.imageSize.height}")
                    put("textureSizeGpu", "${cfg.textureSize.width}x${cfg.textureSize.height}")
                    put("fpsRange", JSONArray().put(cfg.fpsRange.lower).put(cfg.fpsRange.upper))
                    put("depthSensorUsage", cfg.depthSensorUsage.name)
                    put("stereoCameraUsage", cfg.stereoCameraUsage.name)
                })
            }
        } catch (e: Exception) {
            root.put("arcoreError", e.message ?: e.javaClass.simpleName)
        }

        try {
            val cm = context.getSystemService(CameraManager::class.java)
            val id = cameraId ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            if (id != null) {
                val c = cm.getCameraCharacteristics(id)
                root.put("camera2", JSONObject().apply {
                    put("cameraId", id)
                    put("lensFacing", when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                        else -> "UNKNOWN"
                    })
                    put("sensorOrientationDeg", c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1)
                    c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { fl ->
                        put("focalLengthsMm", JSONArray().apply { fl.forEach { put(it.toDouble()) } })
                    }
                    c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { s ->
                        put("sensorPhysicalSizeMm", JSONArray().put(s.width.toDouble()).put(s.height.toDouble()))
                    }
                    c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { s ->
                        put("pixelArraySize", JSONArray().put(s.width).put(s.height))
                    }
                    c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.let {
                        put("hardwareLevel", it)
                    }
                })
            }
        } catch (e: Exception) {
            root.put("camera2Error", e.message ?: e.javaClass.simpleName)
        }
        return root
    }

    /** Intrinsics + computed FOV block for one intrinsics set (image or texture). */
    fun intrinsicsBlock(fx: Float, fy: Float, cx: Float, cy: Float, w: Int, h: Int): JSONObject {
        val (hfov, vfov, dfov) = PoseMath.fovDegrees(fx, fy, w, h)
        return JSONObject().apply {
            put("fx", fx.toDouble()); put("fy", fy.toDouble())
            put("cx", cx.toDouble()); put("cy", cy.toDouble())
            put("width", w); put("height", h)
            put("fovHorizontalDeg", hfov); put("fovVerticalDeg", vfov); put("fovDiagonalDeg", dfov)
        }
    }
}
