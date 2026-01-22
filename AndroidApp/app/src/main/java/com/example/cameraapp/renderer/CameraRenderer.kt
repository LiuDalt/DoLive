package com.example.cameraapp.renderer

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.opengl.GLSurfaceView

/**
 * 相机渲染器抽象接口
 * 
 * 定义了相机渲染器的基本行为，包括设置预览尺寸、摄像头方向、相机准备状态等
 */
interface CameraRenderer : GLSurfaceView.Renderer {
    /**
     * 设置相机预览尺寸
     * 
     * @param width 预览宽度
     * @param height 预览高度
     */
    fun setPreviewSize(width: Int, height: Int)
    
    /**
     * 设置当前使用的摄像头方向
     * 
     * @param lensFacing 摄像头方向，如CameraCharacteristics.LENS_FACING_FRONT或LENS_FACING_BACK
     */
    fun setCameraLensFacing(lensFacing: Int)
    
    /**
     * 设置相机活跃状态
     * 
     * @param active 相机是否活跃，true表示可以开始渲染，false表示暂停渲染
     */
    fun setCameraActive(active: Boolean)
    
    /**
     * 释放资源
     */
    fun release()
}

/**
 * SurfaceTexture渲染器抽象接口
 * 
 * 扩展自CameraRenderer，增加了SurfaceTexture相关的功能
 */
interface SurfaceTextureRenderer : CameraRenderer, SurfaceTexture.OnFrameAvailableListener {
    /**
     * 设置SurfaceTexture回调
     * 
     * @param callback 回调实例，用于接收SurfaceTexture可用事件
     */
    fun setSurfaceTextureCallback(callback: SurfaceTextureCallback)
    
    /**
     * 获取SurfaceTexture实例
     * 
     * @return SurfaceTexture实例，或null表示尚未创建
     */
    fun getSurfaceTexture(): SurfaceTexture?
    
    /**
     * 获取纹理ID
     * 
     * @return 纹理ID
     */
    fun getTextureId(): Int
    
    /**
     * SurfaceTexture回调接口
     */
    interface SurfaceTextureCallback {
        /**
         * 当SurfaceTexture可用时调用
         * 
         * @param surfaceTexture 可用的SurfaceTexture实例
         */
        fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture)
    }
}

/**
 * YUV渲染器抽象接口
 * 
 * 扩展自CameraRenderer，增加了YUV数据处理相关的功能
 */
interface YUVRenderer : CameraRenderer {
    /**
     * 更新YUV数据
     * 
     * @param yData Y通道数据
     * @param uData U通道数据
     * @param vData V通道数据
     * @param width 图像宽度
     * @param height 图像高度
     */
    fun updateYUVData(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int)
}

/**
 * 渲染器工厂类
 * 
 * 根据输出类型创建对应的渲染器实例
 */
class RendererFactory {
    companion object {
        /**
         * 创建渲染器实例
         * 
         * @param context 上下文
         * @param outputType 输出类型
         * @return 对应的渲染器实例
         */
        fun createRenderer(context: Context, outputType: OutputType): CameraRenderer {
            return when (outputType) {
                OutputType.SURFACE_TEXTURE -> CameraRenderSurfaceTexture(context)
                OutputType.YUV -> CameraRenderYuv(context)
            }
        }
    }
    
    /**
     * 输出类型枚举
     */
    enum class OutputType {
        SURFACE_TEXTURE,
        YUV
    }
}
