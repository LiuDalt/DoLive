package com.example.cameraapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.opengl.GLSurfaceView
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.cameraapp.capture.CameraCapture
import com.example.cameraapp.capture.CameraCaptureFactory
import com.example.cameraapp.capture.YUVCapture
import com.example.cameraapp.renderer.CameraRenderer
import com.example.cameraapp.renderer.CameraRendererProxy
import com.example.cameraapp.renderer.RendererFactory
import com.example.cameraapp.renderer.SurfaceTextureRenderer

/**
 * 相机预览活动类，负责显示相机预览界面
 * 
 * 设计模式应用：
 * - 策略模式：通过PreviewType枚举支持不同的预览策略（SurfaceTexture和YUV）
 * - 工厂模式：使用RendererFactory和CameraCaptureFactory创建具体的渲染器和相机捕获实例
 * - 代理模式：通过CameraRendererProxy管理不同渲染器的生命周期和切换
 * 
 * 核心职责：
 * - 处理相机权限请求和管理
 * - 初始化和管理GLSurfaceView
 * - 处理相机预览的启动、停止和切换
 * - 管理不同预览类型的渲染器
 * - 处理摄像头切换逻辑
 */
class PreviewActivity : AppCompatActivity(), SurfaceTextureRenderer.SurfaceTextureCallback, YUVCapture.YUVDataCallback {
    companion object {
        private const val TAG = "PreviewActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        const val EXTRA_PREVIEW_TYPE = "preview_type"
    }

    // 预览类型枚举
    enum class PreviewType {
        SURFACE_TEXTURE,
        YUV
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var cameraRenderer: CameraRenderer
    private lateinit var switchCameraButton: Button
    private var cameraCapture: CameraCapture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var hasCameraPermission = false
    private var currentCameraLens = CameraCharacteristics.LENS_FACING_FRONT // 当前使用的摄像头方向
    private var currentPreviewType = PreviewType.YUV // 当前预览类型

    /**
     * 活动创建时调用，初始化界面和权限检查
     *
     * @param savedInstanceState 保存的实例状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        // 获取传入的预览类型
        val previewTypeStr = intent.getStringExtra(EXTRA_PREVIEW_TYPE) ?: PreviewType.YUV.name
        currentPreviewType = PreviewType.valueOf(previewTypeStr)

        // 初始化GLSurfaceView
        glSurfaceView = findViewById(R.id.preview_view)
        setupGLSurfaceView()

        // 初始化切换摄像头按钮
        switchCameraButton = findViewById(R.id.switch_camera_button)
        switchCameraButton.setOnClickListener {
            // 切换摄像头
            switchCamera()
        }

        // 检查权限
        hasCameraPermission = allPermissionsGranted()
        if (!hasCameraPermission) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
        // 注意：不再在这里直接调用startCamera()，而是等待SurfaceTexture可用
    }

    /**
     * 配置GLSurfaceView
     *
     * 设置EGL上下文版本、渲染器和渲染模式
     */
    private fun setupGLSurfaceView() {
        // 配置GLSurfaceView使用OpenGL ES 2.0
        glSurfaceView.setEGLContextClientVersion(2)
        
        // 使用渲染器代理类，只设置一次
        cameraRenderer = CameraRendererProxy(this)
        if (cameraRenderer is CameraRendererProxy) {
            (cameraRenderer as CameraRendererProxy).setSurfaceTextureCallback(this)
            
            // 初始化渲染器，使用当前预览类型
            (cameraRenderer as CameraRendererProxy).initializeRenderer(
                if (currentPreviewType == PreviewType.SURFACE_TEXTURE) {
                    RendererFactory.OutputType.SURFACE_TEXTURE
                } else {
                    RendererFactory.OutputType.YUV
                }
            )
        }
        glSurfaceView.setRenderer(cameraRenderer)
        // 设置渲染模式为持续渲染，确保相机预览流畅
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }

    /**
     * 检查所有必要权限是否已授予
     *
     * @return 如果所有权限已授予则返回true，否则返回false
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 权限请求结果回调
     *
     * @param requestCode 请求码
     * @param permissions 请求的权限列表
     * @param grantResults 权限授予结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            hasCameraPermission = allPermissionsGranted()
            if (!hasCameraPermission) {
                Toast.makeText(this, "权限被拒绝，无法使用相机", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                // 权限获取成功，根据预览类型启动相机
                startCamera(surfaceTexture)
            }
        }
    }

    // SurfaceTexture回调方法，当SurfaceTexture创建完成后调用
    /**
     * SurfaceTexture可用回调，由CameraRenderer调用
     *
     * @param surfaceTexture 可用的SurfaceTexture实例
     */
    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture) {
        Log.d(TAG, "SurfaceTexture可用")
        this.surfaceTexture = surfaceTexture
        // 如果已经获取了相机权限，则启动相机
        if (hasCameraPermission) {
            startCamera(surfaceTexture)
        }
    }
    
