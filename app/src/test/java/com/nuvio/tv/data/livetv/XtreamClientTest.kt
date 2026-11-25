package com.nuvio.tv.data.livetv

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamClientTest {
    @Test
    fun importsSportsAndOtherLiveChannelsWithEncodedCredentials() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["ts","m3u8"]}}"""
            ))
            server.enqueue(MockResponse().setBody(
                """[{"category_id":"12","category_name":"AU Sports"},{"category_id":18,"category_name":"Local"}]"""
            ))
            server.enqueue(MockResponse().setBody(
                """[{"stream_id":"101","name":"Test Sports","category_id":12},
                   {"stream_id":102,"name":"Test News","category_id":"18"},
                   {"stream_id":true,"name":"Invalid entry"}]"""
            ))

            val source = checkNotNull(XtreamSource.from(server.url("/iptv/").toString(), "aa+bb", "p/a?s"))
            val catalog = XtreamClient().load(source)

            assertEquals(listOf("Test Sports", "Test News"), catalog.channels.map { it.name })
            assertTrue(catalog.channels.first().isSports)
            assertFalse(catalog.channels.last().isSports)
            assertEquals("m3u8", catalog.format)
            assertEquals("/iptv/live/aa+bb/p%2Fa%3Fs/101.m3u8", source.streamUrl(101, catalog.format).encodedPath)

            for (action in listOf(null, "get_live_categories", "get_live_streams")) {
                val request = checkNotNull(server.takeRequest().requestUrl)
                assertEquals("/iptv/player_api.php", request.encodedPath)
                assertEquals("aa+bb", request.queryParameter("username"))
                assertEquals("p/a?s", request.queryParameter("password"))
                assertEquals(action, request.queryParameter("action"))
            }
        }
    }

    @Test
    fun acceptsAnXtreamPlaylistLink() {
        val source = checkNotNull(XtreamSource.from(
            "https://iptv.example.test:9443/custom/get.php?username=viewer&password=x%2By&type=m3u_plus",
            "",
            ""
        ))
        assertEquals("https://iptv.example.test:9443/custom/", source.server.toString())
        assertEquals("viewer", source.username)
        assertEquals("x+y", source.password)
        assertEquals("/custom/live/viewer/x+y/9.ts", source.streamUrl(9, "ts").encodedPath)
    }

    @Test
    fun rejectsUnsupportedUrlsAndIncompleteCredentials() {
        assertNull(XtreamSource.from("file:///storage/source", "user", "password"))
        assertNull(XtreamSource.from("https://iptv.example.test", "", "password"))
        assertNull(XtreamSource.from("https://iptv.example.test/other?username=u&password=p", "", ""))
    }

    @Test
    fun stopsAfterTheProviderRejectsAuthentication() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":"0"}}"""))
            val source = checkNotNull(XtreamSource.from(server.url("/").toString(), "user", "invalid"))
            val failure = assertThrows(XtreamException::class.java) { XtreamClient().load(source) }

            assertEquals(XtreamProblem.SIGN_IN, failure.problem)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun reportsAnEmptyLiveChannelListAsAnImportFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"user_info":{"auth":true}}"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[]"))
            val source = checkNotNull(XtreamSource.from(server.url("/").toString(), "user", "password"))

            val failure = assertThrows(XtreamException::class.java) { XtreamClient().load(source) }
            assertEquals(XtreamProblem.NO_CHANNELS, failure.problem)
        }
    }
}
