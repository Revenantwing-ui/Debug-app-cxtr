package com.foss.appcloner.cloner

import android.content.Context
import android.util.Log
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.File
import java.io.IOException

/**
 * Assembles smali hook sources (bundled in assets/hooks/) into a DEX file.
 *
 * KEY FIX: Smali.assemble() returns false on failure without throwing.
 * The original code ignored the return value and called dexOut.readBytes()
 * unconditionally, which then throws FileNotFoundException because the output
 * file was never written.  We now check the return value and validate the
 * output exists and is non-empty before returning.
 */
object HookInjector {

    private const val TAG = "HookInjector"

    fun buildHookDex(
        context: Context,
        identityJson: String,
        clonePackage: String
    ): ByteArray {
        val tmpDir = File(context.cacheDir, "hook_smali_${System.currentTimeMillis()}").also { it.mkdirs() }
        val dexOut = File(context.cacheDir, "hooks_${System.currentTimeMillis()}.dex")

        try {
            // ── Extract smali sources from assets ─────────────────────────────
            val hookAssets = listOf(
                "hooks/HookConfig.smali",
                "hooks/Hooks.smali",
                "hooks/IdentityReceiver.smali"
            )
            for (asset in hookAssets) {
                val dest = File(tmpDir, asset.substringAfter("/"))
                context.assets.open(asset).use { it.copyTo(dest.outputStream()) }

                // Substitute placeholders
                var src = dest.readText()
                // Escape backslashes and quotes inside the JSON so they survive smali parsing
                val escapedJson = identityJson
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                src = src.replace("%%IDENTITY_JSON%%", escapedJson)
                src = src.replace("%%CLONE_PACKAGE%%", clonePackage)
                dest.writeText(src)
                Log.d(TAG, "Extracted and patched: ${dest.name}")
            }

            // ── Assemble smali → DEX ──────────────────────────────────────────
            val options = SmaliOptions().apply {
                outputDexFile = dexOut.absolutePath   // use absolutePath, not path
                apiLevel      = 34
            }

            val success = Smali.assemble(options, listOf(tmpDir.absolutePath))

            if (!success) {
                throw IOException(
                    "Smali.assemble() returned false — check smali syntax in assets/hooks/. " +
                    "Temp dir preserved at: ${tmpDir.absolutePath}"
                )
            }
            if (!dexOut.exists() || dexOut.length() == 0L) {
                throw IOException(
                    "Hook DEX was not produced by Smali.assemble(). " +
                    "Expected output: ${dexOut.absolutePath}"
                )
            }

            Log.d(TAG, "Hook DEX assembled: ${dexOut.length()} bytes")
            return dexOut.readBytes()

        } catch (e: Exception) {
            Log.e(TAG, "Hook DEX assembly failed", e)
            throw e   // re-throw so ApkRepackager can surface it to the UI
        } finally {
            tmpDir.deleteRecursively()
            if (dexOut.exists()) dexOut.delete()
        }
    }
}
