package com.foss.appcloner.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.foss.appcloner.databinding.ItemCloneBinding
import com.foss.appcloner.db.CloneEntity
import java.text.SimpleDateFormat
import java.util.*

class CloneAdapter(
    private val onNewIdentity: (CloneEntity) -> Unit,
    private val onDelete: (CloneEntity) -> Unit,
    private val onViewIdentity: (CloneEntity) -> Unit
) : ListAdapter<CloneEntity, CloneAdapter.VH>(DIFF) {

    inner class VH(private val binding: ItemCloneBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entity: CloneEntity) {
            binding.tvCloneName.text    = entity.cloneName.ifBlank { entity.clonePackageName }
            binding.tvClonePackage.text = entity.clonePackageName
            binding.tvSourcePackage.text = "Source: ${entity.sourcePackageName}"
            binding.tvLastRefresh.text  = if (entity.lastIdentityRefresh > 0)
                "Identity: ${formatDate(entity.lastIdentityRefresh)}" else "Identity: never refreshed"
            binding.chipInstalled.isChecked = entity.isInstalled

            binding.btnNewIdentity.setOnClickListener { onNewIdentity(entity) }
            binding.btnDelete.setOnClickListener      { onDelete(entity) }
            binding.btnViewIdentity.setOnClickListener{ onViewIdentity(entity) }
        }

        private fun formatDate(ts: Long): String =
            SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(ts))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CloneEntity>() {
            override fun areItemsTheSame(a: CloneEntity, b: CloneEntity) =
                a.clonePackageName == b.clonePackageName
            override fun areContentsTheSame(a: CloneEntity, b: CloneEntity) = a == b
        }
    }
}
