package com.example.cameraapp

import com.example.cameraapp.PreviewActivity.PreviewType

/**
 * 相机功能项数据模型
 *
 * @property id 功能ID
 * @property title 功能标题
 * @property previewType 预览类型，用于跳转到PreviewActivity
 */
data class CameraFeatureItem(
    val id: Int,
    val title: String,
    val previewType: PreviewType
)