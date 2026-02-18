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
import org.jf.dexlib2.immutable.debug.ImmutableDebugItem
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.dexlib2.writer.io.FileDataStore
import java.io.File
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet

object DexPatcher {
    private const val HOOK_CLASS = "Lcom/foss/hook/Hooks;"
    
    // Map of method descriptors to hook methods
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
                // Convert parameters and annotations to Immutable variants strictly
                val immutableParams = ImmutableList.copyOf(method.parameters.map { ImmutableMethodParameter.of(it) })
                val immutableAnnos  = ImmutableSet.copyOf(method.annotations.map { ImmutableAnnotation.of(it) })

                val impl = method.implementation
                if (impl == null) {
                    patchedMethods.add(ImmutableMethod(
                        method.definingClass, method.name, 
                        immutableParams, 
                        method.returnType, method.accessFlags, 
                        immutableAnnos, 
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
                            // Use BuilderInstruction for the replacement within MutableMethodImplementation
                            val newInstr = BuilderInstruction35c(Opcode.INVOKE_STATIC, instr.registerCount, instr.registerC, instr.registerD, instr.registerE, instr.registerF, instr.registerG, hookRef)
                            mutableImpl.replaceInstruction(index, newInstr)
                            patched_flag = true
                        }
                    }
                }

                // If patched, we must convert the Mutable instructions back to Immutable instructions
                val finalInstructions = if (patched_flag) {
                    ImmutableList.copyOf(mutableImpl.instructions.map { ImmutableInstruction.of(it) })
                } else {
                    ImmutableList.copyOf(impl.instructions.map { ImmutableInstruction.of(it) })
                }

                val finalTryBlocks = if (patched_flag) {
                    ImmutableList.copyOf(mutableImpl.tryBlocks.map { ImmutableTryBlock.of(it) })
                } else {
                    ImmutableList.copyOf(impl.tryBlocks.map { ImmutableTryBlock.of(it) })
                }

                val finalDebugItems = if (patched_flag) {
                    ImmutableList.copyOf(mutableImpl.debugItems.map { ImmutableDebugItem.of(it) })
                } else {
                    ImmutableList.copyOf(impl.debugItems.map { ImmutableDebugItem.of(it) })
                }

                patchedMethods.add(ImmutableMethod(
                    method.definingClass, 
                    method.name, 
                    immutableParams, 
                    method.returnType, 
                    method.accessFlags, 
                    immutableAnnos, 
                    null, // hiddenApiRestrictions
                    ImmutableMethodImplementation(
                        if (patched_flag) mutableImpl.registerCount else impl.registerCount,
                        finalInstructions,
                        finalTryBlocks,
                        finalDebugItems
                    )
                ))
            }

            // Convert ClassDef fields/annotations/interfaces to Immutable variants
            patched.internClass(ImmutableClassDef(
                classDef.type, 
                classDef.accessFlags, 
                classDef.superclass, 
                ImmutableList.copyOf(classDef.interfaces), 
                classDef.sourceFile, 
                ImmutableSet.copyOf(classDef.annotations.map { ImmutableAnnotation.of(it) }), 
                ImmutableList.copyOf(classDef.staticFields.map { ImmutableField.of(it) }), 
                ImmutableList.copyOf(classDef.instanceFields.map { ImmutableField.of(it) }), 
                ImmutableList.copyOf(classDef.directMethods.map { ImmutableMethod.of(it) }), 
                patchedMethods // The list we manually built above
            ))
        }

        val out = File.createTempFile("patched", ".dex")
        // Use FileDataStore to write the DexPool to disk
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
