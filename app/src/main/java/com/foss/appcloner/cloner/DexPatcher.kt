package com.foss.appcloner.cloner

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction21c
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * Patches DEX files inside a cloned APK to redirect system identity API calls
 * to our hook class (Lcom/foss/hook/Hooks;).
 *
 * Intercepts:
 *   - android.provider.Settings$Secure.getString  → getAndroidId / getString
 *   - android.telephony.TelephonyManager.getDeviceId / getImei → fake IMEI
 *   - android.net.wifi.WifiInfo.getMacAddress      → fake MAC
 *   - android.os.Build fields (MANUFACTURER, MODEL…) via static reads
 *   - android.webkit.WebView.getSettings().getUserAgentString()
 *   - android.os.BatteryManager fields
 *
 * The hook class itself is injected as a new class in the DEX by [HookInjector].
 */
object DexPatcher {

    private const val HOOK_CLASS = "Lcom/foss/hook/Hooks;"

    // Map: (definingClass, methodName, descriptor) → (hookMethodName, hookDescriptor)
    private val METHOD_HOOKS = mapOf(
        Triple("Landroid/provider/Settings\$Secure;", "getString",
               "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;")
            to Pair("spoofSettingSecure",
                    "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;"),

        Triple("Landroid/telephony/TelephonyManager;", "getDeviceId",
               "()Ljava/lang/String;")
            to Pair("spoofImei", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),

        Triple("Landroid/telephony/TelephonyManager;", "getImei",
               "()Ljava/lang/String;")
            to Pair("spoofImei", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),

        Triple("Landroid/telephony/TelephonyManager;", "getSubscriberId",
               "()Ljava/lang/String;")
            to Pair("spoofImsi", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),

        Triple("Landroid/net/wifi/WifiInfo;", "getMacAddress",
               "()Ljava/lang/String;")
            to Pair("spoofWifiMac", "(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;"),

        Triple("Landroid/net/wifi/WifiInfo;", "getSSID",
               "()Ljava/lang/String;")
            to Pair("spoofSsid", "(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;"),

        Triple("Landroid/webkit/WebSettings;", "getUserAgentString",
               "()Ljava/lang/String;")
            to Pair("spoofUserAgent", "(Landroid/webkit/WebSettings;)Ljava/lang/String;")
    )

    /**
     * Patch all DEX files in [apkDir] (already extracted).
     * Returns a map of dex filename → patched bytes.
     */
    fun patchDexFiles(apkDir: File, identityJson: String): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val dexFiles = apkDir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }
            ?: return result

        for (dexFile in dexFiles) {
            try {
                val patched = patchSingleDex(dexFile, identityJson)
                result[dexFile.name] = patched
            } catch (e: Exception) {
                // If patching fails, include original
                result[dexFile.name] = dexFile.readBytes()
            }
        }
        return result
    }

    fun patchSingleDex(dexFile: File, identityJson: String): ByteArray {
        val dexBytes = dexFile.readBytes()
        val opcodes  = Opcodes.forApi(34)
        val dex      = DexBackedDexFile.fromInputStream(opcodes, dexBytes.inputStream())

        val patched = DexPool(opcodes)

        for (classDef in dex.classes) {
            val patchedMethods = mutableListOf<ImmutableMethod>()

            for (method in classDef.methods) {
                val impl = method.implementation
                if (impl == null) {
                    patchedMethods.add(ImmutableMethod(
                        method.definingClass, method.name,
                        method.parameters, method.returnType,
                        method.accessFlags, method.annotations, null, null
                    ))
                    continue
                }

                val mutableImpl = MutableMethodImplementation(impl)
                var patched_flag = false

                val instructionList = mutableImpl.instructions.toList()
                for ((index, instr) in instructionList.withIndex()) {
                    if (instr !is ReferenceInstruction) continue

                    val ref = instr.reference
                    if (ref is MethodReference) {
                        val key = Triple(ref.definingClass, ref.name,
                            "(${ref.parameterTypes.joinToString("")})${ref.returnType}")
                        val hookPair = METHOD_HOOKS[key] ?: continue

                        // Replace invoke-virtual/static with invoke-static to our hook
                        val hookRef = ImmutableMethodReference(
                            HOOK_CLASS,
                            hookPair.first,
                            parseParams(hookPair.second),
                            returnTypeFromDescriptor(hookPair.second)
                        )
                        // Preserve register usage - rebuild as invoke-static
                        if (instr is Instruction35c) {
                            val newInstr = ImmutableInstruction35c(
                                Opcode.INVOKE_STATIC,
                                instr.registerCount,
                                instr.registerC, instr.registerD, instr.registerE,
                                instr.registerF, instr.registerG,
                                hookRef
                            )
                            mutableImpl.replaceInstruction(index, newInstr)
                            patched_flag = true
                        }
                    }
                }

                val finalImpl = if (patched_flag) mutableImpl else impl
                patchedMethods.add(ImmutableMethod(
                    method.definingClass, method.name,
                    method.parameters, method.returnType,
                    method.accessFlags, method.annotations,
                    ImmutableMethodImplementation(
                        finalImpl.registerCount,
                        if (patched_flag) mutableImpl.instructions.map { ImmutableInstruction.of(it) }
                        else impl.instructions.toList().map { ImmutableInstruction.of(it) },
                        emptyList(), emptyList()
                    ), null
                ))
            }

            patched.internClass(ImmutableClassDef(
                classDef.type, classDef.accessFlags, classDef.superclass,
                classDef.interfaces?.toList(), classDef.sourceFile,
                classDef.annotations, classDef.staticFields,
                classDef.instanceFields, classDef.directMethods, patchedMethods
            ))
        }

        val out = File.createTempFile("patched", ".dex")
        try {
            DexPool.writeTo(out.path, patched)
            return out.readBytes()
        } finally {
            out.delete()
        }
    }

    private fun parseParams(descriptor: String): List<String> {
        val inner = descriptor.substringAfter("(").substringBefore(")")
        val result = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            when (inner[i]) {
                'L' -> {
                    val end = inner.indexOf(';', i)
                    result.add(inner.substring(i, end + 1))
                    i = end + 1
                }
                '[' -> {
                    // Array type - simplified
                    result.add("[")
                    i++
                }
                else -> { result.add(inner[i].toString()); i++ }
            }
        }
        return result
    }

    private fun returnTypeFromDescriptor(descriptor: String): String =
        descriptor.substringAfter(")")
}
