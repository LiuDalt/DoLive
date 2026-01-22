package com.example.cameraapp.capture

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics

/**
 * 相机捕获抽象接口
 * 
 * 定义了相机捕获的基本行为，包括启动、停止、设置回调等
 */
interface CameraCapture {
    /**
     * 启动相机预览
     */
    fun start()
    
    /**
     * 停止相机预览
     */
    fun stop()
    
    /**
     * 设置相机回调
     * 
     * @param callback 相机回调实例
     */
    fun setCameraCallback(callback: CameraCallback)
    
    /**
     * 相机回调接口
     */
    interface CameraCallback {
        /**
         * 相机打开成功回调
         */
        fun onCameraOpened()
        
        /**
         * 相机关闭回调
         */
        fun onCameraClosed()
        
        /**
         * 相机错误回调
         * 
         * @param error 错误信息
         */
        fun onError(error: String)
        
        /**
         * 预览尺寸选择完成回调
         * 
         * @param width 预览宽度
         * @param height 预览高度
         */
        fun onPreviewSizeSelected(width: Int, height: Int)
        
        /**
         * 摄像头方向变化回调
         * 
         * @param lensFacing 摄像头方向，如CameraCharacteristics.LENS_FACING_FRONT或LENS_FACING_BACK
         */
        fun onCameraLensFacingChanged(lensFacing: Int)
    }
}

/**
 * SurfaceTexture相机捕获抽象接口
 * 
 * 扩展自CameraCapture，增加了SurfaceTexture相关的功能
 */
interface SurfaceTextureCapture : CameraCapture {
    /**
     * 获取SurfaceTexture实例
     * 
     * @return SurfaceTexture实例，或null表示尚未创建
     */
    fun getSurfaceTexture(): SurfaceTexture?
}

/**
 * YUV相机捕获抽象接口
 * 
 * 扩展自CameraCapture，增加了YUV数据处理相关的功能
 */
interface YUVCapture : CameraCapture {
    /**
     * 设置YUV数据回调
     * 
     * @param callback YUV数据回调实例
     */
    fun setYUVDataCallback(callback: YUVDataCallback)
    
    /**
     * YUV数据回调接口
     */
    interface YUVDataCallback {
        /**
         * YUV数据可用回调
         * 
         * @param yData Y通道数据
         * @param uData U通道数据
         * @param vData V通道数据
         * @param width 图像宽度
         * @param height 图像高度
         */
        fun onYUVDataAvailable(yData: ByteArray, uData: ByteArray, vData: ByteArray, width: Int, height: Int)
    }
}

/**
 * 相机捕获工厂类
 * 
 * 根据输出类型创建对应的相机捕获实例
 */
class CameraCaptureFactory {
    companion object {
        /**
         * 创建SurfaceTexture相机捕获实例
         * 
         * @param context 上下文
         * @param surfaceTexture SurfaceTexture实例，用于接收相机预览帧
         * @param cameraLensFacing 相机镜头方向，如CameraCharacteristics.LENS_FACING_FRONT或LENS_FACING_BACK
         * @return SurfaceTextureCapture实例
         */
        fun createSurfaceTextureCapture(
            context: Context,
            surfaceTexture: SurfaceTexture,
            cameraLensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
        ): SurfaceTextureCapture {
            return CameraCaptureSurfaceTexture(context, surfaceTexture, cameraLensFacing)
        }
        
        /**
         * 创建YUV相机捕获实例
         * 
         * @param context 上下文
         * @param cameraLensFacing 相机镜头方向，如CameraCharacteristics.LENS_FACING_FRONT或LENS_FACING_BACK
         * @return YUVCapture实例
         */
        fun createYUVCapture(
            context: Context,
            cameraLensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
        ): YUVCapture {
            return CameraCaptureYuv(context, cameraLensFacing)
        }
    }
}