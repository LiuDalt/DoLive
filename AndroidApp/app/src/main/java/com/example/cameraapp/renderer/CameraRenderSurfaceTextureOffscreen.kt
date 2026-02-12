package com.example.cameraapp.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SurfaceTexture离屏渲染器实现类
 * 
 * 负责处理SurfaceTexture类型的相机预览渲染，使用离屏渲染技术
 * 1. 将相机输出渲染到离屏纹理
 * 2. 在离屏纹理上应用滤镜效果
 * 3. 将合成的纹理渲染到屏幕
 * 
 * @param context 上下文
 */
class CameraRenderSurfaceTextureOffscreen(private val context: Context) : SurfaceTextureRenderer {
    companion object {
        private const val TAG = "CameraRenderSurfaceTextureOffscreen"
        
        // 调试相关常量
        private const val MAX_DEBUG_IMAGES = 5
        private const val DEBUG_SAVE_INTERVAL = 5L // 秒
        private const val DEBUG_IMAGE_DIR_NAME = "temimages"
        
        // 相机相关常量
        private const val DEFAULT_PREVIEW_WIDTH = 720
        private const val DEFAULT_PREVIEW_HEIGHT = 1280
        private const val TARGET_ASPECT_RATIO = 9.0f / 16.0f
        
        // SurfaceTexture着色器代码
        // 顶点着色器（原始画面和效果画面共用）
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        // 原始画面片段着色器（无滤镜）
        private const val FRAGMENT_SHADER_ORIGINAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                // 直接输出原始纹理颜色
                vec4 originalColor = texture2D(sTexture, vTexCoord);
                gl_FragColor = originalColor;
            }
        """

        // 效果画面片段着色器（带绿色滤镜）
        private const val FRAGMENT_SHADER_FILTERED = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                // 获取原始纹理颜色
                vec4 originalColor = texture2D(sTexture, vTexCoord);
                // 创建半透明纯绿色滤镜
                vec4 greenFilter = vec4(0.0, 1.0, 0.0, 0.2);
                // 应用滤镜，使用混合模式保留原始图像内容
                gl_FragColor = originalColor * (1.0 - greenFilter.a) + greenFilter * greenFilter.a;
                // 保持原始Alpha值
                gl_FragColor.a = originalColor.a;
            }
        """

        // 全屏四边形顶点坐标
        private val VERTEX_COORDINATES = floatArrayOf(
            -1.0f, 1.0f,
            -1.0f, -1.0f,
            1.0f, 1.0f,
            1.0f, -1.0f
        )

        // 效果画面顶点坐标（右下角1/3大小）
        private val EFFECT_VERTEX_COORDINATES = floatArrayOf(
            0.3333f, -0.3333f,   // 左下角
            0.3333f, -1.0f,       // 右下角
            1.0f, -0.3333f,       // 左上角
            1.0f, -1.0f           // 右上角
        )

        // 前置摄像头纹理坐标（已处理镜像）
        private val FRONT_CAMERA_TEXTURE_COORDINATES = floatArrayOf(
            0.0f, 1.0f,  // 左上角对应纹理左下角（解决旋转和镜像）
            0.0f, 0.0f,  // 左下角对应纹理左上角（解决旋转和镜像）
            1.0f, 1.0f,  // 右上角对应纹理右下角（解决旋转和镜像）
            1.0f, 0.0f   // 右下角对应纹理右上角（解决旋转和镜像）
        )
        
