package com.foss.appcloner.cloner

import android.content.Context
import android.util.Log
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

        // ── Working directory inside app cache (always writable, no permissions needed) ──
        val workDir = File(context.cacheDir, "clone_${System.currentTimeMillis()}")
        workDir.deleteRecursively()
        if (!workDir.mkdirs()) throw IOException("Cannot create work dir: ${workDir.absolutePath}")

        try {
            // ── 1. Validate source APK ───────────────────────────────────────
            val sourceApk = File(sourceApkPath)
            if (!sourceApk.exists()) throw IOException("Source APK not found: $sourceApkPath")
            if (!sourceApk.canRead()) throw IOException("Cannot read APK (permission denied): $sourceApkPath")
            if (sourceApk.length() == 0L) throw IOException("Source APK is empty")

            val workApk = File(workDir, "source.apk").also { sourceApk.copyTo(it, overwrite = true) }

            // ── 2. Patch AndroidManifest.xml ─────────────────────────────────
            progressCallback?.invoke("Patching manifest…", 10)
            val manifestBytes = ZipUtils.extractEntry(workApk, "AndroidManifest.xml")
                ?: throw IOException("AndroidManifest.xml missing from APK — is the source corrupt?")
            val modifiedManifest = ManifestModifier.modify(
                manifestBytes, config.sourcePackageName, config.clonePackageName)

            // ── 3. Build hook DEX ─────────────────────────────────────────────
            progressCallback?.invoke("Building identity hooks…", 25)
            // Validate smali assets exist before starting
            for (asset in listOf("hooks/HookConfig.smali", "hooks/Hooks.smali", "hooks/IdentityReceiver.smali")) {
                runCatching { context.assets.open(asset).close() }.getOrElse {
                    throw IOException("Missing bundled asset: $asset — did you include assets/hooks/ in the APK?")
                }
            }
            val identityJson = gson.toJson(config.identity)
            val hookDex = HookInjector.buildHookDex(context, identityJson, config.clonePackageName)

            // ── 4. Patch DEX files ────────────────────────────────────────────
            progressCallback?.invoke("Patching DEX…", 45)
            val extractDir = File(workDir, "dex").also { it.mkdirs() }
            ZipUtils.listEntries(workApk)
                .filter { it.matches(Regex("classes\\d*\\.dex")) }
                .forEach { name ->
                    ZipUtils.extractEntry(workApk, name)?.let { File(extractDir, name).writeBytes(it) }
                }
            val patchedDexMap = DexPatcher.patchDexFiles(extractDir, identityJson)

            // ── 5. Merge hook DEX into classes.dex ────────────────────────────
            progressCallback?.invoke("Merging hook DEX…", 62)
            val baseDexBytes = patchedDexMap["classes.dex"]
                ?: File(extractDir, "classes.dex").takeIf { it.exists() }?.readBytes()
                ?: throw IOException("classes.dex not found in source APK")
            val mergedClasses = mergeDex(baseDexBytes, hookDex)

            // ── 6. Repack APK ─────────────────────────────────────────────────
            progressCallback?.invoke("Repacking APK…", 75)
            val overrides = mutableMapOf("AndroidManifest.xml" to modifiedManifest,
                                          "classes.dex"        to mergedClasses)
            patchedDexMap.forEach { (name, bytes) -> if (name != "classes.dex") overrides[name] = bytes }

            val unsignedApk = File(workDir, "unsigned.apk")
            ZipUtils.repackApk(workApk, unsignedApk, overrides)

            // ── 7. Sign APK ───────────────────────────────────────────────────
            progressCallback?.invoke("Signing APK…", 88)

            // KEY FIX: getExternalFilesDir can return null if external storage is
            // unmounted or unavailable. Fall back to internal filesDir which is
            // always available and writable without any permissions on all API levels.
            val outputDir: File = context.getExternalFilesDir("clones")
                ?: File(context.filesDir, "clones")
            if (!outputDir.exists() && !outputDir.mkdirs()) {
                throw IOException("Cannot create output dir: ${outputDir.absolutePath}")
            }

            val signedApk = File(outputDir, "${config.clonePackageName}.apk")
            if (signedApk.exists()) signedApk.delete()

            ApkSigner.sign(context, unsignedApk, signedApk)

            if (!signedApk.exists() || signedApk.length() == 0L) {
                throw IOException("Signing produced no output — ApkSigner failed silently")
            }

            Log.d("ApkRepackager", "Done. Signed APK: ${signedApk.absolutePath} (${signedApk.length()} bytes)")
            progressCallback?.invoke("Done!", 100)
            return RepackageResult(signedApk, config.clonePackageName)

        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun mergeDex(existingDex: ByteArray, hookDex: ByteArray): ByteArray {
        val opcodes  = Opcodes.forApi(34)
        val existing = DexBackedDexFile.fromInputStream(opcodes, existingDex.inputStream())
        val hook     = DexBackedDexFile.fromInputStream(opcodes, hookDex.inputStream())

        val pool          = DexPool(opcodes)
        val existingTypes = existing.classes.map { it.type }.toSet()

        for (cls in existing.classes) pool.internClass(cls)
        for (cls in hook.classes) { if (cls.type !in existingTypes) pool.internClass(cls) }

        val out = File.createTempFile("merged_", ".dex")
        pool.writeTo(FileDataStore(out))
        return out.readBytes().also { out.delete() }
    }
}
