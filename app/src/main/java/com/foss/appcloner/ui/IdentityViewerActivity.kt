package com.foss.appcloner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.foss.appcloner.databinding.ActivityIdentityViewerBinding
import com.foss.appcloner.db.AppDatabase
import com.foss.appcloner.model.Identity
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IdentityViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIdentityViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIdentityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val clonePackage = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        title = "Identity: $clonePackage"

        lifecycleScope.launch {
            val entity = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@IdentityViewerActivity)
                    .cloneDao().getByPackage(clonePackage)
            } ?: run { finish(); return@launch }

            val gson = GsonBuilder().setPrettyPrinting().create()
            val prettyJson = try {
                val id = gson.fromJson(entity.identityJson, Identity::class.java)
                gson.toJson(id)
            } catch (e: Exception) { entity.identityJson }

            binding.tvIdentityJson.text = prettyJson

            binding.btnCopyJson.setOnClickListener {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("identity", prettyJson))
                Toast.makeText(this@IdentityViewerActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSupportNavigateUp() = true.also { finish() }

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }
}
