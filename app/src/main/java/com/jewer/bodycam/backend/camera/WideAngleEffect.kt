package com.jewer.bodycam.backend.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.util.Consumer
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.functions.getCurrentBatteryLevel
import com.jewer.bodycam.backend.functions.getCurrentTime
import com.jewer.bodycam.backend.functions.getPhoneName
import com.jewer.bodycam.ui.theme.Black
import com.jewer.bodycam.ui.theme.DarkOrange
import com.jewer.bodycam.ui.theme.DarkRed
import com.jewer.bodycam.ui.theme.DarkYellow
import com.jewer.bodycam.ui.theme.LightGreen
import com.jewer.bodycam.ui.theme.Red
import com.jewer.bodycam.ui.theme.White
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor

class CustomCameraEffect(
    targets: Int,
    executor: Executor,
    processor: SurfaceProcessor,
    errorListener: Consumer<Throwable>
) : CameraEffect(targets, executor, processor, errorListener)

class WideAngleSurfaceProcessor(
    private val context: Context,
    @Volatile private var isPortrait: Boolean,
    @Volatile private var isFrontCamera: Boolean,
    @Volatile private var fisheyeK: Float,
    @Volatile private var fisheyeScale: Float,
    @Volatile private var isFisheyeEnabled: Boolean,
    @Volatile private var brand: String,
    @Volatile private var userName: String,
    @Volatile private var isRecording: Boolean
) : SurfaceProcessor {
    private val glThread = HandlerThread("GLThread").apply { start() }
    private val handler = Handler(glThread.looper)

    // 安全 Executor：確保即使 HandlerThread 正在處理釋放，CameraX 回調命令也能被執行，避免 Completer 未完成拋出異常
    private val glExecutor = Executor { command ->
        if (glThread.isAlive) {
            if (!handler.post(command)) {
                command.run()
            }
        } else {
            command.run()
        }
    }

    @Volatile private var poseBoundingBox: RectF? = null
    @Volatile private var poseFrameWidth: Int = 0
    @Volatile private var poseFrameHeight: Int = 0

    fun updateParams(
        isPortrait: Boolean,
        isFrontCamera: Boolean,
        fisheyeK: Float,
        fisheyeScale: Float,
        isFisheyeEnabled: Boolean,
        brand: String,
        userName: String,
        isRecording: Boolean
    ) {
        this.isPortrait = isPortrait
        this.isFrontCamera = isFrontCamera
        this.fisheyeK = fisheyeK
        this.fisheyeScale = fisheyeScale
        this.isFisheyeEnabled = isFisheyeEnabled
        this.brand = brand
        this.userName = userName
        this.isRecording = isRecording
    }

    fun updatePoseBox(box: RectF?, frameW: Int, frameH: Int) {
        this.poseBoundingBox = box
        this.poseFrameWidth = frameW
        this.poseFrameHeight = frameH
    }

    fun release() {
        releaseGL()
    }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraProgram = 0
    private var watermarkProgram = 0
    private var cameraTexName = -1
    private var watermarkTexName = -1
    private var surfaceTexture: SurfaceTexture? = null

    private var overlayBitmap: Bitmap? = null

    // 儲存每個輸出表面的資訊 (同時支援 PreviewView 與 VideoCapture Recorder 兩個輸出)
    private val outputSurfaces = mutableMapOf<SurfaceOutput, EGLSurface>()

    // 根據方向與鏡頭動態計算頂點數據
    private val cameraVertexData: FloatBuffer by lazy {
        val data = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
             1.0f, -1.0f, 1.0f, 0.0f,
            -1.0f,  1.0f, 0.0f, 1.0f,
             1.0f,  1.0f, 1.0f, 1.0f
        )
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).apply { position(0) }
    }

    private val watermarkVertexData: FloatBuffer by lazy {
        val data = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 0.0f
        )
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).apply { position(0) }
    }

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    private val cameraFragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform float uK;
        uniform float uScale;
        uniform bool uFisheyeEnabled;

        void main() {
            if (!uFisheyeEnabled) {
                gl_FragColor = texture2D(sTexture, vTexCoord);
                return;
            }
            vec2 uv = vTexCoord;
            vec2 pos = (uv - 0.5) * 2.0;
            float r2 = pos.x * pos.x + pos.y * pos.y;
            vec2 distortedPos = pos * (1.0 + uK * r2);
            distortedPos *= uScale;
            vec2 sampleUv = (distortedPos / 2.0) + 0.5;
            
            if (sampleUv.x < 0.0 || sampleUv.x > 1.0 || sampleUv.y < 0.0 || sampleUv.y > 1.0) {
                gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                gl_FragColor = texture2D(sTexture, sampleUv);
            }
        }
    """.trimIndent()

    private val watermarkVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val watermarkFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTexCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTexCoord);
        }
    """.trimIndent()

    override fun onInputSurface(surfaceRequest: SurfaceRequest) {
        if (!glThread.isAlive) return
        handler.post {
            initEGL()
            initGL()

            // 1. 安全釋放舊的 SurfaceTexture 與 Texture ID，防止前後鏡頭切換時 updateTexImage 發生 Native SIGSEGV 崩潰
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            if (cameraTexName != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(cameraTexName), 0)
                cameraTexName = -1
            }

            // 2. 建立新的相機外部紋理與 SurfaceTexture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            cameraTexName = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexName)

            val newSurfaceTexture = SurfaceTexture(cameraTexName)
            newSurfaceTexture.setDefaultBufferSize(surfaceRequest.resolution.width, surfaceRequest.resolution.height)
            surfaceTexture = newSurfaceTexture

            val surface = Surface(newSurfaceTexture)
            surfaceRequest.provideSurface(surface, glExecutor) {
                surface.release()
                newSurfaceTexture.release()
            }

            newSurfaceTexture.setOnFrameAvailableListener {
                if (!glThread.isAlive || surfaceTexture != newSurfaceTexture) return@setOnFrameAvailableListener
                handler.post {
                    if (eglDisplay == EGL14.EGL_NO_DISPLAY || surfaceTexture != newSurfaceTexture) return@post

                    // 確保在調用 updateTexImage 前當前執行緒具備有效的 EGLDisplay、EGLSurface 與 EGLContext，防止 invalid current EGLDisplay 崩潰
                    if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
                    }

                    try {
                        newSurfaceTexture.updateTexImage()
                    } catch (e: Exception) {
                        Log.e("WideAngle", "updateTexImage failed", e)
                        return@post
                    }

                    val transform = FloatArray(16)
                    newSurfaceTexture.getTransformMatrix(transform)

                    for ((output, windowSurface) in outputSurfaces.toList()) {
                        if (windowSurface == EGL14.EGL_NO_SURFACE) continue
                        if (!EGL14.eglMakeCurrent(eglDisplay, windowSurface, windowSurface, eglContext)) continue

                        val size = output.size
                        GLES20.glViewport(0, 0, size.width, size.height)

                        val finalMatrix = FloatArray(16)
                        output.updateTransformMatrix(finalMatrix, transform)

                        updateBrandOverlayTexture(size.width, size.height)
                        render(finalMatrix)
                        EGL14.eglSwapBuffers(eglDisplay, windowSurface)
                    }
                }
            }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (!glThread.isAlive) return
        handler.post {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            val surface = surfaceOutput.getSurface(glExecutor) {
                try {
                    surfaceOutput.close()
                } catch (e: Exception) {
                    Log.e("WideAngle", "surfaceOutput.close error", e)
                }
                glExecutor.execute {
                    val removed = outputSurfaces.remove(surfaceOutput)
                    if (removed != null && removed != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        EGL14.eglDestroySurface(eglDisplay, removed)
                    }
                }
            }
            val windowSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            outputSurfaces[surfaceOutput] = windowSurface
        }
    }

    private fun updateBrandOverlayTexture(w: Int, h: Int) {
        val width = if (w > 0) w else 1080
        val height = if (h > 0) h else 1920

        var bitmap = overlayBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            bitmap?.recycle()
            bitmap = createBitmap(width, height)
            overlayBitmap = bitmap
        } else {
            bitmap.eraseColor(Color.TRANSPARENT)
        }

        val canvas = Canvas(bitmap)
        val nowStr = getCurrentTime()
        val battery = getCurrentBatteryLevel(context)
        val phoneName = getPhoneName()

        val padX = (width * 0.06f)
        val padY = (height * 0.06f)

        // ── 繪製人體辨識 AI 追蹤框 ──
        val currentPose = poseBoundingBox
        if (currentPose != null && poseFrameWidth > 0 && poseFrameHeight > 0) {
            val scaleX = width.toFloat() / poseFrameWidth.toFloat()
            val scaleY = height.toFloat() / poseFrameHeight.toFloat()
            val left = currentPose.left * scaleX
            val top = currentPose.top * scaleY
            val right = currentPose.right * scaleX
            val bottom = currentPose.bottom * scaleY

            val boxPaint = Paint().apply {
                color = DarkYellow.toArgb()
                style = Paint.Style.STROKE
                strokeWidth = 6f
                isAntiAlias = true
            }
            canvas.drawRect(left, top, right, bottom, boxPaint)
        }

        val textFontSize = if (isPortrait) width * 0.030f else height * 0.030f
        val iconSize = if (isPortrait) (width * 0.12f).toInt() else (height * 0.14f).toInt()
        val recIconSize = if (isPortrait) (width * 0.08f).toInt() else (height * 0.10f).toInt()

        val textPaint = Paint().apply {
            color = White.toArgb()
            textSize = textFontSize
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            setShadowLayer(4f, 2f, 2f, Black.toArgb())
        }

        val batteryPaint = Paint().apply {
            color = if (battery <= 20) {
                Red.toArgb()
            } else if (battery <= 50) {
                DarkYellow.toArgb()
            } else {
                White.toArgb()
            }
            textSize = textFontSize
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
            setShadowLayer(4f, 2f, 2f, Black.toArgb())
        }

        val showRecIcon = !isRecording || ((System.currentTimeMillis() / 1000) % 2 == 0L)
        val recDrawable = if (isRecording) {
            ContextCompat.getDrawable(context, R.mipmap.ic_recording_foreground)?.apply { setTint(Red.toArgb()) }
        } else {
            ContextCompat.getDrawable(context, R.drawable.ic_start_record_foreground)?.apply { setTint(DarkYellow.toArgb()) }
        }

        when (brand) {
            "AXON" -> {
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_water_mark_foreground)?.apply { setTint(DarkYellow.toArgb()) }
                logoDrawable?.let {
                    it.setBounds((width - iconSize - padX).toInt(), padY.toInt(), (width - padX).toInt(), (padY + iconSize).toInt())
                    it.draw(canvas)
                }
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$userName $nowStr", width - iconSize - padX - 20f, padY + textFontSize * 1.2f, textPaint)
                canvas.drawText(phoneName, width - iconSize - padX - 20f, padY + textFontSize * 2.5f, textPaint)

                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds(padX.toInt(), padY.toInt(), (padX + recIconSize).toInt(), (padY + recIconSize).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "MOTOROLA" -> {
                val bannerHeight = textFontSize * 3.0f + padY
                val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bgPaint)

                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_motorola_icon_foreground)?.apply { setTint(White.toArgb()) }
                logoDrawable?.let {
                    val lSize = (bannerHeight * 0.5f).toInt()
                    it.setBounds(padX.toInt(), (padY * 0.5f).toInt(), (padX + lSize).toInt(), (padY * 0.5f + lSize).toInt())
                    it.draw(canvas)
                }
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.isFakeBoldText = true
                canvas.drawText("MOTOROLA SOLUTIONS", padX + iconSize + 10f, padY + textFontSize * 1.2f, textPaint)

                if (!isPortrait) {
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.isFakeBoldText = false
                    canvas.drawText("$nowStr $userName $phoneName", width - padX, padY + textFontSize * 1.2f, textPaint)
                } else {
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.isFakeBoldText = false
                    canvas.drawText("$nowStr $userName $phoneName", padX, height - padY - 20f, textPaint)
                }

                if (showRecIcon) {
                    recDrawable?.let {
                        val topPos = (bannerHeight + 20f).toInt()
                        it.setBounds((width - recIconSize - padX).toInt(), topPos, (width - padX).toInt(), topPos + recIconSize)
                        it.draw(canvas)
                    }
                }
            }
            "TRANSCEND" -> {
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_transcend_icon_foreground)?.apply { setTint(DarkRed.toArgb()) }
                logoDrawable?.let {
                    it.setBounds(padX.toInt(), (height - iconSize - padY).toInt(), (padX + iconSize).toInt(), (height - padY).toInt())
                    it.draw(canvas)
                }
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = DarkOrange.toArgb()
                canvas.drawText(userName, padX + iconSize + 20f, height - padY - textFontSize * 1.5f, textPaint)
                canvas.drawText("$nowStr $phoneName", padX + iconSize + 20f, height - padY, textPaint)

                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds(padX.toInt(), padY.toInt(), (padX + recIconSize).toInt(), (padY + recIconSize).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "GETAC" -> {
                val bannerHeight = textFontSize * 3.0f + padY
                val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bgPaint)

                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_getac_icon_foreground)?.apply { setTint(DarkOrange.toArgb()) }
                logoDrawable?.let {
                    val lSize = (bannerHeight * 0.5f).toInt()
                    it.setBounds(padX.toInt(), (padY * 0.5f).toInt(), (padX + lSize * 2).toInt(), (padY * 0.5f + lSize).toInt())
                    it.draw(canvas)
                }

                if (!isPortrait) {
                    textPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText("$nowStr $userName $phoneName", width - padX, padY + textFontSize * 1.2f, textPaint)
                } else {
                    textPaint.textAlign = Paint.Align.LEFT
                    canvas.drawText("$nowStr $userName $phoneName", padX, height - padY - 20f, textPaint)
                }

                if (showRecIcon) {
                    recDrawable?.let {
                        val topPos = (bannerHeight + 20f).toInt()
                        it.setBounds((width - recIconSize - padX).toInt(), topPos, (width - padX).toInt(), topPos + recIconSize)
                        it.draw(canvas)
                    }
                }
            }
            "DOZOR" -> {
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_dozor_icon_foreground)?.apply { setTint(White.toArgb()) }
                logoDrawable?.let {
                    it.setBounds((width - iconSize - padX).toInt(), padY.toInt(), (width - padX).toInt(), (padY + iconSize).toInt())
                    it.draw(canvas)
                }
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("DZ $userName $phoneName *$nowStr", width / 2f, height - padY, textPaint)

                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds(padX.toInt(), padY.toInt(), (padX + recIconSize).toInt(), (padY + recIconSize).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "PANASONIC" -> {
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(nowStr, padX, padY + textFontSize * 1.2f, textPaint)
                canvas.drawText("$userName $phoneName", padX, padY + textFontSize * 2.5f, textPaint)

                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_panasonic1_icon_foreground)?.apply { setTint(LightGreen.toArgb()) }
                logoDrawable?.let {
                    it.setBounds((width - iconSize - padX).toInt(), padY.toInt(), (width - padX).toInt(), (padY + iconSize).toInt())
                    it.draw(canvas)
                }

                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds(padX.toInt(), (height - recIconSize - padY).toInt(), (padX + recIconSize).toInt(), (height - padY).toInt())
                        it.draw(canvas)
                    }
                }
            }
        }

        // 繪製右下角電量資訊
        canvas.drawText("$battery%", width - padX, height - padY, batteryPaint)

        if (watermarkTexName == -1) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            watermarkTexName = textures[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexName)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun initEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        }
        EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)
    }

    private fun initGL() {
        if (cameraProgram == 0) {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, cameraFragmentShaderCode)
            cameraProgram = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vs)
                GLES20.glAttachShader(this, fs)
                GLES20.glLinkProgram(this)
            }
        }
        if (watermarkProgram == 0) {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, watermarkVertexShaderCode)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, watermarkFragmentShaderCode)
            watermarkProgram = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vs)
                GLES20.glAttachShader(this, fs)
                GLES20.glLinkProgram(this)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun render(texMatrix: FloatArray) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        // 1. 繪製相機畫面（若開啟魚眼則疊加魚眼效果，未開啟則為一般相機）
        GLES20.glUseProgram(cameraProgram)
        val matrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uTexMatrix")
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, texMatrix, 0)

        val kHandle = GLES20.glGetUniformLocation(cameraProgram, "uK")
        GLES20.glUniform1f(kHandle, fisheyeK)
        val scaleHandle = GLES20.glGetUniformLocation(cameraProgram, "uScale")
        GLES20.glUniform1f(scaleHandle, fisheyeScale)
        val fisheyeEnabledHandle = GLES20.glGetUniformLocation(cameraProgram, "uFisheyeEnabled")
        GLES20.glUniform1i(fisheyeEnabledHandle, if (isFisheyeEnabled) 1 else 0)

        cameraVertexData.position(0)
        val posHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, cameraVertexData)

        cameraVertexData.position(2)
        val texHandle = GLES20.glGetAttribLocation(cameraProgram, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, cameraVertexData)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexName)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cameraProgram, "sTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2. 繪製品牌浮水印圖層 (開啟 Alpha 混合)
        if (watermarkTexName != -1) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            GLES20.glUseProgram(watermarkProgram)
            val wPosHandle = GLES20.glGetAttribLocation(watermarkProgram, "aPosition")
            GLES20.glEnableVertexAttribArray(wPosHandle)
            watermarkVertexData.position(0)
            GLES20.glVertexAttribPointer(wPosHandle, 2, GLES20.GL_FLOAT, false, 16, watermarkVertexData)

            val wTexHandle = GLES20.glGetAttribLocation(watermarkProgram, "aTexCoord")
            GLES20.glEnableVertexAttribArray(wTexHandle)
            watermarkVertexData.position(2)
            GLES20.glVertexAttribPointer(wTexHandle, 2, GLES20.GL_FLOAT, false, 16, watermarkVertexData)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexName)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(watermarkProgram, "sTexture"), 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun releaseGL() {
        if (!glThread.isAlive) return
        handler.post {
            overlayBitmap?.recycle()
            overlayBitmap = null
            if (pbufferSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                pbufferSurface = EGL14.EGL_NO_SURFACE
            }
            for ((_, windowSurface) in outputSurfaces) {
                if (windowSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglDestroySurface(eglDisplay, windowSurface)
                }
            }
            outputSurfaces.clear()
            if (cameraProgram != 0) { GLES20.glDeleteProgram(cameraProgram); cameraProgram = 0 }
            if (watermarkProgram != 0) { GLES20.glDeleteProgram(watermarkProgram); watermarkProgram = 0 }
            if (cameraTexName != -1) { GLES20.glDeleteTextures(1, intArrayOf(cameraTexName), 0); cameraTexName = -1 }
            if (watermarkTexName != -1) { GLES20.glDeleteTextures(1, intArrayOf(watermarkTexName), 0); watermarkTexName = -1 }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            glThread.quitSafely()
        }
    }
}
