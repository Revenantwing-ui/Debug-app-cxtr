package com.foss.appcloner.cloner

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.foss.appcloner.model.CloneConfig
import com.foss.appcloner.util.ZipUtils
import com.google.gson.Gson
import java.io.File

/**
 * Orchestrates the full APK cloning pipeline:
 *
 *   1. Copy source APK to working directory
 *   2. Extract AndroidManifest.xml → modify package → rewrite
 *   3. Build & inject hook DEX (assembles smali, merges into classes.dex)
 *   4. Patch existing DEX files to redirect system calls to hook class
 *   5. Repack all modifications back into a new APK
 *   6. Sign the APK
 *   7. Return File pointing to the signed APK, ready for PackageInstaller
 */
class ApkRepackager(private val context: Context) {

    private val gson = Gson()

    data class RepackageResult(
        val apkFile: File,
        val clonePackageName: String
    )

    /**
     * Clone the given source APK according to [config].
     * Runs entirely on the calling thread – wrap in a coroutine / IO dispatcher.
     *
     * @param sourceApkPath  Absolute path to the source APK
     * @param config         Clone configuration
     * @param progressCallback  Optional lambda called with (step: String, pct: Int)
     */
    fun repackage(
        sourceApkPath: String,
        config: CloneConfig,
        progressCallback: ((String, Int) -> Unit)? = null
    ): RepackageResult {
        val workDir = File(context.cacheDir, "clone_work_${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            val sourceApk = File(sourceApkPath)
            val workApk   = File(workDir, "source.apk")
            sourceApk.copyTo(workApk, overwrite = true)

            progressCallback?.invoke("Reading manifest…", 10)

            // ── Step 1: Modify AndroidManifest.xml ─────────────────────────────
            val manifestBytes = ZipUtils.extractEntry(workApk, "AndroidManifest.xml")
                ?: error("Cannot extract AndroidManifest.xml from APK")

            val modifiedManifest = ManifestModifier.modify(
                manifestBytes   = manifestBytes,
                sourcePackage   = config.sourcePackageName,
                clonePackage    = config.clonePackageName
            )

            progressCallback?.invoke("Building identity hooks…", 30)

            // ── Step 2: Build hook DEX ─────────────────────────────────────────
            val identityJson = gson.toJson(config.identity)
            val hookDex = HookInjector.buildHookDex(
                context       = context,
                identityJson  = identityJson,
                clonePackage  = config.clonePackageName
            )

            progressCallback?.invoke("Patching DEX files…", 50)

            // ── Step 3: Extract and patch existing DEX files ───────────────────
            val extractDir = File(workDir, "dex_extract").also { it.mkdirs() }
            extractDexFiles(workApk, extractDir)
            val patchedDexMap = DexPatcher.patchDexFiles(extractDir, identityJson)

            progressCallback?.invoke("Merging hook into DEX…", 65)

            // ── Step 4: Merge hook DEX into classes.dex using dexlib2 ──────────
            val mergedClasses = mergeDex(
                existingDex = patchedDexMap["classes.dex"]
                    ?: File(extractDir, "classes.dex").readBytes(),
                hookDex     = hookDex
            )

            progressCallback?.invoke("Repacking APK…", 75)

            // ── Step 5: Repack ─────────────────────────────────────────────────
            val overrides = mutableMapOf<String, ByteArray>()
            overrides["AndroidManifest.xml"] = modifiedManifest
            overrides["classes.dex"]         = mergedClasses
            // Add any additional patched DEX files
            for ((name, bytes) in patchedDexMap) {
                if (name != "classes.dex") overrides[name] = bytes
            }

            val unsignedApk = File(workDir, "unsigned.apk")
            ZipUtils.repackApk(workApk, unsignedApk, overrides)

            progressCallback?.invoke("Signing APK…", 88)

            // ── Step 6: Sign ───────────────────────────────────────────────────
            val signedApk = File(
                context.getExternalFilesDir("clones"),
                "${config.clonePackageName}.apk"
            )
            signedApk.parentFile?.mkdirs()
            ApkSigner.sign(context, unsignedApk, signedApk)

            progressCallback?.invoke("Done!", 100)

            return RepackageResult(signedApk, config.clonePackageName)

        } finally {
            workDir.deleteRecursively()
        }
    }

    /** Extract all classes*.dex files from APK to [dir]. */
    private fun extractDexFiles(apk: File, dir: File) {
        val entries = ZipUtils.listEntries(apk)
        for (name in entries) {
            if (name.matches(Regex("classes\\d*\\.dex"))) {
                val bytes = ZipUtils.extractEntry(apk, name) ?: continue
                File(dir, name).writeBytes(bytes)
            }
        }
    }

    /**
     * Merge [hookDex] classes into [existingDex] using dexlib2.
     * If a class in hookDex already exists in existingDex it is skipped.
     */
    private fun mergeDex(existingDex: ByteArray, hookDex: ByteArray): ByteArray {
        val opcodes = org.jf.dexlib2.Opcodes.forApi(34)
        val existing = org.jf.dexlib2.dexbacked.DexBackedDexFile
            .fromInputStream(opcodes, existingDex.inputStream())
        val hook = org.jf.dexlib2.dexbacked.DexBackedDexFile
            .fromInputStream(opcodes, hookDex.inputStream())

        val pool = org.jf.dexlib2.writer.pool.DexPool(opcodes)

        val existingTypes = existing.classes.map { it.type }.toSet()

        // Add all existing classes
        for (cls in existing.classes) pool.internClass(cls)

        // Add hook classes (skip if already defined)
        for (cls in hook.classes) {
            if (cls.type !in existingTypes) pool.internClass(cls)
        }

        val out = File.createTempFile("merged", ".dex")
        try {
            org.jf.dexlib2.writer.pool.DexPool.writeTo(out.path, pool)
            return out.readBytes()
        } finally {
            out.delete()
        }
    }
}
