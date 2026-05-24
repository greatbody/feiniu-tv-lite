package ink.sunrui.feiniutv.network

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HlsProxyServer(
    private val playlistUrl: String,
    private val token: String
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var port: Int = -1

    fun start(): String {
        if (running.get()) {
            return playlistEntryUrl()
        }
        val socket = ServerSocket(0)
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        thread(name = "hls-proxy-$port", isDaemon = true) {
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { handleClient(client) }
            }
        }

        return playlistEntryUrl()
    }

    fun playlistEntryUrl(): String = "http://127.0.0.1:$port/playlist.m3u8"

    fun proxyUrlFor(upstream: String): String = toLocalProxyLine(upstream)

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val output = socket.getOutputStream()

                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    writeSimple(output, 400, "Bad Request")
                    return
                }

                val method = parts[0].uppercase()
                val headers = mutableMapOf<String, String>()
                var line: String?
                do {
                    line = input.readLine()
                    if (!line.isNullOrEmpty()) {
                        val idx = line.indexOf(':')
                        if (idx > 0) {
                            val key = line.substring(0, idx).trim().lowercase()
                            val value = line.substring(idx + 1).trim()
                            headers[key] = value
                        }
                    }
                } while (line != null && line.isNotEmpty())

                val path = parts[1]
                Log.d(TAG, "client request method=$method path=$path range=${headers["range"]}")
                if (path.startsWith("/playlist.m3u8")) {
                    servePlaylist(output)
                    return
                }
                if (path.startsWith("/proxy")) {
                    val encoded = extractQueryParam(path, "u")
                    if (encoded.isBlank()) {
                        writeSimple(output, 400, "Missing upstream url")
                        return
                    }
                    val upstream = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
                    serveUpstream(output, upstream, method, headers)
                    return
                }

                writeSimple(output, 404, "Not Found")
            } catch (e: Exception) {
                Log.e(TAG, "handleClient fatal", e)
                runCatching { writeSimple(socket.getOutputStream(), 502, "Proxy failure") }
            }
        }
    }

    private fun servePlaylist(output: OutputStream) {
        val raw = fetchText(playlistUrl) ?: run {
            Log.w(TAG, "playlist fetch failed: $playlistUrl")
            writeSimple(output, 502, "Playlist fetch failed")
            return
        }
        val rewritten = rewritePlaylist(raw, playlistUrl)
        Log.d(TAG, "playlist served rewrittenLength=${rewritten.length}")

        val body = rewritten.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, 200, "application/x-mpegURL", body.size.toLong(), isPlaylist = true)
        output.write(body)
        output.flush()
    }

    private fun serveUpstream(output: OutputStream, upstream: String, method: String, requestHeaders: Map<String, String>) {
        var conn: HttpURLConnection? = null
        try {
            conn = openUpstreamConnection(upstream, method, requestHeaders, forceRangeStart = false)
            var code = conn.responseCode
            if (code == 416 && isRangeEndpoint(upstream)) {
                Log.w(TAG, "upstream 416; retrying with Range bytes=0- upstream=$upstream")
                conn.disconnect()
                conn = openUpstreamConnection(upstream, method, requestHeaders, forceRangeStart = true)
                code = conn.responseCode
            }

            Log.d(TAG, "upstream response method=$method code=$code upstream=$upstream ctype=${conn.contentType} reqRange=${requestHeaders["range"]}")
            if (method == "HEAD") {
                writeProxyHeaders(output, code, conn)
                output.flush()
                return
            }

            val stream: InputStream = try {
                if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "open upstream stream failed code=$code upstream=$upstream", e)
                writeSimple(output, 502, "Upstream stream failure")
                return
            }

            val contentType = conn.contentType ?: "application/octet-stream"
            if (code in 200..299 && isPlaylist(upstream, contentType)) {
                val raw = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
                val rewritten = rewritePlaylist(raw, upstream)
                val body = rewritten.toByteArray(StandardCharsets.UTF_8)
                writeHeaders(output, 200, "application/x-mpegURL", body.size.toLong(), isPlaylist = true)
                output.write(body)
                output.flush()
                return
            }

            writeProxyHeaders(output, code, conn)
            stream.use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "serveUpstream fatal upstream=$upstream method=$method", e)
            writeSimple(output, 502, "Upstream failure")
        } finally {
            conn?.disconnect()
        }
    }

    private fun rewritePlaylist(raw: String, sourceUrl: String): String {
        val newline = "\r\n"
        return raw.lineSequence().map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                line
            } else if (!trimmed.startsWith("#")) {
                toLocalProxyLine(resolveAbsolute(sourceUrl, trimmed))
            } else {
                rewriteTagUriAttributes(trimmed, sourceUrl)
            }
        }.joinToString(newline)
    }

    private fun rewriteTagUriAttributes(tagLine: String, sourceUrl: String): String {
        if (!tagLine.contains("URI=\"")) return tagLine
        return URI_ATTR_REGEX.replace(tagLine) { m ->
            val uri = m.groupValues[1]
            val absolute = resolveAbsolute(sourceUrl, uri)
            "URI=\"${toLocalProxyLine(absolute)}\""
        }
    }

    private fun isPlaylist(url: String, contentType: String): Boolean {
        val lowerType = contentType.lowercase()
        return url.lowercase().contains(".m3u8") ||
            lowerType.contains("application/vnd.apple.mpegurl") ||
            lowerType.contains("application/x-mpegurl")
    }

    private fun fetchText(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", token)
                }
                setRequestProperty("cookie", "mode=relay")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (stream == null || code !in 200..299) {
                Log.w(TAG, "fetchText non-2xx code=$code url=$url")
                null
            } else {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchText error url=$url", e)
            null
        }
    }

    private fun openUpstreamConnection(
        upstream: String,
        method: String,
        requestHeaders: Map<String, String>,
        forceRangeStart: Boolean
    ): HttpURLConnection {
        val reqMethod = if (method == "HEAD") "HEAD" else "GET"
        return (URL(upstream).openConnection() as HttpURLConnection).apply {
            requestMethod = reqMethod
            connectTimeout = 15000
            readTimeout = 15000
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", token)
            }
            setRequestProperty("cookie", "mode=relay")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            val incomingRange = requestHeaders["range"]
            when {
                forceRangeStart -> setRequestProperty("Range", "bytes=0-")
                !incomingRange.isNullOrBlank() -> setRequestProperty("Range", incomingRange)
            }
            requestHeaders["if-range"]?.let { setRequestProperty("If-Range", it) }
            requestHeaders["user-agent"]?.let { setRequestProperty("User-Agent", it) }
            instanceFollowRedirects = true
        }
    }

    private fun isRangeEndpoint(url: String): Boolean = url.contains("/api/v1/media/range/")

    private fun toLocalProxyLine(upstream: String): String {
        val encoded = URLEncoder.encode(upstream, StandardCharsets.UTF_8.name())
        return "http://127.0.0.1:$port/proxy?u=$encoded"
    }

    private fun resolveAbsolute(baseUrl: String, path: String): String {
        return try {
            URI(baseUrl).resolve(path).toString()
        } catch (_: Exception) {
            when {
                path.startsWith("http://") || path.startsWith("https://") -> path
                else -> path
            }
        }
    }

    private fun extractQueryParam(path: String, key: String): String {
        val query = path.substringAfter('?', "")
        if (query.isBlank()) return ""
        return query.split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            .orEmpty()
    }

    private fun writeSimple(output: OutputStream, code: Int, text: String) {
        val body = text.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, code, "text/plain; charset=utf-8", body.size.toLong())
        output.write(body)
        output.flush()
    }

    private fun writeHeaders(output: OutputStream, code: Int, contentType: String, contentLength: Long, isPlaylist: Boolean = false) {
        val statusText = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            410 -> "Gone"
            502 -> "Bad Gateway"
            else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (isPlaylist) {
            sb.append("Cache-Control: no-cache, no-store, max-age=0\r\n")
            sb.append("Pragma: no-cache\r\n")
            sb.append("Expires: -1\r\n")
        }
        if (contentLength >= 0) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeProxyHeaders(output: OutputStream, code: Int, conn: HttpURLConnection) {
        val statusText = conn.responseMessage ?: when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            410 -> "Gone"
            502 -> "Bad Gateway"
            else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText).append("\r\n")
        sb.append("Connection: close\r\n")

        conn.headerFields.forEach { (k, values) ->
            if (k == null || values.isNullOrEmpty()) return@forEach
            val lower = k.lowercase()
            if (lower == "transfer-encoding" || lower == "connection" || lower == "keep-alive") return@forEach
            values.forEach { v ->
                sb.append(k).append(": ").append(v).append("\r\n")
            }
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private companion object {
        private const val TAG = "HlsProxy"
        private val URI_ATTR_REGEX = Regex("URI=\\\"([^\\\"]+)\\\"")
    }
}
