package com.nuvio.tv.core.tmdb

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import com.nuvio.tv.data.remote.api.TmdbKeyVerification
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TmdbCredentialStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = ProtectedProfilePreferences(context, "nexus_tmdb", "app.nexusmedia.tv.tmdb.v1")
    private var loaded = false
    private var apiKey: String? = null

    @Synchronized
    fun localKey(): String? {
        if (!loaded) {
            apiKey = runCatching { preferences.read(DEVICE_KEY) }.getOrNull()
            loaded = true
        }
        return apiKey
    }

    @Synchronized
    fun setLocalKey(key: String?) {
        preferences.write(DEVICE_KEY, key)
        apiKey = key
        loaded = true
    }

    private companion object {
        const val DEVICE_KEY = 0
    }
}

internal class TmdbCredentialInterceptor(private val localKey: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.isHttps || original.url.host != "api.themoviedb.org" ||
            original.tag(TmdbKeyVerification::class.java) != null
        ) return chain.proceed(original)

        val key = localKey() ?: return chain.proceed(original)
        val authorizedUrl = original.url.newBuilder().setQueryParameter("api_key", key).build()
        return chain.proceed(original.newBuilder().url(authorizedUrl).build())
    }
}
