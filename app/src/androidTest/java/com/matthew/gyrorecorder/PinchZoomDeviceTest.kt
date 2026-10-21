package com.matthew.gyrorecorder

import android.Manifest
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device test: a real injected two-finger pinch-out must raise the preview
 * zoom (visible in the HUD text). `adb shell input` has no pinch command and
 * sendevent is permission-denied on stock ROMs, hence UiAutomation injection.
 */
@RunWith(AndroidJUnit4::class)
class PinchZoomDeviceTest {

    @Test
    fun pinchOutIncreasesZoom() {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.uiAutomation.grantRuntimePermission(
            "com.matthew.gyrorecorder", Manifest.permission.CAMERA
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Wait for the first ARCore frame so the HUD is populated.
            waitForHud(scenario, timeoutMs = 15_000)
            val before = hudText(scenario)
            pinchOut(scenario)
            Thread.sleep(800)
            val after = hudText(scenario)
            val zBefore = zoomOf(before)
            val zAfter = zoomOf(after)
            assertTrue("pinch should zoom in (was $zBefore, now $zAfter; HUD: $after)", zAfter > zBefore + 0.2f)
        }
    }

    private fun hudText(scenario: ActivityScenario<MainActivity>): String {
        var text = ""
        scenario.onActivity { a -> text = a.findViewById<TextView>(R.id.hudView).text.toString() }
        return text
    }

    private fun waitForHud(scenario: ActivityScenario<MainActivity>, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hudText(scenario).contains("zoom")) return
            Thread.sleep(250)
        }
        fail("HUD never populated (no ARCore frame?): '${hudText(scenario)}'")
    }

    private fun zoomOf(hud: String): Float =
        Regex("zoom ([0-9.]+)x").find(hud)?.groupValues?.get(1)?.toFloat() ?: -1f

    private fun pinchOut(scenario: ActivityScenario<MainActivity>) {
        var cx = 0f; var cy = 0f
        scenario.onActivity { a ->
            val v = a.findViewById<android.view.View>(R.id.glView)
            cx = v.width / 2f; cy = v.height / 2f
        }
        val instr = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        fun event(time: Long, action: Int, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
            val props = arrayOf(
                MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
                MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply { x = x0; y = y0; pressure = 1f; size = 1f },
                MotionEvent.PointerCoords().apply { x = x1; y = y1; pressure = 1f; size = 1f }
            )
            val count = if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) 1 else 2
            return MotionEvent.obtain(
                down, time, action, count,
                props.copyOf(count), coords.copyOf(count),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
        }
        var t = down
        fun inject(action: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            t += 40
            val e = event(t, action, x0, y0, x1, y1)
            instr.uiAutomation.injectInputEvent(e, true)
            e.recycle()
        }
        // Fingers start 120 px apart around the centre, end 560 px apart.
        inject(MotionEvent.ACTION_DOWN, cx - 60, cy - 60, cx - 60, cy - 60)
        inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - 60, cy - 60, cx + 60, cy + 60)
        val steps = 10
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            val d0 = 60 + 220 * f
            inject(MotionEvent.ACTION_MOVE, cx - d0, cy - d0, cx + d0, cy + d0)
        }
        inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), cx - 280, cy - 280, cx + 280, cy + 280)
        inject(MotionEvent.ACTION_UP, cx - 280, cy - 280, cx - 280, cy - 280)
    }
}
