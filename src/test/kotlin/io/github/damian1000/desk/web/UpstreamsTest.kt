package io.github.damian1000.desk.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

class UpstreamsTest {
    private val upstreams =
        Upstreams(
            orderbook = URI("http://localhost:8080"),
            risk = URI("http://localhost:8081"),
            trading = URI("http://localhost:8082"),
        )

    @Test
    fun `fromEnv falls back to the box-local ports`() {
        val upstreams = Upstreams.fromEnv(emptyMap())
        assertEquals(URI("http://localhost:8080"), upstreams.orderbook)
        assertEquals(URI("http://localhost:8081"), upstreams.risk)
        assertEquals(URI("http://localhost:8082"), upstreams.trading)
    }

    @Test
    fun `fromEnv reads overrides`() {
        val upstreams =
            Upstreams.fromEnv(
                mapOf(
                    "ORDERBOOK_UPSTREAM" to "http://10.0.0.150:8080",
                    "RISK_UPSTREAM" to "http://10.0.0.150:8081",
                    "TRADING_UPSTREAM" to "https://trading.damianhoward.com",
                ),
            )
        assertEquals(URI("http://10.0.0.150:8080"), upstreams.orderbook)
        assertEquals(URI("https://trading.damianhoward.com"), upstreams.trading)
    }

    @Test
    fun `resolve strips the tab prefix and routes to the matching base`() {
        val resolved = upstreams.resolve("/orderbook/api/AAPL/stream")!!
        assertEquals(URI("http://localhost:8080"), resolved.base)
        assertEquals("/api/AAPL/stream", resolved.path)
    }

    @Test
    fun `a bare prefix maps to the upstream root`() {
        assertEquals("/", upstreams.resolve("/orderbook")!!.path)
        assertEquals("/", upstreams.resolve("/orderbook/")!!.path)
    }

    @Test
    fun `each tab routes to its own service`() {
        assertEquals(URI("http://localhost:8081"), upstreams.resolve("/risk/api/report")!!.base)
        assertEquals(URI("http://localhost:8082"), upstreams.resolve("/trading/api/stream")!!.base)
    }

    @Test
    fun `a shell path or an unknown prefix does not resolve`() {
        assertNull(upstreams.resolve("/app.css"))
        assertNull(upstreams.resolve("/"))
        assertNull(upstreams.resolve("/orderbookx/api")) // only a whole segment matches
    }
}
