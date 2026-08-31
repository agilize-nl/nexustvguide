package com.nexustvguide.app.data.api

import com.nexustvguide.app.data.model.AppUpdateDto
import retrofit2.http.GET

/**
 * Retrofit interface voor het ophalen van versie-metadata.
 */
interface UpdateApiService {

    @GET("api/v1/app/version")
    suspend fun getLatestVersion(): AppUpdateDto
}