        // 后置摄像头纹理坐标（解决上下镜像问题）
        private val REAR_CAMERA_TEXTURE_COORDINATES = floatArrayOf(
            0.0f, 1.0f,  // 左上角对应纹理左下角（解决旋转）
            0.0f, 0.0f,  // 左下角对应纹理左上角（解决旋转）
            1.0f, 1.0f,  // 右上角对应纹理右下角（解决旋转）
            1.0f, 0.0f   // 右下角对应纹理右上角（解决旋转）
        )
    }
    
    // 回调管理
    private var surfaceTextureCallback: SurfaceTextureRenderer.SurfaceTextureCallback? = null
    
    // 相机状态管理
    @Volatile
    var isCameraReady = false
        set(value) {
            field = value
            // 当相机准备状态变化时，重置frameAvailable标志
            if (!value) {
                synchronized(frameAvailableLock) {
                    frameAvailable = false
                }
            }
        }
    
    @Volatile
    private var saveFrameFlag = false
    
    // 相机配置
    private var cameraPreviewWidth = DEFAULT_PREVIEW_WIDTH
    private var cameraPreviewHeight = DEFAULT_PREVIEW_HEIGHT
    private var currentCameraLens = CameraCharacteristics.LENS_FACING_FRONT
    
    // 视口管理
    private var actualViewportWidth = 0
    private var actualViewportHeight = 0
    private var actualViewportX = 0
    private var actualViewportY = 0
    
    // Debug功能管理
    private val debugImageCounter = AtomicInteger(0)
    private val debugImageDir: File
    private val saveTimer = Executors.newSingleThreadScheduledExecutor()
    private var isTimerRunning = false
    
    // OpenGL相关资源
    private var surfaceTexture: SurfaceTexture? = null
    private var externalTextureId = 0
    // 离屏渲染相关资源
    private var framebufferId = 0
    private var offscreenTextureId = 0
    // 原始画面着色器程序
    private var originalProgramId = 0
    private var originalPositionHandle = 0
    private var originalTexCoordHandle = 0
    private var originalMvpMatrixHandle = 0
    private var originalStMatrixHandle = 0
    private var originalTextureHandle = 0
    // 效果画面着色器程序
    private var filteredProgramId = 0
    private var filteredPositionHandle = 0
    private var filteredTexCoordHandle = 0
    private var filteredMvpMatrixHandle = 0
    private var filteredStMatrixHandle = 0
    private var filteredTextureHandle = 0
    // 最终合成着色器程序
    private var composeProgramId = 0
    private var composePositionHandle = 0
    private var composeTexCoordHandle = 0
    private var composeMvpMatrixHandle = 0
    private var composeTextureHandle = 0
    
    // OpenGL矩阵和缓冲区
    private val vertexBuffer: FloatBuffer
    private val effectVertexBuffer: FloatBuffer
    private val frontTexCoordBuffer: FloatBuffer
    private val rearTexCoordBuffer: FloatBuffer
    private val mvpMatrix = FloatArray(16)
    private val stMatrix = FloatArray(16)
    
    // 线程同步
    private var frameAvailable = false
    private val frameAvailableLock = Object()
    
    init {
        // 初始化OpenGL矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.setIdentityM(stMatrix, 0)
        
        // 初始化顶点和纹理坐标缓冲区
        vertexBuffer = createFloatBuffer(VERTEX_COORDINATES)
        effectVertexBuffer = createFloatBuffer(EFFECT_VERTEX_COORDINATES)
        frontTexCoordBuffer = createFloatBuffer(FRONT_CAMERA_TEXTURE_COORDINATES)
        rearTexCoordBuffer = createFloatBuffer(REAR_CAMERA_TEXTURE_COORDINATES)
        
        // 初始化debug图片保存目录
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        debugImageDir = File(externalDir, DEBUG_IMAGE_DIR_NAME)
        if (!debugImageDir.exists()) {
            debugImageDir.mkdirs()
        }
    }
    
    /**
     * 创建FloatBuffer从float数组
     */
    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(array)
                position(0)
            }
    }
    
    override fun setSurfaceTextureCallback(callback: SurfaceTextureRenderer.SurfaceTextureCallback) {
        this.surfaceTextureCallback = callback
    }
    
    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")

        // 设置背景颜色为黑色
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 创建和配置外部纹理
        createAndConfigureExternalTexture()
        
        // 创建离屏渲染资源
        createOffscreenRenderingResources()
        
        // 创建SurfaceTexture
        surfaceTexture = SurfaceTexture(externalTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        
        // 通知SurfaceTexture已创建完成
        surfaceTexture?.let {
            surfaceTextureCallback?.onSurfaceTextureAvailable(it)
        }

        // 创建和链接着色器程序
        createAndLinkShaderPrograms()
        
        // 获取着色器属性和统一变量位置
        getShaderHandles()
    }
    
    /**
     * 创建并配置外部纹理
     */
    private fun createAndConfigureExternalTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        externalTextureId = textures[0]
        
        // 绑定并配置外部纹理
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    
    /**
     * 创建离屏渲染资源
     */
    private fun createOffscreenRenderingResources() {
        // 创建离屏纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        offscreenTextureId = textures[0]
        
        // 配置离屏纹理
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offscreenTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            cameraPreviewWidth,
            cameraPreviewHeight,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 创建帧缓冲区对象
        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]
        
        // 绑定帧缓冲区并附加纹理
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            offscreenTextureId,
            0
        )
        
        // 检查帧缓冲区是否完整
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer is not complete: $status")
        }
        
        // 解绑帧缓冲区
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
    
    /**
     * 创建并链接着色器程序
     */
    private fun createAndLinkShaderPrograms() {
        // 创建原始画面着色器程序
        createAndLinkShaderProgram(true)
        // 创建效果画面着色器程序
        createAndLinkShaderProgram(false)
        // 创建最终合成着色器程序
        createComposeShaderProgram()
    }
    
    /**
     * 创建并链接着色器程序
     * 
     * @param isOriginal true表示创建原始画面着色器程序，false表示创建效果画面着色器程序
     */
    private fun createAndLinkShaderProgram(isOriginal: Boolean) {
        val fragmentShaderCode = if (isOriginal) FRAGMENT_SHADER_ORIGINAL else FRAGMENT_SHADER_FILTERED
        
        val vertexShaderId = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShaderId = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        val programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShaderId)
        GLES20.glAttachShader(programId, fragmentShaderId)
        GLES20.glLinkProgram(programId)
        
        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(programId))
            GLES20.glDeleteProgram(programId)
            return
        }
        
        // 保存程序ID
        if (isOriginal) {
            originalProgramId = programId
        } else {
            filteredProgramId = programId
        }
    }
    
    /**
     * 创建最终合成着色器程序
     */
    private fun createComposeShaderProgram() {
        // 最终合成顶点着色器
        val composeVertexShader = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """
        
        // 最终合成片段着色器
        val composeFragmentShader = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
        
        val vertexShaderId = loadShader(GLES20.GL_VERTEX_SHADER, composeVertexShader)
        val fragmentShaderId = loadShader(GLES20.GL_FRAGMENT_SHADER, composeFragmentShader)
        
        composeProgramId = GLES20.glCreateProgram()
        GLES20.glAttachShader(composeProgramId, vertexShaderId)
        GLES20.glAttachShader(composeProgramId, fragmentShaderId)
        GLES20.glLinkProgram(composeProgramId)
        
        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(composeProgramId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link compose program: " + GLES20.glGetProgramInfoLog(composeProgramId))
            GLES20.glDeleteProgram(composeProgramId)
            composeProgramId = 0
        }
    }
    
    /**
     * 获取着色器属性和统一变量位置
     */
    private fun getShaderHandles() {
        // 获取原始画面着色器程序的句柄
        originalPositionHandle = GLES20.glGetAttribLocation(originalProgramId, "aPosition")
        originalTexCoordHandle = GLES20.glGetAttribLocation(originalProgramId, "aTexCoord")
        originalMvpMatrixHandle = GLES20.glGetUniformLocation(originalProgramId, "uMVPMatrix")
        originalStMatrixHandle = GLES20.glGetUniformLocation(originalProgramId, "uSTMatrix")
        originalTextureHandle = GLES20.glGetUniformLocation(originalProgramId, "sTexture")
        
        // 获取效果画面着色器程序的句柄
        filteredPositionHandle = GLES20.glGetAttribLocation(filteredProgramId, "aPosition")
        filteredTexCoordHandle = GLES20.glGetAttribLocation(filteredProgramId, "aTexCoord")
        filteredMvpMatrixHandle = GLES20.glGetUniformLocation(filteredProgramId, "uMVPMatrix")
        filteredStMatrixHandle = GLES20.glGetUniformLocation(filteredProgramId, "uSTMatrix")
        filteredTextureHandle = GLES20.glGetUniformLocation(filteredProgramId, "sTexture")
        
        // 获取最终合成着色器程序的句柄
        composePositionHandle = GLES20.glGetAttribLocation(composeProgramId, "aPosition")
        composeTexCoordHandle = GLES20.glGetAttribLocation(composeProgramId, "aTexCoord")
        composeMvpMatrixHandle = GLES20.glGetUniformLocation(composeProgramId, "uMVPMatrix")
        composeTextureHandle = GLES20.glGetUniformLocation(composeProgramId, "sTexture")
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        
        // 重置MVP矩阵，不做任何缩放变换
        Matrix.setIdentityM(mvpMatrix, 0)
        
        // 计算符合目标宽高比的视口尺寸
        calculateAndSetViewport(width, height)
        
        // 更新离屏纹理尺寸
        updateOffscreenTextureSize(width, height)
        
        // 启动定时保存图片的任务
        startDebugSaveTask()
    }
    
    /**
     * 更新离屏纹理尺寸
     */
    private fun updateOffscreenTextureSize(width: Int, height: Int) {
        // 绑定离屏纹理并更新尺寸
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offscreenTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        )
    }
    
    /**
     * 计算并设置符合目标宽高比的视口
     * 
     * @param width 屏幕宽度
     * @param height 屏幕高度
     */
    private fun calculateAndSetViewport(width: Int, height: Int) {
        var viewportWidth: Int
        var viewportHeight: Int
        var viewportX: Int
        var viewportY: Int
        
        if (width.toFloat() / height.toFloat() > TARGET_ASPECT_RATIO) {
            // 屏幕更宽，视口高度等于屏幕高度，计算视口宽度
            viewportHeight = height
            viewportWidth = (height * TARGET_ASPECT_RATIO).toInt()
            // 水平居中
            viewportX = (width - viewportWidth) / 2
            viewportY = 0
        } else {
            // 屏幕更高或相同，视口宽度等于屏幕宽度，计算视口高度
            viewportWidth = width
            viewportHeight = (width / TARGET_ASPECT_RATIO).toInt()
            // 垂直居中
            viewportX = 0
            viewportY = (height - viewportHeight) / 2
        }
        
        // 保存实际的视口尺寸
        actualViewportWidth = viewportWidth
        actualViewportHeight = viewportHeight
        actualViewportX = viewportX
        actualViewportY = viewportY
        
        // 设置视口，实现目标比例居中显示
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
    }
    
    /**
     * 启动定时保存图片的任务
     * 
     * 每隔指定时间（默认5秒）保存一帧相机预览到文件，最多保存5张
     */
    private fun startDebugSaveTask() {
        if (!isTimerRunning) {
            saveTimer.scheduleAtFixedRate({
                // 设置保存帧标志，由onDrawFrame在GL线程中处理
                saveFrameFlag = true
            }, 0, DEBUG_SAVE_INTERVAL, TimeUnit.SECONDS)
            isTimerRunning = true
        }
    }
    
    /**
     * 保存当前帧到文件 - 在GL线程中执行
     * 
     * 读取当前视口内的像素数据，转换为Bitmap并保存到外部存储
     */
    private fun saveCurrentFrameToFile() {
        // 检查是否已达到最大保存数量
        if (debugImageCounter.get() >= MAX_DEBUG_IMAGES) {
            Log.d(TAG, "已达到最大保存数量，停止保存")
            saveTimer.shutdown()
            isTimerRunning = false
            return
        }
        
        try {
            // 读取视口内的像素数据，注意使用视口的实际位置和尺寸
            val pixelBuffer = ByteBuffer.allocateDirect(actualViewportWidth * actualViewportHeight * 4)
            pixelBuffer.order(ByteOrder.nativeOrder()) // 使用本地字节顺序，避免字节序问题
            GLES20.glReadPixels(actualViewportX, actualViewportY, actualViewportWidth, actualViewportHeight, 
                              GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
            
            // 将像素数据转换为Bitmap
            pixelBuffer.rewind()
            val bitmap = Bitmap.createBitmap(actualViewportWidth, actualViewportHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixelBuffer)
            
            // 使用单独的线程保存文件，避免阻塞GL线程
            Executors.newSingleThreadExecutor().execute {
                try {
                    // 创建保存目录
                    if (!debugImageDir.exists()) {
                        debugImageDir.mkdirs()
                    }
                    
                    // 生成文件名：num_width_height.png，使用相机预览尺寸
                    val imageNum = debugImageCounter.incrementAndGet()
                    val fileName = "${imageNum}_${cameraPreviewWidth}_${cameraPreviewHeight}.png"
                    val file = File(debugImageDir, fileName)
                    
                    // 保存Bitmap到文件
                    val fos = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.flush()
                    fos.close()
                    
                    Log.d(TAG, "成功保存图片: ${file.absolutePath}")
                    
                    // 释放Bitmap资源
                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "保存图片文件失败: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败: ${e.message}", e)
        }
    }

    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        // 清除颜色缓冲区
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 同步更新SurfaceTexture纹理
        updateTextureIfAvailable()
        
        // 执行离屏渲染
        performOffscreenRendering()
        
        // 绘制最终合成画面
        drawComposeResult()
        
        // 检查是否需要保存当前帧
        if (saveFrameFlag && isCameraReady) {
            saveFrameFlag = false
            saveCurrentFrameToFile()
        }
    }
    
    /**
     * 同步更新SurfaceTexture纹理内容
     */
    private fun updateTextureIfAvailable() {
        synchronized(frameAvailableLock) {
            if (frameAvailable && isCameraReady) {
                try {
                    surfaceTexture?.updateTexImage() // 更新纹理内容
                    surfaceTexture?.getTransformMatrix(stMatrix) // 获取纹理变换矩阵
                    frameAvailable = false
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Error updating texture: ${e.message}", e)
                    // 如果更新纹理失败，不重置frameAvailable标志，继续等待有效帧
                }
            }
        }
    }
    
    /**
     * 执行离屏渲染
     */
    private fun performOffscreenRendering() {
        // 绑定离屏帧缓冲区
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebufferId)
        
        // 设置视口为离屏纹理大小
        GLES20.glViewport(0, 0, actualViewportWidth, actualViewportHeight)
        
        // 清除离屏缓冲区
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // 绘制原始相机预览画面
        drawOriginalPreview()
        
        // 绘制效果画面（右下角1/3大小，带绿色滤镜）
        drawEffectPreview()
        
        // 解绑帧缓冲区，恢复到默认帧缓冲区
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }
    
    /**
     * 绘制原始相机预览画面
     */
    private fun drawOriginalPreview() {
        // 使用原始画面着色器程序
        GLES20.glUseProgram(originalProgramId)

        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(originalPositionHandle)
        GLES20.glEnableVertexAttribArray(originalTexCoordHandle)

        // 设置顶点坐标
        GLES20.glVertexAttribPointer(originalPositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        
        // 根据摄像头方向选择合适的纹理坐标缓冲区
        val texCoordBuffer = if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) {
            frontTexCoordBuffer
        } else {
            rearTexCoordBuffer
        }
        GLES20.glVertexAttribPointer(originalTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // 设置MVP矩阵和纹理变换矩阵
        GLES20.glUniformMatrix4fv(originalMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(originalStMatrixHandle, 1, false, stMatrix, 0)

        // 激活纹理单元并绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(originalTextureHandle, 0) // 将纹理单元0绑定到采样器

        // 绘制四边形，使用三角形条带模式
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(originalPositionHandle)
        GLES20.glDisableVertexAttribArray(originalTexCoordHandle)
    }
    
    /**
     * 绘制效果画面（右下角1/3大小，带绿色滤镜）
     */
    private fun drawEffectPreview() {
        // 使用效果画面着色器程序
        GLES20.glUseProgram(filteredProgramId)

        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(filteredPositionHandle)
        GLES20.glEnableVertexAttribArray(filteredTexCoordHandle)

        // 设置效果画面顶点坐标（右下角1/3大小）
        GLES20.glVertexAttribPointer(filteredPositionHandle, 2, GLES20.GL_FLOAT, false, 8, effectVertexBuffer)
        
        // 根据摄像头方向选择合适的纹理坐标缓冲区
        val texCoordBuffer = if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) {
            frontTexCoordBuffer
        } else {
            rearTexCoordBuffer
        }
        GLES20.glVertexAttribPointer(filteredTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // 设置MVP矩阵和纹理变换矩阵
        GLES20.glUniformMatrix4fv(filteredMvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(filteredStMatrixHandle, 1, false, stMatrix, 0)

        // 激活纹理单元并绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(filteredTextureHandle, 0) // 将纹理单元0绑定到采样器

        // 绘制四边形，使用三角形条带模式
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(filteredPositionHandle)
        GLES20.glDisableVertexAttribArray(filteredTexCoordHandle)
    }
    
    /**
     * 绘制最终合成画面
     */
    private fun drawComposeResult() {
        // 恢复原始视口
        GLES20.glViewport(actualViewportX, actualViewportY, actualViewportWidth, actualViewportHeight)
        
        // 使用最终合成着色器程序
        GLES20.glUseProgram(composeProgramId)

        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(composePositionHandle)
        GLES20.glEnableVertexAttribArray(composeTexCoordHandle)

        // 设置顶点坐标
        GLES20.glVertexAttribPointer(composePositionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        
        // 设置全屏纹理坐标（0-1范围）
        val fullscreenTexCoords = floatArrayOf(
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )
        val texCoordBuffer = createFloatBuffer(fullscreenTexCoords)
        GLES20.glVertexAttribPointer(composeTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(composeMvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 激活纹理单元并绑定离屏纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, offscreenTextureId)
        GLES20.glUniform1i(composeTextureHandle, 0) // 将纹理单元0绑定到采样器

        // 绘制四边形，使用三角形条带模式
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(composePositionHandle)
        GLES20.glDisableVertexAttribArray(composeTexCoordHandle)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        // 只有当相机准备好时，才设置frameAvailable标志
        if (isCameraReady) {
            synchronized(frameAvailableLock) {
                frameAvailable = true
            }
        }
    }
    
    /**
     * 加载和编译着色器
     * 
     * @param type 着色器类型，如GLES20.GL_VERTEX_SHADER或GLES20.GL_FRAGMENT_SHADER
     * @param shaderCode 着色器源代码
     * @return 编译后的着色器ID，或0表示编译失败
     */
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type) // 创建着色器对象
        GLES20.glShaderSource(shader, shaderCode) // 设置着色器源代码
        GLES20.glCompileShader(shader) // 编译着色器

        // 检查编译状态
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not compile shader $type: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    override fun getSurfaceTexture(): SurfaceTexture? {
        return surfaceTexture
    }

    override fun getTextureId(): Int {
        return externalTextureId
    }
    
    override fun setPreviewSize(width: Int, height: Int) {
        cameraPreviewWidth = width
        cameraPreviewHeight = height
        Log.d(TAG, "设置相机预览尺寸: $width x $height")
    }
    
    override fun setCameraLensFacing(lensFacing: Int) {
        currentCameraLens = lensFacing
        Log.d(TAG, "设置摄像头方向: ${if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}")
    }

    override fun setCameraActive(active: Boolean) {
        isCameraReady = active
    }

    override fun release() {
        // 释放SurfaceTexture资源
        surfaceTexture?.release()
        surfaceTexture = null
        
        // 停止定时保存任务
        if (isTimerRunning) {
            saveTimer.shutdownNow()
            isTimerRunning = false
        }
        
        // 重置相机准备状态
        isCameraReady = false
        
        // 释放OpenGL纹理资源
        if (externalTextureId != 0) {
            val textures = intArrayOf(externalTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            externalTextureId = 0
        }
        
        // 释放离屏渲染资源
        if (offscreenTextureId != 0) {
            val textures = intArrayOf(offscreenTextureId)
            GLES20.glDeleteTextures(1, textures, 0)
            offscreenTextureId = 0
        }
        
        if (framebufferId != 0) {
            val framebuffers = intArrayOf(framebufferId)
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            framebufferId = 0
        }
        
        // 释放OpenGL程序资源
        if (originalProgramId != 0) {
            GLES20.glDeleteProgram(originalProgramId)
            originalProgramId = 0
        }
        if (filteredProgramId != 0) {
            GLES20.glDeleteProgram(filteredProgramId)
            filteredProgramId = 0
        }
        if (composeProgramId != 0) {
            GLES20.glDeleteProgram(composeProgramId)
            composeProgramId = 0
        }
        
        Log.d(TAG, "资源释放完成")
    }
}
