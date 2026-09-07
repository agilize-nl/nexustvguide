package com.nexustvguide.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.data.api.UpdateApiService
import com.nexustvguide.app.data.api.UpdateHttpClient
import com.nexustvguide.app.update.MetadataValidationResult
import com.nexustvguide.app.update.UpdateChannel
import com.nexustvguide.app.update.Sha256Checksum
import com.nexustvguide.app.update.UpdateMetadataValidator
import com.nexustvguide.app.update.ValidatedUpdateMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

interface TimeProvider {
    fun currentTimeMillis(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val metadata: ValidatedUpdateMetadata,
        val isSnoozed: Boolean,
        val channel: UpdateChannel? = null
    ) : UpdateCheckResult()
    data class UpToDate(val currentVersionCode: Long, val currentVersionName: String) : UpdateCheckResult()
    data class Throttled(val remainingTimeMs: Long) : UpdateCheckResult()
    data class Error(val message: String, val isContractError: Boolean) : UpdateCheckResult()
}

sealed class DownloadState {
    data class Progress(val bytesRead: Long, val totalBytes: Long, val progressPercent: Int) : DownloadState()
    data class Success(val apkFile: File, val metadata: ValidatedUpdateMetadata) : DownloadState()
    data class Failed(val error: UpdateError) : DownloadState()
}

sealed class UpdateError(open val detailMessage: String = "") {
    data class InsufficientStorage(override val detailMessage: String = "") : UpdateError(detailMessage)
    data class ChecksumMismatch(override val detailMessage: String = "") : UpdateError(detailMessage)
    data class PreflightFailed(override val detailMessage: String = "") : UpdateError(detailMessage)
    data class NetworkError(override val detailMessage: String = "") : UpdateError(detailMessage)
    data class ContractError(override val detailMessage: String = "") : UpdateError(detailMessage)
    data class Cancelled(override val detailMessage: String = "Download geannuleerd") : UpdateError(detailMessage)
    data class Unknown(override val detailMessage: String = "") : UpdateError(detailMessage)
}

