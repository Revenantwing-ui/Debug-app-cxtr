package com.foss.appcloner.cloner

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.debug.ImmutableDebugItem
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * Rewrites DEX bytecode to redirect system identity API calls to our hook stubs.
 *
 * KEY BUG FIXED: dexlib2 separates ClassDef.directMethods (constructors + static methods)
 * from ClassDef.virtualMethods (overridable instance methods).
 * classDef.methods returns ALL of them concatenated.  Patching "classDef.methods" and passing
 * the result as the virtualMethods argument to ImmutableClassDef caused every direct method
 * to appear TWICE in the output DEX, producing an invalid file that the Android runtime
 * rejects with "Dex checksum mismatch" / INSTALL_FAILED_INVALID_APK.
 *
 * The fix: patch directMethods and virtualMethods separately, then pass each to its
 * correct slot in ImmutableClassDef.
 */
object DexPatcher {

    private const val HOOK_CLASS = "Lcom/foss/hook/Hooks;"

    // Source method → (hookMethodName, hookDescriptor)
    private val METHOD_HOOKS = mapOf(
        Triple("Landroid/provider/Settings\$Secure;", "getString",
               "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;") to
            Pair("spoofSettingSecure",
               "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;"),

        Triple("Landroid/telephony/TelephonyManager;", "getDeviceId",
               "()Ljava/lang/String;") to
            Pair("spoofImei", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),

        Triple("Landroid/telephony/TelephonyManager;", "getImei",
               "()Ljava/lang/String;") to
            Pair("spoofImei", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),

        Triple("Landroid/telephony/TelephonyManager;", "getSubscriberId",
               "()Ljava/lang/String;") to
            Pair("spoofImsi", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),

        Triple("Landroid/net/wifi/WifiInfo;", "getMacAddress",
               "()Ljava/lang/String;") to
            Pair("spoofWifiMac", "(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;"),

        Triple("Landroid/net/wifi/WifiInfo;", "getSSID",
               "()Ljava/lang/String;") to
            Pair("spoofSsid", "(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;"),

        Triple("Landroid/webkit/WebSettings;", "getUserAgentString",
               "()Ljava/lang/String;") to
            Pair("spoofUserAgent", "(Landroid/webkit/WebSettings;)Ljava/lang/String;")
    )

    fun patchDexFiles(apkDir: File, identityJson: String): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        apkDir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
            ?.forEach { dex ->
                result[dex.name] = try {
                    patchSingleDex(dex)
                } catch (e: Exception) {
                    android.util.Log.w("DexPatcher", "Skipping ${dex.name}: ${e.message}")
                    dex.readBytes()
                }
            }
        return result
    }

    fun patchSingleDex(dexFile: File): ByteArray {
        val opcodes = Opcodes.forApi(34)
        val dex     = DexBackedDexFile.fromInputStream(opcodes, dexFile.readBytes().inputStream())
        val pool    = DexPool(opcodes)

        for (classDef in dex.classes) {
            // ── Patch direct methods (constructors, static methods) ──────────────
            val patchedDirect = classDef.directMethods.map { method ->
                patchMethod(method)
            }
            // ── Patch virtual methods (instance overridable methods) ─────────────
            val patchedVirtual = classDef.virtualMethods.map { method ->
                patchMethod(method)
            }

            pool.internClass(ImmutableClassDef(
                classDef.type,
                classDef.accessFlags,
                classDef.superclass,
                ImmutableList.copyOf(classDef.interfaces),
                classDef.sourceFile,
                ImmutableSet.copyOf(classDef.annotations.map { ImmutableAnnotation.of(it) }),
                ImmutableList.copyOf(classDef.staticFields.map   { ImmutableField.of(it) }),
                ImmutableList.copyOf(classDef.instanceFields.map { ImmutableField.of(it) }),
                ImmutableList.copyOf(patchedDirect),   // ← direct methods only
                ImmutableList.copyOf(patchedVirtual)   // ← virtual methods only
            ))
        }

        val out = File.createTempFile("patched_", ".dex")
        pool.writeTo(FileDataStore(out))
        return out.readBytes().also { out.delete() }
    }

    /** Patch a single method: replace matching invoke-virtual calls with invoke-static hooks. */
    private fun patchMethod(method: org.jf.dexlib2.iface.Method): ImmutableMethod {
        val immutableParams = ImmutableList.copyOf(
            method.parameters.map { ImmutableMethodParameter.of(it) })
        val immutableAnnos  = ImmutableSet.copyOf(
            method.annotations.map { ImmutableAnnotation.of(it) })

        val impl = method.implementation
            ?: return ImmutableMethod(
                method.definingClass, method.name, immutableParams,
                method.returnType, method.accessFlags, immutableAnnos, null, null)

        val mutableImpl    = MutableMethodImplementation(impl)
        var patched        = false
        val instrSnapshot  = mutableImpl.instructions.toList()

        for ((index, instr) in instrSnapshot.withIndex()) {
            if (instr !is ReferenceInstruction) continue
            val ref = instr.reference as? MethodReference ?: continue

            val key = Triple(
                ref.definingClass,
                ref.name,
                "(${ref.parameterTypes.joinToString("")})${ref.returnType}"
            )
            val hookPair = METHOD_HOOKS[key] ?: continue

            if (instr is Instruction35c) {
                val hookRef = ImmutableMethodReference(
                    HOOK_CLASS,
                    hookPair.first,
                    parseParams(hookPair.second),
                    returnTypeFromDescriptor(hookPair.second)
                )
                mutableImpl.replaceInstruction(
                    index,
                    BuilderInstruction35c(
                        Opcode.INVOKE_STATIC,
                        instr.registerCount,
                        instr.registerC, instr.registerD, instr.registerE,
                        instr.registerF, instr.registerG,
                        hookRef
                    )
                )
                patched = true
            }
        }

        val finalImpl = if (patched) {
            ImmutableMethodImplementation(
                mutableImpl.registerCount,
                ImmutableList.copyOf(mutableImpl.instructions.map { ImmutableInstruction.of(it) }),
                ImmutableList.copyOf(mutableImpl.tryBlocks.map    { ImmutableTryBlock.of(it) }),
                ImmutableList.copyOf(mutableImpl.debugItems.map   { ImmutableDebugItem.of(it) })
            )
        } else {
            ImmutableMethodImplementation(
                impl.registerCount,
                ImmutableList.copyOf(impl.instructions.map { ImmutableInstruction.of(it) }),
                ImmutableList.copyOf(impl.tryBlocks.map    { ImmutableTryBlock.of(it) }),
                ImmutableList.copyOf(impl.debugItems.map   { ImmutableDebugItem.of(it) })
            )
        }

        return ImmutableMethod(
            method.definingClass, method.name, immutableParams,
            method.returnType, method.accessFlags, immutableAnnos, null, finalImpl)
    }

    // ── Descriptor helpers ────────────────────────────────────────────────────

    private fun parseParams(descriptor: String): List<String> {
        val inner  = descriptor.substringAfter("(").substringBefore(")")
        val result = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            when (inner[i]) {
                'L' -> { val end = inner.indexOf(';', i); result.add(inner.substring(i, end + 1)); i = end + 1 }
                '[' -> { result.add("["); i++ }
                else -> { result.add(inner[i].toString()); i++ }
            }
        }
        return result
    }

    private fun returnTypeFromDescriptor(descriptor: String): String =
        descriptor.substringAfter(")")
}
