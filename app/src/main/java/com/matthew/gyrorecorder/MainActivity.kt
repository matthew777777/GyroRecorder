// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** GyroRecorder capture screen: ARCore preview + scenario-labelled IMU/pose recording.
 * UI: Material 3 (cards/chips/buttons), same palette/theme as PerfOverlay (README "References").
 */
class MainActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var chipGroup: ChipGroup
    private lateinit var hintView: TextView
    private lateinit var bannerView: TextView
    private lateinit var statusDot: TextView
    private lateinit var statusTitle: TextView
    private lateinit var timerView: TextView
    private lateinit var statsView: TextView
    private lateinit var sparkView: SparkView
    private lateinit var notesField: TextInputEditText
    private lateinit var recButton: MaterialButton
    private lateinit var markButton: MaterialButton
    private lateinit var shareButton: MaterialButton

    private var session: Session? = null
    private var recorder: SessionRecorder? = null
    private var scenario = SCENARIOS[0].first
    private var installRequested = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refreshStats(); uiHandler.postDelayed(this, 250) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val (orient, front) = backCameraOrientation()
        recorder = SessionRecorder(this, orient, front)
        renderer = ArRenderer { frame -> recorder?.onArFrame(frame) }

        glView = findViewById(R.id.glView)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        chipGroup = findViewById(R.id.chipGroup)
        hintView = findViewById(R.id.hintView)
        bannerView = findViewById(R.id.bannerView)
        statusDot = findViewById(R.id.statusDot)
        statusTitle = findViewById(R.id.statusTitle)
        timerView = findViewById(R.id.timerView)
        statsView = findViewById(R.id.statsView)
        sparkView = findViewById(R.id.sparkView)
        sparkView.recorder = recorder
        notesField = findViewById(R.id.notesField)
        recButton = findViewById(R.id.recButton)
        markButton = findViewById(R.id.markButton)
        shareButton = findViewById(R.id.shareButton)

        SCENARIOS.forEachIndexed { i, (name, _) ->
            val chip = Chip(this).apply {
                text = name; isCheckable = true; isChecked = i == 0
                setOnClickListener { selectScenario(name) }
            }
            chipGroup.addView(chip)
        }
        recButton.setOnClickListener { toggleRecord() }
        markButton.setOnClickListener { recorder?.mark("mark"); toast("Marked") }
        shareButton.setOnClickListener { shareLast() }

        selectScenario(scenario)
        refreshStats()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }
    }

    private fun backCameraOrientation(): Pair<Int, Boolean> {
        try {
            val cm = getSystemService(CameraManager::class.java)
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    return (c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) to false
                }
            }
        } catch (_: Exception) {}
        return 90 to false
    }

    private fun selectScenario(name: String) {
        scenario = name
        hintView.text = SCENARIOS.firstOrNull { it.first == name }?.second ?: ""
    }

    private fun toggleRecord() {
        val r = recorder ?: return
        if (r.stats.recording) {
            val dir = r.stop()
            markButton.isEnabled = false
            shareButton.isEnabled = dir != null
            recButton.text = getString(R.string.record)
            toast("Saved ${dir?.name} — ${r.stats.gyro} gyro, ${r.stats.frames} frames")
        } else {
            val dir = r.start(scenario, notesField.text?.toString() ?: "")
            if (dir == null) { toast("Could not create session dir"); return }
            markButton.isEnabled = true
            recButton.text = getString(R.string.stop)
            toast("Recording $scenario")
        }
        refreshStats()
    }

    private fun refreshStats() {
        val r = recorder ?: return
        r.tickStats()
        val s = r.stats
        if (s.recording) {
            statusDot.setTextColor(getColor(R.color.bad))
            statusTitle.text = s.dirName
            val total = s.elapsedS.toInt()
            timerView.text = getString(R.string.timer_format, total / 60, total % 60)
            statsView.text = getString(R.string.stats_recording, s.frames, s.frameHz, s.gyro, s.gyroHz, s.accel, s.lastGyroMag, s.tracking)
        } else {
            statusDot.setTextColor(getColor(R.color.dim))
            statusTitle.text = getString(R.string.idle_status)
            timerView.text = getString(R.string.timer_zero)
            statsView.text = getString(R.string.stats_idle, packageName)
        }
        sparkView.invalidate()
    }

    private fun shareLast() {
        val dir = recorder?.sessionDir ?: return
        try {
            val zipDir = File(cacheDir, "share").apply { mkdirs() }
            val zip = File(zipDir, dir.name + ".zip")
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zos.putNextEntry(ZipEntry(f.relativeTo(dir).path))
                    FileInputStream(f).use { it.copyTo(zos) }; zos.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share GyroRecorder session"))
        } catch (e: Exception) { toast("Share failed: ${e.message}") }
    }

    // ---- ARCore lifecycle ----

    override fun onResume() {
        super.onResume()
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                else -> {}
            }
        } catch (e: UnavailableException) { banner("ARCore unavailable: ${e.message} — IMU-only mode"); return }
          catch (e: Exception) { banner("ARCore check failed: ${e.message} — IMU-only mode"); return }

        if (session == null) {
            try {
                val s = Session(this)
                s.configure(Config(s).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    depthMode = Config.DepthMode.DISABLED
                })
                session = s; renderer.session = s
            } catch (e: Exception) { banner("ARCore session failed: ${e.message} — IMU-only mode"); return }
        }
        try { session?.resume() } catch (e: Exception) { banner("ARCore resume failed: ${e.message}") }
        renderer.rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
        }
        glView.onResume()
    }

    override fun onPause() {
        uiHandler.removeCallbacks(ticker)
        if (recorder?.stats?.recording == true) toggleRecord()
        glView.onPause()
        try { session?.pause() } catch (_: Exception) {}
        super.onPause()
    }

    override fun onDestroy() {
        try { session?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun banner(msg: String) { bannerView.text = msg; bannerView.visibility = View.VISIBLE }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        // On grant the system resumes the activity itself, which re-runs the ARCore setup.
        if (rc == 42 && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            banner("Camera permission denied — preview and ARCore need it.")
        }
    }

    companion object {
        val SCENARIOS = listOf(
            "Walking" to "Walk ~10 m at natural pace, phone up, look ahead. Ends with 2 s still.",
            "Shooting" to "Hold like taking photos. Tap Mark at each shutter moment; hold still 1 s between shots.",
            "Two hands" to "Two-handed grip: slow pan right, tilt down, roll slightly. Repeat twice.",
            "One hand" to "One-handed grip: same slow pan / tilt / roll. Note hand in Notes.",
            "Static" to "Phone flat and untouched 10 s. Gyro noise floor / bias reference.",
            "Pan/tilt sign check" to "From still: pan RIGHT 5 s, back; tilt DOWN 5 s, back. Validates gyro axis signs.",
            "Zoom" to "Set max zoom if your camera allows, repeat the pan. Warp ≈ pure rotation here.",
            "Custom" to "Free-form. Describe the motion in Notes.",
        )
    }
}
