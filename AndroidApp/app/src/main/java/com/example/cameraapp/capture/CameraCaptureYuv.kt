package com.example.cameraapp.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * YUV相机捕获实现类
 * 
 * 设计模式应用：
 * - 策略模式：实现YUVCapture接口，作为YUV数据捕获的具体策略
 * - 观察者模式：通过YUVDataCallback将YUV数据传递给渲染器
 * 
 * 核心功能：
 * - 使用Camera2 API初始化和管理相机资源
 * - 设置ImageReader捕获YUV_420_888格式图像
 * - 将Image数据转换为YUV字节数组并传递给回调
 * - 处理相机切换和资源释放
 * 
 * @param context 上下文
 * @param cameraLensFacing 相机镜头方向，如CameraCharacteristics.LENS_FACING_FRONT或LENS_FACING_BACK
 */
class CameraCaptureYuv(
    private val context: Context,
    private val cameraLensFacing: Int = CameraCharacteristics.LENS_FACING_FRONT
) : YUVCapture {
    companion object {
        private const val TAG = "CameraCaptureYuv"
        private const val TARGET_WIDTH = 1280 // 目标预览宽度
        private const val TARGET_HEIGHT = 720 // 目标预览高度
    }

    private var cameraHandler: android.os.Handler? = null
    private var handlerThread: android.os.HandlerThread? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var previewRequest: CaptureRequest? = null
    private var selectedPreviewSize: Size? = null
    
    // YUV相关资源
    private var imageReader: ImageReader? = null
    private var yuvDataCallback: YUVCapture.YUVDataCallback? = null
    private var cameraCallback: CameraCapture.CameraCallback? = null

    override fun setCameraCallback(callback: CameraCapture.CameraCallback) {
        this.cameraCallback = callback
    }

    override fun setYUVDataCallback(callback: YUVCapture.YUVDataCallback) {
        this.yuvDataCallback = callback
    }

    override fun start() {
        // 启动相机线程
        startBackgroundThread()
        
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            // 获取可用相机列表
            val cameraIdList = cameraManager!!.cameraIdList
            if (cameraIdList.isEmpty()) {
                cameraCallback?.onError("No cameras available")
                return
            }

            // 选择指定方向的相机
            for (id in cameraIdList) {
                val cameraCharacteristics = cameraManager!!.getCameraCharacteristics(id)
                val facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing == cameraLensFacing) {
                    cameraId = id
                    break
                }
            }

            if (cameraId == null) {
                // 如果找不到指定方向的相机，使用第一个相机
                cameraId = cameraIdList[0]
                Log.w(TAG, "找不到${if (cameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}摄像头，使用第一个可用摄像头")
            } else {
                Log.d(TAG, "选择了${if (cameraLensFacing == CameraCharacteristics.LENS_FACING_FRONT) "前置" else "后置"}摄像头")
            }

            // 打开相机（使用兼容API 26的方法）
            cameraManager!!.openCamera(cameraId!!, cameraDeviceStateCallback, cameraHandler)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission denied: ${e.message}", e)
            cameraCallback?.onError("Camera permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera: ${e.message}", e)
            cameraCallback?.onError("Failed to start camera: ${e.message}")
        }
    }

    override fun stop() {
        try {
            cameraCaptureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            stopBackgroundThread()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera: ${e.message}", e)
        }
    }

    /**
     * 启动相机后台线程
     */
    private fun startBackgroundThread() {
        handlerThread = android.os.HandlerThread("CameraThread")
        handlerThread?.start()
        cameraHandler = android.os.Handler(handlerThread?.looper!!)
    }

    /**
     * 停止相机后台线程
     */
    private fun stopBackgroundThread() {
        handlerThread?.quitSafely()
        try {
            handlerThread?.join()
            handlerThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread: ${e.message}", e)
        }
    }

    /**
     * 相机设备状态回调
     */
    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened")
            cameraDevice = camera
            try {
                createCameraCaptureSession()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create camera capture session: ${e.message}", e)
                camera.close()
                cameraDevice = null
                cameraCallback?.onError("Failed to create camera capture session: ${e.message}")
                cameraCallback?.onCameraClosed()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.d(TAG, "Camera disconnected")
            camera.close()
            cameraDevice = null
            cameraCallback?.onCameraClosed()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            val errorMessage = when (error) {
                ERROR_CAMERA_IN_USE -> "Camera is in use by another application"
                ERROR_MAX_CAMERAS_IN_USE -> "Maximum number of cameras in use"
                ERROR_CAMERA_DISABLED -> "Camera is disabled"
                ERROR_CAMERA_DEVICE -> "Camera device error"
                ERROR_CAMERA_SERVICE -> "Camera service error"
                else -> "Unknown camera error: $error"
            }
            Log.e(TAG, "Camera error: $errorMessage")
            camera.close()
            cameraDevice = null
            cameraCallback?.onError(errorMessage)
            cameraCallback?.onCameraClosed()
        }
    }

    /**
     * 创建相机捕获会话
     */
    private fun createCameraCaptureSession() {
        try {
            // 创建输出目标列表
            val outputSurfaces = mutableListOf<Surface>()

            // 创建预览请求构建器，使用预览模板
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            // YUV模式：使用ImageReader作为输出目标，接收相机直接输出的YUV数据
            val size = selectedPreviewSize ?: Size(TARGET_WIDTH, TARGET_HEIGHT)
            // 创建ImageReader，使用YUV_420_888格式
            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                // 处理YUV数据
                val image = reader.acquireNextImage()
                image?.let {
                    processYUVImage(it)
                    image.close()
                }
            }, cameraHandler)
            
            val imageReaderSurface = imageReader?.surface
            if (imageReaderSurface != null) {
                outputSurfaces.add(imageReaderSurface)
                previewRequestBuilder!!.addTarget(imageReaderSurface)
                Log.d(TAG, "使用YUV作为输出目标，ImageReader接收相机直接输出的YUV数据")
            }

            // 配置相机参数
            configureCameraParameters()

            // 添加日志，检查相机是否支持YUV格式
            val streamConfigurationMap = cameraManager!!.getCameraCharacteristics(cameraId!!).get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            if (streamConfigurationMap != null) {
                val yuvFormats = streamConfigurationMap.outputFormats.filter { 
                    it == ImageFormat.YUV_420_888 || it == ImageFormat.NV21 || it == ImageFormat.YV12
                }
                Log.d(TAG, "相机支持的YUV格式: $yuvFormats")
                
                // 输出相机支持的尺寸
                val yuvSizes = streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
                Log.d(TAG, "相机支持的YUV尺寸: ${yuvSizes.joinToString { "${it.width}x${it.height}" }}")
            }

            // 日志输出输出目标信息
            Log.d(TAG, "创建相机捕获会话，输出目标数量: ${outputSurfaces.size}")
            for ((index, surface) in outputSurfaces.withIndex()) {
                Log.d(TAG, "输出目标 $index: $surface")
            }

            // 创建相机捕获会话
            cameraDevice!!.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "相机捕获会话配置成功")
                        cameraCaptureSession = session

                        // 创建预览请求
                        previewRequest = previewRequestBuilder?.build()
                        if (previewRequest != null) {
                            // 设置重复预览请求，开始持续预览
                            session.setRepeatingRequest(
                                previewRequest!!,
                                captureCallback,
                                cameraHandler
                            )
                            cameraCallback?.onCameraOpened() // 通知相机已打开
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "相机捕获会话配置失败")
                        session.close()
                        cameraCaptureSession = null
                        cameraCallback?.onError("Failed to configure capture session")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session: ${e.message}", e)
            cameraCallback?.onError("Failed to create capture session: ${e.message}")
        }
    }

    /**
     * 处理YUV图像数据
     * 
     * @param image 相机捕获的YUV图像
     */
    private fun processYUVImage(image: Image) {
        // 在这里处理YUV数据，将其传递给渲染器
        val width = image.width
        val height = image.height
        val format = image.format
        
        Log.d(TAG, "收到YUV图像: ${width}x${height}, 格式: ${format}")
        
        // 提取YUV数据
        val planes = image.planes
        if (planes.size >= 3) {
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]
            
            // 获取YUV数据缓冲区
            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer
            
            // 获取平面的行步长和像素步长
            val yRowStride = yPlane.rowStride
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            
            val yPixelStride = yPlane.pixelStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride
            
            Log.d(TAG, "Y行步长: $yRowStride, 像素步长: $yPixelStride")
            Log.d(TAG, "U行步长: $uRowStride, 像素步长: $uPixelStride")
            Log.d(TAG, "V行步长: $vRowStride, 像素步长: $vPixelStride")
            
            // 分配字节数组，考虑步长
            val yData = ByteArray(width * height)
            val uData = ByteArray((width / 2) * (height / 2))
            val vData = ByteArray((width / 2) * (height / 2))
            
            // 处理Y分量（亮度）
            var yIndex = 0
            for (row in 0 until height) {
                for (col in 0 until width) {
                    yData[yIndex++] = yBuffer[row * yRowStride + col * yPixelStride]
                }
            }
            
            // 处理U分量（色度）
            val uvWidth = width / 2
            val uvHeight = height / 2
            var uIndex = 0
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    uData[uIndex++] = uBuffer[row * uRowStride + col * uPixelStride]
                }
            }
            
            // 处理V分量（色度）
            var vIndex = 0
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    vData[vIndex++] = vBuffer[row * vRowStride + col * vPixelStride]
                }
            }
            
            // 通过回调将YUV数据传递给渲染器
            yuvDataCallback?.onYUVDataAvailable(yData, uData, vData, width, height)
        }
    }

    /**
     * 配置相机参数
     */
    private fun configureCameraParameters() {
        try {
            val cameraCharacteristics = cameraManager!!.getCameraCharacteristics(cameraId!!)
            val streamConfigurationMap: StreamConfigurationMap? = cameraCharacteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            if (streamConfigurationMap == null) {
                Log.e(TAG, "No stream configuration map available")
                return
            }

            // 获取支持的预览尺寸，YUV模式下使用ImageFormat.YUV_420_888格式
            val outputSizes = streamConfigurationMap.getOutputSizes(ImageFormat.YUV_420_888)
            if (outputSizes.isEmpty()) {
                Log.e(TAG, "No YUV output sizes available")
                return
            }

            // 选择1280x720分辨率，如果不可用则选择最接近的尺寸
            var selectedSize: Size? = null
            var minDiff = Int.MAX_VALUE
            
            for (size in outputSizes) {
                Log.d(TAG, "Available size: ${size.width}x${size.height}")
                
                // 检查是否有精确匹配的1280x720尺寸
                if (size.width == TARGET_WIDTH && size.height == TARGET_HEIGHT) {
                    selectedSize = size
                    break
                }
                
                // 计算与目标尺寸的差异，优先考虑宽度匹配
                val diff = Math.abs(size.width - TARGET_WIDTH) + Math.abs(size.height - TARGET_HEIGHT)
                if (diff < minDiff) {
                    minDiff = diff
                    selectedSize = size
                }
            }

            if (selectedSize == null) {
                // 如果找不到任何尺寸，使用第一个可用尺寸
                selectedSize = outputSizes.first()
                Log.w(TAG, "Selected first available size: ${selectedSize.width}x${selectedSize.height}")
            } else {
                Log.d(TAG, "Selected preview size: ${selectedSize.width}x${selectedSize.height}")
            }

            // 获取摄像头方向
            val lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_FRONT
            
            // 设置预览尺寸
            selectedSize?.let {
                selectedPreviewSize = it
                // 通知预览尺寸已选择
                cameraCallback?.onPreviewSizeSelected(it.width, it.height)
                // 通知摄像头方向变化
                cameraCallback?.onCameraLensFacingChanged(lensFacing)
            }

            // 配置相机方向
            val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val displayRotation = 0 // 竖屏模式
            
            // 设置JPEG方向（用于拍照，不影响预览）
            val jpegOrientation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                (360 - ((displayRotation + sensorOrientation) % 360)) % 360
            } else {
                (displayRotation + sensorOrientation + 270) % 360
            }
            previewRequestBuilder?.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)

            // 设置其他相机参数
            previewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO) // 自动控制模式
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) // 连续对焦
            previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH) // 自动曝光带闪光灯
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring camera parameters: ${e.message}", e)
        }
    }

    /**
     * 相机捕获回调
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            // 处理捕获完成事件，例如获取曝光、对焦等信息
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            Log.e(TAG, "Capture failed: ${failure.reason}")
        }
    }
}