package com.nuvio.tv.data.livetv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class XtreamSource private constructor(
    val server: HttpUrl,
    val username: String,
    val password: String
) {
    fun apiUrl(action: String?): HttpUrl = server.newBuilder()
        .addPathSegment("player_api.php")
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .apply { action?.let { addQueryParameter("action", it) } }
        .build()

    fun streamUrl(streamId: Long, format: String): HttpUrl = server.newBuilder()
        .addPathSegment("live")
        .addPathSegment(username)
        .addPathSegment(password)
        .addPathSegment(streamId.toString() + "." + format)
        .build()

    companion object {
        fun from(server: String, username: String, password: String): XtreamSource? {
            val url = server.trim().toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null

            // Xtream providers sometimes hand out their get.php playlist instead of three fields.
            val path = url.encodedPath.trimEnd('/')
            val endpoint = path.substringAfterLast('/')
            val hasCredentialsInUrl = endpoint.equals("get.php", true) || endpoint.equals("player_api.php", true)
            if (url.query != null && !hasCredentialsInUrl) return null

            val login = username.trim().ifEmpty { url.queryParameter("username").orEmpty() }
            val secret = password.ifEmpty { url.queryParameter("password").orEmpty() }
            if (login.isBlank() || secret.isBlank()) return null

            val basePath = if (hasCredentialsInUrl) path.substringBeforeLast('/') else path
            val base = url.newBuilder().encodedPath(basePath + "/").query(null).build()
            return XtreamSource(base, login, secret)
        }
    }
}

internal data class LiveChannel(val id: Long, val name: String, val group: String?) {
    val isSports: Boolean
        get() = group?.contains("sport", ignoreCase = true) == true
}

internal data class XtreamCatalog(val channels: List<LiveChannel>, val format: String)

internal enum class XtreamProblem { SIGN_IN, NO_CHANNELS, RESPONSE }
internal class XtreamException(val problem: XtreamProblem) : IOException()

internal class XtreamClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    fun load(source: XtreamSource): XtreamCatalog {
        val received = read(source, null) as? JsonObject
        val account = received?.get("user_info") as? JsonObject
            ?: throw XtreamException(XtreamProblem.RESPONSE)
        val status = account.field("status")
        if (account.field("auth") !in setOf("1", "true") ||
            status?.lowercase() in setOf("expired", "disabled", "banned")
        ) {
            throw XtreamException(XtreamProblem.SIGN_IN)
        }

        val options = (account["allowed_output_formats"] as? JsonArray).orEmpty()
        val format = if (options.any { (it as? JsonPrimitive)?.content == "m3u8" }) "m3u8" else "ts"
        val groups = rows(source, "get_live_categories").mapNotNull { row ->
            val id = row.field("category_id") ?: return@mapNotNull null
            val name = row.field("category_name") ?: return@mapNotNull null
            id to name
        }.toMap()
        val channels = rows(source, "get_live_streams").mapNotNull { row ->
            val id = row.field("stream_id")?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val name = row.field("name") ?: return@mapNotNull null
            LiveChannel(id, name, groups[row.field("category_id")])
        }.distinct()
        if (channels.isEmpty()) throw XtreamException(XtreamProblem.NO_CHANNELS)
        return XtreamCatalog(channels, format)
    }

    private fun rows(source: XtreamSource, action: String): List<JsonObject> {
        val array = read(source, action) as? JsonArray ?: throw XtreamException(XtreamProblem.RESPONSE)
        return array.filterIsInstance<JsonObject>()
    }

    private fun read(source: XtreamSource, action: String?): JsonElement {
        val request = Request.Builder().url(source.apiUrl(action)).build()
        http.newCall(request).execute().use { response ->
            if (response.code in listOf(401, 403)) throw XtreamException(XtreamProblem.SIGN_IN)
            if (!response.isSuccessful) throw XtreamException(XtreamProblem.RESPONSE)
            val body = response.body?.source() ?: throw XtreamException(XtreamProblem.RESPONSE)
            if (body.request(MAX_RESPONSE_BYTES + 1)) throw XtreamException(XtreamProblem.RESPONSE)
            return try {
                Json.parseToJsonElement(body.readUtf8())
            } catch (_: IllegalArgumentException) {
                throw XtreamException(XtreamProblem.RESPONSE)
            }
        }
    }

    private fun JsonObject.field(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_RESPONSE_BYTES = 24L * 1024 * 1024
    }
}
