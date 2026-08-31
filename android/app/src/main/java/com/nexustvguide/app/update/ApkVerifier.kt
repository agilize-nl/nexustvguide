package com.nexustvguide.app.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.nexustvguide.app.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.Arrays

sealed class ApkVerificationResult {
    data class Success(val packageInfo: PackageInfo, val versionCode: Long) : ApkVerificationResult()
    data class Failure(val reason: String, val detailMessage: String = "") : ApkVerificationResult()
}

object ApkVerifier {

    fun verify(
        context: Context,
        apkFile: File,
        expectedMetadata: ValidatedUpdateMetadata
    ): ApkVerificationResult {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return ApkVerificationResult.Failure("APK-bestand bestaat niet of is niet leesbaar")
        }

        val pm = context.packageManager

        @Suppress("DEPRECATION")
        val archiveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo: PackageInfo? = try {
            pm.getPackageArchiveInfo(apkFile.absolutePath, archiveFlags)
        } catch (e: Exception) {
            return ApkVerificationResult.Failure("Kan APK-archief niet inspecteren", e.localizedMessage ?: "")
        }

        if (archiveInfo == null) {
            return ApkVerificationResult.Failure("APK-archief is ongeldig of beschadigd")
        }

        // 1. Package name check
        if (archiveInfo.packageName != context.packageName) {
            return ApkVerificationResult.Failure(
                "Verkeerd packageName",
                "Verwacht: ${context.packageName}, Ontvangen: ${archiveInfo.packageName}"
            )
        }

        // 2. Version code check
        @Suppress("DEPRECATION")
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }

        if (apkVersionCode != expectedMetadata.versionCode) {
            return ApkVerificationResult.Failure(
                "Versiecode komt niet overeen met metadata",
                "Verwacht: ${expectedMetadata.versionCode}, In APK: $apkVersionCode"
            )
        }

        val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
        if (apkVersionCode <= currentVersionCode) {
            return ApkVerificationResult.Failure(
                "APK bevat geen nieuwere versie",
                "Huidig: $currentVersionCode, In APK: $apkVersionCode"
            )
        }

        // 3. Min SDK check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val minSdk = archiveInfo.applicationInfo?.minSdkVersion ?: 0
            if (minSdk > Build.VERSION.SDK_INT) {
                return ApkVerificationResult.Failure(
                    "APK vereist nieuwere Android-versie",
                    "MinSDK: $minSdk, Apparaat SDK: ${Build.VERSION.SDK_INT}"
                )
            }
        }

        // 4. Signing certificate verification
        val isSignatureValid = verifySignatures(context, archiveInfo)
        if (!isSignatureValid) {
            return ApkVerificationResult.Failure(
                "Signing-certificaat komt niet overeen met de geïnstalleerde app",
                "De gedownloade APK is ondertekend met een andere signing key dan de actieve app."
            )
        }

        return ApkVerificationResult.Success(archiveInfo, apkVersionCode)
    }

    private fun verifySignatures(context: Context, archiveInfo: PackageInfo): Boolean {
        val pm = context.packageManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val currentPkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val currentSigningInfo = currentPkgInfo.signingInfo ?: return false
                val archiveSigningInfo = archiveInfo.signingInfo ?: return false

                val currentCerts = if (currentSigningInfo.hasMultipleSigners()) {
                    currentSigningInfo.apkContentsSigners
                } else {
                    currentSigningInfo.signingCertificateHistory
                }

                val archiveCerts = if (archiveSigningInfo.hasMultipleSigners()) {
                    archiveSigningInfo.apkContentsSigners
                } else {
                    archiveSigningInfo.signingCertificateHistory
                }

                if (currentCerts.isNullOrEmpty() || archiveCerts.isNullOrEmpty()) {
                    return false
                }

                // Check of minstens één van de actieve certificaten matcht
                for (cur in currentCerts) {
                    val curBytes = cur.toByteArray()
                    for (arc in archiveCerts) {
                        if (Arrays.equals(curBytes, arc.toByteArray())) {
                            return true
                        }
                    }
                }
                return false
            } else {
                @Suppress("DEPRECATION")
                val currentPkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val currentSigs = currentPkgInfo.signatures
                @Suppress("DEPRECATION")
                val archiveSigs = archiveInfo.signatures

                if (currentSigs.isNullOrEmpty() || archiveSigs.isNullOrEmpty()) {
                    return false
                }

                for (cur in currentSigs) {
                    val curBytes = cur.toByteArray()
                    for (arc in archiveSigs) {
                        if (Arrays.equals(curBytes, arc.toByteArray())) {
                            return true
                        }
                    }
                }
                return false
            }
        } catch (_: Exception) {
            return false
        }
    }
}
