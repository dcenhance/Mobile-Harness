package com.jarves.mh.runtime

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device bridge is exercised as a real HTTP server on the loopback interface: real sockets, real
 * request lines, real status codes. The Android answers are injected, so this proves the transport,
 * the token gate, the paging and the shape of every payload without pretending to be a device run.
 */
class DeviceQueryServerTest {

    private val token = "0123456789abcdef0123456789abcdef"
    private val servers = mutableListOf<DeviceQueryServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    private fun startServer(
        appsAnswer: (Int, Int) -> JSONObject = { offset, limit ->
            DeviceQueryServer.Payload.launcherApps(
                listOf("com.a" to "A", "com.b" to "B", "com.c" to "C"),
                offset,
                limit,
                total = 3,
            )
        },
    ): DeviceQueryServer {
        val server = DeviceQueryServer(
            port = 0,
            token = token,
            facts = {
                DeviceQueryServer.Payload.deviceInfo(
                    androidRelease = "16",
                    apiLevel = 36,
                    abis = listOf("arm64-v8a"),
                    pageSizeBytes = 16384,
                    freeBytes = 5_000_000_000L,
                    totalRamBytes = 8_000_000_000L,
                    appVersionName = "1.0.7-dce.9",
                    appVersionCode = 17,
                )
            },
            apps = appsAnswer,
        )
        server.start()
        servers += server
        assertTrue("the server must be listening", server.boundPort > 0)
        return server
    }

    private fun request(port: Int, path: String, tokenHeader: String? = token): Triple<Int, String, String> {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            if (tokenHeader != null) setRequestProperty(DeviceQueryServer.TOKEN_HEADER, tokenHeader)
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
        val contentType = connection.getHeaderField("Content-Type").orEmpty()
        connection.disconnect()
        return Triple(status, contentType, body)
    }

    @Test
    fun `device facts are served to a caller with the token`() {
        val server = startServer()
        val (status, contentType, body) = request(server.boundPort, "/device/info")
        assertEquals(200, status)
        assertTrue("JSON content type, was $contentType", contentType.startsWith("application/json"))
        val json = JSONObject(body)
        assertEquals(36, json.getInt("api_level"))
        assertEquals(16384, json.getInt("page_size_bytes"))
        assertEquals("arm64-v8a", json.getJSONArray("abis").getString(0))
        assertEquals("1.0.7-dce.9", json.getString("app_version_name"))
        assertEquals(17L, json.getLong("app_version_code"))
    }

    @Test
    fun `a request without the token is refused`() {
        val server = startServer()
        val (status, _, body) = request(server.boundPort, "/device/info", tokenHeader = null)
        assertEquals(401, status)
        assertEquals("token_required", JSONObject(body).getString("error"))
    }

    @Test
    fun `a request with the wrong token is refused`() {
        val server = startServer()
        val (status, _, _) = request(server.boundPort, "/device/info", tokenHeader = "ffffffffffffffffffffffffffffffff")
        assertEquals(401, status)
    }

    @Test
    fun `the app list is paged and reports the real total`() {
        val server = startServer()
        val (status, _, body) = request(server.boundPort, "/apps?offset=1&limit=1")
        assertEquals(200, status)
        val json = JSONObject(body)
        assertEquals(3, json.getInt("total"))
        assertEquals(1, json.getInt("offset"))
        assertEquals(1, json.getInt("count"))
        assertEquals("com.b", json.getJSONArray("apps").getJSONObject(0).getString("package"))
        assertEquals("B", json.getJSONArray("apps").getJSONObject(0).getString("label"))
        assertEquals(2, json.getInt("next_offset"))
    }

    @Test
    fun `the last page has no next offset`() {
        val server = startServer()
        val (_, _, body) = request(server.boundPort, "/apps?offset=2&limit=10")
        val json = JSONObject(body)
        assertEquals(1, json.getInt("count"))
        assertTrue("next_offset must be null on the last page", json.isNull("next_offset"))
    }

    @Test
    fun `an oversized page request is clamped instead of dumping the device`() {
        var seenLimit = -1
        val server = startServer { offset, limit ->
            seenLimit = limit
            DeviceQueryServer.Payload.launcherApps(emptyList(), offset, limit, total = 0)
        }
        val (status, _, _) = request(server.boundPort, "/apps?limit=99999")
        assertEquals(200, status)
        assertEquals(DeviceQueryServer.MAX_APPS_LIMIT, seenLimit)
    }

    @Test
    fun `a negative offset is treated as the start`() {
        var seenOffset = -1
        val server = startServer { offset, limit ->
            seenOffset = offset
            DeviceQueryServer.Payload.launcherApps(emptyList(), offset, limit, total = 0)
        }
        request(server.boundPort, "/apps?offset=-5")
        assertEquals(0, seenOffset)
    }

    @Test
    fun `an unknown endpoint is named as such`() {
        val server = startServer()
        val (status, _, body) = request(server.boundPort, "/shell?cmd=rm")
        assertEquals(404, status)
        assertEquals("unknown_endpoint", JSONObject(body).getString("error"))
    }

    @Test
    fun `a stopped server no longer answers`() {
        val server = startServer()
        server.stop()
        val failed = runCatching { request(server.boundPort, "/device/info") }.isFailure
        assertTrue("a stopped server must refuse connections", failed)
    }

    @Test
    fun `starting twice keeps one listener`() {
        val server = startServer()
        val port = server.boundPort
        server.start()
        assertEquals(port, server.boundPort)
        assertNotNull(request(server.boundPort, "/device/info").third)
    }
}
