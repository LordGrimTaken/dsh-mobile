# Mobile feature ideas for a native Android DSH client

Status: idea catalog + implementation tracking · Written 2026-10-30 · Reviewed against `0.2.103-preview` 2026-10-31 · Scope: remaining work after 0.2.103 toward v1.0

A practical catalog of **200 feature ideas** for the native Android client for DeepSeek Harness, drawn from
Claude (Android/mobile), ChatGPT (Android), Gemini (Android), and the agent-CLI world (Claude Code and
peers), plus capabilities that only DSH can offer. Every idea is filtered for **Android feasibility**: if
the platform does not expose an API for it, or it needs host work, the entry says so.

Idea numbers are stable. Reference `#57` in an issue, a PR title, or a roadmap row.

---

## Implementation status (0.2.103)

Reviewed 2026-10-31 against `0.2.103-preview` (versionCode 105). The idea list below is unchanged and its numbers
stay stable; this table only maps shipped work to idea numbers. A number is listed only when `0.2.103` covers it;
`(partial)` marks an idea whose core ships with a named gap. Per-area detail and honest gaps:
[`mobile-feature-status.md`](mobile-feature-status.md). Ideas not listed here are planned.

| Shipped area | Ideas | What is in 0.2.103 / what remains |
|---|---|---|
| Live loop / SSE | #1, #2, #146, #148, #175, #176, #145 (partial) | cursor replay + snapshot fallback, idempotent upsert, sync status strip, single owner with backoff/heartbeat watchdog, network auto-heal; `lastEventId` is not persisted across process death |
| Sessions / workspaces | #43, #44 (partial), #47, #48, #52, #93 (partial), #141, #153, #190, #46 (partial) | workspace chips, create-session sheet with the **real host agent-preset roster** (0.2.101; default/broken handling, legacy fallback), local rename + pin, **host rename for live sessions + archive/unarchive with an Archived screen (0.2.100)**, **conversation fork from the latest completed turn or an exact message boundary (0.2.110)**, multi-host profiles, cache-first list, hidden-session manager, **agent-preset label + fork lineage card with accessible Open parent (0.2.122)**; gaps: no fork browser/tree, no starter prompts, no host delete or cold-session rename, no age-based auto-archive (#93), date buckets without sticky headers/this-week bucket |
| Offline / paging | #142, #144, #174, #147 (partial) | cache-first detail, cached reading + global cached-message search, load-earlier paging; attachment cache auto-trimmed to 100 MB/7 days at app start with a thumbnail LRU, but no visible size-cap setting, media eviction rules (#147) or retention policy (#120) |
| Schedules / reminders | #84, #85 (partial), #87 (partial) | Schedules screen: list active/inactive host reminders with next/last run, create one-off "after N minutes" or daily HH:MM (device timezone), **edit title/instruction + after/daily timing (0.2.99)**, delete with confirmation, and **"Schedule this prompt" from the chat draft (0.2.99)**; no pause/run-now, no every/weekly/cron/at timing editing or per-schedule timezone picker, no scheduled-result notifications (#86, still planned) |
| Chat surface | #8 (partial), #9 (partial), #10, #11 (partial), #33 (partial), #41 (partial), #53 (partial) | message actions (copy/share/quote/read aloud), collapse + timestamps, jump-to-latest, templates/snippets/recent prompts, tool cards + tool activity sheet, **tool-result output viewer (0.2.120)** with capped text, monospace selectable sheet, Copy/Share and truncation note; **scheduled prompts show with an accessible “Scheduled” marker (0.2.128)** while staying out of previews/search; **Trajectory journal (0.2.130, inspired by sorsama/deepseek-harness-mobile)** exposes newest-first raw-event summaries with Load older; no select-text of messages, slash palette, snippet tags, summary card or a true timeline visualization |
| Queue / drafts / uploads | #4, #6, #7 (partial), #12, #23, #27 (partial), #71, #74 (partial) | drafts survive restart; cancelable local send queue (edit/reorder/flush on idle); **host inbox queue panel (0.2.109)**: list live waiting prompts, edit text (attachment prompts blocked), remove, steer-into-running-turn; full-res camera, streamed multi-file upload with progress/cancel/retry, 8-pending + 64 MB decoded preflight; gaps: no enqueue/reorder from the host panel, no live queue polling, no per-image captions/compression |
| Attachments read-back / viewer | #32, #65, #67 (partial), #69 (partial) | message attachments carry metadata and are fetched with `GET /sessions/{id}/attachments/{id}` (0.2.96), image chips get bounded thumbnails with an LRU (0.2.97), tapping one opens a **full-screen viewer with pinch/double-tap zoom and prev/next paging (0.2.102)**, other files open via FileProvider; **tool-result image/file blocks (Android screenshots, generated artifacts) are mapped and rendered in chat (host plugin 0.2.6)**, **local Markdown/workspace image links inside the session cwd or configured `artifacts.roots` render as synthetic attachments (0.2.121)**, and **public http(s) Markdown images are proxied by host 0.3.0 (opt-in, SSRF-hardened)**, and the viewer can **Save to Photos / Copy image (0.2.118)** with byte/declared-MIME resolution; no in-app Markdown/PDF/DOCX artifact rendering (#67), no workspace-path/Markdown-image fetching, no MediaStore save-to-device with progress (#69), no HEIC/AVIF normalization |
| Markdown / code | #3 (partial), #70 (partial) | GFM tables, task lists, fenced code with per-block copy + wrap toggle; whole-message reparse, no save-as-file/explain |
| Search | #75 (partial), #76 (partial), #82 (partial) | in-session find, session-list search, cached-message search, and **host-wide message search (0.2.103)**: "Search all sessions" scans visible sessions via `GET /search?q=&limit=` with highlighted snippets, session/role/time and a truncation note; **role/date filters (0.2.111)**; **offset pagination + Load more (0.2.117)** with stale-page/cursor guards and old-host compatibility; the scan is still bounded (no index/rate limit; deep/old results can be truncated) but the app now shows host scan coverage (scanned/total/incomplete) and tapping a hit **jumps to the message** via host seq with bounded older-page loading/highlight (0.2.126); no full-text index, no in-text highlights in find |
| Goals | #194 | GOAL banner (create/edit/pause/resume/**complete**/clear; Complete shipped 0.2.92 with a stale-dialog revision guard), round counts, session-list badge, Goals dashboard, goal alerts, widget suffix |
| Approvals / questions / inbox | #54 (partial), #55, #57, #58 (partial), #89 (partial), #188 (partial) | **answer from the phone by default (0.2.93–0.2.95)**: Allow once/Deny for approvals, option buttons plus free-text/"Other…" for questions, first whole answer wins against the desktop, read-only fallback via `mobileAnswer:false`, lost-race notices; **per-session approval policy Ask/Never (0.2.106, live-session only, Never auto-rejects approval-requiring actions)**; Inbox with waiting count and notification actions; gaps: no tool allowlist/audit trail for unattended runs (#89 full), no deny reason/note (#60), no danger-flag highlighting (#56), no "answer later" (#58), partial multi-question batches keep the agent waiting, no default approvals home tab (#188) |
| Notifications / background | #94 (partial), #95 (partial), #96 (partial), #97, #98, #99, #102, #103 (partial), #149, #168 (partial) | channels incl. usage/goal alerts, quiet hours, per-session mute, grouped summary, foreground dedupe, background poll fallback; notification Allow/Deny decide when the host allows mobile answering (0.2.93) but still open the app; **optional ntfy push (0.2.105)** from the host with approval/question/goal/turn summaries and inbox/session deep links (Settings-configured, SSRF/backoff/rate-limited); **inline free-text question reply (0.2.116)** via RemoteInput with an 8.5 s background send, per-question notifications, lock-screen-private public version, app-lock gating and idempotent stale-answer handling (no option buttons in the shade yet, unavailable while app lock is on); no FCM, and host pushes still cannot honor app-local foreground state (quiet hours + per-session mutes sync to host 0.3.3; #103 partial) |
| Widgets / shortcuts / tiles | #161 (partial), #162 (partial), #164 (partial), #165, #166 | status widget (queued/waiting/goal suffix, inbox action), Quick Settings tiles, static + dynamic pinned shortcuts; not Glance, no camera/mic actions |
| Voice | #13 (partial) | continuous on-device dictation with live partials, offline preference and language picker; no hands-free voice loop (#14) or TTS playback controls (#16–#19) |
| Security / theming | #113, #114 (partial), #115 (partial), #123 (partial), #125 | Keystore tokens, device-credential lock + grace period (biometrics via the system prompt), FLAG_SECURE, AMOLED variant, UI/chat text scales; no BiometricPrompt-specific UI, dynamic color, transport allowlist (#116) or secret redaction (#117) |
| Usage / balance | #108 (partial), #112 | token totals, per-session tokens, balance card, low-credit alerts, today estimate, usage CSV; no daily/weekly spend or per-turn cost |
| Update channel / export | #72, #186, #187 (partial), #155 (partial), #137 (partial) | transcript text + `.md` file share, **PDF transcript export (0.2.119)**, usage CSV, sideload updater with release notes in the dialog, demo mode, dev-host URL shortcut, **versioned local-state backup export/import (0.2.104: aliases/pins/mutes/hidden/drafts/queue/snippets/safe prefs; not transcripts or attachments)**; no full transcript+attachment export bundle, what's-new sheet or print/PDF (#73) |
| Diagnostics / pairing | #122 (partial), #182 (partial), #150 (partial), #152 (partial), #158 (partial), #178 (partial), #199 (partial), #200 (partial) | **paired-device list + revoke (0.2.107)**: Settings → Security lists device metadata, revokes another device with confirmation; host revocation is persisted (rollback on failure), invalidates the token immediately and closes its SSE stream; no token material shown. **Recovery access (0.2.111)**: single-use 256-bit recovery link/code generated while paired (hash-only at rest), redeemable from Welcome or a `dshmobile://recover` deep link after data clear/reinstall with no desktop code; rotates and shows the next link once. **Tailscale one-tap sign-in (0.2.112/0.2.114)**: a Tailscale Serve request carries the tailnet identity header, which the host trusts in exchange for a device token (no 6-digit code); methods-aware Welcome path and Settings → Hosts add-host flow (plus recovery fallback), allowlist/disable config, fallback to code/recovery. **Crash-loop safe mode (0.2.113/0.2.114)**: static Safe mode shortcut, `dshmobile://safe-mode` and a Settings → Diagnostics entry open a minimal recovery screen (clear caches, reset presentation settings, share crash log, App info) without starting the normal shell, preserving pairing/drafts/app lock; crash recorder routes 3-in-10-min loops there; dark platform theme, live-region/status and TalkBack labels. Also `/health` + pre-pair URL test, reconnect event log, copy/share diagnostics, crash report capture, multi-host profiles/health, base URL shown; gaps: tailnet ACL is the auto-pair boundary (header not cryptographically bound beyond Serve/loopback), no admin role/re-auth/audit for revocation (#122 full), no device rename, no Tailscale/clock-skew probes, raw SSE inspector, redacted log bundle, ANR ring buffer, API-compat banner or certificate/no-relay statement, and safe mode has no device-level forced-crash/TalkBack test |

