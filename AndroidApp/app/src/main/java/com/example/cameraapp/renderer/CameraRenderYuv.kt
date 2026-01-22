package com.example.cameraapp.renderer

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
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
 * YUV渲染器实现类
 * 
 * 负责处理YUV数据的相机预览渲染
 * 
 * @param context 上下文
 */
class CameraRenderYuv(private val context: Context) : YUVRenderer {
    companion object {
        private const val TAG = "CameraRenderYuv"
        
        // 调试相关常量
        private const val MAX_DEBUG_IMAGES = 5
        private const val DEBUG_SAVE_INTERVAL = 5L // 秒
        private const val DEBUG_IMAGE_DIR_NAME = "temimages"
        
        // 相机相关常量
        private const val DEFAULT_PREVIEW_WIDTH = 720
        private const val DEFAULT_PREVIEW_HEIGHT = 1280
        private const val TARGET_ASPECT_RATIO = 9.0f / 16.0f
        
        // YUV着色器代码
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uMVPMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D yTexture;
            uniform sampler2D uTexture;
            uniform sampler2D vTexture;
            
            void main() {
                float y = texture2D(yTexture, vTexCoord).r;
                float u = texture2D(uTexture, vTexCoord).r - 0.5;
                float v = texture2D(vTexture, vTexCoord).r - 0.5;
                
                // YUV to RGB conversion
                float r = y + 1.402 * v;
                float g = y - 0.34414 * u - 0.71414 * v;
                float b = y + 1.772 * u;
                
                gl_FragColor = vec4(r, g, b, 1.0);
            }
        """

        // 顶点和纹理坐标
        private val VERTEX_COORDINATES = floatArrayOf(
            -1.0f, 1.0f,
            -1.0f, -1.0f,
            1.0f, 1.0f,
            1.0f, -1.0f
        )

        // 前置摄像头纹理坐标（已处理镜像）
        private val FRONT_CAMERA_TEXTURE_COORDINATES = floatArrayOf(
            0.0f, 1.0f,  // 左上角对应纹理左下角（解决旋转和镜像）
            0.0f, 0.0f,  // 左下角对应纹理左上角（解决旋转和镜像）
            1.0f, 1.0f,  // 右上角对应纹理右下角（解决旋转和镜像）
            1.0f, 0.0f   // 右下角对应纹理右上角（解决旋转和镜像）
        )
        
        // 后置摄像头纹理坐标（解决旋转和左右镜像问题）
        private val REAR_CAMERA_TEXTURE_COORDINATES = floatArrayOf(
            1.0f, 1.0f,  // 左上角对应纹理右下角（解决旋转和左右镜像）
            1.0f, 0.0f,  // 左下角对应纹理右上角（解决旋转和左右镜像）
            0.0f, 1.0f,  // 右上角对应纹理左下角（解决旋转和左右镜像）
            0.0f, 0.0f   // 右下角对应纹理左上角（解决旋转和左右镜像）
        )
    }
    
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
    private var programId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpMatrixHandle = 0
    private var yTextureHandle = 0
    private var uTextureHandle = 0
    private var vTextureHandle = 0
    
    // YUV纹理ID
    private var yTextureId = 0
    private var uTextureId = 0
    private var vTextureId = 0
    
    // YUV数据缓冲区
    private var yBuffer: ByteBuffer? = null
    private var uBuffer: ByteBuffer? = null
    private var vBuffer: ByteBuffer? = null
    private val yuvMutex = Object()
    
    // OpenGL矩阵和缓冲区
    private val vertexBuffer: FloatBuffer
    private val frontTexCoordBuffer: FloatBuffer
    private val rearTexCoordBuffer: FloatBuffer
    private val mvpMatrix = FloatArray(16)
    
    // 线程同步
    private var frameAvailable = false
    private val frameAvailableLock = Object()
    
    init {
        // 初始化OpenGL矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
        
        // 初始化顶点和纹理坐标缓冲区
        vertexBuffer = createFloatBuffer(VERTEX_COORDINATES)
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
    
    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")

        // 设置背景颜色为黑色
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // 创建和配置YUV纹理
        createAndConfigureYUVTextures()

        // 创建和链接着色器程序
        createAndLinkShaderProgram()
        
        // 获取着色器属性和统一变量位置
        getShaderHandles()
    }
    
    /**
     * 创建并配置YUV纹理
     */
    private fun createAndConfigureYUVTextures() {
        // 创建Y纹理
        val yTextures = IntArray(1)
        GLES20.glGenTextures(1, yTextures, 0)
        yTextureId = yTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 创建U纹理
        val uTextures = IntArray(1)
        GLES20.glGenTextures(1, uTextures, 0)
        uTextureId = uTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 创建V纹理
        val vTextures = IntArray(1)
        GLES20.glGenTextures(1, vTextures, 0)
        vTextureId = vTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    
    /**
     * 创建并链接着色器程序
     */
    private fun createAndLinkShaderProgram() {
        val vertexShaderId = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShaderId = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShaderId)
        GLES20.glAttachShader(programId, fragmentShaderId)
        GLES20.glLinkProgram(programId)
        
        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(programId))
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }
    
    /**
     * 获取着色器属性和统一变量位置
     */
    private fun getShaderHandles() {
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        yTextureHandle = GLES20.glGetUniformLocation(programId, "yTexture")
        uTextureHandle = GLES20.glGetUniformLocation(programId, "uTexture")
        vTextureHandle = GLES20.glGetUniformLocation(programId, "vTexture")
    }

    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        
        // 重置MVP矩阵
        Matrix.setIdentityM(mvpMatrix, 0)
        
        // 相机传感器通常是横屏的，而手机屏幕是竖屏的，所以需要旋转90°
        // 添加旋转变换，将画面顺时针旋转90°
        Matrix.rotateM(mvpMatrix, 0, 90.0f, 0.0f, 0.0f, 1.0f)
        
        // 计算符合目标宽高比的视口尺寸
        calculateAndSetViewport(width, height)
        
        // 启动定时保存图片的任务
        startDebugSaveTask()
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

        // 绘制YUV预览
        drawPreview()
        
        // 检查是否需要保存当前帧
        if (saveFrameFlag && isCameraReady) {
            saveFrameFlag = false
            saveCurrentFrameToFile()
        }
    }
    
    /**
     * 绘制YUV预览
     */
    private fun drawPreview() {
        // 使用着色器程序
        GLES20.glUseProgram(programId)

        // 启用顶点属性数组
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        // 设置顶点坐标
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)
        
        // 根据摄像头方向选择合适的纹理坐标缓冲区
        val texCoordBuffer = if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) {
            frontTexCoordBuffer
        } else {
            rearTexCoordBuffer
        }
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // 设置MVP矩阵
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 更新并绑定YUV纹理
        synchronized(yuvMutex) {
            yBuffer?.let { yBuf ->
                uBuffer?.let { uBuf ->
                    vBuffer?.let { vBuf ->
                        // 更新Y纹理
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yTextureId)
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D,
                            0,
                            GLES20.GL_LUMINANCE,
                            cameraPreviewWidth,
                            cameraPreviewHeight,
                            0,
                            GLES20.GL_LUMINANCE,
                            GLES20.GL_UNSIGNED_BYTE,
                            yBuf
                        )
                        GLES20.glUniform1i(yTextureHandle, 0)
                        
                        // 更新U纹理
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uTextureId)
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D,
                            0,
                            GLES20.GL_LUMINANCE,
                            cameraPreviewWidth / 2,
                            cameraPreviewHeight / 2,
                            0,
                            GLES20.GL_LUMINANCE,
                            GLES20.GL_UNSIGNED_BYTE,
                            uBuf
                        )
                        GLES20.glUniform1i(uTextureHandle, 1)
                        
                        // 更新V纹理
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vTextureId)
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D,
                            0,
                            GLES20.GL_LUMINANCE,
                            cameraPreviewWidth / 2,
                            cameraPreviewHeight / 2,
                            0,
                            GLES20.GL_LUMINANCE,
                            GLES20.GL_UNSIGNED_BYTE,
                            vBuf
                        )
                        GLES20.glUniform1i(vTextureHandle, 2)
                    }
                }
            }
        }

        // 绘制四边形，使用三角形条带模式
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用顶点属性数组
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
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
    
    override fun updateYUVData(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int) {
        synchronized(yuvMutex) {
            // 保存预览尺寸
            cameraPreviewWidth = width
            cameraPreviewHeight = height
            
            // 更新YUV缓冲区
            yBuffer = ByteBuffer.wrap(yData)
            uBuffer = ByteBuffer.wrap(uData)
            vBuffer = ByteBuffer.wrap(vData)
        }
        
        // 标记新帧可用
        synchronized(frameAvailableLock) {
            frameAvailable = true
        }
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
        // 停止定时保存任务
        if (isTimerRunning) {
            saveTimer.shutdownNow()
            isTimerRunning = false
        }
        
        // 重置相机准备状态
        isCameraReady = false
        
        // 释放YUV数据缓冲区
        synchronized(yuvMutex) {
            yBuffer = null
            uBuffer = null
            vBuffer = null
        }
        
        // 释放OpenGL纹理资源
        if (yTextureId != 0 || uTextureId != 0 || vTextureId != 0) {
            val textures = intArrayOf(yTextureId, uTextureId, vTextureId)
            GLES20.glDeleteTextures(3, textures, 0)
            yTextureId = 0
            uTextureId = 0
            vTextureId = 0
        }
        
        // 释放OpenGL程序资源
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        
        Log.d(TAG, "资源释放完成")
    }
}