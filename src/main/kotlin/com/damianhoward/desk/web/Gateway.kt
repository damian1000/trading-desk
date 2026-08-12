package com.damianhoward.desk.web

import com.sun.net.httpserver.HttpExchange
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets

/** Forwards a tab-prefixed request to its upstream service. See [ReverseProxy]. */
interface Gateway {
    /** True when [path] is under one of the composed services' tab prefixes. */
    fun handles(path: String): Boolean

    /** Proxies [exchange] to the resolved upstream, streaming the response back. */
    fun forward(exchange: HttpExchange)
}

/**
 * Reverse-proxies the three composed services under one origin. The response body is streamed
 * chunk-by-chunk with a flush after each write, so an upstream `text/event-stream` reaches the
 * browser live rather than being buffered until the connection closes. A single writer thread
 * (the server's request pool) owns each proxied exchange for its lifetime, which is what the
 * long-lived SSE streams need.
 *
 * An upstream that can't be reached maps to a 502 before any bytes are sent; a client that hangs
 * up mid-stream just ends the copy — the status line is already gone, so there's nothing to change.
 */
class ReverseProxy(
    private val upstreams: Upstreams,
    private val client: HttpClient,
) : Gateway {
    override fun handles(path: String): Boolean = upstreams.resolve(path) != null

    override fun forward(exchange: HttpExchange) {
        val resolved =
            upstreams.resolve(exchange.requestURI.path) ?: run {
                respond(exchange, 404, "text/plain", "not found")
                return
            }
        val response =
            try {
                client.send(request(exchange, target(resolved, exchange.requestURI.rawQuery)), BodyHandlers.ofInputStream())
            } catch (e: IOException) {
                respond(exchange, 502, "application/json", """{"error":"upstream unavailable"}""")
                return
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                respond(exchange, 502, "application/json", """{"error":"upstream unavailable"}""")
                return
            }
        exchange.responseHeaders.add("Content-Type", response.headers().firstValue("content-type").orElse("application/octet-stream"))
        // Carry Cache-Control through so the upstream's "no-cache" on the SSE stream is honoured.
        response.headers().firstValue("cache-control").ifPresent { exchange.responseHeaders.add("Cache-Control", it) }
        if (exchange.requestMethod == "HEAD") {
            // The upstream already answered the HEAD with no body; relay headers-only rather than
            // opening a chunked stream the JDK server would warn about on a HEAD.
            response.body().close()
            exchange.sendResponseHeaders(response.statusCode(), -1)
            exchange.close()
            return
        }
        // Length 0 selects chunked transfer, which is what an open-ended SSE stream needs.
        exchange.sendResponseHeaders(response.statusCode(), 0)
        stream(response.body(), exchange)
    }

    private fun target(
        resolved: Upstreams.Resolved,
        rawQuery: String?,
    ): URI {
        val base = resolved.base.toString().trimEnd('/')
        val query = if (rawQuery.isNullOrEmpty()) "" else "?$rawQuery"
        return URI.create("$base${resolved.path}$query")
    }

    private fun request(
        exchange: HttpExchange,
        target: URI,
    ): HttpRequest {
        val method = exchange.requestMethod
        val builder = HttpRequest.newBuilder(target)
        // Forward the content negotiation headers the apps rely on; Host/Connection/
        // Content-Length are managed by HttpClient and rejected if set here.
        exchange.requestHeaders.getFirst("Content-Type")?.let { builder.header("Content-Type", it) }
        exchange.requestHeaders.getFirst("Accept")?.let { builder.header("Accept", it) }
        // Append this hop's peer to X-Forwarded-For so upstreams can still resolve the real
        // client behind the desk (Caddy already appended it before the desk saw the request).
        val peer = exchange.remoteAddress.address.hostAddress
        val inbound = exchange.requestHeaders.getFirst("X-Forwarded-For")
        builder.header("X-Forwarded-For", if (inbound.isNullOrBlank()) peer else "$inbound, $peer")
        val body =
            if (method in BODY_METHODS) {
                HttpRequest.BodyPublishers.ofInputStream { exchange.requestBody }
            } else {
                HttpRequest.BodyPublishers.noBody()
            }
        return builder.method(method, body).build()
    }

    private fun stream(
        upstream: java.io.InputStream,
        exchange: HttpExchange,
    ) {
        try {
            upstream.use { input ->
                exchange.responseBody.use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }
            }
        } catch (e: IOException) {
            // The client disconnected (common for SSE) or the upstream dropped. Headers are already
            // sent, so the exchange just ends here.
            exchange.close()
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        if (exchange.requestMethod == "HEAD") {
            // Headers only — see DeskServer.respond for why the length is -1 on a HEAD.
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private val BODY_METHODS = setOf("POST", "PUT", "PATCH")
    }
}