Planned, not shipped at 0.2.103: an indexed/paged host search endpoint and search filters (#76 full/#82), diff cards, in-app artifact
rendering (#67 is external-open only), print to PDF (#73), scheduled-result
notifications (#86), deny reasons and danger-flag highlighting (#56/#60), preset
matrix (#160), transport allowlist (#116), tool inventory/delegation/
workflow monitors (#191, #193, partial #195), and the remaining DSH-specific surfaces (#196–#200).

---

## How to read this

**Tags.** Every idea carries `[Impact / Effort]` and an API marker:

- Impact: **High**, **Medium**, **Low** — user-visible value for a solo-dev Android client.
- Effort: **Small** (< 1 day), **Medium** (1–3 days), **Large** (1–2 weeks) for one experienced Android dev.
- `API: now` — implementable against the documented Mobile API v1 (`docs/mobile-api-v1.md`) with no host change.
- `API: host` — needs a new or changed host endpoint/capability; the sentence names the shape it needs.

**The two rules used to keep this honest.**

1. No invented Android APIs. Camera, Photo Picker, SAF, `SpeechRecognizer`, TTS, `MediaProjection` (with
   consent), `BiometricPrompt`, Keystore, WorkManager, Glance widgets, `TileService`, notification actions,
   `RemoteInput`, `MediaSession`, `PrintManager`, ML Kit, Health Connect (read-only) all exist and are fair game.
2. No pretending. Anything that requires the host (rename, attachment read-back, push, model choice, usage,
   schedules) is marked `host` — a lot of the "AI app" polish in Claude/ChatGPT/Gemini is server-side, and
   pretending otherwise would produce a plan that stalls in week two. The note in *Android reality check*
   explains the ones people ask for most.

**What already exists, so we do not rebuild it.** Pairing + device tokens, multi-host profiles, workspaces,
session list/detail, create session (real host agent presets), prompt with `attachmentIds` (streamed uploads,
8 per prompt), interrupt, host-side rename/archive/unarchive, goals (`GET`/`POST /sessions/{id}/goal` plus
resume/pause/edit/complete/clear and `goal.changed`), phone answering for approvals and questions (first answer
wins, read-only fallback), attachment read-back (`GET /sessions/{id}/attachments/{id}`), host schedules
(list/create/edit/delete), host-wide message search (`GET /search`), SSE (`message.delta` /
`message.completed` / `approval.requested` / `question.requested` / `goal.changed` …), hidden-session curation,
usage aggregation and API balance. Details in [`mobile-api-v1.md`](mobile-api-v1.md); current shipped/planned
status by area in [`mobile-feature-status.md`](mobile-feature-status.md).

---

## The first 25 to implement (ranked)

Ranked for a solo dev shipping a native Android client: correctness of the live loop first, then the
screens that make it feel like a product, then the Android integrations that create daily habit. All 25 are
`API: now` unless noted, and each maps to one idea number.

| # | Implement | Idea | Why first |
|---|---|---|---|
| 1 | Resumable SSE with cursor replay + snapshot fallback | #1 | Every other feature is built on a socket that survives a subway ride. |
| 2 | Snapshot/delta reconciliation | #2 | Prevents the duplicated-transcript bug that makes the app look broken. |
| 3 | Cache-first session list | #141 | Instant perceived speed on cold start; no spinner on the first screen. |
| 4 | Per-session draft persistence | #4 | Losing a 300-word prompt is the fastest way to lose a user. |
| 5 | Incremental markdown + code copy | #3 | The single biggest visual upgrade over plain text. |
| 6 | Real stop/interrupt control | #5 | Mobile users interrupt constantly; a dead button destroys trust. |
| 7 | Approval card (allow/deny + note) | #54 | The reason a phone client exists: approve from the couch. |
| 8 | Question card (options + free text) | #55 | Same, for the mid-turn "which approach?" moment. |
| 9 | Approvals + questions inbox | #57 | Turns scattered prompts into a triage surface with a badge. |
| 10 | Message action sheet | #8 | Copy/quote/share is 80% of what people do with an answer. |
| 11 | Jump-to-latest streaming | #10 | Fixes the most-cited mobile chat complaint in one change. |
| 12 | Client send queue while a turn runs | #6 | Turns a hard 409 into an obvious "queued" affordance. |
| 13 | Retry last prompt | #7 | Flaky networks are the default; retry should be one tap. |
| 14 | Attachment tray with cap/size preflight | #12 | Host limits are hard errors; catch them before upload. |
| 15 | Camera → upload → prompt | #23 | The most-used multimodal flow on a phone, end to end. |
| 16 | Create session + preset picker | #44 | Makes a phone the place you *start* work, not just watch it. |
| 17 | Workspace switcher | #43 | Users have more than one workspace the day they install. |
| 18 | In-session find | #75 | Long agent answers are unreadable without search. |
| 19 | Keystore token storage | #113 | One day of work; removes the worst finding in any security review. |
| 20 | Deep-link notifications + inline approve | #95 | The notification is the product for background turns (push arrives with #103). |
| 21 | Share-sheet ingest | #135 | "Send to DSH" from Chrome/Photos is the highest-leverage entry point. |
| 22 | Quick-actions home widget | #161 | A launcher icon becomes three one-tap verbs. |
| 23 | Local pin/star sessions | #48 | Cheap list hygiene while host rename/archive is being built. |
| 24 | Sync status strip | #148 | Makes reconnect state legible instead of a silent freeze. |
| 25 | Pairing doctor | #182 | Kills the "it doesn't connect" support thread before it starts. |

This ranking is the original 2026-10-30 plan, not a to-do list: by `0.2.103-preview` most of these 25 are shipped
or partial (see *Implementation status* above). Still open from the next tier are FCM/device registration for a full
#103 push path (ntfy host push ships in 0.2.105), #92
(overnight digest), #172 (render budget), #171/#180 (baseline profile + startup gate), #145 (persisted cursor),
and the DSH-specific surfaces beyond #194 (#191–#193, #195–#198); #115 (app lock) ships in its
device-credential form, #190 (host presets) ships as the real preset roster, and #93 (auto-archive) is manual
host archive for now.

---

## Core chat and composer · ideas 1–12

1. **Resumable stream with cursor replay.** Persist the last SSE `id` and reconnect with `?since=`, falling back to a full detail refetch when the host answers with a snapshot instead of a replay. `[High impact / Small effort]` · `API: now`
   Why: the socket *will* die on mobile; without this, every backgrounded turn reads as a broken app.
2. **Snapshot-then-delta reconciliation.** Apply `session.updated` snapshots before rendering deltas, and upsert by message id (`m_<toolCallId>` for tool rows) so text is never duplicated or dropped. `[High impact / Small effort]` · `API: now`
   Why: naive merging is the #1 cause of the "it printed everything twice" bug report.
3. **Incremental markdown rendering.** Parse only the tail of a streaming message and render headings, tables, task lists, and fenced code with a per-block copy button. `[High impact / Medium effort]` · `API: now`
   Why: plain text makes every agent answer feel like a log file instead of a colleague's reply.
4. **Per-session draft persistence.** Debounce-save composer text and pending attachment names locally so a process kill or a tap on a notification never loses the draft. `[High impact / Small effort]` · `API: now`
   Why: the most expensive thing on the screen is what the user typed.
5. **Stop that means it.** Wire `/interrupt` to a confirm-once button that shows "stopping…" until the next event proves the run ended. `[High impact / Small effort]` · `API: now`
   Why: a button that looks pressed but does nothing is worse than no button.
6. **Client-side send queue.** While a turn is running, hold the next prompt with a "sends after this turn" chip and flush it on `message.completed`. `[High impact / Small effort]` · `API: now`
   Why: people type in the gaps; a rejected second prompt feels arbitrary.
7. **Retry the last prompt in one tap.** Resend the last user text (reusing still-valid attachment ids) with visible `retry 2/3` state. `[High impact / Small effort]` · `API: now`
   Why: networks fail, and a retry should never require retyping.
8. **Message action sheet.** Long-press for copy, share, quote-reply (prefills `> …`), select-text, and read aloud. `[High impact / Small effort]` · `API: now`
   Why: extract-and-reuse is the dominant phone behavior with a good answer.
9. **Collapsible long messages with metadata.** Collapse past ~600 px with "show full", and put timestamp, tool count, and model behind a tap. `[Medium impact / Small effort]` · `API: now`
   Why: a 40-message session has to stay readable on a 6-inch screen.
10. **Jump-to-latest streaming.** Autoscroll only while the user is within about one screen of the bottom; otherwise show a "3 new messages" pill. `[High impact / Small effort]` · `API: now`
    Why: hijacked scroll is the most-cited complaint about every mobile chat app.
11. **Composer slash palette.** Local prompt templates (`/review`, `/summarize-session`) that expand into composer text with no round trip. `[Medium impact / Small effort]` · `API: now`
    Why: typing on glass is expensive; templates turn 40 words into three taps.
12. **Attachment tray with preflight.** Thumbnails, remove-before-send, a live "6/8" counter, and a size check against the 10 MB decoded / 15 MB body limits before upload. `[High impact / Medium effort]` · `API: now`
    Why: host caps are hard errors, and catching them in the UI is cheaper than a failed prompt.

## Voice · ideas 13–22

13. **Push-to-talk dictation.** Hold the mic, stream partial results from the on-device `SpeechRecognizer`, release to insert into the composer. `[High impact / Medium effort]` · `API: now`
    Why: dictation is the single biggest input upgrade a phone can give an agent client.
14. **Hands-free voice mode over existing text.** Loop STT → prompt → SSE deltas → local TTS, sentence by sentence, with no new server contract. `[High impact / Large effort]` · `API: now`
    Why: it delivers "talk to your agent" using only the text API we already have.
15. **Barge-in.** Speaking while TTS plays stops playback and interrupts the run, exactly like tapping Stop. `[Medium impact / Medium effort]` · `API: now`
    Why: talking over the assistant is how humans say "stop" without looking.
16. **Sentence-pipelined TTS.** Start speaking at the first complete sentence instead of waiting for `message.completed`. `[High impact / Medium effort]` · `API: now`
    Why: perceived latency drops from ~8 s to ~1 s without touching the host.
17. **TTS-safe text shaping.** Strip markdown, spell out paths, and read code as "code block, twelve lines" rather than identifiers. `[Medium impact / Small effort]` · `API: now`
    Why: markdown read literally is unusable, and this is the whole difference for voice.
18. **Read-aloud playback controls.** Play/pause/skip through a `MediaSession` so headphone and car buttons work. `[Medium impact / Medium effort]` · `API: now`
    Why: commuting is the natural voice moment; lock-screen controls are table stakes.
19. **Voice and speed picker.** Choose the system TTS voice, 0.8–2.0× rate, and a per-language fallback, remembered per device. `[Medium impact / Small effort]` · `API: now`
    Why: OEM voice quality varies wildly and users have strong preferences.
20. **Audio routing indicator.** Show whether playback goes to speaker, headset, or Bluetooth, and follow route changes mid-reply. `[Low impact / Medium effort]` · `API: now`
    Why: silent playback confusion generates bug reports that are really routing.
21. **Voice-note attachment.** Record AAC/M4A, upload it as a file attachment, and label it "voice note · 0:42". `[Medium impact / Medium effort]` · `API: now` (model support for audio depends on the host)
    Why: for long context dumps, two minutes of speech beats two minutes of thumb typing.
22. **Silence auto-stop with haptics.** End capture after ~2 s of silence, with distinct haptic patterns for start, stop, and error. `[Medium impact / Small effort]` · `API: now`
    Why: it makes hands-free voice usable without looking at the screen.

## Multimodal · ideas 23–32

23. **Camera to chat in one flow.** Capture → downscale to ≤ 2000 px → upload → prompt, in a single sheet that never leaves the chat. `[High impact / Medium effort]` · `API: now`
    Why: "what is this?" plus a camera is the defining mobile AI action.
24. **Photo Picker, no storage permission.** Use the Android Photo Picker (with `ACTION_GET_CONTENT` fallback) so the app never asks for `READ_MEDIA_IMAGES`. `[Medium impact / Small effort]` · `API: now`
    Why: one fewer scary permission prompt increases attachment usage measurably.
25. **Clipboard image paste.** Detect an image on the clipboard and offer "paste image" in the composer. `[Medium impact / Small effort]` · `API: now`
    Why: half of all shared content on Android moves through the clipboard.
26. **Screen capture with consent.** Single-frame `MediaProjection` capture for "what is wrong with this screen?", with per-capture consent. `[Medium impact / Medium effort]` · `API: now`
    Why: debugging UI on a phone currently means describing a screen in words.
27. **Multi-image batch.** Select up to 8 images, upload sequentially with progress, and allow a short caption per image. `[Medium impact / Medium effort]` · `API: now`
    Why: 8 is the host cap, and comparisons are the natural multi-image use case.
28. **Annotate before sending.** Draw, crop, and highlight on the captured image and upload the flattened PNG. `[Medium impact / Medium effort]` · `API: now`
    Why: a red circle communicates a bug faster than a paragraph about it.
29. **Format normalization.** Convert HEIC/AVIF camera output to JPEG and enforce the host image policy before upload. `[Medium impact / Small effort]` · `API: now`
    Why: modern phones shoot formats the host may reject; failing after a 2 s upload feels random.
30. **On-device OCR fallback.** ML Kit text recognition with a "send as text instead" action when an image is rejected by policy. `[Medium impact / Medium effort]` · `API: now`
    Why: a degraded path beats a dead end in a basement with a photo of a receipt.
31. **Region-of-interest follow-up.** Tap a point on a sent image to crop around it and send that crop with the next question. `[Low impact / Medium effort]` · `API: now` (source image from the local cache)
    Why: "what is this part?" is a real question that a whole-image repeat answers badly.
32. **Sent-image viewer.** Zoomable viewer for images this phone uploaded, from the local cache. `[Medium impact / Small effort]` · `API: now` (read-back is not in v0.2.2)
    Why: people want to re-check the photo they attached; only local files are currently reachable.

## Memory and personalization · ideas 33–42

33. **Snippet library.** Locally stored, taggable text snippets (briefs, house style, boilerplate) inserted with one tap. `[High impact / Small effort]` · `API: now`
    Why: most repeated prompting is the same context pasted over and over.
34. **Per-workspace defaults.** Remember preset, reply style, and tone suffix per workspace, applied at session creation. `[Medium impact / Small effort]` · `API: now` (preset) / `API: host` (model)
    Why: a workspace has a personality, and retyping it every time is friction.
35. **Glossary autocomplete.** A local dictionary of project names, acronyms, and paths that completes as you type in the composer. `[Medium impact / Small effort]` · `API: now`
    Why: it removes the spelling drift that makes agent answers subtly wrong.
36. **Session brief.** A pinned note per session, shown under the header and optionally prepended to prompts as `Context: …`. `[Medium impact / Small effort]` · `API: now`
    Why: it gives the agent the one fact the session keeps forgetting.
37. **Tone chips.** One-tap suffixes — "terse", "explain like I'm new", "with tests" — appended to the outgoing prompt. `[Medium impact / Small effort]` · `API: now`
    Why: it is personalization with no storage, no settings screen, and instant payoff.
38. **Continue where you left off.** A home card with the most recently touched session and its last assistant line. `[Medium impact / Small effort]` · `API: now`
    Why: it turns app launch into continuation instead of navigation.
39. **On-device frequency ranking.** Blend recency and open count locally to order the session list. `[Medium impact / Small effort]` · `API: now`
    Why: it adapts to actual usage without shipping a server-side ranking system.
40. **"What this session knows" panel.** Show workspace, preset, attachment count, and whether internal sessions are hidden. `[Medium impact / Small effort]` · `API: now`
    Why: trust in an agent comes from seeing its context, not guessing at it.
41. **Session summarizer.** A one-tap prompt asking the agent to summarize the session so far, rendered as a copyable card. `[Medium impact / Small effort]` · `API: now`
    Why: it makes long sessions portable into a new chat or a status update.
42. **Local personalization inspector.** Show every locally stored snippet, brief, alias, and template, with a one-tap wipe. `[Medium impact / Small effort]` · `API: now`
    Why: "personalization" that cannot be inspected is indistinguishable from data collection.

## Projects and workspaces · ideas 43–52

43. **Workspace switcher.** A top-bar chip that filters the session list and sets the create target. `[High impact / Small effort]` · `API: now`
    Why: the host already returns workspaces at pairing; ignoring them wastes the model.
44. **Create-session sheet with preset picker.** `POST /sessions` with `{workspaceId, preset?}`, then three starter prompts to choose from. `[High impact / Small effort]` · `API: now`
    Why: it makes the phone the place you start work, not just watch it.
45. **Workspace health card.** Name, path, host version, API version, last successful call, and p50 latency. `[Medium impact / Small effort]` · `API: now`
    Why: when something breaks, the first question is always "is the host alive?".
46. **Grouped session list.** Date buckets (Today / Yesterday / This week / Older) with sticky headers and per-workspace counts. `[Medium impact / Small effort]` · `API: now`
    Why: a flat list stops scaling at about 30 sessions.
47. **Local display aliases.** Rename a session's label on-device until the host grows a rename endpoint. `[Medium impact / Small effort]` · `API: now`
    Why: agent-generated titles are often wrong, and "Untitled session" is worse.
48. **Local pin and star.** A pinned section above the date buckets, stored per device (moving to host pinning when the host exposes it). `[High impact / Small effort]` · `API: now`
    Why: the three sessions you care about should never scroll away.
49. **Workspace-scoped search.** Restrict find to a workspace and date range, over cache first and host search when available. `[Medium impact / Small effort]` · `API: now` (cache) / `API: host` (server)
    Why: "where did I fix that deploy script?" is the query people actually have.
50. **Session templates.** Named recipes ("Bug triage", "PR review", "Weekly planning") that create a session and send a canned first prompt. `[Medium impact / Small effort]` · `API: now`
    Why: it packages prompting skill into a button, which is the point of a client.
51. **Workspace safety banner.** Show the workspace path with a read-only vs writable badge so the user knows what the agent can touch. `[Medium impact / Small effort]` · `API: host` (permission preset in the host summary)
    Why: on a phone it is dangerously easy to forget which repo you are in.
52. **Multi-host switcher.** Profiles for desktop and cloud hosts, each with base URL, token, health, and last-seen. `[High impact / Large effort]` · `API: now` (client-side)
    Why: it is the prerequisite for the phone-only phase and for anyone with two machines.

## Agents and tools · ideas 53–64

53. **Tool timeline.** Render tool rows as a compact vertical timeline — name, command, state color — with expand for detail. `[High impact / Medium effort]` · `API: now`
    Why: the tool trace is the agent's work log, and it is the most interesting part.
54. **Approval card that reads well.** Monospace command, full description, allow/deny with optional note, wait time, and a copy action. `[High impact / Small effort]` · `API: now`
    Why: this is the single most valuable screen in the whole app.
55. **Question card.** Option buttons plus free text, sending `optionIndex` when a choice is tapped. `[High impact / Small effort]` · `API: now`
    Why: mid-turn clarifications are where a phone can unblock an agent in five seconds.
56. **Danger-flag highlighting.** Client-side emphasis on `--force`, `rm -rf`, `DROP`, `git push --force`, and credential paths inside approval commands. `[Medium impact / Small effort]` · `API: now`
    Why: cheap, visible safety that costs one regex file.
57. **Approvals inbox.** A dedicated screen listing every pending approval across sessions, with a badge on the tab. `[High impact / Small effort]` · `API: now`
    Why: triage is a different task from reading a transcript, and it deserves its own screen.
58. **Questions inbox.** The same surface for questions, with "answer later" keeping the session in `needs_input`. `[Medium impact / Small effort]` · `API: now`
    Why: a question left unanswered blocks a run; it must be as visible as an approval.
59. **Live activity screen.** One row per working session showing the current tool and elapsed time. `[High impact / Medium effort]` · `API: now`
    Why: "what is it doing right now?" is the question a remote-control app must answer instantly.
60. **Deny-with-reason templates.** Prefilled notes ("wrong branch", "too broad", "ask me first") that populate the `note` field. `[Medium impact / Small effort]` · `API: now`
    Why: a denial with context teaches the agent; a bare denial makes it retry blindly.
61. **Tool filter chips.** Toggle to show only errors, only approvals, or only file edits within a session. `[Medium impact / Small effort]` · `API: now`
    Why: the interesting 10% of a 200-event run is currently buried.
62. **Failed-tool card.** Show the failure excerpt with copy and a "retry with guidance" prompt that quotes the error back. `[High impact / Small effort]` · `API: now`
    Why: errors are where the user's help is most useful, and the raw text is right there.
63. **Interrupt confirmation.** If a tool is running, confirm before interrupting and name what is in flight. `[Medium impact / Small effort]` · `API: now`
    Why: an accidental stop during a long write is unrecoverable from the phone.
64. **Plan/progress strip.** Derive a phase list from tool events and show the current step as a thin strip under the header. `[Medium impact / Medium effort]` · `API: now`
    Why: progress beats a spinner when a run takes four minutes.

## Files and artifacts · ideas 65–74

65. **Attachment read-back.** A host endpoint to fetch an attachment by id, rendered inline for images and offered for download otherwise. `[High impact / Medium effort]` · `API: host` — `GET /sessions/{id}/attachments/{attachmentId}`
    Why: today the phone can send files it can never see again; that is a visible hole.
66. **Diff cards.** Unified diff with +/− coloring, word-level highlight, and collapse for large diffs. `[High impact / Medium effort]` · `API: host` (diff payload or a diff endpoint)
    Why: reviewing an edit from the phone is the killer feature for the coding use case.
67. **Artifact viewer.** Render agent-produced Markdown/PDF/DOCX either in a lightweight in-app viewer or via an OS intent. `[Medium impact / Large effort]` · `API: host` (artifact fetch)
    Why: "show me the report" should end in reading it, not in a path string.
68. **Workspace file browser.** A read-only tree with "attach this file to the next prompt" — **shipped read-only browser/preview (0.2.131/host 0.3.10) and “Mention in prompt” (0.2.139)**; host-file attachment upload and in-app PDF/HTML rendering are still missing. `[Medium impact / Large effort]` · `API: host`
    Why: it removes the desktop round trip for the most common attach case.
69. **Save to device.** `MediaStore`/SAF download of an artifact with a progress notification. `[Medium impact / Medium effort]` · `API: host` (depends on read-back)
    Why: sharing a generated file onward is why it was generated.
70. **Code block toolbar.** Copy, soft-wrap toggle, save as `.txt`, and "explain this block" as a follow-up prompt. `[Medium impact / Small effort]` · `API: now`
    Why: code on a phone is consumed in fragments, not whole files.
71. **Chunked multi-file upload.** Loop the single-attachment endpoint with per-file progress, retry, and cancel. `[Medium impact / Medium effort]` · `API: now`
    Why: the API takes one file per call; the UI should still accept a multi-select.
72. **Transcript export.** Write Markdown/JSON of a session to SAF and hand it to the share sheet. `[Medium impact / Small effort]` · `API: now`
    Why: sessions are work product, and work product leaves the app.
73. **Print to PDF.** `PrintManager` output of a clean transcript for filing or reimbursement. `[Low impact / Small effort]` · `API: now`
    Why: a zero-server way to produce a shareable document.
74. **Attachment preflight.** Warn above 10 MB decoded, offer compression, and show remaining host pending capacity. `[Medium impact / Medium effort]` · `API: now`
    Why: the 32-pending / 8-per-prompt caps are invisible until a prompt fails.

## Search and research · ideas 75–83

75. **In-session find.** Incremental find with highlights and jump-to-match over loaded messages, upgraded to paged host search later. `[High impact / Medium effort]` · `API: now` (cache) / `API: host` (full history)
    Why: long agent answers are unreadable without search.
76. **Global session search.** Local title/preview search first, then a paged host search endpoint. `[High impact / Medium effort]` · `API: host` (message search)
    Why: the DSH session query service already supports paging; the phone should use it.
77. **Source chips.** Extract links from assistant messages into a numbered "Sources" row that expands in place. `[High impact / Small effort]` · `API: now`
    Why: research answers are only trustworthy if the links are one tap away.
78. **Custom Tabs with a trust bar.** Open citations in Android Custom Tabs, showing the host domain. `[Medium impact / Small effort]` · `API: now`
    Why: in-app browsing without leaving the conversation, and visible provenance.
79. **Deep-research mode.** A template prompt plus a "researching" surface showing elapsed time, tools used, and a stop button. `[Medium impact / Medium effort]` · `API: now`
    Why: the mode is mostly presentation, and presentation is what makes waiting bearable.
80. **Bookmarked answers.** Save a message locally with a short note; browse saved items in their own screen. `[Medium impact / Small effort]` · `API: now`
    Why: good answers are references, and references need a shelf.
81. **Follow-up question chips.** After a long answer, suggest three follow-ups derived from its headings and links. `[Medium impact / Small effort]` · `API: now` (client heuristic)
    Why: it keeps a research thread moving without composing from scratch.
82. **Search filters.** Restrict by date range, workspace, and role (user vs assistant). `[Medium impact / Small effort]` · `API: host` (for server search)
    Why: "what did I ask about the updater last week" needs role and date, not keywords alone.
83. **Continue-research handoff.** Start a new session seeded with a one-paragraph summary of the current one. `[Medium impact / Small effort]` · `API: now`
    Why: context windows end; research threads should not.

## Automation and scheduling · ideas 84–93

84. **Schedule this prompt.** A composer overflow action ("run daily at 08:00") that creates a host schedule. `[High impact / Medium effort]` · `API: host` (`POST /schedules`)
    Why: the single most requested "AI app" feature that a phone is uniquely good at setting up.
85. **Schedule manager.** List, pause, edit, and delete schedules with next-run times and last result. `[High impact / Medium effort]` · `API: host`
    Why: automation that cannot be inspected becomes automation nobody trusts.
86. **Scheduled-result notifications.** Notify on completion or failure of a scheduled run, deep-linking into the session. `[High impact / Medium effort]` · `API: host` (with push)
    Why: the value of a scheduled run is the result arriving without you.
87. **"Run again tomorrow".** One tap on any message to schedule the same prompt at the same time. `[Medium impact / Small effort]` · `API: host`
    Why: repetitive asks are discovered in the transcript, not in a settings screen.
88. **Recipes.** Locally stored multi-step prompt chains sent as one numbered message. `[Medium impact / Small effort]` · `API: now`
    Why: it gets plan-like behavior with no server primitive at all.
89. **Unattended-run policy.** An explicit setting allowing scheduled runs to auto-approve a named tool allowlist, with a warning and an audit trail. `[Medium impact / Large effort]` · `API: host`
    Why: unattended runs are useless if they block on the first approval, and dangerous without a policy.
90. **Tasker/intent bridge.** Broadcast and receive intents plus Tasker-callable shortcuts for "new session with prompt X". `[Medium impact / Medium effort]` · `API: now`
    Why: Android power users automate with Tasker, and it costs one exported receiver.
91. **Idle retry.** A WorkManager job that retries a failed prompt with backoff when charging and on Wi-Fi. `[Medium impact / Medium effort]` · `API: now`
    Why: retries should happen when the phone is idle, not while the user is watching.
92. **Overnight digest.** A morning notification summarizing sessions that ran while the phone was asleep. `[Medium impact / Large effort]` · `API: host` (summary endpoint)
    Why: it converts background agent work into something readable in 30 seconds.
93. **Auto-archive.** Locally hide sessions older than N days into an archive view, becoming host archive when available. `[Low impact / Small effort]` · `API: now`
    Why: the list stays meaningful without deleting anything.

## Notifications · ideas 94–103

94. **Four channels with intent.** Approvals, questions, turn-finished, and failures — each with its own importance and sound. `[High impact / Small effort]` · `API: host` (push) / `API: now` (in-process)
    Why: an approval is urgent, a finished turn is not, and one channel makes both feel like spam.
95. **Deep link with context.** Notifications open the exact session, scrolled to the message or approval that triggered them. `[High impact / Small effort]` · `API: now`
    Why: landing on a session list to hunt for the event wastes the notification.
96. **Inline approve and deny.** Notification actions that call the API without opening the app. `[High impact / Medium effort]` · `API: now`
    Why: the fastest possible approval is the one that never opens an app.
97. **Inline question reply.** `RemoteInput` free text, plus option buttons when two or three fit. `[High impact / Medium effort]` · `API: now`
    Why: unblocking an agent from the shade is a genuinely better experience than opening the app.
98. **Session grouping and summary.** Group by session and post one summary notification when several items are pending. `[Medium impact / Small effort]` · `API: now`
    Why: ten separate pings in a minute train people to swipe them all away.
99. **Quiet hours and per-session mute.** Scheduled quiet windows plus a per-session mute that still counts in the inbox. `[Medium impact / Small effort]` · `API: now`
    Why: notifications you cannot silence get disabled at the OS level, which is worse.
100. **Foreground live-turn notification.** An optional ongoing notification with the current tool and a stop action. `[Medium impact / Medium effort]` · `API: now`
    Why: it gives a legitimate way to keep the stream alive with the screen off.
101. **Badge sync.** App icon and per-session shortcut badges equal unresolved approvals plus needs-input sessions. `[Medium impact / Small effort]` · `API: now`
    Why: the badge is the only ambient signal Android gives you; make it mean one thing.
102. **Foreground dedupe.** Suppress a notification when that session is already visible on screen, and clear it when opened. `[Medium impact / Small effort]` · `API: now`
    Why: notifying someone about the screen they are looking at is the classic notification bug.
103. **Push transport abstraction.** One interface over FCM and an ntfy/WebPush path, plus a "send test push" diagnostic screen. `[High impact / Large effort]` · `API: host`
    Why: it is the difference between closing the app and keeping it open as a pager.

## Models, usage, and billing · ideas 104–112

104. **Per-session model picker.** Choose the model at session creation from a host-advertised list. `[High impact / Medium effort]` · `API: host`
    Why: model choice is the main quality/cost lever, and it is currently desktop-only.
105. **Thinking-level toggle.** Fast/default/deep, passed to the host and remembered per session. `[Medium impact / Medium effort]` · `API: host`
    Why: "just answer" and "think hard" are different products in one app.
106. **Context meter.** Show estimated context use with a warning at 80% and a "summarize and continue" action — **partial: read-only context meter with pressure/breakdown and 80/95% warnings shipped in 0.2.136/host 0.3.13**; compact/summarize action still missing. `[Medium impact / Medium effort]` · `API: host` (token counts)
    Why: silent context exhaustion produces confidently wrong answers.
107. **Turn cost estimate.** Token counts and a configurable price per million tokens, per turn and per session. `[Medium impact / Medium effort]` · `API: host`
    Why: people spend more freely when they can see the meter.
108. **Host usage dashboard.** Daily/weekly spend, top sessions by cost, and a budget alert. `[Medium impact / Large effort]` · `API: host`
    Why: a client that can spend money needs a page that explains where it went.
109. **Latency breakdown.** Time-to-first-token, tool time, and total per turn, measured client-side from the SSE stream. `[Medium impact / Small effort]` · `API: now`
    Why: it is the cheapest way to prove a performance regression to yourself.
110. **A/B send.** Send the same prompt to two sessions with different presets and compare the answers side by side. `[Low impact / Medium effort]` · `API: now`
    Why: model and preset choice is currently guesswork, and this makes it empirical.
111. **Cellular guard.** Warn before starting an expensive run (deep mode, large attachments) on mobile data. `[Medium impact / Small effort]` · `API: now`
    Why: one accidental 40 MB upload on a hotspot is a memorable complaint.
112. **API balance card.** Read the host's provider balance and surface it with a low-balance warning. `[Medium impact / Medium effort]` · `API: host`
    Why: a run that fails at 3 a.m. because of an empty balance is entirely preventable.

## Privacy and security · ideas 113–122

113. **Keystore-backed token storage.** Wrap the device token with an Android Keystore key instead of plain app-private preferences. `[High impact / Small effort]` · `API: now`
    Why: one day of work removes the worst line in any security review of this app.
114. **Biometric lock.** `BiometricPrompt` gate with a configurable re-lock timeout after backgrounding. `[High impact / Small effort]` · `API: now`
    Why: the app can run commands on a desktop; that deserves a lock screen of its own.
115. **Recents redaction.** Optional `FLAG_SECURE` and a blurred thumbnail when the app is backgrounded. `[Medium impact / Small effort]` · `API: now`
    Why: the task switcher leaks more than people expect in screenshots and meetings.
116. **Transport allowlist.** Deny cleartext except the emulator/debug target, and ship a real `network_security_config`. `[High impact / Small effort]` · `API: now`
    Why: a device token over cleartext is a full compromise of the host.
117. **Secret redaction before sharing.** Regex-detect keys, tokens, and connection strings before share/export and offer one-tap redaction. `[Medium impact / Small effort]` · `API: now`
    Why: agent transcripts are full of secrets, and the share sheet is a one-tap leak.
118. **Clipboard hygiene.** Auto-clear copied secrets after 60 s and never mark them as sensitive in the clip preview. `[Medium impact / Small effort]` · `API: now`
    Why: copied tokens live in a system-wide clipboard that other apps can read on older Android.
119. **Local approval ledger.** A timestamped record of every approval decision, the command text, and the note, exportable. `[Medium impact / Small effort]` · `API: now`
    Why: after a bad change, "what did I approve at 14:20?" is the first question.
120. **Retention policy.** Auto-purge cached messages and media after N days, with pinned sessions exempt. `[Medium impact / Small effort]` · `API: now`
    Why: a phone that caches every transcript forever is a liability, not a feature.
121. **Lock-screen privacy.** A per-channel setting that hides message text on the lock screen while keeping the alert. `[Medium impact / Small effort]` · `API: now`
    Why: approvals often contain commands and paths that should not sit on a lock screen.
122. **Device revocation.** List paired devices and revoke one, with the host invalidating its token immediately. `[Medium impact / Medium effort]` · `API: host`
    Why: phones get lost; a pairing system without revocation is a one-way door.

## Settings and accessibility · ideas 123–132

123. **Material 3 expressive with dynamic color.** Follow the system palette, with an AMOLED black option and a contrast-safe fallback. `[Medium impact / Medium effort]` · `API: now`
    Why: it makes the app feel native instead of like a web wrapper, at near-zero cost.
124. **TalkBack-quality streaming.** Label every icon, throttle announcements to sentence boundaries, and expose tool state as text. `[High impact / Medium effort]` · `API: now`
    Why: a streaming chat is an accessibility minefield, and the fix is mostly discipline.
125. **Independent font scaling.** Separate scale for UI text and for code blocks, driven by system font size with an override. `[High impact / Small effort]` · `API: now`
    Why: code at 200% system font destroys layouts unless it is handled separately.
126. **Reduced motion.** Disable streaming cursors, shimmer, and list animations when the system asks. `[Medium impact / Small effort]` · `API: now`
    Why: motion sensitivity is a real setting, and the system already exposes it.
127. **Haptics and sound design.** Distinct patterns for send, approval, failure, and stop, all respecting system settings. `[Medium impact / Small effort]` · `API: now`
    Why: the phone is in a hand; feedback should use that channel.
128. **Hardware keyboard shortcuts.** Ctrl/⌘+Enter to send, Esc to stop, Ctrl+K to switch sessions. `[Medium impact / Small effort]` · `API: now`
    Why: tablets, Chromebooks, and DeX users are a real and growing segment.
129. **One-handed layout.** Composer docked at the bottom, optional left-hand mirroring, and header actions within thumb reach. `[Medium impact / Small effort]` · `API: now`
    Why: most usage is one hand on a 6.5-inch screen.
130. **Locale-ready strings and RTL.** Externalize every string, verify RTL mirroring, and format dates with `java.time` and locale. `[Medium impact / Small effort]` · `API: now`
    Why: retrofitting localization after 200 strings is much more expensive.
131. **Data saver.** Lower image compression, no prefetch, and a capped stream render rate on metered networks. `[Medium impact / Small effort]` · `API: now`
    Why: a chat app that eats 500 MB a month gets uninstalled.
132. **Battery optimization prompt.** Ask for exemption only when the user enables background live mode, and explain the tradeoff. `[Medium impact / Small effort]` · `API: now`
    Why: an unexplained Doze workaround looks like malware; an explained one looks like a feature.

## Sharing and collaboration · ideas 133–140

133. **Share answer as an image card.** Render a message or selection to a clean PNG with the session title and a timestamp — **shipped in 0.2.132** for the whole message (PNG card, branding, caps, redaction, share sheet); selection-only cards still missing. `[Medium impact / Medium effort]` · `API: now`
    Why: image cards are how answers travel into group chats.
134. **Share as attributed text.** Session title, timestamp, and a deep link, copied in one tap. `[Medium impact / Small effort]` · `API: now`
    Why: pasted answers without provenance are indistinguishable from the user's own claims.
135. **Receive Android shares.** `ACTION_SEND` for text, images, and PDFs → session picker → upload → prompt. `[High impact / Medium effort]` · `API: now`
    Why: "share to DSH" from Chrome or Photos is the highest-leverage entry point on a phone.
136. **Read-only session link.** The host serves a signed HTML transcript at a short-lived URL. `[Medium impact / Large effort]` · `API: host`
    Why: sometimes a colleague needs to read, not to run commands.
137. **Export bundle.** A zip of transcript plus attachments for handoff or archival. `[Low impact / Medium effort]` · `API: host`
    Why: it is the honest way to get data out of a self-hosted tool.
138. **Second-device pairing.** Pair a tablet to the same host and switch between them without re-pairing. `[Medium impact / Small effort]` · `API: now`
    Why: the pairing flow already supports multiple devices; only the UI assumes one.
139. **Quote with context.** Include the preceding user prompt when quoting an assistant answer. `[Medium impact / Small effort]` · `API: now`
    Why: an answer without its question is often misleading.
140. **Continue-on-desktop card.** A copyable deep link and session id for moving a thread to the desktop. `[Low impact / Small effort]` · `API: now`
    Why: the phone starts work that the desktop finishes, and the handoff should be one tap.

## Offline and sync · ideas 141–149

141. **Cache-first session list.** Paint cached sessions immediately, then reconcile from `session.updated` events and the list call. `[High impact / Small effort]` · `API: now`
    Why: the first screen must never show a spinner; the roadmap's 1.5 s cold-start budget depends on this.
142. **Stale-while-revalidate detail.** Render cached messages instantly and refetch page one in the background. `[High impact / Small effort]` · `API: now`
    Why: re-entering a session should be instant even on a bad connection.
143. **Outbox with real states.** queued → sending → accepted → failed, with retry and cancel per item. `[High impact / Medium effort]` · `API: now`
    Why: "did my prompt send?" is the central anxiety of a remote-control app.
144. **Offline reading and search.** Browse and full-text search the last N cached sessions with no network at all. `[Medium impact / Medium effort]` · `API: now`
    Why: planes and basements are where people catch up.
145. **Cursor safety.** Persist `lastEventId` per host and reset the stream cleanly when the host can no longer replay from it. `[High impact / Small effort]` · `API: now`
    Why: a stale cursor after a host restart causes a silent half-synced transcript.
146. **Idempotent message upsert.** Merge `message.added`, deltas, and tool rows by id, tolerating a streaming id that later becomes durable. `[High impact / Small effort]` · `API: now`
    Why: the contract says streaming ids may not appear as durable messages until refetch; the client must survive that.
147. **Media cache eviction.** LRU image cache with a visible size cap in settings. `[Medium impact / Medium effort]` · `API: now`
    Why: chat media is the fastest way to a 2 GB app.
148. **Sync status strip.** "Live · reconnecting · offline · last synced 2m ago" with a manual refresh. `[High impact / Small effort]` · `API: now`
    Why: invisible connection state makes users blame the agent for network problems.
149. **Background poll fallback.** A WorkManager periodic health + approvals poll when push is unavailable. `[Medium impact / Medium effort]` · `API: now`
    Why: it turns "notifications never arrive" into "notifications arrive within 15 minutes".

## Developer and power-user · ideas 150–160

150. **SSE inspector.** A raw event stream view with type filter, pause, copy, and export. `[High impact / Medium effort]` · `API: now`
    Why: every hard bug in this app will be an event-ordering bug, and this is the microscope.
151. **Request console.** Every call with status, duration, and a cURL/PowerShell export. `[Medium impact / Medium effort]` · `API: now`
    Why: reproducing a mobile-only failure on the desktop starts with a copyable request.
152. **API compatibility banner.** Compare `apiVersion` from `/health` with the client's supported range and explain the mismatch. `[High impact / Small effort]` · `API: now`
    Why: version skew between APK and host plugin is the most predictable post-release failure.
153. **Hidden-session editor.** A debug surface for `GET`/`PUT /sessions/hidden` with visible counts. `[Medium impact / Small effort]` · `API: now`
    Why: the hidden list is powerful and currently has no UI at all.
154. **Local feature flags.** Toggles for in-flight features, with a reset, so risky work can ship dark. `[Medium impact / Small effort]` · `API: now`
    Why: solo dev + auto-updater means every release goes to a real user; flags are the safety net.
155. **Fake-host target.** One-tap switch to `10.0.2.2:3543` demo data for screenshots and QA. `[Medium impact / Small effort]` · `API: now`
    Why: the repo already ships a fake host; making it a first-class target speeds every UI task.
156. **Documented intent surface.** `dsh-mobile://` deep links plus `adb`-friendly extras for automation and testing. `[Medium impact / Small effort]` · `API: now`
    Why: it becomes the integration point for Tasker, widgets, and CI screenshots alike.
157. **Raw message viewer.** JSON for any message or envelope, copyable, behind a long-press. `[Low impact / Small effort]` · `API: now`
    Why: it ends the "is this a rendering bug or a data bug?" debate in seconds.
158. **Redacted log export.** A diagnostics bundle shared via SAF with tokens and paths stripped. `[Medium impact / Small effort]` · `API: now`
    Why: support without logs is guesswork, and logs without redaction are a breach.
159. **Approval explainer, never executor.** An "explain this command" prompt that asks the agent to describe what a pending command would do — without running it. `[Medium impact / Small effort]` · `API: now`
    Why: it is the one safe use of an LLM in an approval flow.
160. **Preset matrix.** Show the host's presets and test each with a trivial prompt to confirm it is wired. `[Medium impact / Medium effort]` · `API: host`
    Why: preset misconfiguration otherwise surfaces in the middle of real work.

## Widgets and shortcuts · ideas 161–170

161. **Quick-actions widget.** A 2×1 Glance widget with new chat, camera, and mic — the pattern Claude ships on Android. `[High impact / Medium effort]` · `API: now`
    Why: it converts an app icon into three one-tap verbs on the home screen.
162. **Status widget.** Last session status plus unresolved approvals count, updating on SSE while the app process lives. `[Medium impact / Medium effort]` · `API: now`
    Why: ambient awareness is the phone's advantage over a desktop tab.
163. **Prompt widget.** Three to five saved snippets that create a session and send immediately. `[Medium impact / Medium effort]` · `API: now`
    Why: it makes recurring asks a single tap, no typing at all.
164. **Quick Settings tile.** "Ask DSH" opens a floating capture sheet from the shade. `[Medium impact / Small effort]` · `API: now`
    Why: reachable from any app, and cheap to build with `TileService`.
165. **Static shortcuts.** New session, Approvals, and Last session on the launcher icon long-press. `[Medium impact / Small effort]` · `API: now`
    Why: two taps saved on the most common entry points.
166. **Dynamic shortcuts for pinned sessions.** Pinned sessions as long-press shortcuts with stable ids. `[Medium impact / Small effort]` · `API: now`
    Why: it pairs naturally with #48 and makes pinning feel rewarded.
167. **App Actions / Assistant integration.** "Hey Google, ask DSH to…" via an App Actions capability with a deep link. `[Medium impact / Large effort]` · `API: now` (requires Play deployment)
    Why: voice entry without opening the app is the highest-ceiling shortcut, at the highest cost.
168. **Notification quick reply.** Answer a session from the shade via `RemoteInput`, with a "sends immediately" hint. `[High impact / Medium effort]` · `API: now`
    Why: it removes the app from the loop for the most common interruption.
169. **Wear OS tile and reply.** Status tile, approval actions, and canned replies from a watch. `[Low impact / Large effort]` · `API: now`
    Why: approving a command from a wrist is the clearest demonstration of the control-plane idea.
170. **Ongoing "current session" surface.** Show the working session with its tool name, using only standard ongoing-notification APIs. `[Medium impact / Small effort]` · `API: now`
    Why: it stays within public APIs while still being visible everywhere Android shows media and navigation.

## Performance and reliability · ideas 171–180

171. **Baseline Profile.** Ship a Baseline Profile plus a startup profile covering the session list and chat. `[High impact / Medium effort]` · `API: now`
    Why: it is the single biggest cold-start win available on Android, measured in hundreds of ms.
172. **Streaming render budget.** Coalesce deltas into ~30–60 ms frames and re-parse only the changed tail of the message. `[High impact / Medium effort]` · `API: now`
    Why: naive per-token recomposition turns a 60-token/s stream into dropped frames.
173. **Compose stability pass.** Immutable message models, stable keys, and no lambdas capturing whole sessions in item scope. `[High impact / Medium effort]` · `API: now`
    Why: the chat list is the app; unstable params make it janky for free.
174. **Paged session detail.** Page history upward with the `before`/`limit` cursor and anchor scroll position on prepend. `[High impact / Medium effort]` · `API: now`
    Why: a 500-message session should open in the same time as a 5-message one.
175. **Single SSE owner.** One app-scoped connection with exponential backoff, jitter, and a heartbeat watchdog on the 20 s `ping`. `[High impact / Medium effort]` · `API: now`
    Why: duplicate connections and silent dead sockets are the top two live-stream bugs.
176. **Connectivity awareness.** Pause reconnect attempts in airplane mode and resume on a `NetworkCallback`. `[Medium impact / Small effort]` · `API: now`
    Why: retry storms drain battery and produce nothing.
177. **Request budgets.** 10 s for reads, 30 s for prompt acceptance, with a visible cancel on every long call. `[Medium impact / Small effort]` · `API: now`
    Why: an indefinite spinner is indistinguishable from a crash to a user.
178. **Crash and ANR breadcrumbs.** A 200-event ring buffer in memory plus opt-in upload. `[High impact / Medium effort]` · `API: now`
    Why: with an auto-updater and no Play Console, this is the only signal you will get.
179. **Memory guard.** Cap decoded bitmaps and drop off-screen message bodies for very long sessions. `[Medium impact / Medium effort]` · `API: now`
    Why: image-heavy sessions are the realistic OOM path on low-end devices.
180. **Startup budget in CI.** Macrobenchmark start-to-list with a gate at 1.5 s cold and 400 ms warm. `[High impact / Medium effort]` · `API: now`
    Why: performance regressions are invisible without a gate, and this app's promise is speed.

## Onboarding and education · ideas 181–187

181. **Three-step onboarding.** Pair (QR or code) → choose workspace → send a first prompt that is already useful. `[High impact / Medium effort]` · `API: now`
    Why: the current first run asks for a code before it has shown any value.
182. **Pairing doctor.** Probe `/health`, Tailscale reachability, clock skew, and token validity, with a specific fix per failure. `[High impact / Medium effort]` · `API: now`
    Why: pairing is the only hard gate in the product, and every failure there is a support ticket.
183. **Empty states with suggested prompts.** Per-workspace starter prompts that actually send when tapped. `[Medium impact / Small effort]` · `API: now`
    Why: a blank composer is a worse teacher than three good examples.
184. **Coach marks for approvals.** A one-time explainer on the approval card, shown once per install and dismissible. `[Medium impact / Small effort]` · `API: now`
    Why: "what happens if I tap Allow?" is the question that stops first-time use.
185. **Glossary sheet.** Short definitions of host, workspace, session, preset, approval, and question. `[Low impact / Small effort]` · `API: now`
    Why: DSH's model is richer than a chat app's, and the vocabulary carries the value.
186. **Demo mode with the fake host.** Exploratory data before pairing, clearly labelled as demo. `[Medium impact / Medium effort]` · `API: now`
    Why: it lets a curious user see the product before trusting it with a token.
187. **What's-new sheet plus re-pair guidance.** Changelog from the updater feed, and a clear path when a 401 means the host revoked the device. `[Medium impact / Small effort]` · `API: now`
    Why: auto-updates plus token rotation will produce 401s that look like crashes.

## DSH-specific differentiators · ideas 188–200

188. **Approvals as a first-class home tab.** The control plane, not a settings page: approvals and questions are the default surface when anything is pending. `[High impact / Medium effort]` · `API: now`
    Why: this is the one thing Claude, ChatGPT, and Gemini cannot ship — a real permission boundary.
189. **Live agent presence everywhere.** Status chips (working / needs input / idle) with `live` state, in the list, the header, and the widget. `[High impact / Small effort]` · `API: now`
    Why: presence is what makes an agent feel like a colleague rather than a form.
190. **Preset picker from the host.** List the host's presets and pick one at session creation. `[High impact / Medium effort]` · `API: host` (list; `preset` is already accepted on create)
    Why: presets are DSH's real capability model, and they are invisible on mobile today.
191. **Tool inventory screen.** "What this host can do", generated from the host's tool list, with a search field. `[Medium impact / Large effort]` · `API: host`
    Why: users cannot ask for what they do not know the agent can do.
192. **Delegation view.** Show subagent/teammate activity counts inside the parent session while internal sessions stay hidden — **shipped in 0.2.125/host 0.3.4** as aggregate `delegation:{total,working}` badges (no child ids/titles). `[Medium impact / Medium effort]` · `API: host`
    Why: the phone should show that four agents are working without listing four sessions.
193. **Skills browser.** List installed skills with descriptions and "insert into prompt". `[Medium impact / Medium effort]` · `API: host`
    Why: skills are reusable expertise, and the phone is where you remember you have them.
194. **Goal progress.** Active goal, round count, and a "mark complete" action for long-running objectives. `[Medium impact / Medium effort]` · `API: host`
    Why: a multi-round objective is invisible in a chat transcript; it needs its own state.
195. **Team/workflow run monitor.** Phase progress and per-subagent rows for multi-agent runs — **partial: Agent Teams board with members/phases and active tasks (0.2.129/host 0.3.8)**; per-subagent transcripts still missing. `[Medium impact / Large effort]` · `API: host`
    Why: DSH runs fan-out work that no consumer AI app can even represent.
196. **Steering a running turn.** Send a follow-up that reaches the agent mid-run instead of waiting for completion. `[High impact / Large effort]` · `API: host`
    Why: correcting course while it works is the main reason to watch a run live.
197. **Workspace safety badge.** Resolved path plus permission preset (read-only / workspace-write / danger-full-access) in the header. `[High impact / Small effort]` · `API: host` (preset in summary)
    Why: on a phone it is dangerously easy to approve a write in the wrong repository.
198. **One policy, one setting.** "Ask me on the phone before anything that writes files or runs commands." `[High impact / Medium effort]` · `API: host`
    Why: it expresses the entire value of approvals in one sentence a user can act on.
199. **Host parity card.** The same UI against desktop, cloud, and future managed hosts, with a capability matrix per host. `[High impact / Large effort]` · `API: now` (client) / `API: host` (capabilities)
    Why: the roadmap's host-agnostic promise needs a visible surface or it quietly dies.
200. **Self-hosted trust story.** Show the actual base URL, the certificate issuer, and "no relay" — in-app, not in a blog post. `[Medium impact / Small effort]` · `API: now`
    Why: the privacy claim is a feature, and unverifiable claims are marketing.

---

## Android reality check

What is genuinely available, and what people will ask for that we should not attempt.

**Feasible and worth the code:**

- **Intents and shares.** `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, `ACTION_VIEW`, Custom Tabs, calendar insert intents, `AlarmClock` intents, `ACTION_DIAL`/geo URIs. Claude's "use your Android apps" integration is exactly this pattern; it does not need privileged access.
- **Camera, Photo Picker, SAF, clipboard image.** All permission-light on modern Android. HEIC normalization via `ImageDecoder`/`BitmapFactory` and re-encode.
- **On-device speech and TTS.** `SpeechRecognizer` (many devices support offline), `TextToSpeech`, `MediaSession`. Real-time voice is achievable purely over the existing text SSE contract.
- **Notifications.** Channels, `RemoteInput`, notification actions, grouping, `MessagingStyle`. `POST_NOTIFICATIONS` on Android 13+.
- **Background work.** WorkManager for periodic poll/retry; a foreground service for a live stream while the user is watching. Doze means an endless background SSE is not a plan.
- **Widgets, tiles, shortcuts.** Glance/AppWidgetProvider, `TileService`, static + dynamic shortcuts. App Actions needs a Play deployment.
- **Keystore, BiometricPrompt, FLAG_SECURE.** Standard, cheap, high value.
- **MediaProjection.** One-frame screen capture is allowed with explicit consent per capture; no persistent capture.

**Not feasible, or not worth it:**

- **Running the DSH agent on the phone.** Node + toolchains + model calls: battery, RAM, and process death make it impractical (the roadmap already calls this an anti-goal).
- **Reading other apps' data.** No reading SMS/email/other apps' storage without permissions we should not request. Claude's integrations *write* intents; they do not scrape.
- **AccessibilityService-based cross-app automation.** Technically possible, fragile, hostile to Play policy, and a surveillance smell. Skip it.
- **A virtual browser on the phone** (the ChatGPT agent model). That is a server-side capability; the honest phone equivalent is "open this URL / share this page".
- **Arbitrary background execution.** No always-on socket, no reliable timers below 15 minutes without FCM, no waking the host.
- **Cloud-side model features** (web search, deep research, live camera understanding) unless the host exposes them. The client can only present the host's capabilities.
- **Company-wide collaboration.** Multi-user hosts, RBAC, and shared links are a server product, not an app feature. Ideas #136–#137 mark the boundary.

---

## Host API gaps to close, in priority order

Each unlock is a cluster of client features; this is the highest-leverage host work for the mobile app.
Since this list was written, `0.2.80`–`0.2.103` added the goals bridge (including complete), phone answering for
approvals/questions with read-only toggles, attachment read-back, host schedules, live-session rename +
archive/unarchive + archived list, real agent presets, and a bounded host-wide message search. Push, an indexed
search endpoint, model/thinking controls, per-turn usage and diff/artifact payloads remain open.

1. `PATCH /sessions/{id}` (rename) and archive/delete — 0.2.100 ships live-session rename plus
   archive/unarchive and an archived list; a proper delete, cold-session rename and age-based auto-archive
   (#93) still open (local alias and pin #47/#48 already ship client-side).
2. `GET /sessions/{id}/attachments/{attachmentId}` — shipped in 0.2.96 and consumed on the phone (#32/#65);
   in-app artifact rendering (#67) and save-to-device (#69) still partial.
3. Message search endpoint — 0.2.103 ships a bounded `GET /search?q=&limit=` scan; an indexed/paged
   full-history endpoint and role/date filters (#76 full, #82) remain open.
4. Push registration + device push config (`FCM` token or ntfy topic, per device) — the host can publish
   ntfy pushes from 0.2.105 and the app configures the topic; FCM/device registration and app-local
   quiet-hours/mute honoring remain open.
5. Host capability document: presets, models, thinking levels, tools, permission presets — presets ship via
   `GET /presets` (#190, 0.2.101) and model/thinking controls via `GET /models` + `GET/PUT /sessions/{id}/model`
   (0.2.108, #104/#105 partial: changing a model also becomes the DSH default; no tool inventory, permission
   presets or preset matrix (#160, #191, #193, #197) yet.
6. Usage/token accounting per turn and per session — aggregate usage and the #112 balance card already ship;
   per-turn accounting still unlocks #106–#108.
7. Schedules CRUD with next-run and last-result — 0.2.98–0.2.99 ship list/create (after-minutes, daily)/
   edit/delete, next/last run and "schedule this prompt"; pause/run-now, other timing kinds (every/weekly/cron/
   at editing), a per-schedule timezone picker and scheduled-result notifications (#85/#86/#89) remain open.
8. Diff/artifact payload on tool rows — unlocks #66, #67, #69.
9. Device revocation endpoint — shipped 0.2.107 (`GET /devices`, `DELETE /devices/{id}`, app UI); an admin
   role/re-auth and revocation audit remain open (#122 partial).
10. Optional push transport for `question.requested`/`approval.requested` — 0.2.105 ships optional ntfy
    publishing from the host (configurable from the app); FCM/device registration and a coalescing window
    remain open.

---

## Suggested screen map

- **Sessions** (home): workspace chips, date buckets, pinned section, status chips, sync strip, FAB new session.
- **Chat**: stream, markdown, tool timeline, approval/question cards, composer with attachment tray, stop/queue.
- **Inbox**: approvals + questions across sessions, grouped by session, with counts and quick actions.
- **Activity**: working sessions with current tool and elapsed time.
- **Session detail extras**: "what this session knows", attachments, export.
- **Saved**: bookmarked messages and snippets.
- **Settings**: host + devices, notifications, privacy/lock, appearance/accessibility, data & storage, developer.
- **Onboarding**: pair → workspace → first prompt, with the pairing doctor behind a help link.

---

## Sources

Official pages fetched while writing this catalog (2026-10-30). Google support pages render partially to a
plain fetcher; their feature lists were cross-checked against `android.com` and current product docs.

- Claude — Use Claude with Android apps (intents, calendar, alarms, Health Connect): https://support.claude.com/en/articles/11869629-use-claude-with-android-apps
- Claude — Use the Claude widget on Android: https://support.claude.com/en/articles/10534883-use-the-claude-widget-on-android
- Claude — Claude Mobile apps collection: https://support.claude.com/en/collections/9387080-claude-mobile-apps
- Claude Code — Platforms and integrations: https://code.claude.com/docs/en/platforms
- Claude Code — Extend Claude Code (skills, hooks, plugins, MCP): https://code.claude.com/docs/en/features-overview
- ChatGPT — iOS and Android app FAQ (history, search, projects, read-aloud, haptics, data controls): https://help.openai.com/en/articles/8142208-chatgpt-ios-and-android-app-faq
- ChatGPT — agent (tools, scheduling, takeover, confirmations): https://help.openai.com/en/articles/11752874-chatgpt-agent
- Gemini — Use Gemini Apps on Android phone and tablet: https://support.google.com/gemini/answer/13275745
- Gemini — Talk naturally with Gemini Live (camera and screen sharing): https://support.google.com/gemini/answer/15274899
- Android — Gemini on Android, camera and screen sharing: https://www.android.com/articles/gemini-on-android/
- Local contract: [`mobile-api-v1.md`](mobile-api-v1.md) · current shipped/planned status: [`mobile-feature-status.md`](mobile-feature-status.md)
