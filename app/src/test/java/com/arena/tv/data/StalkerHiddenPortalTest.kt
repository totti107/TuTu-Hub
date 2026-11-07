package com.arena.tv.data

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A portal like the ones that failed on the TV: any unknown address redirects to (or shows) the box's web page,
 * and the real API address is only written in that page's script.
 */
class StalkerHiddenPortalTest {
    private val server = MockWebServer()
    private val page = "<html><head><!-- stalker_portal --><script>var debug = 0; var stb;</script>" +
        "<script src=\"xpcom.common.js?v=5\"></script></head></html>"

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val q = request.requestUrl!!.encodedQuery.orEmpty()
                return when {
                    path == "/c/" -> MockResponse().setBody(page)
                    path == "/c/xpcom.common.js" -> MockResponse().setBody("this.ajax_loader = this.portal_protocol + '://' + this.portal_ip + '/api/v9/portal.php';")
                    path == "/api/v9/portal.php" -> MockResponse().setBody(when {
                        "action=handshake" in q -> "{\"js\":{\"token\":\"T9\"}}"
                        "action=get_profile" in q -> "{\"js\":{\"status\":0}}"
                        "action=get_genres" in q -> "{\"js\":[{\"id\":\"1\",\"title\":\"Sports\"}]}"
                        "action=get_all_channels" in q -> "{\"js\":{\"data\":[{\"id\":\"1\",\"name\":\"Sport 1\",\"number\":\"1\",\"cmd\":\"ffrt http://x/1\",\"tv_genre_id\":\"1\"}]}}"
                        else -> "{\"js\":{}}"
                    })
                    path.endsWith("load.php") -> MockResponse().setBody(page) // web page instead of data
                    else -> MockResponse().setResponseCode(302).setHeader("Location", "/c/")
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    @Test fun findsTheApiFromThePortalPage() {
        val p = Playlist("t", "titan", SourceType.MAC, server = "http://127.0.0.1:${server.port}/c/", mac = "00:1A:79:C6:63:D0")
        val (channels, _) = StalkerClient(OkHttpClient()).load(p)
        assertEquals(listOf("Sport 1"), channels.map { it.name })
    }
}