class UpdateRepository(
    private val context: Context,
    private val apiService: UpdateApiService = UpdateHttpClient.getService(),
    private val okHttpClient: OkHttpClient = UpdateHttpClient.getOkHttpClient(),
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * De bronnen die een controle achtereenvolgens probeert: eerst het geconfigureerde
     * kanaal, dan het LAN-noodkanaal. Injecteerbaar zodat tests de volgorde kunnen sturen.
     */
    private val channelProvider: () -> List<UpdateChannel> = { UpdateHttpClient.channels() },
    /** Levert de client voor een kanaal; per bron een eigen Retrofit-instantie. */
    private val serviceProvider: (UpdateChannel) -> UpdateApiService = { UpdateHttpClient.getService(it.baseUrl) }
) {

    companion object {
        const val PREFS_NAME = "nexus_update_preferences"
        const val KEY_LAST_PASSIVE_CHECK_TIME = "last_passive_check_success_time"
        const val KEY_SNOOZED_VERSION = "snoozed_version_code"
        const val KEY_PENDING_SESSION_ID = "pending_session_id"
        const val KEY_PENDING_TARGET_VERSION = "pending_target_version_code"

        const val PASSIVE_CHECK_THROTTLE_MS = 24 * 60 * 60 * 1000L // 24 uur
        const val PROGRESS_EMIT_INTERVAL_MS = 100L // Max 10 updates per seconde
        const val EXTRA_STORAGE_MARGIN_BYTES = 25 * 1024 * 1024L // 25 MiB
    }

    private val downloadMutex = Mutex()

    /** Het kanaal waar de laatst gevonden update vandaan kwam; de download volgt diezelfde bron. */
    @Volatile
    private var activeChannel: UpdateChannel? = null

    fun getUpdatesDir(): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun cleanupOldUpdateFiles(excludeVersionCode: Long? = null) {
        try {
            val dir = getUpdatesDir()
            val files = dir.listFiles() ?: return
            val excludeApk = excludeVersionCode?.let { "update_$it.apk" }
            val excludePart = excludeVersionCode?.let { "update_$it.apk.part" }

            for (file in files) {
                val name = file.name
                if ((name.startsWith("update_") && name.endsWith(".apk")) ||
                    (name.startsWith("update_") && name.endsWith(".apk.part"))
                ) {
                    if (name != excludeApk && name != excludePart) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {
            // Geen harde crash op cleanup
        }
    }

    suspend fun checkForUpdates(isManual: Boolean, customBaseUrl: String? = null): UpdateCheckResult = withContext(ioDispatcher) {
        val now = timeProvider.currentTimeMillis()

        if (!isManual) {
            val lastSuccessTime = sharedPreferences.getLong(KEY_LAST_PASSIVE_CHECK_TIME, 0L)
            if (lastSuccessTime > 0L) {
                val elapsed = now - lastSuccessTime
                if (elapsed in 0 until PASSIVE_CHECK_THROTTLE_MS) {
                    return@withContext UpdateCheckResult.Throttled(PASSIVE_CHECK_THROTTLE_MS - elapsed)
                }
            }
        }

        // Een expliciete customBaseUrl (tests, handmatige override) verslaat de
        // kanaalvolgorde; anders proberen we het geconfigureerde kanaal en daarna het LAN.
        val channels = if (customBaseUrl != null) {
            listOf(UpdateChannel.primary(customBaseUrl).copy(allowInsecure = true))
        } else {
            channelProvider()
        }

        var lastError: UpdateCheckResult.Error? = null

        for (channel in channels) {
            UpdateHttpClient.setActiveChannel(channel)
            val service = if (customBaseUrl != null) apiService else serviceProvider(channel)

            val outcome = try {
                val dto = service.getLatestVersion(channel.manifestPath)
                val validation = UpdateMetadataValidator.validate(
                    dto = dto,
                    updateBaseUrl = channel.baseUrl,
                    allowlist = channel.allowlist,
                    allowInsecure = channel.allowInsecure
                )

                when (validation) {
                    is MetadataValidationResult.Invalid ->
                        UpdateCheckResult.Error(
                            "Ongeldige release metadata: ${validation.reason}",
                            isContractError = true
                        )
                    is MetadataValidationResult.Success -> {
                        val metadata = validation.metadata
                        val currentVersionCode = BuildConfig.VERSION_CODE.toLong()

                        if (!isManual) {
                            sharedPreferences.edit().putLong(KEY_LAST_PASSIVE_CHECK_TIME, now).apply()
                        }

                        if (metadata.versionCode <= currentVersionCode) {
                            UpdateCheckResult.UpToDate(currentVersionCode, BuildConfig.VERSION_NAME)
                        } else {
                            val snoozedVersion = sharedPreferences.getLong(KEY_SNOOZED_VERSION, 0L)
                            UpdateCheckResult.UpdateAvailable(
                                metadata,
                                isSnoozed = (snoozedVersion == metadata.versionCode),
                                channel = channel
                            )
                        }
                    }
                }
            } catch (e: HttpException) {
                if (e.code() == 503) {
                    if (!isManual) {
                        sharedPreferences.edit().putLong(KEY_LAST_PASSIVE_CHECK_TIME, now).apply()
                    }
                    UpdateCheckResult.UpToDate(BuildConfig.VERSION_CODE.toLong(), BuildConfig.VERSION_NAME)
                } else {
                    UpdateCheckResult.Error("HTTP fout: ${e.code()} ${e.message()}", isContractError = false)
                }
            } catch (e: IOException) {
                UpdateCheckResult.Error(
                    "Kan updatebron (${channel.label}) niet bereiken: ${e.localizedMessage ?: "Netwerkfout"}",
                    isContractError = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCheckResult.Error("Onverwachte fout: ${e.localizedMessage ?: "Fout"}", isContractError = false)
            }

            // Een bereikte bron is beslissend: alleen als het kanaal zelf onbruikbaar is
            // (netwerk- of contractfout) schuiven we door naar het volgende.
            if (outcome !is UpdateCheckResult.Error) {
                if (outcome is UpdateCheckResult.UpdateAvailable) {
                    activeChannel = outcome.channel
                }
                return@withContext outcome
            }
            lastError = outcome
        }

        lastError ?: UpdateCheckResult.Error("Geen updatebron beschikbaar", isContractError = false)
    }

    fun snoozeUpdate(versionCode: Long) {
        sharedPreferences.edit().putLong(KEY_SNOOZED_VERSION, versionCode).apply()
    }

    fun clearSnooze() {
        sharedPreferences.edit().remove(KEY_SNOOZED_VERSION).apply()
    }

    fun savePendingSession(sessionId: Int, targetVersionCode: Long) {
        sharedPreferences.edit()
            .putInt(KEY_PENDING_SESSION_ID, sessionId)
            .putLong(KEY_PENDING_TARGET_VERSION, targetVersionCode)
            .apply()
    }

    fun getPendingSessionId(): Int? {
        val id = sharedPreferences.getInt(KEY_PENDING_SESSION_ID, -1)
        return if (id != -1) id else null
    }

    fun getPendingTargetVersionCode(): Long? {
        val vc = sharedPreferences.getLong(KEY_PENDING_TARGET_VERSION, -1L)
        return if (vc != -1L) vc else null
    }

    fun clearPendingSession() {
        sharedPreferences.edit()
            .remove(KEY_PENDING_SESSION_ID)
            .remove(KEY_PENDING_TARGET_VERSION)
            .apply()
    }

    fun reconcileStartupState(): Boolean {
        val pendingTarget = getPendingTargetVersionCode() ?: return false
        val current = BuildConfig.VERSION_CODE.toLong()

        if (current >= pendingTarget) {
            clearPendingSession()
            cleanupOldUpdateFiles()
            return true
        }
        return false
    }

    fun downloadApk(metadata: ValidatedUpdateMetadata): Flow<DownloadState> = flow {
        downloadMutex.withLock {
            val updatesDir = getUpdatesDir()
            val targetFile = File(updatesDir, "update_${metadata.versionCode}.apk")
            val partFile = File(updatesDir, "update_${metadata.versionCode}.apk.part")

            // 1. Controleer of het bestand al eerder volledig en geldig is gedownload
            if (targetFile.exists() && targetFile.length() == metadata.fileSizeBytes) {
                if (Sha256Checksum.verify(targetFile, metadata.sha256)) {
                    emit(DownloadState.Progress(metadata.fileSizeBytes, metadata.fileSizeBytes, 100))
                    emit(DownloadState.Success(targetFile, metadata))
                    return@flow
                } else {
                    targetFile.delete()
                }
            }

            // 2. Opslagruimte controleren
            val requiredSpace = (2 * metadata.fileSizeBytes) + EXTRA_STORAGE_MARGIN_BYTES
            val usableSpace = context.cacheDir.usableSpace
            if (usableSpace < requiredSpace) {
                emit(DownloadState.Failed(UpdateError.InsufficientStorage(
                    "Onvoldoende vrije opslagruimte. Vereist: ${requiredSpace / (1024 * 1024)} MB, Beschikbaar: ${usableSpace / (1024 * 1024)} MB"
                )))
                return@flow
            }

            // 3. Oude bestanden opruimen
            cleanupOldUpdateFiles(excludeVersionCode = metadata.versionCode)
            if (partFile.exists()) {
                partFile.delete()
            }

            // 4. Download starten via OkHttp. Zet eerst het kanaal terug dat deze metadata
            // leverde, zodat de redirect-interceptor de juiste allowlist en transportregels
            // hanteert -- een LAN-download over http mag niet de https-eis van het publieke
            // kanaal omzeilen en andersom.
            activeChannel?.let { UpdateHttpClient.setActiveChannel(it) }
            val request = Request.Builder()
                .url(metadata.downloadUrl)
                .build()

            var lastEmitTime = 0L

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        emit(DownloadState.Failed(UpdateError.NetworkError("HTTP ${response.code}: ${response.message}")))
                        return@flow
                    }

                    val body = response.body
                    if (body == null) {
                        emit(DownloadState.Failed(UpdateError.NetworkError("Lege responsebody ontvangen")))
                        return@flow
                    }

                    val contentLength = body.contentLength()
                    if (contentLength > 0 && contentLength != metadata.fileSizeBytes) {
                        emit(DownloadState.Failed(UpdateError.ContractError(
                            "Content-Length mismatch: verwacht ${metadata.fileSizeBytes}, ontvangen $contentLength"
                        )))
                        return@flow
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead = 0L
                            var read: Int

                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesRead += read

                                if (bytesRead > metadata.fileSizeBytes) {
                                    throw IOException("Bestand overschrijdt verwachte grootte van ${metadata.fileSizeBytes} bytes")
                                }

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= PROGRESS_EMIT_INTERVAL_MS || bytesRead == metadata.fileSizeBytes) {
                                    val percent = if (metadata.fileSizeBytes > 0) {
                                        ((bytesRead * 100) / metadata.fileSizeBytes).toInt().coerceIn(0, 100)
                                    } else 0
                                    emit(DownloadState.Progress(bytesRead, metadata.fileSizeBytes, percent))
                                    lastEmitTime = now
                                }
                            }
                            output.flush()
                        }
                    }

                    // 5. Bestandsgrootte en SHA-256 verificatie
                    if (partFile.length() != metadata.fileSizeBytes) {
                        partFile.delete()
                        emit(DownloadState.Failed(UpdateError.ContractError(
                            "Download onvolledig: ${partFile.length()} van ${metadata.fileSizeBytes} bytes"
                        )))
                        return@flow
                    }

                    if (!Sha256Checksum.verify(partFile, metadata.sha256)) {
                        partFile.delete()
                        emit(DownloadState.Failed(UpdateError.ChecksumMismatch(
                            "SHA-256 checksum komt niet overeen met release-metadata"
                        )))
                        return@flow
                    }

                    // 6. Atomaire hernoeming
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }

                    emit(DownloadState.Progress(metadata.fileSizeBytes, metadata.fileSizeBytes, 100))
                    emit(DownloadState.Success(targetFile, metadata))
                }
            } catch (e: CancellationException) {
                if (partFile.exists()) partFile.delete()
                emit(DownloadState.Failed(UpdateError.Cancelled()))
                throw e
            } catch (e: IOException) {
                if (partFile.exists()) partFile.delete()
                emit(DownloadState.Failed(UpdateError.NetworkError(e.localizedMessage ?: "Netwerkfout tijdens downloaden")))
            } catch (e: Exception) {
                if (partFile.exists()) partFile.delete()
                emit(DownloadState.Failed(UpdateError.Unknown(e.localizedMessage ?: "Onbekende fout tijdens downloaden")))
            }
        }
    }.flowOn(ioDispatcher)
}
