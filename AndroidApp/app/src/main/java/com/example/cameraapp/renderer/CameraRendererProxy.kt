package com.example.cameraapp.renderer

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.opengl.GLSurfaceView
import android.util.Log

/**
 * 相机渲染器代理类
 * 
 * 用于管理不同的相机渲染器，支持动态切换渲染器类型
 * 
 * @param context 上下文
 */
class CameraRendererProxy(private val context: android.content.Context) : CameraRenderer, SurfaceTextureRenderer.SurfaceTextureCallback {
    companion object {
        private const val TAG = "CameraRendererProxy"
    }
    
    // 当前渲染器类型
    private var currentOutputType = RendererFactory.OutputType.SURFACE_TEXTURE
    
    // 当前渲染器实例
    private var currentRenderer: CameraRenderer? = null
    
    // SurfaceTexture回调
    private var surfaceTextureCallback: SurfaceTextureRenderer.SurfaceTextureCallback? = null
    
    // 相机准备标志
    private var isCameraReady = false
    
    init {
        // 初始化为SurfaceTexture渲染器
        currentRenderer = RendererFactory.createRenderer(context, currentOutputType)
        if (currentRenderer is SurfaceTextureRenderer) {
            (currentRenderer as SurfaceTextureRenderer).setSurfaceTextureCallback(this)
        }
    }
    
    /**
     * 初始化渲染器，使用指定的输出类型
     * 
     * @param outputType 输出类型
     */
    fun initializeRenderer(outputType: RendererFactory.OutputType) {
        Log.d(TAG, "初始化渲染器，使用输出类型: $outputType")
        
        // 保存当前相机准备状态
        val cameraReady = isCameraReady
        
        // 确保当前渲染器不再处理帧
        setCameraActive(false)
        
        // 释放当前渲染器资源
        currentRenderer?.release()
        
        // 创建新的渲染器
        currentOutputType = outputType
        currentRenderer = RendererFactory.createRenderer(context, currentOutputType)
        
        // 设置SurfaceTexture回调
        if (currentRenderer is SurfaceTextureRenderer) {
            (currentRenderer as SurfaceTextureRenderer).setSurfaceTextureCallback(this)
        }
        
        // 恢复相机准备状态
        setCameraActive(cameraReady)
        
        Log.d(TAG, "渲染器初始化完成")
    }
    
    /**
     * 切换渲染器类型
     * 
     * @param outputType 输出类型
     */
    fun switchOutputType(outputType: RendererFactory.OutputType) {
        Log.d(TAG, "切换渲染器类型: $outputType")
        
        // 如果类型相同，不做任何操作
        if (currentOutputType == outputType) {
            Log.d(TAG, "当前渲染器类型与目标类型相同，无需切换")
            return
        }
        
        // 确保当前渲染器不再处理帧
        setCameraActive(false)
        
        // 释放当前渲染器资源
        currentRenderer?.release()
        
        // 创建新的渲染器
        currentOutputType = outputType
        currentRenderer = RendererFactory.createRenderer(context, currentOutputType)
        
        // 设置SurfaceTexture回调
        if (currentRenderer is SurfaceTextureRenderer) {
            (currentRenderer as SurfaceTextureRenderer).setSurfaceTextureCallback(this)
        }
        
        // 恢复相机准备状态
        setCameraActive(isCameraReady)
        
        Log.d(TAG, "渲染器类型切换完成")
    }
    
    /**
     * 设置SurfaceTexture回调
     * 
     * @param callback 回调实例
     */
    fun setSurfaceTextureCallback(callback: SurfaceTextureRenderer.SurfaceTextureCallback) {
        this.surfaceTextureCallback = callback
    }
    
    /**
     * 获取当前SurfaceTexture渲染器
     * 
     * @return SurfaceTextureRenderer实例，或null如果当前不是SurfaceTexture渲染器
     */
    fun getSurfaceTextureRenderer(): SurfaceTextureRenderer? {
        return currentRenderer as? SurfaceTextureRenderer
    }
    
    /**
     * 获取当前YUV渲染器
     * 
     * @return CameraRenderYuv实例，或null如果当前不是YUV渲染器
     */
    fun getYuvRenderer(): CameraRenderYuv? {
        return currentRenderer as? CameraRenderYuv
    }
    
    /**
     * 设置相机活跃状态
     * 
     * @param active 相机是否活跃，true表示可以开始渲染，false表示暂停渲染
     */
    override fun setCameraActive(active: Boolean) {
        isCameraReady = active
        currentRenderer?.setCameraActive(active)
    }
    
    // ------------------ SurfaceTextureCallback实现 ------------------
    
    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture) {
        Log.d(TAG, "SurfaceTexture可用，转发给外部回调")
        surfaceTextureCallback?.onSurfaceTextureAvailable(surfaceTexture)
    }
    
    // ------------------ CameraRenderer实现 ------------------
    
    override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")
        currentRenderer?.onSurfaceCreated(gl, config)
    }
    
    override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: width=$width, height=$height")
        currentRenderer?.onSurfaceChanged(gl, width, height)
    }
    
    override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        currentRenderer?.onDrawFrame(gl)
    }
    
    override fun setPreviewSize(width: Int, height: Int) {
        Log.d(TAG, "设置预览尺寸: $width x $height")
        currentRenderer?.setPreviewSize(width, height)
    }
    
    override fun setCameraLensFacing(lensFacing: Int) {
        Log.d(TAG, "设置摄像头方向: ${if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}")
        currentRenderer?.setCameraLensFacing(lensFacing)
    }
    
    override fun release() {
        Log.d(TAG, "释放渲染器资源")
        currentRenderer?.release()
    }
}