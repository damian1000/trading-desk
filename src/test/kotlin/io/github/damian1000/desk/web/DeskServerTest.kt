package io.github.damian1000.desk.web

import com.sun.net.httpserver.HttpExchange
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/** Loopback tests over a real [DeskServer]: static routes serve the shell, tab prefixes delegate. */
class DeskServerTest {
    // Records what it was asked to proxy and answers with a marker, so the routing is what's tested.
    private class RecordingGateway : Gateway {
        var forwarded: String? = null

        override fun handles(path: String): Boolean =
            path.startsWith("/orderbook") || path.startsWith("/risk") || path.startsWith("/trading")

        override fun forward(exchange: HttpExchange) {
            forwarded = exchange.requestURI.path
            val bytes = "proxied:${exchange.requestURI.path}".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private val gateway = RecordingGateway()
    private lateinit var server: DeskServer
    private val client: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        server = DeskServer(WebAssets.load(), gateway, port = 0)
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    private fun request(
        method: String,
        path: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:${server.boundPort}$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `healthz responds ok`() {
        val response = request("GET", "/healthz")
        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
    }

    @Test
    fun `serves the shell with content types`() {
        assertTrue(request("GET", "/").body().contains("TRADING DESK"))
        assertEquals("text/css; charset=utf-8", request("GET", "/app.css").headers().firstValue("Content-Type").get())
        assertEquals("text/javascript; charset=utf-8", request("GET", "/app.js").headers().firstValue("Content-Type").get())
    }

    @Test
    fun `a tab-prefixed path is delegated to the gateway`() {
        val response = request("GET", "/orderbook/api/AAPL/stream")
        assertEquals(200, response.statusCode())
        assertEquals("proxied:/orderbook/api/AAPL/stream", response.body())
        assertEquals("/orderbook/api/AAPL/stream", gateway.forwarded)
    }

    @Test
    fun `an unknown path is a 404`() {
        assertEquals(404, request("GET", "/nope").statusCode())
    }

    @Test
    fun `the shell routes reject non-GET methods`() {
        val response = request("POST", "/")
        assertEquals(405, response.statusCode())
        assertEquals("GET", response.headers().firstValue("Allow").get())
    }

    @Test
    fun `an unexpected exception from the gateway still answers with a 500`() {
        val throwingGateway =
            object : Gateway {
                override fun handles(path: String): Boolean = true

                override fun forward(exchange: HttpExchange): Unit = throw IllegalStateException("boom")
            }
        val throwingServer = DeskServer(WebAssets.load(), throwingGateway, port = 0)
        throwingServer.start()
        try {
            val request =
                HttpRequest
                    .newBuilder(URI("http://localhost:${throwingServer.boundPort}/orderbook/anything"))
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(500, response.statusCode())
            assertTrue(response.body().contains("internal error"))
        } finally {
            throwingServer.stop()
        }
    }
}
