package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbKeyVerification
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class TmdbCredentialInterceptorTest {
    private val client = OkHttpClient.Builder()
        .addInterceptor(TmdbCredentialInterceptor { "fixture-device-key" })
        .addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }
        .build()

    @Test
    fun addsTheSavedKeyToTmdbRequests() {
        val request = Request.Builder()
            .url("https://api.themoviedb.org/3/movie/11/credits?api_key=&language=en")
            .build()
        client.newCall(request).execute().use {
            assertEquals("fixture-device-key", it.request.url.queryParameter("api_key"))
            assertEquals("en", it.request.url.queryParameter("language"))
        }
    }

    @Test
    fun keepsTheExplicitKeyOnValidationRequests() {
        val request = Request.Builder()
            .url("https://api.themoviedb.org/3/configuration?api_key=fixture-new-key")
            .tag(TmdbKeyVerification::class.java, TmdbKeyVerification)
            .build()
        client.newCall(request).execute().use {
            assertEquals("fixture-new-key", it.request.url.queryParameter("api_key"))
        }
    }

    @Test
    fun onlySendsTheKeyToTmdbOverHttps() {
        listOf("https://example.test/3/movie/11", "http://api.themoviedb.org/3/movie/11").forEach { url ->
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { assertEquals(request.url, it.request.url) }
        }
    }
}
