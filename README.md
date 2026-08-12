# trading-desk

[![CI](https://github.com/damianhoward/trading-desk/actions/workflows/ci.yml/badge.svg)](https://github.com/damianhoward/trading-desk/actions/workflows/ci.yml)
[![CodeQL](https://github.com/damianhoward/trading-desk/actions/workflows/codeql.yml/badge.svg)](https://github.com/damianhoward/trading-desk/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/damianhoward/trading-desk/graph/badge.svg)](https://codecov.io/gh/damianhoward/trading-desk)

One trading workspace over two services. The desk presents the live order book and the trading
dashboard — positions, risk, and PnL off the fill stream — as tabs in a single browser page:
one chrome, one status bar, one origin.

Live at **[desk.damianhoward.com](https://desk.damianhoward.com)**.

## What it is

A composing gateway. The desk owns no domain logic: [`orderbook`](https://github.com/damianhoward/orderbook)
and [`trading-system`](https://github.com/damianhoward/trading-system) stay standalone, independently
deployed services. This module serves the shell UI and reverse-proxies each service under a tab
prefix, so the browser talks to one origin while each backend runs untouched. The trading tab's
risk numbers come from the [`risk-engine`](https://github.com/damianhoward/risk-engine) library,
which also runs standalone at [risk.damianhoward.com](https://risk.damianhoward.com).

```
Browser ──▶ desk.damianhoward.com
             ├─ /                     shell: topbar + tab bar + status
             ├─ /orderbook/**  ─▶  order book service   (live book, SSE)
             └─ /trading/**    ─▶  trading-system        (positions off the fill stream, SSE)
```

Each tab is the real service's front end in an iframe, with its own topbar and status bar hidden
(`?embed=1`) so the desk supplies the single surrounding chrome. Both tabs stay mounted, so a
tab's live stream keeps running while the other is in view.

The proxy and the frame answer different questions, and the pair is deliberate. The proxy makes it
one origin, which is what lets `frame-ancestors` stay `'self'` rather than being opened up to a
second hostname. The frame is what keeps each tab the service's own front end: orderbook and
trading-system stay independently deployed, each tab keeps its own SSE stream running while another
is in view, a keystroke stays with the app being typed into rather than reaching the shell, and a
child that fails renders a broken tab instead of a broken desk.

## Routing and streaming

`ReverseProxy` matches a tab prefix, strips it, and forwards the request to the resolved upstream —
`/orderbook/api/AAPL/stream` becomes `/api/AAPL/stream` against the order book service. The response
body is streamed chunk-by-chunk with a flush after each write, so an upstream `text/event-stream`
reaches the browser as frames are produced rather than being buffered until the connection closes.
An upstream that can't be reached maps to a `502` before any bytes are sent; a client that hangs up
mid-stream ends the copy.

Upstream bases come from the environment (`ORDERBOOK_UPSTREAM` / `TRADING_UPSTREAM`), defaulting
to the box-local ports, so the same artifact runs against loopback in a test and box-local (or
cross-box) URLs in production.

## Build and run

```bash
./gradlew --no-daemon spotlessCheck   # ktlint + Prettier (web assets, YAML, Markdown)
./gradlew --no-daemon clean build     # tests, 90% coverage gate, and packaging — what CI runs
./gradlew installDist && PORT=8084 build/install/trading-desk/bin/trading-desk
```

With the two services running on their default ports, open `http://localhost:8084`.

## Tests

`ReverseProxy` is exercised over a loopback `HttpServer` upstream — prefix rewrite, query and body
forwarding, `502` on an unreachable upstream, and SSE frames delivered as they are produced (an
upstream gate holds the second frame until the first has arrived downstream, so buffering would
fail the test). `DeskServer` routing and `Upstreams` resolution are covered directly.
