package io.github.damian1000.desk.web

import java.net.URI

/**
 * The desk's operational truth for `/readyz`: it can reach the upstreams it proxies. `/healthz`
 * proves the desk process answers; this proves each tab has a live service behind it, so a deploy
 * that comes up while an upstream is down or misconfigured reads as not-ready rather than serving a
 * shell whose tabs 502 on the first click.
 *
 * [reachable] probes one upstream — the real one GETs its `/healthz` with a bound timeout and
 * fails closed, so a slow or unreachable upstream is not-ready, never a hung probe. The probe is
 * per-request (the deploy gate samples it), so it reflects the estate's current state, not a
 * cached one.
 */
class Readiness(
    private val upstreams: Upstreams,
    private val reachable: (URI) -> Boolean,
) {
    data class Probe(
        val ready: Boolean,
        val json: String,
    )

    fun probe(): Probe {
        val checks = upstreams.bases().map { (name, base) -> name to reachable(base) }
        val ready = checks.all { it.second }
        val json =
            """{"ready":$ready,"upstreams":{""" +
                checks.joinToString(",") { (name, ok) -> """"$name":{"ok":$ok}""" } +
                "}}"
        return Probe(ready, json)
    }
}
