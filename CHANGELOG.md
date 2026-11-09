# Changelog

## Unreleased — plugin ecosystem inventory (local, not released yet)

- **Host: `GET /plugins`** reads the active DSH profile (`$DSH_HOME/profiles/web/package.json`) and
  returns the installed packages (`name`, `version`, `specifier`, `local`), the profile's bundle
  list, a `truncated` flag (200 entries max) and `supported:false` when unreadable. Read-only,
  bounded, never throws.
- **App: Settings → Plugins & ecosystem** lists the profile package inventory with versions and
  `local` badges, plus the profile bundle list. Runtime health/enable actions stay desktop-side.
- Fake host mirrors the endpoint for UI/contract tests; new host test covers the parser
  (link/local detection, missing profile, 200-entry cap).

## Unreleased — session defaults (local, not released yet)

- **Session defaults page** (Settings → Session defaults): pick the default agent preset and the
  default provider/model for sessions created from the phone ("Host default" preserved for both).
  A disabled/broken preset is shown but not selectable.
- New Session sheet preselects the chosen default preset; session creation applies the default
  model via the existing `PUT /sessions/{id}/model` API (best effort — hosts without the model
  service fall back to the host default instead of failing creation).
- Choices persist in the app's presentation preferences; the per-session model picker in chat still
  overrides for a single session.


## 0.11.1 — Connection route row layout fix

- Fixed the Connection page route rows ballooning in height when a route was unreachable
  (signal bars + summary now share one compact row, error text stays on its own line).

## 0.11.0 — Inbox Done history, theme transfer, accessibility pass

- **Inbox "Done" history**: resolving an approval (approve/deny) or answering a question now records
  a Done card with the title, session, action and age, so the Inbox shows what was just handled.
- **Theme transfer**: Appearance page can copy the theme (accent, dark/AMOLED, chat/interface text
  scales, code wrapping) as JSON and import one from the clipboard on another phone.
- **Stalled-stream timing**: the chat retry card now shows how long the stream has been stalled in
  seconds, counting live.
- **Accessibility/haptics**: settings icon rows expose content descriptions; bottom-nav tabs expose
  labels/badges to TalkBack and give haptic feedback on tap.

## 0.10.0 — Connection speed test, signal bars, chat stalled-stream card

- **Run speed test** on the Connection page: three rounds per route, real **p50/p90** shown under
  each route (plus the route probes update so the active route can be compared fairly).
- **Signal bars** on every route row (green/amber/red by measured latency; grey when unreachable).
- **Chat stalled-stream card**: while a reply is in flight and the stream is down, the banner now
  reads "stream stalled · retrying via <route>…" with **Retry now** and **Switch route** actions.
- Docs: `docs/ui-refresh-batches.md` maps each mockup element to its shipped release.

SSE resume was already implemented (`events?since=<lastEventId>` + host replay); noted in the docs.

## 0.9.0 — Settings hub with per-section pages, larger home tabs

- **Every settings section is now its own page**: the overview is a hub of mint icon rows
  (Hosts, Routes & failover, Pair a device, Tailscale, Theme, Text & density, Usage, Data source,
  Sessions, Recovery & security, Notifications, Voice, Advanced) and each secondary section opens
  a dedicated page with a back header instead of one long scroll.
- **Home tabs**: the bottom navigation is now a row of individual boxes with bigger glyphs/labels
  (no more underline row), and the old `dsh · live · sessions` status strip is hidden on Home so
  the nav gets the full bottom area.

## 0.8.0 — Real connection metrics, Inbox filters

- **Request timing instrumentation**: OkHttp event listener records first-byte and total time for
  every API call (`PerfBus`); the Connection page now shows real **FIRST BYTE** and **RECONNECTS**
  numbers instead of placeholders.
- **Connection page buffering section**: honest status rows for gzip payloads, stale cache and warm
  connect (what is actually on, not fake switches).
- **Inbox restyle**: filter chips (All / Approvals N / Questions N) above the approval and question
  cards; first-answer-wins behaviour unchanged.

## 0.7.0 — Mockup-faithful settings rows, home bottom nav, retry banner

Third UI-refresh batch: the remaining design elements from the approved mockups.

- **Settings home** now uses the mockup's mint icon-tile rows with chevrons/pills:
  Hosts (count), Routes & failover ("fastest" pill), Pair a device, Sign in with Tailscale,
  Theme (swatch accessory), Text & density; the Advanced row got the same icon treatment.
  Hero buttons are now **Connection** (primary) + **Pair device** (ghost).
