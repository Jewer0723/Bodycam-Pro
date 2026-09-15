package com.jewer.bodycam.backend.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import androidx.core.graphics.withTranslation
import androidx.core.util.Consumer
import com.jewer.bodycam.R
import com.jewer.bodycam.backend.functions.getCurrentBatteryLevel
import com.jewer.bodycam.backend.functions.getCurrentTime
import com.jewer.bodycam.backend.functions.getPhoneName
import com.jewer.bodycam.backend.functions.getVideoQuality
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
    context: Context,
    @Volatile private var isPortrait: Boolean,
    @Volatile private var isFrontCamera: Boolean,
    @Volatile private var fisheyeK: Float,
    @Volatile private var fisheyeScale: Float,
    @Volatile private var isFisheyeEnabled: Boolean,
    @Volatile private var brand: String,
    @Volatile private var userName: String,
    @Volatile private var isRecording: Boolean
) : SurfaceProcessor {
    private val appContext: Context = context.applicationContext
    private val context: Context get() = appContext
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

    @Suppress("unused")
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
        val quality = getVideoQuality(context)
        val isSd = (quality == "SD")



        // ── 四組 UI 繪製邏輯分支 ──
        when {
            !isPortrait && isSd -> drawLandscapeSdOverlay(canvas, width, height, nowStr, battery, phoneName)
            !isPortrait && !isSd -> drawLandscapeHdOverlay(canvas, width, height, nowStr, battery, phoneName)
            isPortrait && isSd -> drawPortraitSdOverlay(canvas, width, height, nowStr, battery, phoneName)
            else -> drawPortraitHdOverlay(canvas, width, height, nowStr, battery, phoneName)
        }

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

    // ── 分組 1: 水平 SD (Landscape SD) 專用精細微調邏輯 ──
    private fun drawLandscapeSdOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        nowStr: String,
        battery: Int,
        phoneName: String
    ) {
        val padX = width * 0.04f
        val padY = height * 0.04f

        val textFontSize = height * 0.032f
        val iconSize = (height * 0.16f).toInt()
        val recIconSize = (height * 0.08f).toInt()

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
                // Axon Logo: 向右移動一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_water_mark_foreground)?.apply { setTint(DarkYellow.toArgb()) }
                val logoRight = width - padX + 80f
                val logoLeft = logoRight - iconSize
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), padY.toInt(), logoRight.toInt(), (padY + iconSize).toInt())
                    it.draw(canvas)
                }

                // Axon Text: 上下對齊 (使用統一的 X 座標)
                textPaint.textAlign = Paint.Align.LEFT
                val axonTextX = logoLeft - 650f
                canvas.drawText("$userName $nowStr", axonTextX, padY + textFontSize * 2.3f, textPaint)
                canvas.drawText(phoneName, axonTextX, padY + textFontSize * 3.5f, textPaint)

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 50f).toInt(), (padY + 40f).toInt(), (padX - 50f + recIconSize).toInt(), (padY + recIconSize + 40f).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "MOTOROLA" -> {
                // 上方透明黑色背景變細一點
                val bannerHeight = textFontSize * 2f + padY
                val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bgPaint)

                // Motorola Icon: 往下向左一點在黑色背景中間
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_motorola_icon_foreground)?.apply { setTint(White.toArgb()) }
                val logoSize = (bannerHeight * 0.5f).toInt()
                val logoTop = ((bannerHeight - logoSize) / 0.9f).toInt()
                val logoLeft = (padX - 80f).toInt()
                logoDrawable?.let {
                    it.setBounds(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize)
                    it.draw(canvas)
                }

                // Motorola 字體粗體斜體，SOLUTIONS 斜體，兩者靠左且高度和 icon 切齊
                val motoPaintBoldItalic = Paint(textPaint).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
                    textAlign = Paint.Align.LEFT
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = 2.5f
                    textScaleX = 2f
                    textSize = textFontSize * 0.55f
                }
                val motoPaintItalic = Paint(textPaint).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
                    textAlign = Paint.Align.LEFT
                    textScaleX = 1.8f
                    textSize = textFontSize * 0.55f
                }

                val textY = logoTop + logoSize * 0.6f
                val motoTextLeft = logoLeft + logoSize + 0.45f
                canvas.drawText("MOTOROLA", motoTextLeft, textY, motoPaintBoldItalic)
                val motoWidth = motoPaintBoldItalic.measureText("MOTOROLA")
                canvas.drawText("SOLUTIONS", motoTextLeft + motoWidth + 13f, textY, motoPaintItalic)

                // 其餘時間和使用者名稱等等都往右靠
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.isFakeBoldText = false
                canvas.drawText("$nowStr $userName", width - padX + 50f, textY, textPaint)

                // 待機/錄影中 Icon: 稍微向右一點
                if (showRecIcon) {
                    recDrawable?.let {
                        val topPos = (bannerHeight + 30f).toInt()
                        it.setBounds((width - recIconSize - padX + 50f).toInt(), topPos, (width - padX + 50f).toInt(), topPos + recIconSize)
                        it.draw(canvas)
                    }
                }
            }
            "TRANSCEND" -> {
                // Transcend Icon: 往左靠一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_transcend_icon_foreground)?.apply { setTint(DarkRed.toArgb()) }
                val logoLeft = padX - 90f
                val logoTop = height - iconSize - padY
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), logoTop.toInt(), (logoLeft + iconSize).toInt(), (height - padY).toInt())
                    it.draw(canvas)
                }

                // 字體往上和 icon 切齊且往左靠近 icon 一點
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = DarkOrange.toArgb()
                val textLeft = logoLeft + iconSize - 20f
                canvas.drawText(userName, textLeft, logoTop + textFontSize * 2f, textPaint)
                canvas.drawText("$nowStr $phoneName", textLeft, logoTop + textFontSize * 3.5f, textPaint)

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 50f).toInt(), padY.toInt(), (padX - 50f + recIconSize).toInt(), (padY + recIconSize).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "GETAC" -> {
                // 上方透明黑色背景
                val bannerHeight = textFontSize * 2f + padY
                val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bgPaint)

                // Getac Icon: 高度稍微寬一點 (不擠壓)，向下往左靠一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_getac_icon_foreground)?.apply { setTint(DarkOrange.toArgb()) }
                val logoHeight = (bannerHeight * 1f).toInt()
                val logoWidth = (logoHeight * 1f).toInt()
                val logoTop = ((bannerHeight - logoHeight) / 2f).toInt() + 40
                val logoLeft = (padX - 50f).toInt()
                logoDrawable?.let {
                    it.setBounds(logoLeft, logoTop, logoLeft + logoWidth, logoTop + logoHeight)
                    it.draw(canvas)
                }

                // 字體往右靠一點
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$nowStr $userName $phoneName", width - padX + 50f, logoTop + logoHeight * 0.6f, textPaint)

                // 待機/錄影中 Icon: 稍微向右一點
                if (showRecIcon) {
                    recDrawable?.let {
                        val topPos = (bannerHeight + 15f).toInt()
                        it.setBounds((width - recIconSize - padX + 60f).toInt(), topPos, (width - padX + 60f).toInt(), topPos + recIconSize)
                        it.draw(canvas)
                    }
                }
            }
            "DOZOR" -> {
                // Dozor Icon: 稍微放大一點且往右靠一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_dozor_icon_foreground)?.apply { setTint(White.toArgb()) }
                val dIconSize = (iconSize * 1.25f).toInt()
                val logoLeft = width - dIconSize - padX + 50f
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), (padY - 50f).toInt(), (logoLeft + dIconSize).toInt(), (padY + dIconSize - 50f).toInt())
                    it.draw(canvas)
                }

                // 字體往上一點
                val textY = height - padY - 40f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("DZ $userName $phoneName *$nowStr", width / 2f, textY, textPaint)

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 50f).toInt(), padY.toInt(), (padX - 50f + recIconSize).toInt(), (padY + recIconSize).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "PANASONIC" -> {
                // 字體往左靠一點
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(nowStr, padX - 50f, padY + textFontSize * 1.8f, textPaint)
                canvas.drawText("$userName $phoneName", padX - 50f, padY + textFontSize * 2.8f, textPaint)

                // Panasonic Icon: 放大一點且靠右一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_panasonic1_icon_foreground)?.apply { setTint(LightGreen.toArgb()) }
                val pIconSize = (iconSize * 1.8f).toInt()
                val logoLeft = width - pIconSize - padX + 150f
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), (padY - 100f).toInt(), (logoLeft + pIconSize).toInt(), (padY + pIconSize - 100f).toInt())
                    it.draw(canvas)
                }

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 80f).toInt(), (height - recIconSize - padY).toInt(), (padX + recIconSize - 80f).toInt(), (height - padY).toInt())
                        it.draw(canvas)
                    }
                }
            }
        }

        // 繪製右下角電量資訊
        canvas.drawText("$battery%", width - padX + 30f, height - padY - 50f, batteryPaint)
    }

    // ── 分組 2: 水平 HD / FHD ──
    private fun drawLandscapeHdOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        nowStr: String,
        battery: Int,
        phoneName: String
    ) {
        val padX = width * 0.04f
        val padY = height * 0.04f

        val textFontSize = height * 0.032f
        val iconSize = (height * 0.16f).toInt()
        val recIconSize = (height * 0.08f).toInt()

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
                // Axon Logo: 向右移動一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_water_mark_foreground)?.apply { setTint(DarkYellow.toArgb()) }
                val logoRight = width - padX + 100f
                val logoLeft = logoRight - iconSize
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), (padY + 100f).toInt(), logoRight.toInt(), (padY + iconSize + 100f).toInt())
                    it.draw(canvas)
                }

                textPaint.textAlign = Paint.Align.LEFT
                val axonTextX = logoLeft - 650f
                canvas.drawText("$userName $nowStr", axonTextX, padY + textFontSize * 4.4f, textPaint)
                canvas.drawText(phoneName, axonTextX, padY + textFontSize * 5.6f, textPaint)

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 50f).toInt(), (padY + 140f).toInt(), (padX - 50f + recIconSize).toInt(), (padY + recIconSize + 140f).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "MOTOROLA" -> {
                // 上方透明黑色背景變細一點
                val bannerHeight = textFontSize * 3.9f + padY
                val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bgPaint)

                // Motorola Icon: 往下向左一點在黑色背景中間
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_motorola_icon_foreground)?.apply { setTint(White.toArgb()) }
                val logoSize = (bannerHeight * 0.3f).toInt()
                val logoTop = ((bannerHeight - logoSize) / 0.95f).toInt()
                val logoLeft = (padX - 80f).toInt()
                logoDrawable?.let {
                    it.setBounds(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize)
                    it.draw(canvas)
                }

                // Motorola 字體粗體斜體，SOLUTIONS 斜體，兩者靠左且高度和 icon 切齊
                val motoPaintBoldItalic = Paint(textPaint).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
                    textAlign = Paint.Align.LEFT
                    style = Paint.Style.FILL_AND_STROKE
                    strokeWidth = 2.5f
                    textScaleX = 2f
                    textSize = textFontSize * 0.55f
                }
                val motoPaintItalic = Paint(textPaint).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
                    textAlign = Paint.Align.LEFT
                    textScaleX = 1.8f
                    textSize = textFontSize * 0.55f
                }

                val textY = logoTop + logoSize * 0.6f
                val motoTextLeft = logoLeft + logoSize + 0.45f
                canvas.drawText("MOTOROLA", motoTextLeft, textY, motoPaintBoldItalic)
                val motoWidth = motoPaintBoldItalic.measureText("MOTOROLA")
                canvas.drawText("SOLUTIONS", motoTextLeft + motoWidth + 13f, textY, motoPaintItalic)

                // 其餘時間和使用者名稱等等都往右靠
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.isFakeBoldText = false
                canvas.drawText("$nowStr $userName", width - padX + 60f, textY, textPaint)

                // 待機/錄影中 Icon: 稍微向右一點
                if (showRecIcon) {
                    recDrawable?.let {
                        val topPos = (bannerHeight - 1f).toInt()
                        it.setBounds((width - recIconSize - padX + 70f).toInt(), topPos, (width - padX + 70f).toInt(), topPos + recIconSize)
                        it.draw(canvas)
                    }
                }
            }
            "TRANSCEND" -> {
                // Transcend Icon: 往左靠一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_transcend_icon_foreground)?.apply { setTint(DarkRed.toArgb()) }
                val logoLeft = padX - 90f
                val logoTop = height - iconSize - padY - 80f
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), logoTop.toInt(), (logoLeft + iconSize).toInt(), (height - padY - 80f).toInt())
                    it.draw(canvas)
                }

                // 字體往上和 icon 切齊且往左靠近 icon 一點
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = DarkOrange.toArgb()
                val textLeft = logoLeft + iconSize - 20f
                canvas.drawText(userName, textLeft, logoTop + textFontSize * 2f, textPaint)
                canvas.drawText("$nowStr $phoneName", textLeft, logoTop + textFontSize * 3.5f, textPaint)

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 60f).toInt(), (padY + 100f).toInt(), (padX - 60f + recIconSize).toInt(), (padY + recIconSize + 100f).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "GETAC" -> {
                // 上方透明黑色背景
                val bannerHeight = textFontSize * 4.2f + padY
                val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                canvas.drawRect(0f, 0f, width.toFloat(), bannerHeight, bgPaint)

                // Getac Icon: 高度稍微寬一點 (不擠壓)，向下往左靠一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_getac_icon_foreground)?.apply { setTint(DarkOrange.toArgb()) }
                val logoHeight = (bannerHeight * 0.7f).toInt()
                val logoWidth = (logoHeight * 1f).toInt()
                val logoTop = ((bannerHeight - logoHeight) / 2f).toInt() + 90
                val logoLeft = (padX - 60f).toInt()
                logoDrawable?.let {
                    it.setBounds(logoLeft, logoTop, logoLeft + logoWidth, logoTop + logoHeight)
                    it.draw(canvas)
                }

                // 字體往右靠一點
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$nowStr $userName $phoneName", width - padX + 50f, logoTop + logoHeight * 0.6f, textPaint)

                // 待機/錄影中 Icon: 稍微向右一點
                if (showRecIcon) {
                    recDrawable?.let {
                        val topPos = (bannerHeight + 5f).toInt()
                        it.setBounds((width - recIconSize - padX + 60f).toInt(), topPos, (width - padX + 60f).toInt(), topPos + recIconSize)
                        it.draw(canvas)
                    }
                }
            }
            "DOZOR" -> {
                // Dozor Icon: 稍微放大一點且往右靠一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_dozor_icon_foreground)?.apply { setTint(White.toArgb()) }
                val dIconSize = (iconSize * 1.25f).toInt()
                val logoLeft = width - dIconSize - padX + 50f
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), (padY + 40f).toInt(), (logoLeft + dIconSize).toInt(), (padY + dIconSize + 40).toInt())
                    it.draw(canvas)
                }

                // 字體往上一點
                val textY = height - padY - 140f
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText("DZ $userName $phoneName *$nowStr", width / 2f, textY, textPaint)

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 50f).toInt(), (padY + 100f).toInt(), (padX - 50f + recIconSize).toInt(), (padY + recIconSize + 100f).toInt())
                        it.draw(canvas)
                    }
                }
            }
            "PANASONIC" -> {
                // 字體往左靠一點
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(nowStr, padX - 50f, padY + textFontSize * 4.3f, textPaint)
                canvas.drawText("$userName $phoneName", padX - 50f, padY + textFontSize * 5.5f, textPaint)

                // Panasonic Icon: 放大一點且靠右一點
                val logoDrawable = ContextCompat.getDrawable(context, R.mipmap.ic_panasonic1_icon_foreground)?.apply { setTint(LightGreen.toArgb()) }
                val pIconSize = (iconSize * 1.8f).toInt()
                val logoLeft = width - pIconSize - padX + 150f
                logoDrawable?.let {
                    it.setBounds(logoLeft.toInt(), (padY + 10f).toInt(), (logoLeft + pIconSize).toInt(), (padY + pIconSize + 10f).toInt())
                    it.draw(canvas)
                }

                // 待機/錄影中 Icon: 稍微向左一點
                if (showRecIcon) {
                    recDrawable?.let {
                        it.setBounds((padX - 80f).toInt(), (height - recIconSize - padY - 100).toInt(), (padX + recIconSize - 80f).toInt(), (height - padY - 100).toInt())
                        it.draw(canvas)
                    }
                }
            }
        }

        // 繪製右下角電量資訊
        canvas.drawText("$battery%", width - padX + 50f, height - padY - 150f, batteryPaint)
    }

    // ── 分組 3: 垂直 SD (獨立繪圖函數，可自由微調每個元件座標) ──
    private fun drawPortraitSdOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        nowStr: String,
        battery: Int,
        phoneName: String
    ) {
        val translateX = if (isFrontCamera) width.toFloat() else 0f
        val translateY = if (isFrontCamera) 0f else height.toFloat()
        val rotateAngle = if (isFrontCamera) 90f else -90f

        canvas.withTranslation(translateX, translateY) {
            rotate(rotateAngle)

            // 垂直畫布維度: vWidth = height, vHeight = width
            val vWidth = height
            val vHeight = width

            val padX = vWidth * 0.04f
            val padY = vHeight * 0.04f

            val textFontSize = vHeight * 0.023f
            val iconSize = (vHeight * 0.12f).toInt()
            val recIconSize = (vHeight * 0.05f).toInt()

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
                ContextCompat.getDrawable(context, R.mipmap.ic_recording_foreground)
                    ?.apply { setTint(Red.toArgb()) }
            } else {
                ContextCompat.getDrawable(context, R.drawable.ic_start_record_foreground)
                    ?.apply { setTint(DarkYellow.toArgb()) }
            }

            when (brand) {
                "AXON" -> {
                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_water_mark_foreground)
                            ?.apply { setTint(DarkYellow.toArgb()) }
                    val logoRight = vWidth - padX + 5f
                    val logoLeft = logoRight - iconSize
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            (padY - 100f).toInt(),
                            logoRight.toInt(),
                            (padY + iconSize - 100f).toInt()
                        )
                        it.draw(this)
                    }

                    textPaint.textAlign = Paint.Align.LEFT
                    val axonTextX = logoLeft - 600f
                    drawText("$userName $nowStr", axonTextX, padY + textFontSize * 0.25f, textPaint)
                    drawText(phoneName, axonTextX, padY + textFontSize * 1.4f, textPaint)

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 50f).toInt(),
                                (padY - 50f).toInt(),
                                (padX + 50f + recIconSize).toInt(),
                                (padY + recIconSize - 50f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }

                "MOTOROLA" -> {
                    val bannerHeight = textFontSize * 0.005f + padY
                    val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                    drawRect(0f, 0f, vWidth.toFloat(), bannerHeight, bgPaint)

                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_motorola_icon_foreground)
                            ?.apply { setTint(White.toArgb()) }
                    val logoSize = (bannerHeight * 0.9f).toInt()
                    val logoTop = ((bannerHeight - logoSize) / 1f).toInt()
                    val logoLeft = (padX + 20f).toInt()
                    logoDrawable?.let {
                        it.setBounds(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize)
                        it.draw(this)
                    }

                    val motoPaintBoldItalic = Paint(textPaint).apply {
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
                        textAlign = Paint.Align.LEFT
                        style = Paint.Style.FILL_AND_STROKE
                        strokeWidth = 2.5f
                        textScaleX = 2f
                        textSize = textFontSize * 0.55f
                    }
                    val motoPaintItalic = Paint(textPaint).apply {
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
                        textAlign = Paint.Align.LEFT
                        textScaleX = 1.8f
                        textSize = textFontSize * 0.55f
                    }

                    val textY = logoTop + logoSize * 0.6f
                    val motoTextLeft = logoLeft + logoSize + 0.45f
                    drawText("MOTOROLA", motoTextLeft, textY, motoPaintBoldItalic)
                    val motoWidth = motoPaintBoldItalic.measureText("MOTOROLA")
                    drawText("SOLUTIONS", motoTextLeft + motoWidth + 13f, textY, motoPaintItalic)

                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.isFakeBoldText = false
                    drawText("$nowStr $userName", vWidth - padX - 50f, textY, textPaint)

                    if (showRecIcon) {
                        recDrawable?.let {
                            val topPos = (bannerHeight - 10f).toInt()
                            it.setBounds(
                                (vWidth - recIconSize - padX - 30f).toInt(),
                                topPos,
                                (vWidth - padX - 30f).toInt(),
                                topPos + recIconSize
                            )
                            it.draw(this)
                        }
                    }
                }

                "TRANSCEND" -> {
                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_transcend_icon_foreground)
                            ?.apply { setTint(DarkRed.toArgb()) }
                    val logoLeft = padX - 10f
                    val logoTop = vHeight - iconSize - padY + 100f
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            logoTop.toInt(),
                            (logoLeft + iconSize).toInt(),
                            (vHeight - padY + 100f).toInt()
                        )
                        it.draw(this)
                    }

                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = DarkOrange.toArgb()
                    val textLeft = logoLeft + iconSize - 20f
                    drawText("$userName $phoneName", textLeft, logoTop + textFontSize * 2f, textPaint)
                    drawText(
                        nowStr,
                        textLeft,
                        logoTop + textFontSize * 3.5f,
                        textPaint
                    )

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 30f).toInt(),
                                (padY - 80f).toInt(),
                                (padX + 30f + recIconSize).toInt(),
                                (padY + recIconSize - 80f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }

                "GETAC" -> {
                    val bannerHeight = textFontSize * 0.0001f + padY
                    val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                    drawRect(0f, 0f, vWidth.toFloat(), bannerHeight, bgPaint)

                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_getac_icon_foreground)
                            ?.apply { setTint(DarkOrange.toArgb()) }
                    val logoHeight = (bannerHeight * 2f).toInt()
                    val logoWidth = (logoHeight * 1f).toInt()
                    val logoTop = ((bannerHeight - logoHeight) / 2f).toInt() + 5
                    val logoLeft = (padX + 50f).toInt()
                    logoDrawable?.let {
                        it.setBounds(logoLeft, logoTop, logoLeft + logoWidth, logoTop + logoHeight)
                        it.draw(this)
                    }

                    textPaint.textAlign = Paint.Align.RIGHT
                    drawText(
                        "$nowStr $userName",
                        vWidth - padX - 30f,
                        logoTop + logoHeight * 0.6f,
                        textPaint
                    )

                    if (showRecIcon) {
                        recDrawable?.let {
                            val topPos = (bannerHeight - 10f).toInt()
                            it.setBounds(
                                (vWidth - recIconSize - padX - 30f).toInt(),
                                topPos,
                                (vWidth - padX - 30f).toInt(),
                                topPos + recIconSize
                            )
                            it.draw(this)
                        }
                    }
                }

                "DOZOR" -> {
                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_dozor_icon_foreground)
                            ?.apply { setTint(White.toArgb()) }
                    val dIconSize = (iconSize * 1.25f).toInt()
                    val logoLeft = vWidth - dIconSize - padX - 30f
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            (padY - 150f).toInt(),
                            (logoLeft + dIconSize).toInt(),
                            (padY + dIconSize - 150f).toInt()
                        )
                        it.draw(this)
                    }

                    val textY = vHeight - padY + 30f
                    textPaint.textAlign = Paint.Align.CENTER
                    drawText("DZ $userName *$nowStr", vWidth / 2f, textY, textPaint)

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 30f).toInt(),
                                (padY - 80f).toInt(),
                                (padX + 30f + recIconSize).toInt(),
                                (padY + recIconSize - 80f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }

                "PANASONIC" -> {
                    textPaint.textAlign = Paint.Align.LEFT
                    drawText(nowStr, padX + 40f, padY + textFontSize * -0.5f, textPaint)
                    drawText(
                        "$userName $phoneName",
                        padX + 40f,
                        padY + textFontSize * 0.8f,
                        textPaint
                    )

                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_panasonic1_icon_foreground)
                            ?.apply { setTint(LightGreen.toArgb()) }
                    val pIconSize = (iconSize * 1.8f).toInt()
                    val logoLeft = vWidth - pIconSize - padX + 40f
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            (padY - 200f).toInt(),
                            (logoLeft + pIconSize).toInt(),
                            (padY + pIconSize - 200f).toInt()
                        )
                        it.draw(this)
                    }

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 40f).toInt(),
                                (vHeight - recIconSize - padY + 60f).toInt(),
                                (padX + recIconSize + 40f).toInt(),
                                (vHeight - padY + 60f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }
            }

            drawText("$battery%", vWidth - padX - 50f, vHeight - padY + 30f, batteryPaint)
        }
    }

    // ── 分組 4: 垂直 HD / FHD (獨立繪圖函數，可自由微調每個元件座標) ──
    private fun drawPortraitHdOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        nowStr: String,
        battery: Int,
        phoneName: String
    ) {
        val translateX = if (isFrontCamera) width.toFloat() else 0f
        val translateY = if (isFrontCamera) 0f else height.toFloat()
        val rotateAngle = if (isFrontCamera) 90f else -90f

        canvas.withTranslation(translateX, translateY) {
            rotate(rotateAngle)

            // 垂直畫布維度: vWidth = height, vHeight = width
            val vWidth = height
            val vHeight = width

            val padX = vWidth * 0.04f
            val padY = vHeight * 0.04f

            val textFontSize = vHeight * 0.021f
            val iconSize = (vHeight * 0.11f).toInt()
            val recIconSize = (vHeight * 0.05f).toInt()

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
                ContextCompat.getDrawable(context, R.mipmap.ic_recording_foreground)
                    ?.apply { setTint(Red.toArgb()) }
            } else {
                ContextCompat.getDrawable(context, R.drawable.ic_start_record_foreground)
                    ?.apply { setTint(DarkYellow.toArgb()) }
            }

            when (brand) {
                "AXON" -> {
                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_water_mark_foreground)
                            ?.apply { setTint(DarkYellow.toArgb()) }
                    val logoRight = vWidth - padX - 80f
                    val logoLeft = logoRight - iconSize
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            (padY - 110f).toInt(),
                            logoRight.toInt(),
                            (padY + iconSize - 110f).toInt()
                        )
                        it.draw(this)
                    }

                    textPaint.textAlign = Paint.Align.LEFT
                    val axonTextX = logoLeft - 550f
                    drawText("$userName $nowStr", axonTextX, padY + textFontSize * -0.3f, textPaint)
                    drawText(phoneName, axonTextX, padY + textFontSize * 0.9f, textPaint)

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 120f).toInt(),
                                (padY - 80f).toInt(),
                                (padX + 120f + recIconSize).toInt(),
                                (padY + recIconSize - 80f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }

                "MOTOROLA" -> {
                    val bannerHeight = textFontSize * 0.005f + padY
                    val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                    drawRect(0f, 0f, vWidth.toFloat(), bannerHeight, bgPaint)

                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_motorola_icon_foreground)
                            ?.apply { setTint(White.toArgb()) }
                    val logoSize = (bannerHeight * 0.9f).toInt()
                    val logoTop = ((bannerHeight - logoSize) / 1f).toInt()
                    val logoLeft = (padX + 120f).toInt()
                    logoDrawable?.let {
                        it.setBounds(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize)
                        it.draw(this)
                    }

                    val motoPaintBoldItalic = Paint(textPaint).apply {
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
                        textAlign = Paint.Align.LEFT
                        style = Paint.Style.FILL_AND_STROKE
                        strokeWidth = 2.5f
                        textScaleX = 2f
                        textSize = textFontSize * 0.55f
                    }
                    val motoPaintItalic = Paint(textPaint).apply {
                        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
                        textAlign = Paint.Align.LEFT
                        textScaleX = 1.8f
                        textSize = textFontSize * 0.55f
                    }

                    val textY = logoTop + logoSize * 0.6f
                    val motoTextLeft = logoLeft + logoSize + 0.45f
                    drawText("MOTOROLA", motoTextLeft, textY, motoPaintBoldItalic)
                    val motoWidth = motoPaintBoldItalic.measureText("MOTOROLA")
                    drawText("SOLUTIONS", motoTextLeft + motoWidth + 13f, textY, motoPaintItalic)

                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.isFakeBoldText = false
                    drawText(nowStr, vWidth - padX - 150f, textY, textPaint)

                    if (showRecIcon) {
                        recDrawable?.let {
                            val topPos = (bannerHeight - 10f).toInt()
                            it.setBounds(
                                (vWidth - recIconSize - padX - 130f).toInt(),
                                topPos,
                                (vWidth - padX - 130f).toInt(),
                                topPos + recIconSize
                            )
                            it.draw(this)
                        }
                    }
                }

                "TRANSCEND" -> {
                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_transcend_icon_foreground)
                            ?.apply { setTint(DarkRed.toArgb()) }
                    val logoLeft = padX + 100f
                    val logoTop = vHeight - iconSize - padY + 100f
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            logoTop.toInt(),
                            (logoLeft + iconSize).toInt(),
                            (vHeight - padY + 100f).toInt()
                        )
                        it.draw(this)
                    }

                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = DarkOrange.toArgb()
                    val textLeft = logoLeft + iconSize - 20f
                    drawText("$userName $phoneName", textLeft, logoTop + textFontSize * 2f, textPaint)
                    drawText(
                        nowStr,
                        textLeft,
                        logoTop + textFontSize * 3.5f,
                        textPaint
                    )

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 120f).toInt(),
                                (padY - 80f).toInt(),
                                (padX + 120f + recIconSize).toInt(),
                                (padY + recIconSize - 80f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }

                "GETAC" -> {
                    val bannerHeight = textFontSize * 0.0001f + padY
                    val bgPaint = Paint().apply { color = Black.copy(alpha = 0.5f).toArgb() }
                    drawRect(0f, 0f, vWidth.toFloat(), bannerHeight, bgPaint)

                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_getac_icon_foreground)
                            ?.apply { setTint(DarkOrange.toArgb()) }
                    val logoHeight = (bannerHeight * 2f).toInt()
                    val logoWidth = (logoHeight * 1f).toInt()
                    val logoTop = ((bannerHeight - logoHeight) / 2f).toInt() + 5
                    val logoLeft = (padX + 140f).toInt()
                    logoDrawable?.let {
                        it.setBounds(logoLeft, logoTop, logoLeft + logoWidth, logoTop + logoHeight)
                        it.draw(this)
                    }

                    textPaint.textAlign = Paint.Align.RIGHT
                    drawText(
                        "$nowStr $userName",
                        vWidth - padX - 140f,
                        logoTop + logoHeight * 0.6f,
                        textPaint
                    )

                    if (showRecIcon) {
                        recDrawable?.let {
                            val topPos = (bannerHeight - 10f).toInt()
                            it.setBounds(
                                (vWidth - recIconSize - padX - 130f).toInt(),
                                topPos,
                                (vWidth - padX - 130f).toInt(),
                                topPos + recIconSize
                            )
                            it.draw(this)
                        }
                    }
                }

                "DOZOR" -> {
                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_dozor_icon_foreground)
                            ?.apply { setTint(White.toArgb()) }
                    val dIconSize = (iconSize * 1.25f).toInt()
                    val logoLeft = vWidth - dIconSize - padX - 140f
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            (padY - 150f).toInt(),
                            (logoLeft + dIconSize).toInt(),
                            (padY + dIconSize - 150f).toInt()
                        )
                        it.draw(this)
                    }

                    val textY = vHeight - padY + 50f
                    textPaint.textAlign = Paint.Align.CENTER
                    drawText("DZ $userName *$nowStr", vWidth / 2f, textY, textPaint)

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 130f).toInt(),
                                (padY - 80f).toInt(),
                                (padX + 130f + recIconSize).toInt(),
                                (padY + recIconSize - 80f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }

                "PANASONIC" -> {
                    textPaint.textAlign = Paint.Align.LEFT
                    drawText(nowStr, padX + 140f, padY + textFontSize * -0.5f, textPaint)
                    drawText(
                        "$userName $phoneName",
                        padX + 140f,
                        padY + textFontSize * 0.8f,
                        textPaint
                    )

                    val logoDrawable =
                        ContextCompat.getDrawable(context, R.mipmap.ic_panasonic1_icon_foreground)
                            ?.apply { setTint(LightGreen.toArgb()) }
                    val pIconSize = (iconSize * 1.8f).toInt()
                    val logoLeft = vWidth - pIconSize - padX - 70f
                    logoDrawable?.let {
                        it.setBounds(
                            logoLeft.toInt(),
                            (padY - 200f).toInt(),
                            (logoLeft + pIconSize).toInt(),
                            (padY + pIconSize - 200f).toInt()
                        )
                        it.draw(this)
                    }

                    if (showRecIcon) {
                        recDrawable?.let {
                            it.setBounds(
                                (padX + 130f).toInt(),
                                (vHeight - recIconSize - padY + 80f).toInt(),
                                (padX + recIconSize + 130f).toInt(),
                                (vHeight - padY + 80f).toInt()
                            )
                            it.draw(this)
                        }
                    }
                }
            }

            drawText("$battery%", vWidth - padX - 150f, vHeight - padY + 50f, batteryPaint)
        }
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
