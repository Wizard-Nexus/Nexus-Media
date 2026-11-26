package com.nuvio.tv.core.server

import com.nuvio.tv.data.livetv.XtreamSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class XtreamPhoneSetupServerTest {
    @Test
    fun onlyTheQrKeyCanSubmitAndTheSameSessionQueuesOnePlaylist() {
        val received = LinkedBlockingQueue<XtreamSource>()
        val server = XtreamPhoneSetupServer("127.0.0.1", { "form".encodeToByteArray() }) { _, source ->
            received.add(source)
        }
        server.start(3_000, true)
        try {
            val root = server.url.substringBefore('#')
            val key = server.url.substringAfter('#').chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray()
            val http = OkHttpClient()
            http.newCall(Request.Builder().url(root).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("no-store", response.header("Cache-Control"))
                assertFalse(response.body!!.string().contains(server.url.substringAfter('#')))
            }
            fun send(body: String): Int {
                val request = Request.Builder().url(root + "api/playlist")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                return http.newCall(request).execute().use { it.code }
            }

            assertEquals(400, send(encrypted(ByteArray(32))))
            assertTrue(received.isEmpty())
            assertEquals(202, send(encrypted(key)))
            val source = checkNotNull(received.poll(3, TimeUnit.SECONDS))
            assertEquals("https://channels.example.test/", source.server.toString())
            assertEquals("tester", source.username)
            assertEquals("fixture-password", source.password)
            assertEquals(409, send(encrypted(key)))
            server.retry()
            assertEquals(409, send(encrypted(key)))
            assertTrue(received.isEmpty())
        } finally {
            server.stop()
        }
    }

    private fun encrypted(key: ByteArray): String {
        val nonce = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val form = """{"server":"https://channels.example.test","username":"tester","password":"fixture-password"}"""
        val encoder = Base64.getEncoder()
        val ciphertext = encoder.encodeToString(cipher.doFinal(form.encodeToByteArray()))
        return """{"nonce":"${encoder.encodeToString(nonce)}","ciphertext":"$ciphertext"}"""
    }
}