- **Home**: workspace chips row (All + each workspace), and the mockup's **bottom navigation**
  (Home / Inbox with pending badge / Usage / Settings).
- **Chat**: while a reply is in flight and the stream is down, an inline retry banner shows
  "stream busy · retrying via <route>…" with Retry and Fastest actions.
- **Appearance page**: accent picker is now the mockup's two-column gradient theme gallery
  (Hadal/Ocean/Amethyst/Ember/Sakura/Slate) with selected checkmark.

## 0.6.0 — Appearance & connection pages, chat status strip

Second UI-refresh batch, filling in the mockup screens on top of the 0.5.0 shell.

- **Appearance page** (Settings → Appearance): live preview card, accent theme gallery, dark/AMOLED
  toggles, chat + interface text-size chips and code wrapping — the same controls, now a dedicated
  page instead of a long inline block.
- **Connection page** (Settings hero → Connection): live connection hero with host/version, active
  route, latency/routes/sync metrics, Probe all / Use fastest, per-route health list with click to
  switch route, and an SSE transport note; links to the full diagnostics screen.
- **Settings sub-page navigation**: the settings overview now opens dedicated surfaces for
  Appearance and Connection, with back navigation and no state loss.
- **Chat connection strip**: a compact always-visible line above the composer shows connection
  state, active route, probe latency and streaming/idle state.
- **Host plugin buffering win**: the host now gzips JSON responses ≥1 KB when the client advertises
  `Accept-Encoding: gzip` (transcripts/snapshots are the big payloads on slow links). Existing host
  test suite still passes (350/350).

## 0.5.0 — Settings & home refresh

- **Settings overhaul (first UI-refresh batch).** Live connection hero at the top (host, active
  route, latency, routes, last sync, Hosts/Connection shortcuts), filter-as-you-type search across
  settings groups, refreshed group titles, and a collapsed **Advanced** section holding diagnostics,
  storage and build info. Groups: host & connection, usage, data source, appearance, sessions,
  recovery & security, notifications, voice, advanced.
- **Home refresh**: connection pill + active route/latency/sync strip under the host prompt, and
  one-tap quick filters (All / Working / Needs input / Idle) with counts plus a Filters shortcut.
- No functional regressions: every existing settings control and host flow is unchanged, just
  reorganized.

## 0.4.0 — QR pairing, connection routes & design preview

First release with the mobile-ecosystem batch 1 features: QR pairing (host page + app scanner),
multi-route hosts with latency probing and automatic failover, and the connections UI/design
proposals under `docs/design/`.

- **P0 connection manager (multi-route hosts).** A paired host can now carry several endpoints
  (LAN / Tailscale / Cloudflare / custom). Every route shares the pairing token; the app probes
  them in parallel (`/health`, 4 s budget) and shows latency in Settings → Hosts → Routes and in
  Connection diagnostics. When the live connection drops, the app probes the routes and
  reconnects through the fastest reachable one instead of retrying the dead URL (60 s storm
  guard); a manual Reconnect follows the same policy. Routes are validated, capped at 8 per host,
  at least one is kept, and legacy single-URL profiles migrate to one "Direct" route.

- **QR pairing (P0).** The host plugin serves a loopback "Connect a phone" page at
  `http://127.0.0.1:3543/pair`: it mints a one-use code, renders the `qrPayload` as a QR (vendored
  MIT `qrcode-generator`, no CDN), shows the six-digit fallback and an expiry countdown, under a
  strict CSP. `/pair/new` keeps its loopback + anti-proxy guard.
- **App scanner.** Pairing screen now has **Scan QR code**; a CameraX + ML Kit (bundled model)
  scanner reads the host QR and pairs from the payload, with a permission/fallback UI and the
  manual six-digit path unchanged.
- **Pairing deep link.** `dshmobile://pair?base=...&code=...&host=...` opens the pairing screen
  prefilled (never pairs silently); the parser rejects malformed payloads with a clear message and
  is covered by JVM unit tests.
- Toolchain: `gradlew` is executable for Linux checkouts.

## 0.3.4 — faster session sync
- Session lists now fetch host metadata, sessions, approvals and questions concurrently.
- Session switching reuses a fresh transcript for 20 seconds and loads a smaller 60-message first page.
- Optional skills, plan, team, jobs and context metadata load only when opened.
- Chat requests the transcript before model/queue metadata to reduce host contention.
- Background sync reuses its HTTP pool and pauses polling while the app is foregrounded.
- Tailnet DNS outages and offline periods use bounded backoff instead of rapid retry.
## 0.3.3 — DSH Android rebrand
- Renamed the Play listing and launcher label to **DSH Android**; the Play title is **DSH Android Unofficial Harness**.
- Refreshed the app icon, feature graphic and six Play screenshots for the new brand.
- Added the first-run setup guide covering desktop, cloud, Tailscale, recovery and demo mode.
- Demo data is now the first option on the welcome screen; no host or account is required to explore.

