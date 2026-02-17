package com.foss.appcloner.cloner

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.builder.MutableMethodImplementation
import org.jf.dexlib2.builder.instruction.BuilderInstruction35c
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.*
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.dexlib2.writer.io.FileDataStore
import java.io.File
import com.google.common.collect.ImmutableList

object DexPatcher {
    private const val HOOK_CLASS = "Lcom/foss/hook/Hooks;"
    private val METHOD_HOOKS = mapOf(
        Triple("Landroid/provider/Settings\$Secure;", "getString", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;") to Pair("spoofSettingSecure", "(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;"),
        Triple("Landroid/telephony/TelephonyManager;", "getDeviceId", "()Ljava/lang/String;") to Pair("spoofImei", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),
        Triple("Landroid/telephony/TelephonyManager;", "getImei", "()Ljava/lang/String;") to Pair("spoofImei", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),
        Triple("Landroid/telephony/TelephonyManager;", "getSubscriberId", "()Ljava/lang/String;") to Pair("spoofImsi", "(Landroid/telephony/TelephonyManager;)Ljava/lang/String;"),
        Triple("Landroid/net/wifi/WifiInfo;", "getMacAddress", "()Ljava/lang/String;") to Pair("spoofWifiMac", "(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;"),
        Triple("Landroid/net/wifi/WifiInfo;", "getSSID", "()Ljava/lang/String;") to Pair("spoofSsid", "(Landroid/net/wifi/WifiInfo;)Ljava/lang/String;"),
        Triple("Landroid/webkit/WebSettings;", "getUserAgentString", "()Ljava/lang/String;") to Pair("spoofUserAgent", "(Landroid/webkit/WebSettings;)Ljava/lang/String;")
    )

    fun patchDexFiles(apkDir: File, identityJson: String): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        apkDir.listFiles { f -> f.name.matches(Regex("classes\\d*\\.dex")) }?.forEach { dex ->
            result[dex.name] = try { patchSingleDex(dex, identityJson) } catch (e: Exception) { dex.readBytes() }
        }
        return result
    }

    fun patchSingleDex(dexFile: File, identityJson: String): ByteArray {
        val dexBytes = dexFile.readBytes()
        val opcodes  = Opcodes.forApi(34)
        val dex      = DexBackedDexFile.fromInputStream(opcodes, dexBytes.inputStream())
        val patched  = DexPool(opcodes)

        for (classDef in dex.classes) {
            val patchedMethods = mutableListOf<ImmutableMethod>()
            for (method in classDef.methods) {
                val impl = method.implementation
                if (impl == null) {
                    patchedMethods.add(ImmutableMethod(
                        method.definingClass, method.name, 
                        ImmutableList.copyOf(method.parameters), 
                        method.returnType, method.accessFlags, 
                        ImmutableList.copyOf(method.annotations), 
                        null, null
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
                        val key = Triple(ref.definingClass, ref.name, "(${ref.parameterTypes.joinToString("")})${ref.returnType}")
                        val hookPair = METHOD_HOOKS[key] ?: continue
                        val hookRef = ImmutableMethodReference(HOOK_CLASS, hookPair.first, parseParams(hookPair.second), returnTypeFromDescriptor(hookPair.second))
                        if (instr is Instruction35c) {
                            // Fix: Use BuilderInstruction35c
                            val newInstr = BuilderInstruction35c(Opcode.INVOKE_STATIC, instr.registerCount, instr.registerC, instr.registerD, instr.registerE, instr.registerF, instr.registerG, hookRef)
                            mutableImpl.replaceInstruction(index, newInstr)
                            patched_flag = true
                        }
                    }
                }
                val finalImpl = if (patched_flag) mutableImpl else impl
                
                // Fix: Correct argument order and types for ImmutableMethod
                patchedMethods.add(ImmutableMethod(
                    method.definingClass, 
                    method.name, 
                    ImmutableList.copyOf(method.parameters), 
                    method.returnType, 
                    method.accessFlags, 
                    ImmutableList.copyOf(method.annotations), 
                    null, // hiddenApiRestrictions comes BEFORE implementation
                    ImmutableMethodImplementation(
                        finalImpl.registerCount, 
                        if (patched_flag) mutableImpl.instructions.map { ImmutableInstructionFactory.of(it) } 
                        else impl.instructions.toList().map { ImmutableInstructionFactory.of(it) }, 
                        emptyList(), 
                        emptyList()
                    )
                ))
            }
            patched.internClass(ImmutableClassDef(
                classDef.type, classDef.accessFlags, classDef.superclass, 
                ImmutableList.copyOf(classDef.interfaces), 
                classDef.sourceFile, 
                ImmutableList.copyOf(classDef.annotations), 
                ImmutableList.copyOf(classDef.staticFields), 
                ImmutableList.copyOf(classDef.instanceFields), 
                ImmutableList.copyOf(classDef.directMethods), 
                patchedMethods
            ))
        }
        val out = File.createTempFile("patched", ".dex")
        // Fix: Use FileDataStore for writing
        patched.writeTo(FileDataStore(out))
        return out.readBytes().also { out.delete() }
    }

    private fun parseParams(descriptor: String): List<String> {
        val inner = descriptor.substringAfter("(").substringBefore(")")
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
    private fun returnTypeFromDescriptor(descriptor: String): String = descriptor.substringAfter(")")
}
