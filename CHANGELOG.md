# Changelog

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
