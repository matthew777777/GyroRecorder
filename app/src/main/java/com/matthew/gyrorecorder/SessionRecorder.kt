// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * One Pantheon session: ARCore poses + full-rate gyro/accel/rotvec.
 * Timestamps are boot-time ns (SensorEvent/elapsedRealtime documented as such;
 * ARCore Frame capture ns is audited against host_elapsed_ns in frames.csv —
 * see README "Session files").
 *
 * Files in the session dir:
 *  session.json    device/sensor/scenario provenance
 *  frames.csv      per ARCore frame: pose, intrinsics, tracking, host clock audit
 *  gyro.csv        device-frame rad/s + camera-frame (GyroCameraFrameMapper)
 *  accel.csv       device-frame m/s^2 incl. gravity
 *  rotvec.csv      rotation-vector quaternion (x,y,z,w)
 *  events.csv      MARK taps (e.g. shutter moments in "shooting")
 *  frame_motion.csv written on stop: gyro integrated between frames ->
 *                  delta rotation + predicted subpixel shift at (cx,cy):
 *                  shift_x = fx*dry, shift_y = -fy*drx (RawLens seed convention).
 *                  This is the gyro-alignment product: at high zoom the warp is
 *                  ~pure rotation, so this shift is the alignment itself.
 * Sources: Android motion-sensors guide + SensorEvent/SensorManager refs (README "References").
 */
