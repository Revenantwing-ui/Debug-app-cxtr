package com.foss.appcloner.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.foss.appcloner.AppClonerApp
import com.foss.appcloner.db.AppDatabase
import com.foss.appcloner.db.CloneEntity
import com.foss.appcloner.identity.IdentityManager
import com.foss.appcloner.model.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClonesViewModel(app: Application) : AndroidViewModel(app) {

    private val db  = AppDatabase.getInstance(app)
    private val mgr = IdentityManager(app)

    val clones: LiveData<List<CloneEntity>> = db.cloneDao().observeAll()

    private val _message = MutableLiveData<String>()
    val message: LiveData<String> get() = _message

    fun generateNewIdentity(
        clonePackageName: String,
        clearCache: Boolean = false,
        deleteAppData: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val identity = mgr.generateNewIdentity(clonePackageName, clearCache, deleteAppData)
                _message.postValue("New identity generated for $clonePackageName")
            }.onFailure {
                _message.postValue("Error: ${it.message}")
            }
        }
    }

    fun deleteClone(entity: CloneEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.cloneDao().delete(entity)
        }
    }
}
