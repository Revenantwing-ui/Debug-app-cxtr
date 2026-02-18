package com.foss.appcloner.cloner

import android.content.Context
import com.foss.appcloner.model.CloneConfig
import com.foss.appcloner.util.ZipUtils
import com.google.gson.Gson
import org.jf.dexlib2.writer.io.FileDataStore
import java.io.File
import java.io.IOException

class ApkRepackager(private val context: Context) {
    private val gson = Gson()

    data class RepackageResult(val apkFile: File, val clonePackageName: String)

    fun repackage(sourceApkPath: String, config: CloneConfig, progressCallback: ((String, Int) -> Unit)? = null): RepackageResult {
        // 1. Setup Working Directory (With safety checks)
        val workDir = File(context.cacheDir, "clone_work_${System.currentTimeMillis()}")
        
        // Clean up any old runs
        if (workDir.exists()) workDir.deleteRecursively()
        
        // CRITICAL FIX: Ensure directory exists before copying
        if (!workDir.mkdirs()) {
            throw IOException("Failed to create working directory: ${workDir.absolutePath}. Check device storage.")
        }

        try {
            // 2. Validate Source APK
            val sourceApk = File(sourceApkPath)
            if (!sourceApk.exists()) throw IOException("Source APK not found at: $sourceApkPath")
            if (!sourceApk.canRead()) throw IOException("Permission denied reading Source APK")

            // 3. Copy Source to Work Dir
            val workApk = File(workDir, "source.apk")
            try {
                sourceApk.copyTo(workApk, overwrite = true)
            } catch (e: Exception) {
                throw IOException("Failed to copy APK. Error: ${e.message}")
            }

            progressCallback?.invoke("Reading manifest…", 10)

            val manifestBytes = ZipUtils.extractEntry(workApk, "AndroidManifest.xml")
                ?: throw IOException("Could not find AndroidManifest.xml. Is this a valid APK?")

            val modifiedManifest = ManifestModifier.modify(manifestBytes, config.sourcePackageName, config.clonePackageName)

            progressCallback?.invoke("Building identity hooks…", 30)
            
            // Check if assets exist to prevent crash later
            try {
                context.assets.open("hooks/Hooks.smali").close()
            } catch (e: Exception) {
                throw IOException("Missing Asset: hooks/Hooks.smali. Re-check 'app/src/main/assets/hooks/' folder.")
            }

            val identityJson = gson.toJson(config.identity)
            val hookDex = HookInjector.buildHookDex(context, identityJson, config.clonePackageName)

            progressCallback?.invoke("Patching DEX files…", 50)
            val extractDir = File(workDir, "dex_extract").also { it.mkdirs() }
            extractDexFiles(workApk, extractDir)
            val patchedDexMap = DexPatcher.patchDexFiles(extractDir, identityJson)

            progressCallback?.invoke("Merging hook into DEX…", 65)
            // Use patched classes.dex if available, otherwise original
            val baseDexBytes = patchedDexMap["classes.dex"] ?: File(extractDir, "classes.dex").readBytes()
            val mergedClasses = mergeDex(baseDexBytes, hookDex)

            progressCallback?.invoke("Repacking APK…", 75)
            val overrides = mutableMapOf<String, ByteArray>()
            overrides["AndroidManifest.xml"] = modifiedManifest
            overrides["classes.dex"]         = mergedClasses
            // Add other patched dex files (classes2.dex, etc.)
            for ((name, bytes) in patchedDexMap) { 
                if (name != "classes.dex") overrides[name] = bytes 
            }

            val unsignedApk = File(workDir, "unsigned.apk")
            ZipUtils.repackApk(workApk, unsignedApk, overrides)

            progressCallback?.invoke("Signing APK…", 88)
            val clonesDir = context.getExternalFilesDir("clones") ?: context.filesDir
            if (!clonesDir.exists()) clonesDir.mkdirs()

            val signedApk = File(clonesDir, "${config.clonePackageName}.apk")
            
            // Call the robust ApkSigner (supports V1/V2)
            ApkSigner.sign(context, unsignedApk, signedApk)

            progressCallback?.invoke("Done!", 100)
            return RepackageResult(signedApk, config.clonePackageName)
        } finally {
            // Clean up temp files
            workDir.deleteRecursively()
        }
    }

    private fun extractDexFiles(apk: File, dir: File) {
        ZipUtils.listEntries(apk)
            .filter { it.matches(Regex("classes\\d*\\.dex")) }
            .forEach { name ->
                ZipUtils.extractEntry(apk, name)?.let { File(dir, name).writeBytes(it) }
            }
    }

    private fun mergeDex(existingDex: ByteArray, hookDex: ByteArray): ByteArray {
        val opcodes = org.jf.dexlib2.Opcodes.forApi(34)
        val existing = org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(opcodes, existingDex.inputStream())
        val hook = org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(opcodes, hookDex.inputStream())
        
        val pool = org.jf.dexlib2.writer.pool.DexPool(opcodes)
        val existingTypes = existing.classes.map { it.type }.toSet()
        
        for (cls in existing.classes) pool.internClass(cls)
        for (cls in hook.classes) { 
            if (cls.type !in existingTypes) pool.internClass(cls) 
        }
        
        val out = File.createTempFile("merged", ".dex")
        pool.writeTo(FileDataStore(out))
        return out.readBytes().also { out.delete() }
    }
}