    /**
     * 启动相机预览
     *
     * @param surfaceTexture 用于接收相机预览帧的SurfaceTexture，YUV模式下可为null
     */
    private fun startCamera(surfaceTexture: SurfaceTexture? = null) {
        try {
            // 停止并释放旧的相机捕获实例
            cameraCapture?.stop()
            cameraCapture = null
            
            Log.d(TAG, "启动相机，预览类型: $currentPreviewType，摄像头: ${if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}")
            
            // 根据当前预览类型创建不同的相机捕获实例
            cameraCapture = when (currentPreviewType) {
                PreviewType.SURFACE_TEXTURE -> {
                    requireNotNull(surfaceTexture) { "SurfaceTexture模式下SurfaceTexture不能为空" }
                    // 创建SurfaceTexture相机捕获实例
                    CameraCaptureFactory.createSurfaceTextureCapture(
                        this,
                        surfaceTexture,
                        currentCameraLens
                    )
                }
                PreviewType.YUV -> {
                    // 创建YUV相机捕获实例
                    CameraCaptureFactory.createYUVCapture(
                        this,
                        currentCameraLens
                    ).also {
                        // 设置YUV数据回调，用于接收YUV数据
                        it.setYUVDataCallback(this)
                    }
                }
            }
            
            // 设置相机回调
            cameraCapture?.setCameraCallback(object : CameraCapture.CameraCallback {
                /**
                 * 相机打开成功回调
                 */
                override fun onCameraOpened() {
                    Log.d(TAG, "相机已打开")
                    // 设置相机活跃标志，允许渲染器更新纹理
                        cameraRenderer.setCameraActive(true)
                }

                /**
                 * 相机关闭回调
                 */
                override fun onCameraClosed() {
                    Log.d(TAG, "相机已关闭")
                    // 重置相机活跃标志，停止渲染器更新纹理
                    cameraRenderer.setCameraActive(false)
                }

                /**
                 * 相机错误回调
                 *
                 * @param error 错误信息
                 */
                override fun onError(error: String) {
                    Log.e(TAG, "相机错误: $error")
                    runOnUiThread {
                        Toast.makeText(this@PreviewActivity, "相机错误: $error", Toast.LENGTH_SHORT).show()
                    }
                    // 重置相机活跃标志，停止渲染器更新纹理
                    cameraRenderer.setCameraActive(false)
                }

                /**
                 * 预览尺寸选择完成回调
                 *
                 * @param width 预览宽度
                 * @param height 预览高度
                 */
                override fun onPreviewSizeSelected(width: Int, height: Int) {
                    Log.d(TAG, "获取到相机预览尺寸: $width x $height")
                    // 将相机预览尺寸传递给渲染器
                    cameraRenderer.setPreviewSize(width, height)
                }

                /**
                 * 摄像头方向变化回调
                 *
                 * @param lensFacing 摄像头方向，如CameraCharacteristics.LENS_FACING_FRONT或LENS_FACING_BACK
                 */
                override fun onCameraLensFacingChanged(lensFacing: Int) {
                    Log.d(TAG, "摄像头方向变化: ${if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}")
                    // 将摄像头方向传递给渲染器
                    cameraRenderer.setCameraLensFacing(lensFacing)
                }
            })

            // 启动相机预览
            cameraCapture?.start()
        } catch (e: Exception) {
            Log.e(TAG, "启动相机失败: ${e.message}", e)
            Toast.makeText(this, "启动相机失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * YUV数据可用回调，由CameraCapture调用
     *
     * @param yData Y通道数据
     * @param uData U通道数据
     * @param vData V通道数据
     * @param width 图像宽度
     * @param height 图像高度
     */
    override fun onYUVDataAvailable(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int) {
        // 将YUV数据传递给渲染器
        Log.d(TAG, "收到YUV数据: ${width}x${height}")
        
        // 通过代理类获取真正的YUV渲染器
        if (cameraRenderer is CameraRendererProxy) {
            val proxy = cameraRenderer as CameraRendererProxy
            val yuvRenderer = proxy.getYuvRenderer()
            if (yuvRenderer != null) {
                yuvRenderer.updateYUVData(yData, uData, vData, width, height)
                Log.d(TAG, "YUV数据已传递给渲染器")
            } else {
                Log.e(TAG, "无法获取YUV渲染器，当前渲染器类型可能不是YUV")
            }
        }
    }

    /**
     * 活动恢复时调用，恢复GLSurfaceView和相机预览
     */
    override fun onResume() {
        super.onResume()
        // 恢复GLSurfaceView
        glSurfaceView.onResume()
        // 根据预览类型启动相机
        if (hasCameraPermission) {
            startCamera(surfaceTexture)
        }
    }

    /**
     * 活动暂停时调用，暂停GLSurfaceView和相机预览
     */
    override fun onPause() {
        super.onPause()
        // 暂停GLSurfaceView
        glSurfaceView.onPause()
        // 停止相机，避免在后台运行消耗资源
        cameraCapture?.stop()
    }

    /**
     * 切换摄像头
     */
    private fun switchCamera() {
        // 切换摄像头方向
        currentCameraLens = if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        
        Log.d(TAG, "切换到${if (currentCameraLens == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}摄像头")
        
        // 停止当前相机
        cameraCapture?.stop()
        cameraCapture = null
        
        // 重置相机准备标志
        cameraRenderer.setCameraActive(false)
        
        // 根据当前预览类型重新启动相机
        if (hasCameraPermission) {
            startCamera(surfaceTexture)
        }
    }
    
    /**
     * 活动销毁时调用，释放相机和渲染器资源
     */
    override fun onDestroy() {
        super.onDestroy()
        // 停止相机
        cameraCapture?.stop()
        // 释放渲染器资源
        cameraRenderer.release()
    }
}