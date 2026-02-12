package com.example.cameraapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 主活动类，负责显示应用主页
 */
class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CameraFeatureAdapter
    private val featureList = mutableListOf<CameraFeatureItem>()

    /**
     * 活动创建时调用，初始化界面
     *
     * @param savedInstanceState 保存的实例状态
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化RecyclerView
        recyclerView = findViewById(R.id.recycler_view_camera_features)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        // 初始化数据列表
        initFeatureList()
        
        // 初始化适配器
        adapter = CameraFeatureAdapter(featureList) {
            // 处理点击事件，跳转到相应的预览界面
            startPreviewActivity(it.previewType)
        }
        recyclerView.adapter = adapter
    }

    /**
     * 初始化相机功能列表数据
     */
    private fun initFeatureList() {
        featureList.clear()
        featureList.add(
            CameraFeatureItem(
                id = 1,
                title = "Camera2 SurfaceTexture预览",
                previewType = PreviewActivity.PreviewType.SURFACE_TEXTURE
            )
        )
        featureList.add(
            CameraFeatureItem(
                id = 2,
                title = "Camera2 yuv预览",
                previewType = PreviewActivity.PreviewType.YUV
            )
        )
        featureList.add(
            CameraFeatureItem(
                id = 3,
                title = "Camera2 SurfaceTexture预览-离屏滤镜效果",
                previewType = PreviewActivity.PreviewType.SURFACE_TEXTURE_OFFSCREEN
            )
        )
    }

    /**
     * 启动预览活动
     *
     * @param previewType 预览类型
     */
    private fun startPreviewActivity(previewType: PreviewActivity.PreviewType) {
        val intent = Intent(this, PreviewActivity::class.java)
        intent.putExtra(PreviewActivity.EXTRA_PREVIEW_TYPE, previewType.name)
        startActivity(intent)
    }
}