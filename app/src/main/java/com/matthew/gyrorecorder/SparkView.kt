// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** Live |omega| sparkline drawn from the recorder ring buffer (sage, PerfOverlay palette). */
class SparkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var recorder: SessionRecorder? = null
    private val paint = Paint().apply {
        color = 0xFF93B5A2.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val grid = Paint().apply {
        color = 0x3393B5A2; strokeWidth = 1f; style = Paint.Style.STROKE
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, grid)
        val r = recorder ?: return
        val data: List<Float> = synchronized(r.spark) { r.spark.toList() }
        if (data.size < 2) return
        val maxV = maxOf(1f, data.max())
        path.reset()
        data.forEachIndexed { i, v ->
            val x = i.toFloat() / (data.size - 1) * width
            val y = height - (v / maxV) * (height - 6f) - 3f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
