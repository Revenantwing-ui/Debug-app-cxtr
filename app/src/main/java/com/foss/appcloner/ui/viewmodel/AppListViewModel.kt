package com.foss.appcloner.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.foss.appcloner.model.AppInfo
import com.foss.appcloner.util.PackageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppListViewModel(app: Application) : AndroidViewModel(app) {

    private val _apps = MutableLiveData<List<AppInfo>>()
    val apps: LiveData<List<AppInfo>> get() = _apps

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> get() = _loading

    private var allApps: List<AppInfo> = emptyList()

    init { load() }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.postValue(true)
            allApps = PackageUtils.getInstalledApps(getApplication())
            _apps.postValue(allApps)
            _loading.postValue(false)
        }
    }

    fun filter(query: String) {
        val q = query.trim().lowercase()
        _apps.value = if (q.isEmpty()) allApps
        else allApps.filter { it.appName.lowercase().contains(q) || it.packageName.contains(q) }
    }
}
