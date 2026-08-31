package com.nexustvguide.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.nexustvguide.app.data.repository.UpdateRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class InstallEvent {
    data class PendingUserAction(val sessionId: Int, val confirmIntent: Intent) : InstallEvent()
    data class Success(val sessionId: Int, val targetVersionCode: Long) : InstallEvent()
    data class Aborted(val sessionId: Int) : InstallEvent()
    data class Blocked(val sessionId: Int, val message: String) : InstallEvent()
    data class Invalid(val sessionId: Int, val message: String) : InstallEvent()
    data class Storage(val sessionId: Int, val message: String) : InstallEvent()
    data class Failed(val sessionId: Int, val statusCode: Int, val message: String) : InstallEvent()
}

class UpdateInstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "UpdateInstallReceiver"
        const val ACTION_INSTALL_STATUS = "com.nexustvguide.app.action.INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "com.nexustvguide.app.extra.SESSION_ID"
        const val EXTRA_TARGET_VERSION_CODE = "com.nexustvguide.app.extra.TARGET_VERSION_CODE"

        private val _events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 10)
        val events: SharedFlow<InstallEvent> = _events.asSharedFlow()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        val targetVersionCode = intent.getLongExtra(EXTRA_TARGET_VERSION_CODE, -1L)

        Log.d(TAG, "Install callback received for session $sessionId: status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    _events.tryEmit(InstallEvent.PendingUserAction(sessionId, confirmIntent))
                    try {
                        context.startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start pending user action intent", e)
                    }
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION received without Intent.EXTRA_INTENT")
                    _events.tryEmit(InstallEvent.Failed(sessionId, status, "Geen systeemdialoog intent ontvangen"))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "PackageInstaller session $sessionId succeeded.")
                val repo = UpdateRepository(context)
                repo.clearPendingSession()
                repo.cleanupOldUpdateFiles()
                _events.tryEmit(InstallEvent.Success(sessionId, targetVersionCode))
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "PackageInstaller session $sessionId aborted by user.")
                _events.tryEmit(InstallEvent.Aborted(sessionId))
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.w(TAG, "PackageInstaller session $sessionId blocked by device policy or permissions: $message")
                _events.tryEmit(InstallEvent.Blocked(sessionId, message))
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "PackageInstaller session $sessionId failed with INVALID APK/signature: $message")
                _events.tryEmit(InstallEvent.Invalid(sessionId, message))
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "PackageInstaller session $sessionId failed due to STORAGE: $message")
                _events.tryEmit(InstallEvent.Storage(sessionId, message))
            }

            else -> {
                Log.e(TAG, "PackageInstaller session $sessionId failed with code $status: $message")
                _events.tryEmit(InstallEvent.Failed(sessionId, status, message))
            }
        }
    }
}
