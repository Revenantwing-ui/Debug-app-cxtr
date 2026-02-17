package com.foss.appcloner.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.foss.appcloner.identity.IdentityGenerator
import com.foss.appcloner.model.CloneConfig
import com.foss.appcloner.model.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CloneOptionsViewModel(app: Application) : AndroidViewModel(app) {

    private val _config = MutableLiveData<CloneConfig>()
    val config: LiveData<CloneConfig> get() = _config

    private val _identity = MutableLiveData<Identity>()
    val identity: LiveData<Identity> get() = _identity

    private val _progress = MutableLiveData<Pair<String, Int>>()
    val progress: LiveData<Pair<String, Int>> get() = _progress

    fun initForPackage(sourcePackage: String, clonePackage: String, label: String) {
        val cfg = CloneConfig(
            sourcePackageName = sourcePackage,
            clonePackageName  = clonePackage,
            cloneName         = label
        )
        _config.value = cfg
        _identity.value = Identity()
    }

    fun regenerateIdentity() {
        viewModelScope.launch(Dispatchers.IO) {
            val id = IdentityGenerator.generate()
            _identity.postValue(id)
        }
    }

    fun updateConfig(transform: (CloneConfig) -> CloneConfig) {
        _config.value = _config.value?.let(transform)
    }

    fun buildFinalConfig(): CloneConfig {
        val base = _config.value!!
        val id   = _identity.value ?: Identity()
        return base.copy(
            identity         = id,
            enableNewIdentity = base.enableNewIdentity
        )
    }
}
