# UI refresh — from mockups to shipped app

The approved mockups (`docs/design/mockups/`) were implemented in four release batches.

| Mockup element | Status | Release |
| --- | --- | --- |
| Settings: searchable overview, live host/route hero, grouped rows | shipped | 0.5.0 |
| Settings: compact host actions, collapsed Advanced | shipped | 0.5.0 |
| Home: route/latency/sync strip, status quick filters | shipped | 0.5.0 |
| Appearance page: preview, dark/AMOLED, text sizes, code wrap | shipped | 0.6.0 |
| Connection page: route health, Probe all/Use fastest, transport note | shipped | 0.6.0 |
| Chat: connection strip above composer | shipped | 0.6.0 |
| Settings icon-tile rows (Hosts/Routes/Pair/Tailscale/Theme/Text), hero buttons | shipped | 0.7.0 |
| Appearance: two-column gradient theme gallery | shipped | 0.7.0 |
| Home: workspace chips, bottom navigation | shipped | 0.7.0 |
| Chat: stalled-stream retry card (Retry now / Switch route) | shipped | 0.10.0 |
| Every settings section as its own page (hub) | shipped | 0.9.0 |
| Home: boxed larger bottom tabs, status strip removed | shipped | 0.9.0 |
| Connection: real first-byte/reconnects metrics (OkHttp event listener) | shipped | 0.8.0 |
| Connection: buffering status rows (gzip/stale cache/warm connect) | shipped | 0.8.0 |
| Connection: 3-round speed test (p50/p90) + signal bars | shipped | 0.10.0 |
| Inbox: filter chips (All/Approvals/Questions) | shipped | 0.8.0 |
| Host: gzip JSON responses ≥1 KB | shipped | 0.6.0 |

## Deliberately not faked

- The mockup's Warm connect / Prefetch / Stale cache / gzip **switches** are shown as honest status
  rows: those behaviours are always-on today. Wiring them into real preferences needs a settings
  contract on the host and is tracked as backlog.
- First token / tok/s labels on the chat strip are not shown — the app does not measure model
  token rates; the strip shows real route/latency/stream state instead.

## Remaining, larger research items (not part of the UI refresh)

- Notification-center actions outside the Inbox (goal/turn-finished cards) — Inbox approve/deny is
  live; grouped "Done" history is backlog.
- Plugin/ecosystem screen, provider + default session config, MCP/tool visibility, PWA fallback,
  accessibility/haptics pass: larger features requiring host API or design work; not started.
- Connectivity P1: ETag/`?since` deltas (SSE resume from `lastEventId` already ships), WebSocket
  transport (beta), image thumbnails, stall-duration instrumentation.
