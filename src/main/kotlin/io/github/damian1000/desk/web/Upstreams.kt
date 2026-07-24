package io.github.damian1000.desk.web

import java.net.URI

/**
 * The services the desk composes, each behind a tab prefix. A request path like
 * `/orderbook/api/AAPL/stream` [resolve]s to the order book's base URI plus the prefix-stripped
 * upstream path `/api/AAPL/stream`; the [ReverseProxy] forwards that on. Bases come from the
 * environment so the same artifact runs against localhost in a test and box-local ports (or a
 * cross-box URL) in production.
 */
data class Upstreams(
    val orderbook: URI,
    val trading: URI,
) {
    private val routes: List<Pair<String, URI>> =
        listOf(
            "/orderbook" to orderbook,
            "/trading" to trading,
        )

    /**
     * The upstream base and the path to send it for [path], or null when no tab prefix matches.
     * `/orderbook` and `/orderbook/` both map to upstream `/`; `/orderbook/app.css` maps to
     * `/app.css`. Only a whole path segment counts, so `/orderbookx` never matches `/orderbook`.
     */
    fun resolve(path: String): Resolved? {
        val match = routes.firstOrNull { (prefix, _) -> path == prefix || path.startsWith("$prefix/") } ?: return null
        val (prefix, base) = match
        val stripped = path.removePrefix(prefix)
        return Resolved(base, stripped.ifEmpty { "/" })
    }

    /** Each upstream keyed by tab name (the prefix without its slash) — for the readiness probe. */
    fun bases(): Map<String, URI> = routes.associate { (prefix, base) -> prefix.removePrefix("/") to base }

    data class Resolved(
        val base: URI,
        val path: String,
    )

    companion object {
        fun fromEnv(env: Map<String, String>): Upstreams =
            Upstreams(
                orderbook = URI(env["ORDERBOOK_UPSTREAM"] ?: "http://localhost:8080"),
                trading = URI(env["TRADING_UPSTREAM"] ?: "http://localhost:8082"),
            )
    }
}