## 0.3.2 — first-run setup and demo-first onboarding
- Added a guided setup screen that opens on first launch and remains available from the welcome screen.
- Moved demo mode to the first position and made the no-host path explicit.
- Added step-by-step instructions for desktop, cloud, Tailscale, recovery and demo users.

## 0.3.1 — composer and bug-sweep fixes
- Fixed sent text remaining in the composer; drafts clear optimistically and restore only on failure.
- Composer text now uses the full width; attachments/send moved to their own row.
- Enter/Send IME action sends; model picker moved into the chat More menu.
- Fixed stale foreground flag suppressing background notifications; failed attachments block send; find offsets, upload/session binding and attachment-retention fixes.

## 0.3.0 — Google Play release preparation
- Added a signed Play release build targeting SDK 36 (versionCode 146) with Play App Signing upload-key support.
- Play build removes the sideload APK installer permission and self-updater/feed; debug/sideload builds keep them.
- Added privacy policy, Play listing copy, data-safety and reviewer-access notes, app icon and feature graphic.
- Remote approval mockups/design reviewed separately; the Play build does not include any OS privilege escalation.

## 0.2.143-preview — terminal cockpit drawer
- Drawer rebuilt as a cockpit: ❯ dsh + live status, compact New/Search/Refresh, data-driven navigation and live session rows with status/unread/current state.
- Rich session rows open by id and show workspace/status/time; current session highlighted; 8 visible with overflow and view-all.
- Chat drawer About now opens the shared About dialog; all drawer actions wired on Home and Chat.

## 0.2.142-preview — terminal UI simplification
- Compact terminal shell: Home and Chat hide the command strip/workspace deck and use a single-line status bar, reclaiming vertical space.
- Home: simplified `dsh@<host>` header, New session, one-line filters summary, and a single Filters sheet (workspace/status/sort) instead of three chip rows.
- Chat: Skills, Plan, Prompt templates, Session info and Tool activity now live in one accessible More overflow, so the top bar no longer holds five separate actions.
- Readability: larger JetBrains Mono body/label sizes, softer radii, and 48dp composer/top-bar/action targets.
- All previous screens remain reachable; Goals/Schedules/Archived/Search all/Inbox/Usage now in Home More.

## 0.2.141-preview
- Update feed moved to the latest release asset (`releases/latest/download/latest.json`) for reliable, cache-safe manifests.
- The updater tolerates a UTF-8 byte order mark in a feed.

## 0.2.140-preview — first public source release

- Combined Android app, host plugin and release feed into a single public repository.
- Files browser: **Mention in prompt** appends a safe `@relative/path` to the composer.
- Startup recovery: a failed `AppState` construction shows Retry / Safe mode / Share report instead of crashing.
- Fixed the paired-launch `NullPointerException` caused by `messageJumpTarget` being declared after the constructor init block.
- Releases are still debug-signed preview APKs for testing.

## 0.2.136–0.2.139-preview

- Context-window meter in Session Info (pressure/percentage, breakdown, amber/red warnings).
- Adaptive two-pane layout on windows ≥960 dp; accent theme packs; background jobs; share-message-as-image cards.
- Host: generalized credential/path redaction for jobs and trajectory; context-pressure endpoint; jobs endpoint with status folding and cap metadata.

## 0.2.130–0.2.135-preview

- Trajectory journal, Agent Teams board, plan/todos sheet, scheduled prompts, search-hit jumps, delegation badges.
- Remote Markdown images, tool-output viewer, PDF export, attachments and image sharing.
- Accent themes and adaptive layout groundwork.

## 0.2.120–0.2.129-preview

- Workspace file browser, team/lineage views, skills browser, push quiet hours and mute sync.
- Approval policy and structured question answers, queue management, session presets and model selection.
- Markdown images and attachment reliability improvements.

## Earlier previews

- Native Compose shell, pairing, session list, streaming chat, approvals, SSE reconnect and push notifications.
- Host plugin Mobile API v1, fake host, release updater.

See the repository releases and `docs/mobile-feature-ideas.md` for the larger catalogue of explored ideas.
