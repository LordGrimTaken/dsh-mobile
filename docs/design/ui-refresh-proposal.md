# DSH Mobile — UI refresh + connectivity plan (proposal, 2026-11-09)

Design mockups: [`docs/design/mockups/`](mockups/) (PNG renders + HTML sources). Everything here is a
**proposal** — nothing in this folder is implemented app code yet.

## What the refresh is trying to fix

1. Settings has grown by accretion (Hosts, pairing, recovery, notifications, appearance, updates,
   diagnostics, storage) with no hierarchy; the useful "is my host healthy?" signal is buried.
2. The home screen answers "what sessions exist" but not "what needs me right now".
3. Connectivity work is invisible: users can't tell host processing from network, and a dead route
   looks the same as a busy host.
4. Buffering feels worse than it is: blank first paint, lost partial replies on reconnect, no
   skeleton/status affordance.

## Screen proposals

| Mockup | What's proposed |
|---|---|
| `settings-home.png` | Searchable settings, a **live host/route health hero** (latency, routes, last sync, one tap to Connection / Pair device), then six labelled groups: Hosts & connection, Appearance, Notifications, Data & advanced. Destructive/rare things (safe mode, logs) move under Advanced. |
| `settings-connection.png` | One page for connectivity: route health list (latency, active, unreachable), auto-failover policy, buffering metrics (first byte / render / reconnects), buffering toggles (warm connect, prefetch, stale-while-revalidate, gzip), transport segmented control (SSE / WebSocket beta / auto), probe policy, and a **speed test** that reports p50/p90 per route. |
| `settings-appearance.png` | Visual theme gallery with live preview, text-size slider, density (compact/cosy/comfortable), reduce-motion, transitions, haptics. Accent packs + per-device scale + theme export/import. |
| `home-sessions.png` | Host+route+latency strip, search + primary New, filter chips (All/Working/Needs input/Idle) and workspace chips, pull-to-refresh that keeps the cached list, richer session cards with goal/streaming badges, skeleton card for the next page, and a **bottom nav** (Home / Inbox / Usage / Settings) for one-hand reach. |
| `chat-buffering.png` | A visible streaming contract: streaming status strip (first token, tok/s, route), buffering banner when the host is busy, stalled-stream card with **Retry now / Switch route** while keeping partial text, collapsed tool rows with spinner/timing, composer status and connection strip. |
| `inbox-actions.png` | P1 notification center: approval cards with command preview + Approve/Deny, inline question answers, finished-turn/goal rows, per-session mute and grouped history. |

## Connectivity & buffering plan

### P0 — visible + cheap wins (days)
- **Stale-while-revalidate everywhere**: render cached session list/transcripts instantly, refresh in
  background; skeleton only on first run.
- **Warm connect on launch**: open HTTP connection + `/health` + snapshot concurrently, reuse the
  connection for the first prompt (OkHttp dispatcher/connection pool per route).
- **Probe policy**: probe routes on app start/resume/drop; never block the first render on a probe.
- **Client timeline instrumentation**: DNS / connect / TLS / first byte / JSON parse / first frame per
  request, surfaced in Connection diagnostics + a "Speed test" (p50/p90 ×3 per route).
- **Stream resilience**: keep already-rendered text on stream drop; on reconnect resume from last
  event id; show "retrying via <route>…" instead of a blank reply.
- **Offline send queue**: queue prompts when offline with an idempotency token; host dedupes retries.

### P1 — transport + payload (1–2 weeks)
- **gzip/brotli JSON responses** in the host plugin (`Accept-Encoding`, `zlib`) for transcripts and
  snapshots — transcripts are the largest payloads on a slow link.
- **Incremental fetches**: `ETag`/`If-None-Match` on snapshots, `?since` transcript deltas, and
  session-list diffing instead of full refetches.
- **Paging/prefetch**: first 60 messages + prefetch next page at 60 % scroll; virtualise long transcripts.
- **SSE hardening**: gap detection on replay cursor → delta refetch (never full reload), heartbeat-aware
  liveness (detect dead streams in <5 s), exponential backoff with jitter, per-route circuit breaker.
- **WebSocket transport (beta)** with SSE fallback: one duplex channel for prompts + events, fewer
  reconnects on flaky networks, heartbeat/ping, backpressure.
- **Timeouts by operation**: 2 s route probes, 8 s health/snapshot, 90 s stream read, 30 s prompt ack.

### P2 — scale + polish
- HTTP/2 tuning through the tunnel, TLS session resumption, QUIC experiment (Tailscale/Cloudflare).
- Downscaled image thumbnails + lazy full-res fetch; attachment prefetch limits.
- Compose perf: stable models/keys, derivedState, baseline profile, R8 for release, defer ML Kit init
  until the scanner opens; cold-start budget.
- Background sync: incremental `since`-based sync, coalesced events, pause on idle, battery-aware.

### Targets (measured, not vibes)
- Cold open → cached list visible: **< 300 ms**.
- Session switch (cached transcript): **< 150 ms**; uncached: first frame < 600 ms.
- First streamed token: p50 **< 1.2 s** on the tailnet route.
- Reconnect/failover visible to the user: **< 3 s**.
- Route probe round: p95 **< 2 s** (parallel, 4 s cap).
- Transcript page (200 messages) render: **< 400 ms**.

## Other upgrades worth queueing

- **P1** notification center actions (mock above) — approve/deny/answer from the inbox, grouped,
  per-session mute, quiet hours.
- **P1** plugin/ecosystem screen: installed plugins, versions, health, update availability (read-only
  until the official marketplace API exists).
- **P1** provider/default session config: provider-grouped model search, default preset/permission
  mode, create-session sheet with workspace + preset; per-session model memory.
- **P2** MCP/tool visibility + tool approvals in the stream; diff cards for edited files; artifact
  viewer; transcript export/search improvements.
- **P2** PWA/web fallback with the same auth model; home-screen widgets; theming packs import/export.
- **Cross-cutting** accessibility pass (TalkBack labels, contrast, 48 dp targets, one-hand reach),
  motion/haptics system, and a diagnostics "share bundle" for troubleshooting.

## Suggested order

1. Connectivity P0 + settings/connection/chat mockups (highest perceived improvement, low risk).
2. Settings IA refresh (search + groups + hero) and home filter/nav.
3. Notification center P1.
4. Transport P1 (gzip/incremental/WebSocket beta) measured against the targets above.
