package com.nexustvguide.app.update

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Bewaakt redirects tijdens het downloaden van een update.
 *
 * Release-hosts sturen een asset-download door naar een aparte CDN-host (GitHub gebruikt
 * `objects.githubusercontent.com` met een ondertekende URL). Redirects volledig uitzetten
 * maakt zo'n kanaal onbruikbaar; ze blind volgen zou een gecompromitteerde metadata-bron
 * naar een willekeurige host laten wijzen. Daarom volgt OkHttp de redirect wél, maar
 * controleert deze interceptor elke hop tegen [UpdateOriginPolicy].
 *
 * Een geweigerde hop levert een [IOException] op, die de download als netwerkfout afbreekt.
 */
class UpdateRedirectInterceptor(
    private val allowlistProvider: () -> Set<String>,
    private val allowInsecureProvider: () -> Boolean
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url

        val result = UpdateOriginPolicy.check(
            url = url,
            allowlist = allowlistProvider(),
            allowInsecure = allowInsecureProvider()
        )

        if (result is UpdateOriginPolicy.Result.Rejected) {
            throw IOException("Update-download geweigerd: ${result.reason}")
        }

        return chain.proceed(chain.request())
    }
}
