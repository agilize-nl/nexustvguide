package com.nexustvguide.app.data.api

import com.nexustvguide.app.data.model.AppUpdateDto
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit interface voor het ophalen van versie-metadata.
 *
 * Het pad is niet vast: de LAN-backend serveert `api/v1/app/version`, terwijl een
 * release-host een statisch `version.json` publiceert. Het pad komt daarom uit
 * `BuildConfig.UPDATE_MANIFEST_PATH` en wordt als [Url] meegegeven — relatief aan de
 * ingestelde base-URL.
 */
interface UpdateApiService {

    @GET
    suspend fun getLatestVersion(@Url manifestPath: String): AppUpdateDto
}
