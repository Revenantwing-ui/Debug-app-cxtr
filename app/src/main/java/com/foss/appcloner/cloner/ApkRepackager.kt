package com.foss.appcloner.cloner

import android.content.Context
import com.foss.appcloner.model.CloneConfig
import com.foss.appcloner.util.ZipUtils
import com.google.gson.Gson
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File
import java.io.IOException

class ApkRepackager(private val context: Context) {

    private val gson = Gson()

    data class RepackageResult(val apkFile: File, val clonePackageName: String)

    fun repackage(
        sourceApkPath: String,
        config: CloneConfig,
        progressCallback: ((String, Int) -> Unit)? = null
    ): RepackageResult {

        // Helper to log to the callback AND console
        fun log(msg: String, pct: Int = 0) {
            progressCallback?.invoke(msg, pct)
        }

        val workDir = File(context.cacheDir, "clone_${System.currentTimeMillis()}")
        workDir.deleteRecursively()
        if (!workDir.mkdirs()) throw IOException("Cannot create work dir: ${workDir.absolutePath}")

        try {
            log("Validating source...", 5)
            val sourceApk = File(sourceApkPath)
            if (!sourceApk.exists()) throw IOException("Source APK not found")

            val workApk = File(workDir, "source.apk")
            sourceApk.copyTo(workApk, overwrite = true)

            log("Patching manifest...", 15)
            val manifestBytes = ZipUtils.extractEntry(workApk, "AndroidManifest.xml")
                ?: throw IOException("AndroidManifest.xml missing")
            val modifiedManifest = ManifestModifier.modify(
                manifestBytes, config.sourcePackageName, config.clonePackageName)

            log("Preparing hooks...", 25)
            // Verify assets
            for (asset in listOf("hooks/HookConfig.smali", "hooks/Hooks.smali", "hooks/IdentityReceiver.smali")) {
                try { context.assets.open(asset).close() } 
                catch (e: Exception) { throw IOException("Missing asset: $asset") }
            }
            
            val identityJson = gson.toJson(config.identity)
            
            // Pass a simple logger to HookInjector
            log("Compiling Smali hooks...", 35)
            val hookDex = HookInjector.buildHookDex(context, identityJson, config.clonePackageName)

            log("Extracting DEX files...", 45)
            val extractDir = File(workDir, "dex").also { it.mkdirs() }
            ZipUtils.listEntries(workApk)
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .forEach { name ->
                    ZipUtils.extractEntry(workApk, name)?.let { File(extractDir, name).writeBytes(it) }
                }

            log("Injecting bytecode hooks...", 55)
            val patchedDexMap = DexPatcher.patchDexFiles(extractDir, identityJson)

            log("Merging DEX files...", 65)
            val baseDexBytes = patchedDexMap["classes.dex"]
                ?: File(extractDir, "classes.dex").takeIf { it.exists() }?.readBytes()
                ?: throw IOException("classes.dex not found")
            val mergedClasses = mergeDex(baseDexBytes, hookDex)

            log("Repacking APK...", 75)
            val overrides = mutableMapOf("AndroidManifest.xml" to modifiedManifest, "classes.dex" to mergedClasses)
            patchedDexMap.forEach { (name, bytes) -> if (name != "classes.dex") overrides[name] = bytes }

            val unsignedApk = File(workDir, "unsigned.apk")
            ZipUtils.repackApk(workApk, unsignedApk, overrides)

            log("Signing APK...", 90)
            val outputDir: File = context.getExternalFilesDir("clones") ?: File(context.filesDir, "clones")
            if (!outputDir.exists()) outputDir.mkdirs()
            val signedApk = File(outputDir, "${config.clonePackageName}.apk")
            if (signedApk.exists()) signedApk.delete()

            ApkSigner.sign(context, unsignedApk, signedApk)

            return RepackageResult(signedApk, config.clonePackageName)

        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun mergeDex(existingDex: ByteArray, hookDex: ByteArray): ByteArray {
        val opcodes  = Opcodes.forApi(26)
        val existing = DexBackedDexFile.fromInputStream(opcodes, existingDex.inputStream())
        val hook     = DexBackedDexFile.fromInputStream(opcodes, hookDex.inputStream())
        val pool     = DexPool(opcodes)
        val existingTypes = existing.classes.map { it.type }.toSet()

        for (cls in existing.classes) pool.internClass(cls)
        for (cls in hook.classes) { if (cls.type !in existingTypes) pool.internClass(cls) }

        val out = File.createTempFile("merged_", ".dex")
        pool.writeTo(FileDataStore(out))
        return out.readBytes().also { out.delete() }
    }
}
