package com.foss.appcloner.cloner

import android.content.Context
import android.util.Log
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.io.IOException

object HookInjector {

    private const val TAG = "HookInjector"

    fun buildHookDex(
        context: Context,
        identityJson: String,
        clonePackage: String
    ): ByteArray {
        // 1. Setup Temp Directory with EXPLICIT Check
        val tmpDir = File(context.cacheDir, "hook_smali_${System.currentTimeMillis()}")
        
        // Clean up potential leftovers
        if (tmpDir.exists()) tmpDir.deleteRecursively()

        // CRITICAL FIX: Throw if directory creation fails
        if (!tmpDir.mkdirs()) {
            throw IOException("Failed to create temp directory at: ${tmpDir.absolutePath}")
        }

        val dexOut = File(tmpDir, "hooks.dex")

        try {
            // 2. Extract and Patch Smali Sources
            val hookAssets = listOf(
                "hooks/HookConfig.smali",
                "hooks/Hooks.smali",
                "hooks/IdentityReceiver.smali"
            )

            for (asset in hookAssets) {
                // Verify asset exists
                try {
                    context.assets.open(asset).close()
                } catch (e: Exception) {
                    throw IOException("Asset not found: $asset. Ensure it exists in src/main/assets/hooks/")
                }

                val dest = File(tmpDir, asset.substringAfter("/"))
                
                context.assets.open(asset).use { input ->
                    dest.outputStream().use { output -> 
                        input.copyTo(output) 
                    }
                }

                // Patch placeholders
                var src = dest.readText()
                // Escape JSON for Smali string literal
                val escapedJson = identityJson
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                
                src = src.replace("%%IDENTITY_JSON%%", escapedJson)
                src = src.replace("%%CLONE_PACKAGE%%", clonePackage)
                dest.writeText(src)
            }

            // 3. Assemble Smali → DEX
            val options = SmaliOptions().apply {
                outputDexFile = dexOut.absolutePath
                apiLevel = 26 // Use safer API level
                verboseErrors = true
            }

            val success = Smali.assemble(options, listOf(tmpDir.absolutePath))

            if (!success) {
                throw IOException("Smali assembly failed! Check Logcat for syntax errors.")
            }

            if (!dexOut.exists() || dexOut.length() == 0L) {
                throw IOException("Assembly reported success, but output DEX is empty.")
            }

            return dexOut.readBytes()

        } catch (e: Exception) {
            Log.e(TAG, "Hook DEX assembly failed", e)
            throw e
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