class SessionRecorder(
    private val context: Context,
    private val sensorOrientationDeg: Int,
    private val frontFacing: Boolean,
) {
    data class Stats(
        val recording: Boolean = false,
        val elapsedS: Double = 0.0,
        val frames: Long = 0,
        val gyro: Long = 0,
        val accel: Long = 0,
        val gyroHz: Double = 0.0,
        val frameHz: Double = 0.0,
        val lastGyroMag: Float = 0f,
        val tracking: String = "-",
        val dirName: String = "",
    )

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotVecSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Live ARCore pose for the on-screen HUD; updated for every frame, recording or not. */
    data class Hud(
        val seen: Boolean = false,
        val tracking: String = "-",
        val failure: String = "",
        val tx: Float = 0f, val ty: Float = 0f, val tz: Float = 0f,
        val qx: Float = 0f, val qy: Float = 0f, val qz: Float = 0f, val qw: Float = 1f,
        val yawDeg: Double = 0.0, val pitchDeg: Double = 0.0, val rollDeg: Double = 0.0,
        val fwdX: Float = 0f, val fwdY: Float = 0f, val fwdZ: Float = 0f,
        val fx: Float = 0f, val fy: Float = 0f,
        val imgW: Int = 0, val imgH: Int = 0,
        val fovH: Double = 0.0, val fovV: Double = 0.0,
        val lagMs: Double = 0.0,
    )

    @Volatile var hud = Hud(); private set

    /** Simulated digital zoom set by pinch before/during recording; stored in session.json. */
    @Volatile var zoomFactor: Float = 1f

    @Volatile var stats = Stats(); private set
    @Volatile var sessionDir: File? = null; private set
    private var thread: HandlerThread? = null
    @Volatile private var running = false
    private var t0Ns = 0L
    private var startWallMs = 0L
    private val frameCount = AtomicLong(0)
    private val gyroCount = AtomicLong(0)
    private val accelCount = AtomicLong(0)
    // Written on sensor/GL threads, read on the UI thread.
    @Volatile private var lastGyroMag = 0f
    @Volatile private var lastTracking = "-"
    @Volatile private var gyroHz = 0.0
    @Volatile private var frameHz = 0.0
    private var gyroRateWindowStart = 0L; private var gyroRateWindowCount = 0L
    private var frameRateWindowStart = 0L; private var frameRateWindowCount = 0L

    private var gyroW: BufferedWriter? = null
    private var accelW: BufferedWriter? = null
    private var rotVecW: BufferedWriter? = null
    private var framesW: BufferedWriter? = null
    private var eventsW: BufferedWriter? = null
    private val writeLock = Any()

    // Kept in RAM for frame_motion integration (400 Hz * 10 min = 240k rows, fine).
    private data class GS(val ts: Long, val cx: Float, val cy: Float, val cz: Float)
    private data class FR(val idx: Long, val ts: Long, val fx: Float, val fy: Float)
    private val gyroSamples = ArrayList<GS>(8192)
    private val frameList = ArrayList<FR>(4096)

    /** Downsampled |omega| ring for the HUD sparkline (rad/s), ~last 30 s at 10 Hz. */
    val spark = ArrayDeque<Float>(300)
    private var sparkLastNs = 0L

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!running) return
            val v = e.values
            when (e.sensor.type) {
                Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> {
                    val cam = try {
                        GyroCameraFrameMapper.map(v[0], v[1], v[2], sensorOrientationDeg, frontFacing)
                    } catch (_: Exception) { Triple(v[0], v[1], v[2]) }
                    synchronized(writeLock) {
                        gyroW?.write("${e.timestamp},${rel(e.timestamp)},${v[0]},${v[1]},${v[2]},${cam.first},${cam.second},${cam.third}\n")
                        synchronized(gyroSamples) { gyroSamples.add(GS(e.timestamp, cam.first, cam.second, cam.third)) }
                    }
                    gyroCount.incrementAndGet()
                    lastGyroMag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                    if (e.timestamp - sparkLastNs > 100_000_000L) {
                        sparkLastNs = e.timestamp
                        synchronized(spark) { if (spark.size >= 300) spark.removeFirst(); spark.addLast(lastGyroMag) }
                    }
                    updateGyroRate(e.timestamp)
                }
                Sensor.TYPE_ACCELEROMETER -> synchronized(writeLock) {
                    accelW?.write("${e.timestamp},${rel(e.timestamp)},${v[0]},${v[1]},${v[2]}\n"); accelCount.incrementAndGet()
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val w = if (v.size > 3) v[3] else sqrt(maxOf(0f, 1f - v[0] * v[0] - v[1] * v[1] - v[2] * v[2]))
                    synchronized(writeLock) { rotVecW?.write("${e.timestamp},${rel(e.timestamp)},${v[0]},${v[1]},${v[2]},$w\n") }
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }

    private fun rel(ts: Long) = (ts - t0Ns) / 1e9

    private fun updateGyroRate(ts: Long) {
        if (gyroRateWindowStart == 0L) gyroRateWindowStart = ts
        if (++gyroRateWindowCount >= 100) {
            gyroHz = gyroRateWindowCount * 1e9 / (ts - gyroRateWindowStart).coerceAtLeast(1)
            gyroRateWindowStart = ts; gyroRateWindowCount = 0
        }
    }

    private var metaJson: JSONObject? = null
    private var metaFile: File? = null
    private var intrinsicsWritten = false

    fun start(scenario: String, notes: String, cameraInfo: JSONObject? = null): File? {
        if (running) return sessionDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val slug = scenario.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val root = File(context.getExternalFilesDir(null), "Pantheon")
        // Unique per run: a same-second restart must not truncate the prior session.
        var dir = File(root, "${stamp}_$slug"); var suffix = 2
        while (dir.exists()) { dir = File(root, "${stamp}_${slug}_$suffix"); suffix++ }
        if (!dir.mkdirs()) return null
        sessionDir = dir
        t0Ns = SystemClock.elapsedRealtimeNanos(); startWallMs = System.currentTimeMillis()
        frameCount.set(0); gyroCount.set(0); accelCount.set(0); lastFrameTs = -1L
        synchronized(gyroSamples) { gyroSamples.clear() }; synchronized(frameList) { frameList.clear() }
        synchronized(spark) { spark.clear() }

        fun writer(name: String, header: String): BufferedWriter {
            val w = BufferedWriter(FileWriter(File(dir, name)), 64 * 1024); w.write(header + "\n"); return w
        }
        try {
            gyroW = writer("gyro.csv", "timestamp_ns,t_rel_s,wx_rad_s,wy_rad_s,wz_rad_s,cam_wx_rad_s,cam_wy_rad_s,cam_wz_rad_s")
            accelW = writer("accel.csv", "timestamp_ns,t_rel_s,ax_m_s2,ay_m_s2,az_m_s2")
            rotVecW = writer("rotvec.csv", "timestamp_ns,t_rel_s,qx,qy,qz,qw")
            framesW = writer("frames.csv", "frame_index,timestamp_ns,t_rel_s,host_elapsed_ns,tracking_state,tx,ty,tz,qx,qy,qz,qw,fx,fy,cx,cy,img_w,img_h")
            eventsW = writer("events.csv", "timestamp_ns,t_rel_s,label")
        } catch (e: Exception) {
            Log.e(TAG, "could not create session files in $dir", e)
            return null
        }

        val t = HandlerThread("PantheonSensors").apply { start() }; thread = t
        val handler = Handler(t.looper); running = true
        // 1 ms request = fastest the HAL gives (BMI260: 400 Hz). FASTEST can throw w/o the permission.
        var ok = false
        try { ok = register(handler, 1000) } catch (_: SecurityException) { Log.w(TAG, "fast rate denied") }
        if (!ok) { try { ok = register(handler, SensorManager.SENSOR_DELAY_GAME) } catch (_: Exception) {} }
        if (!ok) Log.w(TAG, "no IMU registered — ARCore-only session")

        val meta = JSONObject().apply {
            put("app", "GyroRecorder " + BuildConfig.VERSION_NAME); put("scenario", scenario); put("notes", notes)
            put("startWallTimeMs", startWallMs); put("t0ElapsedRealtimeNs", t0Ns)
            put("timestampDomain", "boot-time ns: SensorEvent/elapsedRealtime documented; ARCore Frame capture ns (audit via frames.host_elapsed_ns)")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("androidSdk", Build.VERSION.SDK_INT)
            put("sensorOrientationDeg", sensorOrientationDeg); put("frontFacing", frontFacing)
            put("gyroMapperVersion", GyroCameraFrameMapper.MAPPER_VERSION)
            put("gyroSensor", gyroSensor?.name ?: "none"); put("accelSensor", accelSensor?.name ?: "none")
            put("gyroMaxHz", (gyroSensor?.minDelay?.let { if (it > 0) 1e6 / it else 0.0 } ?: 0.0))
            put("zoomFactor", zoomFactor.toDouble())
            put("zoomType", "simulated_digital_center_crop (preview only); frames.csv intrinsics are native — effective fx = fx * zoomFactor, fy likewise, principal point unchanged")
            put("hint", "shift_x_px = fx*dry_rad, shift_y_px = -fy*drx_rad per frame_motion.csv")
            cameraInfo?.let { put("camera", it) }
        }
        metaJson = meta
        metaFile = File(dir, "session.json")
        intrinsicsWritten = false
        metaFile?.writeText(meta.toString(2))
        publishStats(); return dir
    }

    private fun register(handler: Handler, periodUs: Int): Boolean {
        var ok = false
        gyroSensor?.let { ok = sensorManager?.registerListener(listener, it, periodUs, handler) == true || ok }
        accelSensor?.let { ok = sensorManager?.registerListener(listener, it, periodUs, handler) == true || ok }
        rotVecSensor?.let { sensorManager?.registerListener(listener, it, periodUs, handler) }
        return ok
    }

    @Volatile private var lastFrameTs = -1L

    /** Called on the GL thread for every ARCore frame; always refreshes the HUD. */
    fun onArFrame(frame: Frame) {
        updateHud(frame)
        if (!running) return
        // session.update() can hand back the same image twice; log each exposure once.
        if (frame.timestamp == lastFrameTs) return
        lastFrameTs = frame.timestamp
        val cam = frame.camera
        val pose = cam.pose
        val q = FloatArray(4); pose.getRotationQuaternion(q, 0)
        val intr = cam.imageIntrinsics
        val f = intr.focalLength; val p = intr.principalPoint; val d = intr.imageDimensions
        if (!intrinsicsWritten) {
            intrinsicsWritten = true
            try {
                val tex = cam.textureIntrinsics
                val tf = tex.focalLength; val tp = tex.principalPoint; val td = tex.imageDimensions
                metaJson?.put("cameraIntrinsicsFirstFrame", JSONObject().apply {
                    put("image", CameraInfo.intrinsicsBlock(f[0], f[1], p[0], p[1], d[0], d[1]))
                    put("texture", CameraInfo.intrinsicsBlock(tf[0], tf[1], tp[0], tp[1], td[0], td[1]))
                })
                metaFile?.writeText(metaJson.toString())
            } catch (e: Exception) {
                Log.w(TAG, "intrinsics metadata failed: ${e.message}")
            }
        }
        val idx = frameCount.incrementAndGet() - 1
        val host = SystemClock.elapsedRealtimeNanos()
        lastTracking = cam.trackingState.name
        synchronized(writeLock) {
            framesW?.write("$idx,${frame.timestamp},${rel(frame.timestamp)},$host,${cam.trackingState}," +
                "${pose.tx()},${pose.ty()},${pose.tz()},${q[0]},${q[1]},${q[2]},${q[3]}," +
                "${f[0]},${f[1]},${p[0]},${p[1]},${d[0]},${d[1]}\n")
            synchronized(frameList) { frameList.add(FR(idx, frame.timestamp, f[0], f[1])) }
            if (idx % 30L == 0L) { framesW?.flush(); gyroW?.flush(); accelW?.flush(); rotVecW?.flush() }
        }
        if (frameRateWindowStart == 0L) frameRateWindowStart = frame.timestamp
        if (++frameRateWindowCount >= 30) {
            frameHz = frameRateWindowCount * 1e9 / (frame.timestamp - frameRateWindowStart).coerceAtLeast(1)
            frameRateWindowStart = frame.timestamp; frameRateWindowCount = 0
        }
        if (cam.trackingState != TrackingState.TRACKING) lastTracking = "${cam.trackingState}/${cam.trackingFailureReason}"
        publishStats()
    }

    private fun updateHud(frame: Frame) {
        val cam = frame.camera
        val pose = cam.pose
        val q = FloatArray(4); pose.getRotationQuaternion(q, 0)
        val (yaw, pitch, roll) = PoseMath.eulerYXZDegrees(q[0], q[1], q[2], q[3])
        val fwd = PoseMath.forward(q[0], q[1], q[2], q[3])
        val intr = cam.imageIntrinsics
        val host = SystemClock.elapsedRealtimeNanos()
        val dims = intr.imageDimensions
        val (fovH, fovV, _) = PoseMath.fovDegrees(intr.focalLength[0], intr.focalLength[1], dims[0], dims[1])
        hud = Hud(
            seen = true,
            tracking = cam.trackingState.name,
            failure = if (cam.trackingState == TrackingState.TRACKING) "" else cam.trackingFailureReason.name,
            tx = pose.tx(), ty = pose.ty(), tz = pose.tz(),
            qx = q[0], qy = q[1], qz = q[2], qw = q[3],
            yawDeg = yaw, pitchDeg = pitch, rollDeg = roll,
            fwdX = fwd.first, fwdY = fwd.second, fwdZ = fwd.third,
            fx = intr.focalLength[0], fy = intr.focalLength[1],
            imgW = dims[0], imgH = dims[1], fovH = fovH, fovV = fovV,
            lagMs = (host - frame.timestamp) / 1e6,
        )
    }

    fun mark(label: String) {
        if (!running) return
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(writeLock) { eventsW?.write("$now,${rel(now)},$label\n"); eventsW?.flush() }
    }

    private fun publishStats() {
        stats = Stats(true, (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1e9,
            frameCount.get(), gyroCount.get(), accelCount.get(), gyroHz, frameHz,
            lastGyroMag, lastTracking, sessionDir?.name ?: "")
    }

    fun tickStats() { if (running) publishStats() }

    fun stop(): File? {
        if (!running) return sessionDir
        running = false
        try { sensorManager?.unregisterListener(listener) } catch (_: Exception) {}
        thread?.quitSafely(); thread?.join(1500); thread = null
        synchronized(writeLock) {
            listOf(gyroW, accelW, rotVecW, framesW, eventsW).forEach { try { it?.flush(); it?.close() } catch (_: Exception) {} }
            gyroW = null; accelW = null; rotVecW = null; framesW = null; eventsW = null
        }
        sessionDir?.let { writeFrameMotion(it) }
        stats = stats.copy(recording = false)
        return sessionDir
    }

    /** Trapezoidal gyro integration between consecutive frame timestamps. */
    private fun writeFrameMotion(dir: File) {
        val gs: List<GS>; val fs: List<FR>
        synchronized(gyroSamples) { gs = ArrayList(gyroSamples) }
        synchronized(frameList) { fs = ArrayList(frameList) }
        val out = BufferedWriter(FileWriter(File(dir, "frame_motion.csv")))
        out.write("frame_index,timestamp_ns,dt_s,drx_rad,dry_rad,drz_rad,angle_rad,shift_x_px,shift_y_px,gyro_samples\n")
        var gi = 0
        for (i in 1 until fs.size) {
            val a = fs[i - 1]; val b = fs[i]
            // Seed with the last sample at/before the window so the first
            // trapezoid covers from ~a.ts instead of dropping the head.
            var prev: GS? = null
            while (gi < gs.size && gs[gi].ts < a.ts) { prev = gs[gi]; gi++ }
            var drx = 0.0; var dry = 0.0; var drz = 0.0; var n = 0
            var j = gi
            while (j < gs.size && gs[j].ts <= b.ts) {
                val s = gs[j]
                prev?.let { p ->
                    val dt = (s.ts - p.ts) / 1e9
                    drx += (p.cx + s.cx) / 2.0 * dt; dry += (p.cy + s.cy) / 2.0 * dt; drz += (p.cz + s.cz) / 2.0 * dt
                }
                prev = s; n++; j++
            }
            val angle = sqrt(drx * drx + dry * dry + drz * drz)
            out.write("${b.idx},${b.ts},${(b.ts - a.ts) / 1e9},$drx,$dry,$drz,$angle,${b.fx * dry},${-b.fy * drx},$n\n")
        }
        out.close()
    }

    companion object { private const val TAG = "GyroRecorder" }
}
