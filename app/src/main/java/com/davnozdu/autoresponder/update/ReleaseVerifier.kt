package com.davnozdu.autoresponder.update

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Root module entry point: CLASSPATH=base.apk app_process /system/bin ...ReleaseVerifier. */
object ReleaseVerifier {
    @JvmStatic fun main(args: Array<String>) {
        try {
            when(args.firstOrNull()) {
                "module" -> {
                    require(args.size==4)
                    val key=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(File(args[3]).readBytes()))
                    val verifier=Signature.getInstance("SHA256withRSA").apply { initVerify(key) }
                    File(args[1]).inputStream().use { input ->
                        val buffer=ByteArray(65536)
                        while(true) { val n=input.read(buffer); if(n<0) break; verifier.update(buffer,0,n) }
                    }
                    check(verifier.verify(File(args[2]).readBytes())) { "Invalid module signature" }
                }
                "database" -> {
                    require(args.size==2)
                    com.davnozdu.autoresponder.store.Backup.validate(File(args[1]))
                }
                "apk" -> {
                    require(args.size==2)
                    if(android.os.Looper.getMainLooper()==null) android.os.Looper.prepareMainLooper()
                    val type=Class.forName("android.app.ActivityThread")
                    val thread=type.getMethod("systemMain").invoke(null)
                    val context=type.getMethod("getSystemContext").invoke(thread) as android.content.Context
                    val pm=context.packageManager
                    val flags=android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    val candidate=pm.getPackageArchiveInfo(args[1],flags) ?: error("Invalid APK")
                    check(candidate.packageName=="com.davnozdu.autoresponder") { "Wrong package" }
                    val installed=pm.getPackageInfo(candidate.packageName,flags)
                    check(candidate.longVersionCode>installed.longVersionCode) { "Version is not newer" }
                    val old=installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                    val new=candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                    check(!old.isNullOrEmpty() && old==new) { "APK signer changed" }
                }
                else -> error("Expected module or apk")
            }
            println("verified")
        } catch(e: Throwable) {
            System.err.println("Verification failed: ${e.message}")
            kotlin.system.exitProcess(1)
        }
        kotlin.system.exitProcess(0)
    }
}
