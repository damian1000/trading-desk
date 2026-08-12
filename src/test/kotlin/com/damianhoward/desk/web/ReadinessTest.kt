package com.damianhoward.desk.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class ReadinessTest {
    private val upstreams = Upstreams(orderbook = URI("http://ob:8080"), trading = URI("http://ts:8082"))

    @Test
    fun `ready when every upstream is reachable`() {
        val probe = Readiness(upstreams) { true }.probe()
        assertTrue(probe.ready)
        assertTrue(probe.json.contains(""""orderbook":{"ok":true}"""), probe.json)
        assertTrue(probe.json.contains(""""trading":{"ok":true}"""), probe.json)
    }

    @Test
    fun `not ready when an upstream is unreachable, and the json names which`() {
        val probe = Readiness(upstreams) { base -> base == upstreams.orderbook }.probe()
        assertFalse(probe.ready)
        assertTrue(probe.json.contains(""""ready":false"""), probe.json)
        assertTrue(probe.json.contains(""""orderbook":{"ok":true}"""), probe.json)
        assertTrue(probe.json.contains(""""trading":{"ok":false}"""), probe.json)
    }

    @Test
    fun `the probe base is each upstream's own uri`() {
        val seen = mutableListOf<URI>()
        Readiness(upstreams) { base ->
            seen.add(base)
            true
        }.probe()
        assertTrue(seen.contains(upstreams.orderbook), seen.toString())
        assertTrue(seen.contains(upstreams.trading), seen.toString())
    }
}
