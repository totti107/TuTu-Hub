package com.arena.tv.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Portals that answer "Authorization failed." unless the box sends its device ids (or the MAC isn't active at all). */
class StalkerStrictPortalTest {
    private val server = MockWebServer()
    @Volatile private var authorized = false
    @Volatile private var activated = true

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.encodedQuery.orEmpty()
                if (request.requestUrl!!.encodedPath != "/portal.php") return MockResponse().setResponseCode(404)
                val body = when {
                    "action=handshake" in q -> { authorized = false; "{\"js\":{\"token\":\"T\"}}" }
                    "action=get_profile" in q -> { authorized = activated && "device_id=" in q && "signature=" in q; "{\"js\":{\"id\":\"5\"}}" }
                    !authorized -> "Authorization failed."
                    "action=get_genres" in q -> "{\"js\":[{\"id\":\"1\",\"title\":\"Sports\"}]}"
                    "action=get_all_channels" in q -> "{\"js\":{\"data\":[{\"id\":\"1\",\"name\":\"Sport 1\",\"number\":\"1\",\"cmd\":\"ffrt http://x/1\",\"tv_genre_id\":\"1\"}]}}"
                    else -> "{\"js\":{}}"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun playlist() = Playlist("c", "cod4", SourceType.MAC, server = "http://127.0.0.1:${server.port}", mac = "00:1A:79:C6:63:D0")

    @Test fun sendsDeviceIdsWhenThePortalWantsThem() {
        val (channels, _) = StalkerClient(OkHttpClient()).load(playlist())
        assertEquals(listOf("Sport 1"), channels.map { it.name })
    }

    @Test fun explainsAnInactiveMac() {
        activated = false
        val e = runCatching { StalkerClient(OkHttpClient()).load(playlist()) }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.contains("Authorization failed") && e.message!!.contains("activated"))
    }
}

/** A portal that turns away old boxes, like cod4 ("old firmware" for a MAG250). */
class StalkerModelPortalTest {
    private val server = MockWebServer()
    @Volatile private var ok = false

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.encodedQuery.orEmpty()
                if (request.requestUrl!!.encodedPath != "/portal.php") return MockResponse().setResponseCode(404)
                val body = when {
                    "action=handshake" in q -> { ok = false; "{\"js\":{\"token\":\"T\"}}" }
                    "action=get_profile" in q -> if ("stb_type=MAG324" in q && "STB%20API%20version" in q &&
                        request.getHeader("X-User-Agent")!!.contains("MAG324")) { ok = true; "{\"js\":{\"id\":\"1\",\"status\":0}}" }
                        else "{\"js\":{\"status\":1,\"msg\":\"old firmware\"}}"
                    !ok -> "Authorization failed."
                    "action=get_genres" in q -> "{\"js\":[{\"id\":\"1\",\"title\":\"Sports\"}]}"
                    "action=get_all_channels" in q -> "{\"js\":{\"data\":[{\"id\":\"1\",\"name\":\"Sport 1\",\"number\":\"1\",\"cmd\":\"ffrt http://x/1\",\"tv_genre_id\":\"1\"}]}}"
                    else -> "{\"js\":{}}"
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    @Test fun triesNewerBoxModels() {
        val p = Playlist("m", "cod4", SourceType.MAC, server = "http://127.0.0.1:${server.port}/c/", mac = "00:1A:79:C6:63:DD")
        val (channels, _) = StalkerClient(OkHttpClient()).load(p)
        assertEquals(listOf("Sport 1"), channels.map { it.name })
        assertTrue(channels[0].userAgent!!.contains("MAG200"))
    }
}
