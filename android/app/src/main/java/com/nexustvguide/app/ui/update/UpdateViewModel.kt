package com.nexustvguide.app.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexustvguide.app.R
import com.nexustvguide.app.data.repository.DownloadState
import com.nexustvguide.app.data.repository.UpdateCheckResult
import com.nexustvguide.app.data.repository.UpdateError
import com.nexustvguide.app.data.repository.UpdateRepository
import com.nexustvguide.app.update.ApkInstaller
import com.nexustvguide.app.update.ApkVerificationResult
import com.nexustvguide.app.update.ApkVerifier
import com.nexustvguide.app.update.InstallEvent
import com.nexustvguide.app.update.UpdateInstallResultReceiver
import com.nexustvguide.app.update.ValidatedUpdateMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class UpdateUiState {
    object Idle : UpdateUiState()
    data class Checking(val isManual: Boolean) : UpdateUiState()
    data class UpdateAvailable(val metadata: ValidatedUpdateMetadata) : UpdateUiState()
    data class Downloading(
        val metadata: ValidatedUpdateMetadata,
        val bytesRead: Long,
        val totalBytes: Long,
        val percent: Int
    ) : UpdateUiState()
    data class Verifying(val metadata: ValidatedUpdateMetadata) : UpdateUiState()
    data class PermissionRequired(
        val metadata: ValidatedUpdateMetadata,
        val apkFile: File,
        val settingsIntent: Intent
    ) : UpdateUiState()
    data class ReadyToInstall(
        val metadata: ValidatedUpdateMetadata,
        val apkFile: File
    ) : UpdateUiState()
    data class WaitingForSystemDialog(
        val metadata: ValidatedUpdateMetadata,
        val sessionId: Int
    ) : UpdateUiState()
    data class UpToDate(val versionName: String, val versionCode: Long) : UpdateUiState()
    data class Error(
        val title: String,
        val message: String,
        val canRetry: Boolean,
        val metadata: ValidatedUpdateMetadata? = null
    ) : UpdateUiState()
}

sealed class UpdateNavigationEvent {
    object ShowUpdateDialog : UpdateNavigationEvent()
    object DismissDialog : UpdateNavigationEvent()
}

class UpdateViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: UpdateRepository = UpdateRepository(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val _navEvents = MutableSharedFlow<UpdateNavigationEvent>(extraBufferCapacity = 5)
    val navEvents: SharedFlow<UpdateNavigationEvent> = _navEvents.asSharedFlow()

    private var downloadJob: Job? = null
    private var activeMetadata: ValidatedUpdateMetadata? = null
    private var stagedApkFile: File? = null

    init {
        // Reconcilieer bewaarde sessie na start
        repository.reconcileStartupState()

        // Luister naar installatie-events van de PackageInstaller callback
        viewModelScope.launch {
            UpdateInstallResultReceiver.events.collect { event ->
                handleInstallEvent(event)
            }
        }
    }

    private fun handleInstallEvent(event: InstallEvent) {
        when (event) {
            is InstallEvent.PendingUserAction -> {
                // Systeemdialoog geopend door receiver
                val meta = activeMetadata
                if (meta != null) {
                    _uiState.value = UpdateUiState.WaitingForSystemDialog(meta, event.sessionId)
                }
            }
            is InstallEvent.Success -> {
                _uiState.value = UpdateUiState.Idle
                _navEvents.tryEmit(UpdateNavigationEvent.DismissDialog)
            }
            is InstallEvent.Aborted -> {
                // Gebruiker heeft in het Android-dialoogvenster geannuleerd
                val apk = stagedApkFile
                val meta = activeMetadata
                if (apk != null && meta != null && apk.exists()) {
                    _uiState.value = UpdateUiState.ReadyToInstall(meta, apk)
                } else {
                    _uiState.value = UpdateUiState.Idle
                }
            }
            is InstallEvent.Blocked -> {
                _uiState.value = UpdateUiState.Error(
                    title = getApplication<Application>().getString(R.string.update_error_title),
                    message = "Installatie geblokkeerd door apparaatbeleid: ${event.message}",
                    canRetry = true,
                    metadata = activeMetadata
                )
            }
            is InstallEvent.Invalid -> {
                _uiState.value = UpdateUiState.Error(
                    title = getApplication<Application>().getString(R.string.update_error_title),
                    message = "Installatie geweigerd (ongeldige APK of handtekening): ${event.message}",
                    canRetry = false,
                    metadata = activeMetadata
                )
            }
            is InstallEvent.Storage -> {
                _uiState.value = UpdateUiState.Error(
                    title = getApplication<Application>().getString(R.string.update_error_title),
                    message = "Onvoldoende opslagruimte voor installatie: ${event.message}",
                    canRetry = true,
                    metadata = activeMetadata
                )
            }
            is InstallEvent.Failed -> {
                _uiState.value = UpdateUiState.Error(
                    title = getApplication<Application>().getString(R.string.update_error_title),
                    message = "Installatiefout (code ${event.statusCode}): ${event.message}",
                    canRetry = true,
                    metadata = activeMetadata
                )
            }
        }
    }

    fun checkForUpdates(isManual: Boolean, customBaseUrl: String? = null) {
        if (_uiState.value is UpdateUiState.Checking || _uiState.value is UpdateUiState.Downloading) {
            return
        }

        _uiState.value = UpdateUiState.Checking(isManual)
        if (isManual) {
            _navEvents.tryEmit(UpdateNavigationEvent.ShowUpdateDialog)
        }

        viewModelScope.launch {
            when (val result = repository.checkForUpdates(isManual, customBaseUrl)) {
                is UpdateCheckResult.UpdateAvailable -> {
                    activeMetadata = result.metadata
                    if (isManual || !result.isSnoozed) {
                        _uiState.value = UpdateUiState.UpdateAvailable(result.metadata)
                        _navEvents.tryEmit(UpdateNavigationEvent.ShowUpdateDialog)
                    } else {
                        _uiState.value = UpdateUiState.Idle
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    if (isManual) {
                        _uiState.value = UpdateUiState.UpToDate(result.currentVersionName, result.currentVersionCode)
                    } else {
                        _uiState.value = UpdateUiState.Idle
                    }
                }
                is UpdateCheckResult.Throttled -> {
                    _uiState.value = UpdateUiState.Idle
                }
                is UpdateCheckResult.Error -> {
                    if (isManual) {
                        _uiState.value = UpdateUiState.Error(
                            title = getApplication<Application>().getString(R.string.update_error_title),
                            message = result.message,
                            canRetry = true
                        )
                    } else {
                        _uiState.value = UpdateUiState.Idle
                    }
                }
            }
        }
    }

    fun startDownload(metadata: ValidatedUpdateMetadata) {
        activeMetadata = metadata
        downloadJob?.cancel()

        downloadJob = viewModelScope.launch {
            _uiState.value = UpdateUiState.Downloading(metadata, 0, metadata.fileSizeBytes, 0)

            repository.downloadApk(metadata).collect { downloadState ->
                when (downloadState) {
                    is DownloadState.Progress -> {
                        _uiState.value = UpdateUiState.Downloading(
                            metadata,
                            downloadState.bytesRead,
                            downloadState.totalBytes,
                            downloadState.progressPercent
                        )
                    }
                    is DownloadState.Success -> {
                        stagedApkFile = downloadState.apkFile
                        verifyAndProceed(downloadState.apkFile, metadata)
                    }
                    is DownloadState.Failed -> {
                        handleDownloadError(downloadState.error, metadata)
                    }
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        repository.cleanupOldUpdateFiles()
        _uiState.value = UpdateUiState.Idle
        _navEvents.tryEmit(UpdateNavigationEvent.DismissDialog)
    }

    private fun handleDownloadError(error: UpdateError, metadata: ValidatedUpdateMetadata) {
        val app = getApplication<Application>()
        val (title, msg, canRetry) = when (error) {
            is UpdateError.InsufficientStorage -> Triple(
                app.getString(R.string.update_error_title),
                error.detailMessage.ifEmpty { "Onvoldoende opslagruimte." },
                false
            )
            is UpdateError.ChecksumMismatch -> Triple(
                app.getString(R.string.update_error_title),
                "Checksum controle mislukt. Het gedownloade bestand is mogelijk beschadigd.",
                true
            )
            is UpdateError.PreflightFailed -> Triple(
                app.getString(R.string.update_error_title),
                "APK verificatie mislukt: ${error.detailMessage}",
                false
            )
            is UpdateError.NetworkError -> Triple(
                app.getString(R.string.update_error_title),
                "Netwerkfout tijdens downloaden: ${error.detailMessage}",
                true
            )
            is UpdateError.ContractError -> Triple(
                app.getString(R.string.update_error_title),
                "Updatecontract fout: ${error.detailMessage}",
                false
            )
            is UpdateError.Cancelled -> Triple(
                app.getString(R.string.update_dialog_title_downloading),
                "Download geannuleerd.",
                true
            )
            is UpdateError.Unknown -> Triple(
                app.getString(R.string.update_error_title),
                error.detailMessage.ifEmpty { "Onbekende downloadfout opgetreden." },
                true
            )
        }

        if (error is UpdateError.Cancelled) {
            _uiState.value = UpdateUiState.Idle
        } else {
            _uiState.value = UpdateUiState.Error(title, msg, canRetry, metadata)
        }
    }

    private fun verifyAndProceed(apkFile: File, metadata: ValidatedUpdateMetadata) {
        _uiState.value = UpdateUiState.Verifying(metadata)
        val context = getApplication<Application>()

        val preflightResult = ApkVerifier.verify(context, apkFile, metadata)
        when (preflightResult) {
            is ApkVerificationResult.Failure -> {
                apkFile.delete()
                _uiState.value = UpdateUiState.Error(
                    title = context.getString(R.string.update_error_title),
                    message = "${preflightResult.reason}: ${preflightResult.detailMessage}",
                    canRetry = false,
                    metadata = metadata
                )
            }
            is ApkVerificationResult.Success -> {
                if (!ApkInstaller.canRequestPackageInstalls(context)) {
                    val settingsIntent = ApkInstaller.getManageUnknownAppSourcesIntent(context)
                    _uiState.value = UpdateUiState.PermissionRequired(metadata, apkFile, settingsIntent)
                } else {
                    _uiState.value = UpdateUiState.ReadyToInstall(metadata, apkFile)
                    commitInstallation(context, apkFile, metadata)
                }
            }
        }
    }

    fun onResumeFromSettings() {
        val current = _uiState.value
        if (current is UpdateUiState.PermissionRequired) {
            val context = getApplication<Application>()
            if (ApkInstaller.canRequestPackageInstalls(context)) {
                _uiState.value = UpdateUiState.ReadyToInstall(current.metadata, current.apkFile)
                commitInstallation(context, current.apkFile, current.metadata)
            }
        }
    }

    fun commitInstallation(context: Context, apkFile: File, metadata: ValidatedUpdateMetadata) {
        viewModelScope.launch {
            try {
                _uiState.value = UpdateUiState.Verifying(metadata)
                val sessionId = ApkInstaller.stageAndCommitSession(context, apkFile, metadata, repository)
                _uiState.value = UpdateUiState.WaitingForSystemDialog(metadata, sessionId)
            } catch (e: Exception) {
                _uiState.value = UpdateUiState.Error(
                    title = context.getString(R.string.update_error_title),
                    message = "Fout bij starten van installatie: ${e.localizedMessage}",
                    canRetry = true,
                    metadata = metadata
                )
            }
        }
    }

    fun snooze(versionCode: Long) {
        repository.snoozeUpdate(versionCode)
        _uiState.value = UpdateUiState.Idle
        _navEvents.tryEmit(UpdateNavigationEvent.DismissDialog)
    }

    fun dismiss() {
        _uiState.value = UpdateUiState.Idle
        _navEvents.tryEmit(UpdateNavigationEvent.DismissDialog)
    }
}
