package com.nuvio.tv.data.livetv

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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
                """[{"stream_id":"101","name":"Test Sports","category_id":12,"stream_icon":"https://images.example.test/sport.png","tv_archive":1,"tv_archive_duration":"3"},
                   {"stream_id":102,"name":"Test News","category_id":"18","tv_archive":0,"tv_archive_duration":7},
                   {"stream_id":true,"name":"Invalid entry"}]"""
            ))

            val source = checkNotNull(XtreamSource.from(server.url("/iptv/").toString(), "aa+bb", "p/a?s"))
            val catalog = XtreamClient().load(source)

            assertEquals(listOf("Test Sports", "Test News"), catalog.channels.map { it.name })
            assertTrue(catalog.channels.first().isSports)
            assertEquals("https://images.example.test/sport.png", catalog.channels.first().logoUrl)
            assertNull(catalog.channels.last().logoUrl)
            assertFalse(catalog.channels.last().isSports)
            assertEquals(listOf(3, 0), catalog.channels.map { it.archiveDays })
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
    fun readsReportedConnectionCountAndLimit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"user_info":{"auth":1,"active_cons":"2","max_connections":"4"}}"""
            ))
            server.enqueue(MockResponse().setBody(
                """{"user_info":{"auth":1,"active_cons":0,"max_connections":"0"}}"""
            ))
            server.enqueue(MockResponse().setBody(
                """{"user_info":{"auth":1,"max_connections":"4"}}"""
            ))
            val source = requireNotNull(XtreamSource.from(server.url("/iptv").toString(), "viewer", "sample"))
            val client = XtreamClient()
            assertEquals(XtreamConnections(2, 4), client.playlistConnections(source))
            assertEquals(XtreamConnections(0, null), client.playlistConnections(source))
            assertEquals(null, client.playlistConnections(source))
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
    fun fullGuideRetainsEarlierAndLaterListingsWithDescriptions() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"epg_listings":[
                    {"title":"Earlier news","start_timestamp":1795608000,"stop_timestamp":1795609800},
                    {"title":"Later news","start_timestamp":1795611600,"stop_timestamp":1795613400,
                     "description":"TmV3cyBhbmQgd2VhdGhlci4="}]}"""
            ))
            val source = checkNotNull(XtreamSource.from(server.url("/").toString(), "viewer", "password"))
            val programmes = XtreamClient().guide(source, 42, "UTC", full = true)

            assertEquals(listOf("Earlier news", "Later news"), programmes.map { it.title })
            assertEquals("News and weather.", programmes.last().description)
            val request = checkNotNull(server.takeRequest().requestUrl)
            assertEquals("get_simple_data_table", request.queryParameter("action"))
            assertEquals("42", request.queryParameter("stream_id"))
            assertNull(request.queryParameter("limit"))
        }
    }

    @Test
    fun usesProviderLocalTimeAndArchiveRightsForReplays() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"epg_listings":[
                    {"title":"Earlier match","start":"2026-11-27 13:15:39","end":"2026-11-27 14:00:08","has_archive":"1"},
                    {"title":"Rights restricted","start":"2026-11-27 14:00:08","end":"2026-11-27 14:45:00","has_archive":0},
                    {"title":"Late match","start":"2026-11-27 18:00:00","end":"2026-11-27 20:00:00"}]}"""
            ))
            val source = checkNotNull(XtreamSource.from(server.url("/iptv/").toString(), "aa+bb", "p/a?s"))
            val channel = LiveChannel(101, "Arena", "Sports", archiveDays = 3)
            val catalog = XtreamCatalog(listOf(channel), "m3u8", "Australia/Sydney")
            val (match, restricted, future) = XtreamClient().guide(source, channel.id, catalog.timezone, full = true)
            val now = Instant.parse("2026-11-27T04:00:00Z").toEpochMilli()

            assertTrue(match.canReplay(channel, now))
            assertTrue(match.canReplay(channel.copy(archiveDays = 0), now))
            assertFalse(restricted.canReplay(channel, now))
            assertFalse(future.canReplay(channel, now))
            assertFalse(match.canReplay(channel, now + 3 * 86_400_000L))
            assertEquals(
                "/iptv/timeshift/aa+bb/p%2Fa%3Fs/46/2026-11-27:13-15/101.m3u8",
                source.replayUrl(channel, match, catalog).encodedPath
            )
            assertEquals(
                source.replayUrl(channel, match, catalog),
                source.replayUrl(channel, match.copy(archiveStart = null), catalog)
            )
        }
    }

    @Test
    fun switchesToShortGuideWhenTheProviderHasNoFullTable() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"epg_listings":[]}"""))
            val source = checkNotNull(XtreamSource.from(server.url("/").toString(), "viewer", "password"))

            assertTrue(XtreamClient().guide(source, 42, "UTC", full = true).isEmpty())
            assertEquals("get_simple_data_table", server.takeRequest().requestUrl?.queryParameter("action"))
            val request = checkNotNull(server.takeRequest().requestUrl)
            assertEquals("get_short_epg", request.queryParameter("action"))
            assertEquals("16", request.queryParameter("limit"))
        }
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
