package com.foss.appcloner.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.foss.appcloner.databinding.FragmentAppListBinding
import com.foss.appcloner.model.AppInfo
import com.foss.appcloner.ui.adapter.AppListAdapter
import com.foss.appcloner.ui.viewmodel.AppListViewModel

class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = _binding!!
    private val vm: AppListViewModel by viewModels()
    private lateinit var adapter: AppListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentAppListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AppListAdapter(::onAppSelected)
        binding.recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { vm.load() }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true.also { vm.filter(q ?: "") }
            override fun onQueryTextChange(q: String?) = true.also { vm.filter(q ?: "") }
        })

        vm.apps.observe(viewLifecycleOwner)    { adapter.submitList(it) }
        vm.loading.observe(viewLifecycleOwner) { binding.swipeRefresh.isRefreshing = it }
    }

    private fun onAppSelected(info: AppInfo) {
        val intent = Intent(requireContext(), CloneOptionsActivity::class.java).apply {
            putExtra(CloneOptionsActivity.EXTRA_PACKAGE,  info.packageName)
            putExtra(CloneOptionsActivity.EXTRA_APP_NAME, info.appName)
            putExtra(CloneOptionsActivity.EXTRA_APK_PATH, info.apkPath)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
