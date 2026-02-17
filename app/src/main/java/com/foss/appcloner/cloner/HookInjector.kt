package com.foss.appcloner.cloner

import android.content.Context
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File

/**
 * Assembles the smali hook sources (bundled in assets/hooks/) into a DEX file
 * and returns the DEX bytes to be merged into the cloned APK.
 *
 * The assembled DEX contains:
 *   - Lcom/foss/hook/Hooks;           – intercepts all system API calls
 *   - Lcom/foss/hook/HookConfig;      – reads/caches identity values from storage
 *   - Lcom/foss/hook/IdentityReceiver;– BroadcastReceiver for live identity updates
 */
object HookInjector {

    /**
     * Assemble the hook smali files and return the resulting DEX bytes.
     * @param context  Application context (to access assets)
     * @param identityJson  JSON-serialised Identity to embed in the hook config
     * @param clonePackage  Package name of the clone (used by the receiver)
     */
    fun buildHookDex(
        context: Context,
        identityJson: String,
        clonePackage: String
    ): ByteArray {
        val tmpDir  = File(context.cacheDir, "hook_smali").also { it.mkdirs() }
        val dexOut  = File(context.cacheDir, "hooks.dex")

        try {
            // Extract smali sources from assets
            val hookAssets = listOf(
                "hooks/HookConfig.smali",
                "hooks/Hooks.smali",
                "hooks/IdentityReceiver.smali"
            )
            for (asset in hookAssets) {
                val dest = File(tmpDir, asset.substringAfter("/"))
                context.assets.open(asset).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // Substitute placeholders
                var src = dest.readText()
                src = src.replace("%%IDENTITY_JSON%%",
                    identityJson.replace("\\", "\\\\").replace("\"", "\\\""))
                src = src.replace("%%CLONE_PACKAGE%%", clonePackage)
                dest.writeText(src)
            }

            // Assemble smali → DEX
            val options = SmaliOptions().apply {
                outputDexFile = dexOut.path
                apiLevel = 34
            }
            Smali.assemble(options, listOf(tmpDir.path))

            return dexOut.readBytes()
        } finally {
            tmpDir.deleteRecursively()
            dexOut.delete()
        }
    }
}
