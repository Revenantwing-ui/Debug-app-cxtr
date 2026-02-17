package com.foss.appcloner.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.foss.appcloner.databinding.ItemAppBinding
import com.foss.appcloner.model.AppInfo

class AppListAdapter(
    private val onClick: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.VH>(DIFF) {

    inner class VH(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(info: AppInfo) {
            binding.tvAppName.text    = info.appName
            binding.tvPackage.text    = info.packageName
            binding.tvVersion.text    = info.versionName
            binding.ivIcon.setImageDrawable(info.icon)
            binding.root.setOnClickListener { onClick(info) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(a: AppInfo, b: AppInfo) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppInfo, b: AppInfo) = a == b
        }
    }
}
