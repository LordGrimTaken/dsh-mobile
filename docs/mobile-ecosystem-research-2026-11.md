# DSH mobile ecosystem research — November 2026

Research date: 2026-11-08. This is a **product/feature survey**, not an instruction to copy
third-party code. Some projects below use GPL-2.0 or other licences; ideas are fine, copied source is
not. Verify licences before reusing anything.

## Why now

The DeepSeek Harness plugin ecosystem is moving from side projects toward an official distribution
channel:

- TechNode reported that DeepSeek plans an **official plugin marketplace** for Harness, with plugin
  API stability, compatibility and security improvements. It also reported **1.54 million daily
  active Harness users** (Nov 4) and about **35,000 third-party plugins**, with roughly 60% of active
  users having installed or created at least one.
  <https://technode.com/2026/11/06/deepseek-plans-official-plugin-marketplace-as-harness-daily-users-reach-1-54-million/>
- The community Awesome DSH registry tracks plugins, skills, workflows, clients and integrations,
  including a growing mobile/remote-access category.
  <https://github.com/fendouai/awesome-deepseek-harness>
- Google's on-device AI app trends reinforce three expectations: **MCP tool registration**,
  **local notifications/reminders**, and **persistent chat history**.
  <https://developers.googleblog.com/en/a-smarter-google-ai-edge-gallery-mcp-integration-notifications-and-session-continuity/>

## Mobile-oriented projects worth watching

### 1. `saya-ch/dsh-mobile` — full mobile-access product

- Plugin **0.8.4**, Android app **0.7.2**, plus iPhone/iPad PWA.
- LAN/remote access: Tailscale, cpolar, cloudflared quick/named tunnels, self-hosted FRP, custom
  reverse proxy, experimental iroh encrypted direct/relay.
- QR/link/key pairing; multi-device list with reachability and re-pair/delete.
- In-chat `/mobile <request>` customises the mobile experience by editing mobile resources.
- Mobile-specific session drawer, tool details, questions, composer; remembers drawer position.
- Third-party plugin adaptation and an allowlist for plugin UI/WebSocket behaviour.
- One-click diagnostics covering versions, gateway, interfaces, firewall, tunnel; redacted report.
- <https://github.com/saya-ch/dsh-mobile>

### 2. `shaobeichen/dsh-pocket` — the “whole DSH UI in your pocket” approach

- 796★; a plugin plus a phone-facing proxy, not a native client.
- LAN QR, public cloudflared QR, optional **named tunnel + fixed domain**.
- Random 8-character public password by default; custom fixed LAN/public passwords; session login
  persists until the desktop DSH process restarts.
- **WebSocket full-duplex passthrough**, heartbeat/keepalive and reconnect; gzip/brotli compression;
  mobile CSS adapted from `dsh-web-mobile`.
- This project validates that ease of setup (scan → use) and stable remote hostnames matter as much as
  the client UI itself.
- <https://github.com/shaobeichen/dsh-pocket>

### 3. `Clarklevis1995/dsh-mobile` (Whaleness) — native cross-platform client

- Kotlin Multiplatform: native Android Compose + iOS SwiftUI, sharing protocol/state reducers.
- WebSocket gateway; optional **WebRTC data channels** with WebSocket fallback for lower latency.
- QR pairing, plugin management, scheduled tasks, files/images, active turn status, team traces,
  WebRTC/latency diagnostics, remote log export.
- Update checks with source fallback and APK/SHA verification.
- <https://github.com/Clarklevis1995/dsh-mobile>

### 4. `201222-L/dsh-mobile-remote` — feature-dense Flutter control console

- QR pairing, session continuation, model/reasoning/permission presets, default agent preset.
- Server-side configurable approval location: both / mobile only / desktop only.
- Notification bridges (ServerChan, ntfy, Bark), notification inbox with delete/clear.
- Model-provider configuration from phone (base URL + API key), provider-grouped model picker.
- Third-party plugin action area, environment diagnostics, background task cards and team/subagent
  panels, goal controls, desktop pet.
- <https://github.com/201222-L/dsh-mobile-remote>

### 5. `THEWOLFWALKER/dsh-notifier` — omnichannel notification/control plane

- 28 outbound and 6 inbound channels for notifications, approvals, questions and remote conversation.
- Task/session control from the notification channel; unsafe inbound actions fail closed.
- This is a clear signal that notifications are becoming **actionable control surfaces**, not just
  alerts.
- <https://github.com/THEWOLFWALKER/dsh-notifier>

### 6. Mobile web UI / tunnel kits

- `@qingshanyuluo/dsh-client-ui-mobile`: CSS-only narrow-screen DSH UI (drawer, full-width chat),
  public tunnel kit with cloudflared + Caddy, and a WebView Android shell.
  <https://www.npmjs.com/package/@qingshanyuluo/dsh-client-ui-mobile>
- `dsh-web-mobile`: mobile adaptation of the DSH Web UI.
  <https://github.com/mexiaosqwq/dsh-web-mobile>
- `dsh-sev`, `dsh-remote`, `dsh-full-remote`: remote host/gateway management, token-gated access,
  Host/Origin rewriting, QR and per-device sessions.

## What the DSH Android app already has

Already covered in our app:

- native live sessions, streaming chat, approvals and structured questions;
- files/attachments/preview, plans, Agent Teams, jobs, schedules, usage and context;
- multi-host profiles, cached transcripts, background notification sync, widget/quick tiles;
- terminal-first theming, adaptive two-pane layout, demo mode and setup guide;
- background polling, local notification actions and an optional ntfy path.

