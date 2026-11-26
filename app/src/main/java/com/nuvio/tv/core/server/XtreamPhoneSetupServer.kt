package com.nuvio.tv.core.server

import com.nuvio.tv.data.livetv.XtreamSource
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class XtreamPhoneSetupServer(
    private val address: String,
    private val assets: (String) -> ByteArray,
    private val onPlaylist: (XtreamPhoneSetupServer, XtreamSource) -> Unit
) : NanoHTTPD(address, 0) {
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val startedAt = System.nanoTime()
    private val submissions = mutableSetOf<String>()
    private var queued = false

    // The key is a URL fragment. The phone uses it locally and the browser omits it from HTTP requests.
    val url: String
        get() = "http://$address:$listeningPort/#" + key.joinToString("") { "%02x".format(it) }

    @Synchronized
    fun retry() {
        queued = false
    }

    @Synchronized
    private fun enqueue(nonce: String): Boolean {
        if (queued || submissions.size >= MAX_SUBMISSIONS || !submissions.add(nonce)) return false
        queued = true
        return true
    }

    override fun serve(session: IHTTPSession): Response {
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= VALID_FOR_MS) {
            return message(Response.Status.GONE, "Open a new setup code on your TV.")
        }
        if (session.headers["host"] != "$address:$listeningPort") {
            return message(Response.Status.FORBIDDEN, "Open the link from your TV.")
        }
        if (session.method == Method.GET) {
            return when (session.uri) {
                "/" -> asset("index.html", "text/html; charset=utf-8")
                "/pair.js" -> asset("pair.js", "text/javascript; charset=utf-8")
                "/aes-gcm.js" -> asset("aes-gcm.js", "text/javascript; charset=utf-8")
                "/pair.css" -> asset("pair.css", "text/css; charset=utf-8")
                else -> message(Response.Status.NOT_FOUND, "Page unavailable.")
            }
        }
        if (session.method != Method.POST || session.uri != "/api/playlist") {
            return message(Response.Status.NOT_FOUND, "Page unavailable.")
        }
        val (nonce, source) = readPlaylist(session)
            ?: return message(Response.Status.BAD_REQUEST, "Check the details or scan a fresh code.")
        if (!enqueue(nonce)) {
            return message(Response.Status.CONFLICT, "Check your TV for the current setup.")
        }
        onPlaylist(this, source)
        return message(Response.Status.ACCEPTED, "Sent to your TV. Check the connection result there.")
    }

    private fun readPlaylist(session: IHTTPSession): Pair<String, XtreamSource>? {
        if (session.headers["content-type"]?.substringBefore(';') != "application/json") return null
        val length = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (length !in 1..MAX_BODY_BYTES) return null
        return runCatching {
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = session.inputStream.read(bytes, offset, length - offset)
                if (read <= 0) return null
                offset += read
            }
            val payload = Json.decodeFromString<EncryptedForm>(bytes.decodeToString())
            val decoder = Base64.getDecoder()
            val nonce = decoder.decode(payload.nonce)
            if (nonce.size != 12) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val plaintext = cipher.doFinal(decoder.decode(payload.ciphertext))
            val form = Json.decodeFromString<PlaylistForm>(plaintext.decodeToString())
            val source = XtreamSource.from(form.server, form.username, form.password) ?: return null
            Base64.getEncoder().encodeToString(nonce) to source
        }.getOrNull()
    }

    private fun asset(name: String, type: String): Response {
        val bytes = runCatching { assets(name) }.getOrNull()
            ?: return message(Response.Status.NOT_FOUND, "Page unavailable.")
        return newFixedLengthResponse(Response.Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong())
            .withPrivateHeaders()
    }

    private fun message(status: Response.Status, text: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", text).withPrivateHeaders()

    private fun Response.withPrivateHeaders(): Response = apply {
        closeConnection(true)
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader(
            "Content-Security-Policy",
            "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"
        )
    }

    @Serializable
    private data class EncryptedForm(val nonce: String, val ciphertext: String)

    @Serializable
    private data class PlaylistForm(val server: String, val username: String, val password: String)

    companion object {
        const val VALID_FOR_MS = 5 * 60_000L
        private const val MAX_BODY_BYTES = 8_192
        private const val MAX_SUBMISSIONS = 20
    }
}
