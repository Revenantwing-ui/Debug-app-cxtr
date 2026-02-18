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
        
        // delete if exists to ensure clean state
        if (workDir.exists()) workDir.deleteRecursively()
        
        if (!workDir.mkdirs()) {
            throw IOException("CRITICAL: Failed to create cache directory at ${workDir.absolutePath}. Check device storage.")
        }

        try {
            // 2. Validate Source APK
            val sourceApk = File(sourceApkPath)
            if (!sourceApk.exists()) throw IOException("Source APK missing: $sourceApkPath")
            if (!sourceApk.canRead()) throw IOException("Cannot read Source APK (Permission denied): $sourceApkPath")

            // 3. Copy Source to Work Dir
            val workApk = File(workDir, "source.apk")
            sourceApk.copyTo(workApk, overwrite = true)

            progressCallback?.invoke("Reading manifest…", 10)

            val manifestBytes = ZipUtils.extractEntry(workApk, "AndroidManifest.xml")
                ?: throw IOException("Failed to extract AndroidManifest.xml. Invalid APK?")

            val modifiedManifest = ManifestModifier.modify(manifestBytes, config.sourcePackageName, config.clonePackageName)

            progressCallback?.invoke("Building identity hooks…", 30)
            
            // Verify Assets exist before proceeding
            try {
                context.assets.open("hooks/Hooks.smali").close()
            } catch (e: Exception) {
                throw IOException("Asset missing: hooks/Hooks.smali. Re-check project structure.")
            }

            val identityJson = gson.toJson(config.identity)
            val hookDex = HookInjector.buildHookDex(context, identityJson, config.clonePackageName)

            progressCallback?.invoke("Patching DEX files…", 50)
            val extractDir = File(workDir, "dex_extract").also { it.mkdirs() }
            extractDexFiles(workApk, extractDir)
            val patchedDexMap = DexPatcher.patchDexFiles(extractDir, identityJson)

            progressCallback?.invoke("Merging hook into DEX…", 65)
            // If classes.dex wasn't patched, use the original extracted one
            val baseDexBytes = patchedDexMap["classes.dex"] ?: File(extractDir, "classes.dex").readBytes()
            val mergedClasses = mergeDex(baseDexBytes, hookDex)

            progressCallback?.invoke("Repacking APK…", 75)
            val overrides = mutableMapOf<String, ByteArray>()
            overrides["AndroidManifest.xml"] = modifiedManifest
            overrides["classes.dex"]         = mergedClasses
            for ((name, bytes) in patchedDexMap) { 
                if (name != "classes.dex") overrides[name] = bytes 
            }

            val unsignedApk = File(workDir, "unsigned.apk")
            ZipUtils.repackApk(workApk, unsignedApk, overrides)

            progressCallback?.invoke("Signing APK…", 88)
            val clonesDir = context.getExternalFilesDir("clones") ?: context.filesDir
            if (!clonesDir.exists()) clonesDir.mkdirs()

            val signedApk = File(clonesDir, "${config.clonePackageName}.apk")
            
            // Call the V2-compatible signer
            ApkSigner.sign(context, unsignedApk, signedApk)

            progressCallback?.invoke("Done!", 100)
            return RepackageResult(signedApk, config.clonePackageName)
        } finally {
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
        // Fix: Use FileDataStore
        pool.writeTo(FileDataStore(out))
        return out.readBytes().also { out.delete() }
    }
}