The gap is less about **chat basics** and more about **setup, control, discovery and ecosystem
integration**.

## Recommended additions, in priority order

### P0 — QR pairing and one-tap host connection

Most mature projects now start with a QR code. Our current host code is a manual six-digit flow.

- Host plugin endpoint to create a short-lived `dshmobile://pair?...` payload with endpoint, code,
  host name and protocol version.
- App CameraX/ML Kit scanner, permission flow, confirmation screen, then the existing pair-complete
  API.
- Desktop “Connect a phone” page showing QR + a fallback 6-digit code; rotate/expire on pair.
- Deep link handling for shared/notification links.

**Why:** removes the biggest onboarding failure point and brings us in line with the ecosystem.

### P0 — Connection manager with endpoint failover

This directly addresses the Tailscale/Cloudflare latency and session-switch pain.

- Let one host expose several endpoints: LAN, Tailscale Serve, named Cloudflare tunnel, custom URL.
- Probe each endpoint in parallel (health + latency); pick the fastest reachable route and remember it.
- Auto-fail over when the active route drops; show the selected route in diagnostics.
- Surface per-endpoint latency and last-success time; do not hide a 2s host stall behind “VPN”.
- Keep the existing Cloudflare/Caddy setup as one endpoint; no new public exposure.

**Why:** the app should choose the best route automatically instead of asking users to maintain
multiple host profiles and discover the slow one by feel.

### P1 — Notification center + actionable notifications

Our current notifications are useful but session-local and stateless.

- Notification inbox grouped by session, with mark-read/clear and “needs input” filters.
- Approve/deny and free-text answers from the notification, not only from the app.
- Per-session mute, quiet hours and notification grouping.
- Optional outbound channels (ntfy/Telegram/Discord/email) using host-side config; fail closed for
  inbound control.
- Keep the persistent background-sync notification informative (current turn/approval count).

**Why:** competitors have turned notifications into a remote-control plane; this is a high-retention
feature for people away from the desktop.

### P1 — Plugin management and “Ecosystem” screen

The official plugin market is coming; mobile apps will be expected to expose installed plugins.

- List installed host plugins with version, enabled state, update availability and health.
- Enable/disable/restart plugin and show its UI/actions where the plugin exposes a mobile-safe entry.
- **Read-only marketplace browse** first, with install/update behind explicit confirmation and host
  approval. Wait for the official marketplace API before promising installation.
- Show plugin actions relevant to the current session (e.g., a plugin action area), not arbitrary
  desktop panels.

**Why:** official distribution will make plugins mainstream; a phone client that cannot see them will
feel incomplete.

### P1 — Model/provider and agent defaults configuration

- Model catalog grouped by provider; searchable; per-session model and reasoning effort (partly
  present).
- Edit provider base URL/API key from the phone only when the host exposes a safe write API; never
  store provider keys on the phone.
- Default agent preset and default permission mode for new sessions; approval-location preference
  (phone/desktop/both).
- Create-session flow with workspace + agent preset.

**Why:** competitors let users configure the agent, not only chat with it.

### P2 — MCP/tool integration visibility

Google AI Edge Gallery now imports MCP tool definitions and resource schemas; DSH has MCP/integration
plugins.

- Show MCP servers, connection state, tools and resource health in the host settings.
- Surface new/changed tool approvals and failures in the notification stream.
- Possibly allow enabling/disable MCP servers from the phone with host-side confirmation.

**Why:** MCP is becoming the standard way agents reach outside their sandbox; users need to know what
tools their agent can use.

### P2 — Transport improvements and measured latency

- Consider a WebSocket multiplexed channel alongside SSE for chat/control, with heartbeat,
  backpressure and cursor replay; keep SSE as fallback.
- Request priorities and cancellation on session switch (we already reduced burst requests).
- Compression for large transcript payloads; adaptive page size based on measured latency.
- Add per-request timings to diagnostics (DNS/connect/TTFB/parse) so host vs network is obvious.

**Why:** our SSE loop is solid, but competitors are moving to WebSocket/WebRTC and users feel the
difference when switching sessions.

### P2 — Cross-platform / web fallback

- A read-only or limited mobile web console (or PWA) served by the host plugin gives iPhone/iPad and
  locked-down work PCs a path without an Android APK.
- If we do this, reuse the same auth/pairing model and never expose the DSH web UI unauthenticated.
- This is strategic, not a near-term core change.

### P3 — Quality-of-life

- QR/deep links in share cards.
- Long-press text selection and full-screen tables/code with CSV/JSON export.
- Better offline session continuity and compressed transcript caches.
- Live-updating ongoing notification/status for the current turn.
- Theme/preset import/export; per-device UI scale.

## What not to chase

- Do not turn DSH Android into a generic WebView wrapper. Its differentiators are native caching,
  notifications, adaptive layout and testable protocol code.
- Do not copy GPL project code into our MIT app. Reimplement ideas independently.
- Do not add an official marketplace integration against an unannounced private API. Wait for a stable
  endpoint or keep discovery read-only.
- Do not expose the host or web UI without authentication. The Quick Tunnel + basic auth is a
  temporary workaround, not a product design.

## Suggested next batch

1. QR pairing + host “Connect a phone” screen.
2. Connection manager with LAN/Tailscale/Cloudflare endpoint probing and failover.
3. Notification center with inline approval/answer actions.
4. Host plugin list screen (read-only first).
5. Model/provider/default-preset settings. 

This sequence gives the most visible improvement without destabilizing the session/SSE core.
