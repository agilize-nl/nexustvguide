package com.nexustvguide.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.nexustvguide.app.data.repository.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object ApkInstaller {

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun getManageUnknownAppSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    suspend fun stageAndCommitSession(
        context: Context,
        apkFile: File,
        metadata: ValidatedUpdateMetadata,
        updateRepository: UpdateRepository = UpdateRepository(context)
    ): Int = withContext(Dispatchers.IO) {
        val packageInstaller = context.packageManager.packageInstaller

        // 1. Ruim oude niet-gecommitte eigen sessies op
        abandonStaleSessions(packageInstaller)

        // 2. Maak SessionParams aan
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(apkFile.length())
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        try {
            // 3. Kopieer APK bytes naar de installer sessie
            session.openWrite("NexusTVGuideUpdate", 0, apkFile.length()).use { output ->
                FileInputStream(apkFile).use { input ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
                session.fsync(output)
            }

            // 4. PendingIntent opbouwen
            val callbackIntent = Intent(context, UpdateInstallResultReceiver::class.java).apply {
                action = UpdateInstallResultReceiver.ACTION_INSTALL_STATUS
                putExtra(UpdateInstallResultReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(UpdateInstallResultReceiver.EXTRA_TARGET_VERSION_CODE, metadata.versionCode)
            }

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            )

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )

            // 5. Bewaar de sessie-ID en doelversie vóór commit
            updateRepository.savePendingSession(sessionId, metadata.versionCode)

            // 6. Commit de sessie
            session.commit(pendingIntent.intentSender)
            session.close()

            return@withContext sessionId
        } catch (e: Exception) {
            try {
                session.abandon()
            } catch (_: Exception) {}
            throw IOException("Fout bij het overdragen van de APK naar de Package Installer: ${e.localizedMessage}", e)
        }
    }

    private fun abandonStaleSessions(packageInstaller: PackageInstaller) {
        try {
            val mySessions = packageInstaller.mySessions
            for (sessionInfo in mySessions) {
                // Abandon sessies die niet geactiveerd zijn
                if (!sessionInfo.isActive && sessionInfo.mode == PackageInstaller.SessionParams.MODE_FULL_INSTALL) {
                    try {
                        val session = packageInstaller.openSession(sessionInfo.sessionId)
                        session.abandon()
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }
}
