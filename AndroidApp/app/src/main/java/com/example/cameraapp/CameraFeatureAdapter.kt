package com.example.cameraapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView

/**
 * 相机功能列表适配器
 *
 * @property featureList 相机功能数据列表
 * @property onItemClickListener 点击事件监听器
 */
class CameraFeatureAdapter(
    private val featureList: List<CameraFeatureItem>,
    private val onItemClickListener: (CameraFeatureItem) -> Unit
) : RecyclerView.Adapter<CameraFeatureAdapter.CameraFeatureViewHolder>() {

    /**
     * 相机功能ViewHolder，持有item布局中的视图引用
     */
    class CameraFeatureViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val btnCameraFeature: Button = itemView.findViewById(R.id.btn_camera_feature)
    }

    /**
     * 创建ViewHolder实例
     *
     * @param parent 父视图组
     * @param viewType 视图类型
     * @return CameraFeatureViewHolder实例
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CameraFeatureViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_camera_feature, parent, false)
        return CameraFeatureViewHolder(view)
    }

    /**
     * 将数据绑定到ViewHolder上
     *
     * @param holder ViewHolder实例
     * @param position 数据位置
     */
    override fun onBindViewHolder(holder: CameraFeatureViewHolder, position: Int) {
        val featureItem = featureList[position]
        holder.btnCameraFeature.text = featureItem.title
        holder.btnCameraFeature.setOnClickListener {
            onItemClickListener(featureItem)
        }
    }

    /**
     * 获取数据列表的大小
     *
     * @return 数据列表的大小
     */
    override fun getItemCount(): Int {
        return featureList.size
    }
}