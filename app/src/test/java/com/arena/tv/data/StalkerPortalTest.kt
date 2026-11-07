package com.arena.tv.data

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A fake MAG portal with the quirks real ones have: it only lives at /stalker_portal/server/load.php,
 * only accepts the MAC cookie with plain colons, and puts a byte-order mark before its JSON.
 */
class StalkerPortalTest {
    private val server = MockWebServer()
    private var port = 0
    private val mac = "00:1A:79:C6:63:D0"

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val q = request.requestUrl!!.encodedQuery.orEmpty()
                val cookie = request.getHeader("Cookie").orEmpty()
                val (code, body) = when {
                    path != "/stalker_portal/server/load.php" -> 404 to "<html><body>Not found</body></html>"
                    !cookie.contains("mac=$mac") -> 200 to "{\"js\":{\"token\":\"\"}}"
                    "action=handshake" in q -> 200 to "\uFEFF{\"js\":{\"token\":\"T123\"}}"
                    request.getHeader("Authorization") != "Bearer T123" -> 200 to "{\"js\":false}"
                    "action=get_profile" in q -> 200 to "{\"js\":{\"status\":0}}"
                    "action=get_genres" in q -> 200 to "{\"js\":[{\"id\":\"1\",\"title\":\"Sports\"},{\"id\":\"9\",\"title\":\"XXX\",\"censored\":1}]}"
                    "action=get_all_channels" in q -> 200 to """{"js":{"data":[
                        {"id":"10","name":"beIN Sports 1","number":"2","cmd":"ffrt http://localhost/ch/10_","tv_genre_id":"1","logo":"b.png"},
                        {"id":"11","name":"Sky Sports","number":"1","cmd":"ffrt http://localhost/ch/11_","tv_genre_id":"1"},
                        {"id":"99","name":"Hot","number":"3","cmd":"ffrt http://localhost/ch/99_","tv_genre_id":"9"}]}}"""
                    "action=create_link" in q -> 200 to "{\"js\":{\"cmd\":\"ffmpeg http://cdn.example/live/10.ts?token=abc\"}}"
                    "action=get_main_info" in q -> 200 to "{\"js\":{\"phone\":\"2027-11-15\"}}"
                    else -> 200 to "{\"js\":{}}"
                }
                return MockResponse().setResponseCode(code).setBody(body)
            }
        }
        server.start()
        port = server.port
    }

    @After fun stop() = server.shutdown()

    @Test fun logsInLoadsChannelsAndResolvesStreams() {
        val client = StalkerClient(OkHttpClient())
        val p = Playlist("p1", "cod4", SourceType.MAC, server = "http://127.0.0.1:$port/stalker_portal/c/", mac = "001a79c663d0")
        val (channels, account) = client.load(p)
        assertEquals(listOf("Sky Sports", "beIN Sports 1", "Hot"), channels.map { it.name }) // genre order, then number
        assertTrue(channels.last().adult)
        assertTrue(account.contains("2027"))
        assertEquals("http://cdn.example/live/10.ts?token=abc", client.resolve(p, channels[1].url))
    }

    @Test fun explainsWhatWentWrong() {
        val client = StalkerClient(OkHttpClient())
        val p = Playlist("p2", "bad", SourceType.MAC, server = "http://127.0.0.1:$port/wrong/c/", mac = "00:1A:79:00:00:01")
        val e = runCatching { client.load(p) }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("Couldn't log in") && e.message!!.contains("HTTP 404"))
    }
}
