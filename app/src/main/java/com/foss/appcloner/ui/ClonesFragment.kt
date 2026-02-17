package com.foss.appcloner.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.foss.appcloner.databinding.FragmentClonesBinding
import com.foss.appcloner.db.CloneEntity
import com.foss.appcloner.ui.adapter.CloneAdapter
import com.foss.appcloner.ui.viewmodel.ClonesViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ClonesFragment : Fragment() {

    private var _binding: FragmentClonesBinding? = null
    private val binding get() = _binding!!
    private val vm: ClonesViewModel by viewModels()
    private lateinit var adapter: CloneAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentClonesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CloneAdapter(
            onNewIdentity = ::showNewIdentityDialog,
            onDelete      = ::confirmDelete,
            onViewIdentity = ::openIdentityViewer
        )
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        vm.clones.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }

        vm.message.observe(viewLifecycleOwner) { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNewIdentityDialog(entity: CloneEntity) {
        var clearCache   = false
        var deleteData   = false

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Identity")
            .setMessage("Generate a fresh random identity for ${entity.cloneName}?")
            .setMultiChoiceItems(
                arrayOf("Clear cache", "Delete app data"),
                booleanArrayOf(false, false)
            ) { _, which, checked ->
                when (which) {
                    0 -> clearCache  = checked
                    1 -> deleteData  = checked
                }
            }
            .setPositiveButton("Generate") { _, _ ->
                vm.generateNewIdentity(entity.clonePackageName, clearCache, deleteData)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entity: CloneEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Clone")
            .setMessage("Remove ${entity.cloneName} from the database? " +
                        "(This does not uninstall the app – do that via Android Settings.)")
            .setPositiveButton("Delete") { _, _ -> vm.deleteClone(entity) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openIdentityViewer(entity: CloneEntity) {
        val intent = Intent(requireContext(), IdentityViewerActivity::class.java).apply {
            putExtra(IdentityViewerActivity.EXTRA_PACKAGE, entity.clonePackageName)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
