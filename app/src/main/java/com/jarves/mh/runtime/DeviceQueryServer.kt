package com.jarves.mh.runtime

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * A deliberately small, read-only query server on the loopback interface.
 *
 * The coding agents run as command-line programs inside this app's own runtime, so they cannot ask
 * Android anything themselves — an app has to answer. This server answers two read-only questions
 * (device facts, installed launcher apps) and nothing else: no writes, no installs, no launches, no user
 * files. It binds 127.0.0.1 only, requires a per-run token, and rejects any peer that is not the local
 * host, so another app on the phone cannot use it as a device-inventory oracle.
 *
 * The Android-specific answers are injected as lambdas ([facts], [apps]), which keeps the whole request
 * path testable on the JVM.
 */
class DeviceQueryServer(
    private val port: Int,
    private val token: String,
    private val facts: () -> JSONObject,
    private val apps: (offset: Int, limit: Int) -> JSONObject,
    private val log: (String) -> Unit = {},
) {

    companion object {
        const val TOKEN_HEADER = "X-MH-Token"
        const val MAX_REQUEST_LINE = 512
        const val MAX_APPS_LIMIT = 300
        const val DEFAULT_APPS_LIMIT = 100
        const val SOCKET_TIMEOUT_MS = 2_000

        /** Bind a loopback socket on an ephemeral port and read the port the OS gave us. */
        fun open(server: ServerSocket): Int = server.localPort
    }

    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = running.get()

    /** The port actually bound; with `port = 0` this is the ephemeral port the OS assigned. */
    var boundPort: Int = -1
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = SOCKET_TIMEOUT_MS
        boundPort = socket.localPort
        server = socket
        thread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                runCatching { handle(client) }.onFailure { log("device query failed: ${it.message}") }
            }
        }, "mh-device-query").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        thread = null
    }

    /** One request per connection, answered immediately, then closed. */
    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            if (!socket.inetAddress.isLoopbackAddress) {
                writeResponse(socket.getOutputStream(), 403, JSONObject().put("error", "loopback_only").toString())
                return
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine()?.take(MAX_REQUEST_LINE) ?: return
            val parts = requestLine.split(' ')
            val path = parts.getOrNull(1) ?: return
            var headerToken: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                if (line.substring(0, separator).trim().equals(TOKEN_HEADER, ignoreCase = true)) {
                    headerToken = line.substring(separator + 1).trim()
                }
            }
            val output = socket.getOutputStream()
            if (headerToken != token) {
                writeResponse(output, 401, JSONObject().put("error", "token_required").toString())
                return
            }
            val route = path.substringBefore('?')
            val query = parseQuery(path.substringAfter('?', ""))
            when (route) {
                "/device/info" -> writeResponse(output, 200, facts().toString())
                "/apps" -> {
                    val limit = (query["limit"]?.toIntOrNull() ?: DEFAULT_APPS_LIMIT).coerceIn(1, MAX_APPS_LIMIT)
                    val offset = (query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    writeResponse(output, 200, apps(offset, limit).toString())
                }

                else -> writeResponse(output, 404, JSONObject().put("error", "unknown_endpoint").toString())
            }
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.contains('=') }.associate { pair ->
            pair.substringBefore('=') to pair.substringAfter('=')
        }

    private fun writeResponse(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${reason(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        else -> "Not Found"
    }

    /** The documented answers, so a wrapper script and its tests agree on the field names. */
    object Payload {
        fun deviceInfo(
            androidRelease: String,
            apiLevel: Int,
            abis: List<String>,
            pageSizeBytes: Int,
            freeBytes: Long,
            totalRamBytes: Long,
            appVersionName: String,
            appVersionCode: Long,
        ): JSONObject = JSONObject().apply {
            put("android_release", androidRelease)
            put("api_level", apiLevel)
            put("abis", JSONArray(abis))
            put("page_size_bytes", pageSizeBytes)
            put("free_bytes", freeBytes)
            put("total_ram_bytes", totalRamBytes)
            put("app_version_name", appVersionName)
            put("app_version_code", appVersionCode)
        }

        fun launcherApps(packages: List<Pair<String, String>>, offset: Int, limit: Int, total: Int): JSONObject {
            val page = packages.drop(offset).take(limit)
            return JSONObject().apply {
                put("total", total)
                put("offset", offset)
                put("count", page.size)
                put(
                    "apps",
                    JSONArray().apply {
                        page.forEach { (packageName, label) ->
                            put(JSONObject().put("package", packageName).put("label", label))
                        }
                    },
                )
                val next = offset + page.size
                if (next < total) put("next_offset", next) else put("next_offset", JSONObject.NULL)
            }
        }
    }
}
