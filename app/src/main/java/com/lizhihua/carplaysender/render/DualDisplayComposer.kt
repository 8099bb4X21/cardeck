package com.lizhihua.carplaysender.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 双虚拟屏 GL 合成器：dock 屏（左窄条）+ 应用屏（右侧）→ 拼接输出到编码器输入 Surface。
 *
 * 两个 SurfaceTexture 作为各虚拟屏的输出；GL 线程轮询新帧标志，有新帧即重绘合成画面。
 * 输入 Surface 在 EGL/纹理就绪后经 [onSurfacesReady] 交给调用方去 createVirtualDisplay。
 */
class DualDisplayComposer(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
) {
    companion object {
        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    /** start() 的就绪闩：GL 交付输入 Surface 后放行（注意不能等合成循环退出） */
    @Volatile
    private var startLatch: CountDownLatch? = null

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface) -> Unit)? = null

    @Throws(IllegalStateException::class)
    fun start(dockWidthPx: Int, dockHeightPx: Int, appWidthPx: Int, appHeightPx: Int) {
        if (running.getAndSet(true)) return
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread = Thread({
            try {
                runGl(dockWidthPx, dockHeightPx, appWidthPx, appHeightPx)
            } catch (error: IllegalStateException) {
                failure = error
            } finally {
                ready.countDown()
            }
        }, "dual-composer").apply {
            isDaemon = true
            start()
        }
        ready.await()
        failure?.let { running.set(false); throw it }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        glThread?.interrupt()
        glThread = null
    }

    private class Input(val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
        @Volatile
        var hasNewFrame = false

        /** 是否已产出过至少一帧（未产出的输入不参与绘制，避免空纹理） */
        @Volatile
        var hasContent = false
    }

    private fun runGl(dockW: Int, dockH: Int, appW: Int, appH: Int) {
        // ---- EGL ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            output,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // ---- 输入纹理 ----
        fun newInput(width: Int, height: Int): Input {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(width, height)
            val input = Input(tex[0], st, Surface(st))
            st.setOnFrameAvailableListener { input.hasNewFrame = true }
            return input
        }

        val dock = newInput(dockW, dockH)
        val app = newInput(appW, appH)

        // ---- 着色器 ----
        val program = buildProgram()
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val matrix = FloatArray(16)
        fun drawInput(input: Input, x0: Float, x1: Float, y0: Float, y1: Float) {
            input.surfaceTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
            // 像素坐标 → 裁剪坐标（GL 左下原点；纹理翻转由 uSTMatrix 处理，勿重复翻转）
            fun px(v: Float) = v / outWidth * 2f - 1f
            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions = floatBuffer(
                px(x0), py(y0), px(x1), py(y0), px(x0), py(y1), px(x1), py(y1),
            )
            val coords = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun composeFrame(fullRedraw: Boolean) {
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // 只对有新帧的输入 updateTexImage；已有内容的输入沿用旧纹理（画面持久）
            if (dock.hasNewFrame) {
                dock.hasNewFrame = false
                dock.hasContent = true
                dock.surfaceTexture.updateTexImage()
            }
            if (app.hasNewFrame) {
                app.hasNewFrame = false
                app.hasContent = true
                app.surfaceTexture.updateTexImage()
            }
            if (dock.hasContent) drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
            if (app.hasContent) {
                drawInput(app, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
            }
            if (fullRedraw || dock.hasContent || app.hasContent) {
                EGL14.eglSwapBuffers(display, outputSurface)
            }
        }

        // 初始黑帧（只清屏，不碰空纹理）+ 交付输入 Surface + 放行 start()
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, outputSurface)
        onSurfacesReady?.invoke(dock.surface, app.surface)
        startLatch?.countDown()

        // ---- 合成循环：轮询新帧（16ms 节拍足够 60fps）----
        try {
            while (running.get()) {
                if (dock.hasNewFrame || app.hasNewFrame) {
                    composeFrame(fullRedraw = false)
                } else {
                    Thread.sleep(16)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { dock.surface.release() }
            runCatching { app.surface.release() }
            runCatching { dock.surfaceTexture.release() }
            runCatching { app.surfaceTexture.release() }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    private fun buildProgram(): Int {
        val vertex = """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()
        val fragment = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(ok: Boolean, stage: String) {
        if (!ok) {
            Log.e(TAG, "EGL 失败：$stage")
            throw IllegalStateException("EGL $stage")
        }
    }
}
