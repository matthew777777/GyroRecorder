// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.gyrorecorder

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Minimal ARCore background renderer: live camera texture + per-frame callback.
 * Pattern follows Google's official Hello AR sample background renderer (README "References").
 */
class ArRenderer(private val onFrame: (Frame) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var rotation = 0
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0
    private var textureId = -1
    private var program = 0
    private var posLoc = -1; private var uvLoc = -1; private var texLoc = -1
    private var quadPos: FloatBuffer? = null
    private var quadUv: FloatBuffer? = null
    private val transformedUv = FloatArray(8)
    // Display geometry last pushed to the session; -1 = not yet pushed.
    private var appliedRotation = -1
    private var appliedWidth = -1
    private var appliedHeight = -1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        session?.setCameraTextureName(textureId)

        val vs = compile(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){ gl_Position=a_Position; v_TexCoord=a_TexCoord; }")
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 v_TexCoord; uniform samplerExternalOES u_Texture; void main(){ gl_FragColor=texture2D(u_Texture, v_TexCoord); }")
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        }
        posLoc = GLES20.glGetAttribLocation(program, "a_Position")
        uvLoc = GLES20.glGetAttribLocation(program, "a_TexCoord")
        texLoc = GLES20.glGetUniformLocation(program, "u_Texture")
        quadPos = floatBuffer(QUAD)
        quadUv = floatBuffer(QUAD.copyOf())
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width; viewHeight = height
        appliedRotation = -1 // force re-push of display geometry on next frame
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        try {
            if (textureId >= 0) s.setCameraTextureName(textureId)
            // Per Session.setDisplayGeometry docs this is only needed when the
            // aspect ratio / rotation changes, not every frame.
            val geometryDirty = rotation != appliedRotation || viewWidth != appliedWidth || viewHeight != appliedHeight
            if (geometryDirty && viewWidth > 0) {
                s.setDisplayGeometry(rotation, viewWidth, viewHeight)
                appliedRotation = rotation; appliedWidth = viewWidth; appliedHeight = viewHeight
            }
            val frame = s.update()
            if (geometryDirty || frame.hasDisplayGeometryChanged()) {
                // Remap NDC quad -> camera UVs for the current display geometry.
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, QUAD, Coordinates2d.TEXTURE_NORMALIZED, transformedUv)
                quadUv?.apply { clear(); put(transformedUv); rewind() }
            }
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(texLoc, 0)
            quadPos?.let { GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, it); GLES20.glEnableVertexAttribArray(posLoc) }
            quadUv?.let { GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 0, it); GLES20.glEnableVertexAttribArray(uvLoc) }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            onFrame(frame)
        } catch (_: CameraNotAvailableException) {
            Log.w(TAG, "camera not available this frame")
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.message}")
        }
    }

    private fun compile(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src); GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(id)}")
        return id
    }
    private fun floatBuffer(v: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); rewind() }

    companion object {
        private const val TAG = "ArPreview"
        private val QUAD = floatArrayOf(-1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f)
    }
}
