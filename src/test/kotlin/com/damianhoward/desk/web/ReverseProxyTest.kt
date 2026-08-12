package com.damianhoward.desk.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Drives a real [ReverseProxy] over loopback: a fake upstream (which echoes what it received and
 * can hold an SSE stream open) behind a front server whose sole handler is the proxy. The upstream
 * gate lets the SSE test prove frames are streamed, not buffered.
 */
class ReverseProxyTest {
    private lateinit var upstream: HttpServer
    private lateinit var front: HttpServer
    private lateinit var proxy: Gateway
    private var deadPort = 0
    private lateinit var sseGate: CountDownLatch
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @BeforeEach
    fun start() {
        sseGate = CountDownLatch(1)

        upstream = HttpServer.create(InetSocketAddress(0), 0)
        upstream.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        upstream.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/sse") serveSse(exchange) else echo(exchange)
        }
        upstream.start()

        front = HttpServer.create(InetSocketAddress(0), 0)
        front.executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        // Unconditional: a non-matching path exercises the proxy's own 404.
        front.createContext("/") { proxy.forward(it) }

        // A port nobody listens on: open a socket, take its number, close it fully. Both servers are
        // already bound, so this can't collide with them, and connecting to it is refused at once.
        deadPort = ServerSocket(0).use { it.localPort }

        val upstreams =
            Upstreams(
                orderbook = URI("http://localhost:${upstream.address.port}"),
                trading = URI("http://localhost:$deadPort"),
            )
        proxy = ReverseProxy(upstreams, client)
        front.start()
    }

    @AfterEach
    fun stop() {
        sseGate.countDown()
        front.stop(0)
        upstream.stop(0)
    }

    private fun echo(exchange: HttpExchange) {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
        val forwardedFor = exchange.requestHeaders.getFirst("X-Forwarded-For") ?: ""
        val text =
            "method=${exchange.requestMethod} path=${exchange.requestURI.path} " +
                "query=${exchange.requestURI.rawQuery ?: ""} body=$body ct=$contentType xff=[$forwardedFor]"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serveSse(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        out.write("data: frame-1\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
        // Hold the second frame back until the test says frame-1 has already arrived downstream.
        sseGate.await(2, TimeUnit.SECONDS)
        out.write("data: frame-2\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
        out.close()
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        contentType: String? = null,
        forwardedFor: String? = null,
    ): HttpResponse<String> {
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val builder = HttpRequest.newBuilder(URI("http://localhost:${front.address.port}$path")).method(method, publisher)
        if (contentType != null) builder.header("Content-Type", contentType)
        if (forwardedFor != null) builder.header("X-Forwarded-For", forwardedFor)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun forwardedForOf(echoed: String): String {
        val match = Regex("""xff=\[([^\]]*)]""").find(echoed) ?: error("no xff in: $echoed")
        return match.groupValues[1]
    }

    @Test
    fun `rewrites the tab prefix onto the upstream path`() {
        val response = request("GET", "/orderbook/api/AAPL/state")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("path=/api/AAPL/state"), response.body())
    }

    @Test
    fun `forwards the query string`() {
        val response = request("GET", "/orderbook/api/report?confidence=99&years=0.25")
        assertTrue(response.body().contains("query=confidence=99&years=0.25"), response.body())
    }

    @Test
    fun `a bare prefix maps to the upstream root`() {
        val response = request("GET", "/orderbook")
        assertTrue(response.body().contains("path=/ "), response.body())
    }

    @Test
    fun `forwards the request body and content type on POST`() {
        val response = request("POST", "/orderbook/api/report", body = "equityQty=100", contentType = "application/x-www-form-urlencoded")
        assertTrue(response.body().contains("method=POST"), response.body())
        assertTrue(response.body().contains("body=equityQty=100"), response.body())
        assertTrue(response.body().contains("ct=application/x-www-form-urlencoded"), response.body())
    }

    @Test
    fun `identifies the connecting peer as X-Forwarded-For when none arrived`() {
        val forwardedFor = forwardedForOf(request("GET", "/orderbook/api/AAPL/state").body())
        assertTrue(forwardedFor.isNotBlank(), "expected the peer address, got '$forwardedFor'")
        assertTrue(!forwardedFor.contains(","), "expected a single hop, got '$forwardedFor'")
    }

    @Test
    fun `appends the connecting peer to an inbound X-Forwarded-For chain`() {
        val forwardedFor = forwardedForOf(request("GET", "/orderbook/api/AAPL/state", forwardedFor = "203.0.113.9").body())
        assertTrue(forwardedFor.startsWith("203.0.113.9, "), "expected the inbound chain kept, got '$forwardedFor'")
        assertTrue(forwardedFor.length > "203.0.113.9, ".length, "expected the peer appended, got '$forwardedFor'")
    }

    @Test
    fun `an unreachable upstream is a 502, not a hung tab`() {
        val response = request("GET", "/trading/api/state")
        assertEquals(502, response.statusCode())
        assertTrue(response.body().contains("\"error\""), response.body())
    }

    @Test
    fun `relays a HEAD as the upstream's status and headers with no body`() {
        val response = request("HEAD", "/orderbook/api/AAPL/state")
        assertEquals(200, response.statusCode())
        assertEquals("text/plain", response.headers().firstValue("Content-Type").get())
        assertEquals("", response.body())
    }

    @Test
    fun `a HEAD to an unreachable upstream is still a bodyless 502`() {
        val response = request("HEAD", "/trading/api/state")
        assertEquals(502, response.statusCode())
        assertEquals("", response.body())
    }

    @Test
    fun `a path with no tab prefix is a 404`() {
        assertEquals(404, request("GET", "/nope").statusCode())
    }

    @Test
    fun `handles is true only for a recognised tab prefix`() {
        assertTrue(proxy.handles("/orderbook/api/AAPL/state"))
        assertTrue(proxy.handles("/trading/api/report"))
        assertTrue(!proxy.handles("/risk"))
        assertTrue(!proxy.handles("/nope"))
        assertTrue(!proxy.handles("/"))
    }

    @Test
    fun `a client that disconnects mid-stream ends the copy without an error`() {
        val connection = URI("http://localhost:${front.address.port}/orderbook/sse").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 5_000
        connection.inputStream.bufferedReader().use { reader ->
            assertEquals("frame-1", dataFrame(reader), "first frame should arrive before disconnecting")
        }
        connection.disconnect()
        // Release the upstream's held second frame now that nothing is reading it downstream.
        sseGate.countDown()
        Thread.sleep(200)
        assertEquals(200, request("GET", "/orderbook/api/AAPL/state").statusCode())
    }

    @Test
    fun `streams SSE frames through as they are produced`() {
        val connection = URI("http://localhost:${front.address.port}/orderbook/sse").toURL().openConnection() as HttpURLConnection
        connection.readTimeout = 5_000
        assertEquals("text/event-stream", connection.getHeaderField("Content-Type"))
        connection.inputStream.bufferedReader().use { reader ->
            assertEquals("frame-1", dataFrame(reader), "first frame should arrive before the second is sent")
            // The upstream only sends frame-2 after this; receiving it proves the stream isn't buffered.
            sseGate.countDown()
            assertEquals("frame-2", dataFrame(reader))
        }
        connection.disconnect()
    }

    private fun dataFrame(reader: BufferedReader): String {
        while (true) {
            val line = reader.readLine() ?: error("stream closed before a data frame arrived")
            if (line.startsWith("data: ")) return line.removePrefix("data: ")
        }
    }
}
