# Deploying the trading desk

Box 1 (`145.241.193.169`), alongside orderbook (`:8080`), risk-engine (`:8081`), and
visitor-analytics (`:8083`). The desk runs on `:8084` and reverse-proxies the three services
under one origin at `desk.damianhoward.com`.

## Automated deploy

`.github/workflows/deploy.yml` runs on merge to `main` (or `workflow_dispatch`): it builds and
tests the distribution, ships the tested artifact over SSH, keeps a `-prev` copy for rollback,
syncs the systemd unit only when it changed, restarts, and gates success on a `/healthz` 200.
Secrets: `DEPLOY_SSH_KEY` (the box-1 `oracle_orderbook` key), `DEPLOY_HOST`, `DEPLOY_USER`.

## One-time host setup

1. **DNS** — Cloudflare `desk.damianhoward.com` A record → `145.241.193.169`, **DNS only / grey**
   (proxied breaks Caddy's ACME challenge).
2. **Caddy** — append the block in `deploy/Caddyfile` to `/etc/caddy/Caddyfile`, then
   `sudo systemctl reload caddy`. 80/443 are already open; no new port is exposed — `8084` stays
   localhost-only.
3. **Upstreams** — the box-local defaults (`localhost:8080/8081/8082`) cover orderbook and risk.
   For trading (box 2), create `/etc/trading-desk/upstreams.env` with
   `TRADING_UPSTREAM=http://10.0.0.91:8082` (VCN private IP; needs an OCI ingress rule `8082 from
   10.0.0.150/32` and a box-2 iptables ACCEPT) or `TRADING_UPSTREAM=https://trading.damianhoward.com`
   (no firewall change).
4. **systemd** — the first deploy installs `deploy/trading-desk.service`; thereafter the pipeline
   keeps it in sync. `JAVA_OPTS=-Xmx96m` (the desk is a proxy plus static assets — check `free -m`
   before adding it, as box 1 already runs three JVMs).

## Memory note

Box 1 is a ~1 GB Always-Free AMD micro already committing ~512 MB of heap across three JVMs.
`-Xmx96m` sizes the desk tight to fit; if `free -m` shows pressure, trim one of the existing heaps
or add swap. A tactical compromise of the 1 GB box, not the design.
