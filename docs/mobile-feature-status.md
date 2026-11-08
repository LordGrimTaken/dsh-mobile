# DSH Android — feature status

**Last updated:** 2026-11-01 · **Current release:** `0.3.3` (versionCode 149)

## Shipped in 0.2.117 (this round)

- **Host search pagination** — `GET /search` accepts `offset` 0..500 and returns additive
  `offset`/`nextOffset`/`hasMore` with a frozen contract: nextOffset advances only when hasMore, an empty
  beyond-collected page ends pagination (no load-more loop), `truncated` keeps its scan-cap meaning, and
  filters/ordering still apply before slicing.
- **App Load more** — the host search screen appends/dedupes pages by (sessionId,messageId), guards stale
  pages/cursor regressions, keeps existing hits + inline Retry on load-more errors, shows “Showing N matches”,
  resets on query/filter changes, omits offset for the first request, and behaves exactly as before on hosts
  without pagination.

## Shipped in 0.2.116 (this round)

- **Inline question reply** — question notifications now show the prompt and a RemoteInput “Reply” action.
  A non-exported `QuestionReplyReceiver` validates the action/extras, re-checks app lock, loads the
  encrypted active host profile, answers through the existing API under an 8.5 s timeout, and updates the
  same per-question notification (“Answer sent”, “Already answered”, or “Couldn't send answer - open the app”).
  Reply actions are withheld while app lock is on; lock-screen public versions never expose the prompt;
  failed sends keep Open inbox and re-add Reply while lock remains off.
- **Regression guard** — `AppStateInitializationOrderTest` asserts the delegated state written by
  `startRemote()` during construction stays declared before the `init` block, covering the 0.2.115 hotfix.

## Shipped in 0.2.139 (this round)

- **Mention a workspace file in the prompt** — the Files browser preview now has a **Mention in prompt** action.
  It appends a safe `@relative/path` mention (quoted when the path contains spaces/quotes, traversal stripped) to
  the existing composer draft, closes the sheet, focuses the composer and never auto-sends. Drafts still survive
  rotation/process death and are picked up through the existing draft revision. This closes the file-browser gap
  left by the sorsama-inspired read-only browser.

- 0.2.139: app `testDebugUnitTest` **483 tests, 0 failures, 1 env-gated skip**; host-plugin **345/345**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Files mention/composer focus and IME behavior are JVM/static tested only (no device).

## Shipped in 0.2.138 (this round)

- **Startup recovery instead of a crash loop** — if `AppState` construction throws (corrupt snapshot, preference or
  keystore bootstrap), the app now catches it and shows a dependency-free **Startup failure** screen with “Retry”,
  “Open Safe mode” and “Share error report” actions instead of dying at the first frame. The caught failure is written
  to the crash report and recorded by StartupGuard so safe mode still activates after repeated failures; pairing,
  drafts and settings are not cleared automatically. The summary shown is one sanitized line (exception type + capped
  message), while the full stack trace stays in the shared report.
- **Regression guard** — the source-level AppStateInitializationOrderTest now includes `messageJumpTarget`, the delegate
  whose late declaration caused the 0.2.137 paired-launch NPE; it must stay before the init block that calls
  `startRemote()`.

- 0.2.138: app `testDebugUnitTest` **480 tests, 0 failures, 1 env-gated skip**; host-plugin **345/345**; fake-host **175/175**; `assembleDebug` + `lintDebug` PASS. The startup screen and Safe mode hand-off are JVM/static tested only (startup-failure helper + source-order guard); no forced-construction device test.

## Hotfix in 0.2.137

- **Startup NPE on paired launch** — `messageJumpTarget` (introduced with search-hit jumps) was declared after the
  constructor init block, but `startRemote()` resets it during construction, so every paired launch could crash with
  `NullPointerException ... MutableState.setValue ... on a null object reference` before the UI appeared. The
  delegate now sits in the pre-init group (alongside lastGoalChange/presetRoster/presetsLoading) and the source-level
  AppStateInitializationOrderTest covers it. No VPN involvement: the VPN only coincided with the crash.

## Shipped in 0.2.136 (this round)

- **Context-window meter** — inspired by the sorsama context meter (MIT; idea only). Host 0.3.13 adds
  `GET /sessions/{id}/context` reading the DSH projection cache read-only (contextPressure/contextBreakdown), with
  finite-number sanitization, percent = clamp(pressureTokens/contextWindow × 100) rounded to 0.1, breakdown buckets,
  optional sampled time, visible-session 404/no activation/auth, regular-file/lstat hardening and bounded warning
  bookkeeping; missing/malformed data returns `supported:false`. App 0.2.136 adds a **Context** section in Session
  Info: percent + progress bar with accessibility semantics, compact tokens (“583k / 1.0M”), pressure and
  “Breakdown (approx.)” rows, silent unsupported/error states, Refresh, and text warnings at ≥80% (amber) and ≥95%
  (red). Fallback percent prefers pressureTokens then surfaceTokens; partial data shows what is known without
  fabricating 0%.

## Shipped in 0.2.135 (this round)

- **Adaptive two-pane layout** — inspired by the dsh-Go adaptive tablet/landscape layout (idea only; AGPL code not used).
  On windows **≥ 960 dp** with a session open and no full-screen overlay, the app shows a persistent 308 dp
  **SessionRail** (brand/connection header, New session, scrollable current/status/unread session list with a
  non-colour current accent bar and TalkBack descriptions, Inbox/Usage/Settings footer) next to the existing chat
  pane. Sheets/dialogs still cover the whole window; Back closes the session and returns to the full single-pane
  list. Below 960 dp (all phones and many landscape layouts) the existing single-pane UI is unchanged; threshold/
  overlay decisions are pure helpers with tests.

## Shipped in 0.2.134 (this round)

- **Accent theme packs** — inspired by the dsh-Go accent-colour feature (idea only; AGPL code not used). App
  0.2.134 adds six persisted accents (Hadal default, Ocean, Amethyst, Ember, Sakura, Slate) that override only the
  Material primary/secondary/container roles in dark, light and AMOLED schemes while surfaces, typography, shapes and
  error stay unchanged; Hadal is byte-for-byte the current look and unknown/blank prefs fall back to it. Settings →
  Appearance gets a responsive 2/3-column swatch grid with 48 dp chips, non-colour-only selection tick and TalkBack
  labels; light/dark swatches match the applied palette.
- **Credential-redaction hardening (security)** — QA found a real provider token in a live job label. Host 0.3.12
  generalises the shared redactor used by jobs and trajectory: assignment keys containing TOKEN/SECRET/PASSWORD/
  API_KEY/ACCESS_KEY/PRIVATE_KEY anywhere, provider prefixes (`cfat_`, `cf_`, GitHub, GitLab, Slack, `sk-`, `npm_`,
  `ya29.`, `AIza`, AWS `AKIA`/`ASIA`), JWTs and long base64 are replaced with `<redacted>` before length caps;
  commands and paths survive the credential pass. Live scans across 16 sessions of jobs and a 200-event trajectory now
  show **zero provider-token leaks**. Separate absolute-path redaction remains active.

## Shipped in 0.2.133 (this round)

- **Background jobs** — inspired by the sorsama project jobs view (MIT; idea only). Host 0.3.11 adds
  `GET /sessions/{id}/jobs` folding the latest `job/registered`/`job/settled` per job: running-first ordering,
  counts over all jobs, caps (50 listed/240 kind+label/120 detail), `truncated`/`totalJobs`, credential + absolute
  path redaction, no owner/tool-call ids, visible sessions only, no agent activation. App 0.2.133 adds a **Jobs**
  action in Session Info with a running hint and a read-only sheet: counts, running-first rows with kind/status/label,
  status is non-color-only (Running/Pending/Completed/Failed/Stopped; terminal statuses never count as active),
  duration/start meta, redacted failure detail, 30 s cache + Refresh, loading/error/unsupported/empty, no kill
  control.

## Shipped in 0.2.132 (this round)

- **Share message as image** — inspired by the dsh-Go share cards (idea only; AGPL code not used). The message action
  sheet gains **“Share as image”** for user/assistant/tool messages. It renders a 1080 px PNG card off the main
  thread: DSH Android branding, session title, role/time, markdown-stripped body, fenced code blocks in monospace,
  footer, and a truncation note at the 6,000-char / 80-line caps. Credential-like strings are redacted before
  rendering; emoji/CJK are code-point-safe; paragraph direction is pinned; height is bounded. Unique cards are
  written atomically to a new `share-cards/` FileProvider cache root and shared as `image/png`; failures toast and
  cancellation is respected. No storage permissions, no new route.

## Shipped in 0.2.131 (this round)

- **Workspace file browser** — inspired by the sorsama/deepseek-harness-mobile file browser/preview (MIT; idea only).
  Host 0.3.10 adds read-only `GET /sessions/{id}/files` (relative-path listing, dirs first, 500 cap with
  `truncated`/`entryCount`), `/files/content` (text preview ≤1 MB/200k chars, binary/oversize 415) and `/files/raw`
  (≤25 MB, exact headers, active types forced to attachment). All three reject absolute/drive/UNC/ADS/traversal,
  re-realpath + containment + no-follow/fd reads, skip symlinks, hide a credential denylist (`.env*`, `.credentials*`,
  `.git`, keys/keystores, `*secret*`, …) with generic 404s, and never activate an agent. App 0.2.131 adds a **Files**
  action in Session Info opening a read-only browser (dirs first, size labels, breadcrumb/Up/Refresh) with a
  selectable text preview and raw-download/external-open for other files; per-session/path 30 s cache, stale-navigation
  guards and no writes.
- **Trajectory privacy hardening** — trajectory summaries now redact absolute host paths (`<path>`) in addition to
  credentials; the live scan over 100 events found **0 absolute-path leaks** (previous round: 46).

## Shipped in 0.2.130 (this round)

- **Trajectory journal** — inspired by the sorsama/deepseek-harness-mobile Trajectory tab (MIT; idea only). Host 0.3.9
  adds `GET /sessions/{id}/trajectory?limit=100&beforeSeq=` returning newest-first, compact, allowlisted raw-event
  summaries (turns/steps, user/assistant text, tool calls/results, goal/todo/team/schedule; internal user context
  is `[context] <kind>` with no body; unknown types are type-only). Credentials (`sk-…`, Bearer/Authorization,
  token/api_key/password/secret values, long base64) are redacted before the 300-char cap; no raw payloads or
  attachment bytes. Seq-based pagination with `nextBeforeSeq`/`hasMore`; visible sessions only, no agent activation.
  The app adds a **Trajectory** action in Session Info opening a read-only newest-first journal sheet with friendly
  type labels, monospace seq/time meta, secondary error tint, “Load older” cursor pagination (dedupe/stale guards),
  30 s cache, Refresh and loading/error/unsupported/empty states.

## Shipped in 0.2.129 (this round)

- **Agent Teams board** — host 0.3.8 adds `GET /sessions/{id}/team` folding the latest `team/task` per board id
  and `team/member` per member id: active tasks (pending/in progress/blocked, max 50), members name + phase, and
  counts. Completed tasks are counts-only; descriptions/owner ids/write scopes/member ids never leave the host;
  visible sessions only, snapshot read without activating an agent. App 0.2.129 adds a **Team** action in Session
  Info (active-task hint) opening a read-only sheet with counts, Agents and Active tasks (blocked-by ids),
  loading/retry/unsupported/empty states and a 30 s cache + Refresh.

## Shipped in 0.2.128 (this round)

- **Scheduled prompts in chat** — host 0.3.7 includes `source.kind === 'schedule'` user deliveries in the
  transcript and live SSE as normal user messages with an additive `source:"schedule"` marker (scheduled framing
  text remains self-identifying). Previews, title fallback and host-wide search stay strict-human via a separate
  predicate, so scheduler instructions do not leak into list previews or search; semantic search filters scheduled
  framing without snapshot reads (fast path preserved). The app shows a compact accessible “Scheduled” label in
  the bubble, prefixes copy/share with “Scheduled: ”, and exports “User (Scheduled)” in Markdown transcripts;
  quote keeps the raw prompt.
- **Search regression caught and fixed during verification** — the first predicate split forced snapshots for every
  user-text semantic match and broke search (q=bot: 0 hits/12.8 s); the framing heuristic restored q=bot to
  5 hits across 14/14 sessions in ~5.8 s.

## Shipped in 0.2.127 (this round)

- **Plan / todo sheet** — host 0.3.6 adds `GET /sessions/{id}/todos` built from the latest `todo/write` event
  (sanitized content, status mapping, counts, 50-item/500-char caps, `supported:false` when none; visible sessions
  only, no agent activation). The chat top bar gains an accessible **Plan** action with an `x/y` progress hint,
  opening a read-only modal sheet with a progress line, ordered checklist (in progress → pending → done), status
  markers/labels and TalkBack descriptions, plus loading / retry / unsupported / empty states. Per-session 30 s
  cache with stale-while-refresh and manual Refresh; no mutations or auto-send.

## Shipped in 0.2.126 (this round)

- **Jump to search hit** — host 0.3.5 adds optional `seq` to `/search` hits (semantic documents and snapshot
  fallback, never guessed); session-detail messages already carry `seq`. Tapping a host-wide search hit now opens
  the session and jumps to the matching message: seq-first resolution with exact/nearest fallbacks, bounded
  automatic older-page loading (max 20 pages), older-header-aware scroll, a brief row highlight, then target
  consumption so reloads/rotations do not loop. Message-id-only hits also jump (old-host/mixed fallback); if the
  message is not loaded after the bound, chat opens normally and shows “Message not found (it may be older than
  the loaded history).”

## Shipped in 0.2.125 (this round)

- **Delegation activity** — host 0.3.4 adds optional `delegation:{total,working}` to visible session summaries:
  aggregate counts of **internal direct child sessions** (no ids/titles/prompts), excluding archived children,
  best-effort `working` from the raw list row, omitted when none/unknown. The session-list row shows a compact
  “2 agents · 1 working” badge (99+ cap, TalkBack content description), and Session Info shows “2 subagents ·
  1 working”; old hosts/normal sessions render exactly as before.

## Shipped in 0.2.124 (this round)

- **Host push quiet hours + mutes** — host 0.3.3 adds persisted `PUT /push/suppress` state and an additive
  `suppression` summary on `GET /push`. The phone syncs its quiet-hours enabled/start/end, device IANA timezone and
  per-session notification mutes (debounced, best-effort, silent on old/unsupported hosts). The host suppresses all
  event pushes during quiet hours (DST-safe local hour; midnight-crossing windows; start==end means none) and
  session-scoped approval/question/turn pushes for muted sessions; goal/usage pushes are quiet-only. Push test
  bypasses suppression. Settings explains that connected hosts honor these settings.

## Shipped in 0.2.123 (this round)

- **Skills browser** — host 0.3.2 adds `GET /sessions/{id}/skills` (visible sessions only, no agent activation;
  name/description only, sanitized/sorted/capped, `supported:false` when no registry). The chat top bar gains an
  accessible **Skills** action opening a modal sheet with loading/retry/unsupported/empty/session-unavailable
  states. Tapping a skill prefills the composer with `Use the <name> skill: ` (blank-line appended to an existing
  draft, cursor at end, **never auto-sends**), with a copy+toast fallback. Skills cache is per-session with a
  5-minute TTL; unsupported/removed sessions are distinct from an empty catalog.

## Shipped in 0.2.122 (this round)

- **Session preset + lineage** — host 0.3.1 adds optional `agentPreset` and `parentSessionId` to session summaries
  (detail/list/archived), omitting unknown/non-visible parents and self-parents. The app shows “Preset: <name>” in
  the chat subtitle and Session Info, plus a “Forked from <parent>” card with an accessible 48 dp **Open parent**
  button when the parent is in the loaded list; unavailable parents show a short id + “Parent session not
  available” instead of a dead control. Full snapshot/detail refreshes clear stale lineage; SSE partial updates
  preserve it. Old hosts render exactly as before.

## Host plugin 0.3.0 — remote Markdown images (no app update needed)

- **Remote image proxy (opt-in)** — public http(s) `![alt](https://…)` links in visible user/assistant text become
  `ext_<hash>` synthetic attachments; bytes are fetched only when the attachment is opened, never during
  listing/detail. Strict SSRF policy (public addresses only, DNS pinning, ≤3 revalidated redirects, 80/443 only,
  no auth/cookies, 10 MB/timeout limits, magic/Content-Type match), bounded TTL cache + 4-way concurrency and
  negative cache. Disabled by default; enabled in this profile for `C:\Users\Austin\tools`-style screenshots.
- **Collection consistency fix** — synthetic refs are now collected for every visible message instead of a
  session-wide 16/32 cap, so attachments shown in detail are always servable (this was caught live: late
  Bao-Chinese `ext_` ids 404ed while earlier ones worked).

## Shipped in 0.2.121 (this round)

- **Workspace/Markdown images** — host 0.2.9 turns `![alt](path)` image links in visible user/assistant/tool text
  into synthetic chat attachments (`ws_<hash>` ids, no host paths in public JSON) when the file is inside the
  session cwd or an operator-configured `artifacts.roots` directory. Files are served read-only through the
  existing attachment route with realpath containment, extension+magic validation, caps (8/message, 32/session,
  25 MB, no zero-byte), current-config root revalidation and TOCTOU-hardened reads. Hidden sessions 404 for
  synthetic reads. App 0.2.121 hides unknown/0-byte sizes in chips and the full-screen viewer.

## Shipped in 0.2.120 (this round)

- **Tool output viewer** — host 0.2.8 adds optional `tool.output` to tool rows (text blocks of the tool
  result, capped at 8,000 chars + `… (output truncated)`; image/file blocks stay attachments) in both session
  detail and live SSE. Chat tool cards gain a “View output” affordance when output exists, opening a
  monospace, selectable, scrollable sheet with Copy/Share/Close and a truncation warning; message copy/share
  is unchanged and never includes the output implicitly.
- **Fake host** — seeded long tool output fixture and present/absent/paged checks (attachments suite 12).

## Shipped in 0.2.119 (this round)

- **Export PDF** — session info gains “Export PDF”: a paginated A4 PDF with session title, exported time,
  role/time labels and “Page N” footer, built off the main thread into the existing exports FileProvider
  cache and shared as `application/pdf`. Caps (200 messages / 120k chars / 80 pages) are reported in a
  Toast; truncation is code-point-safe; cancellation deletes the partial file without a failure toast.
- **Honest search coverage** — the app now reads host `scanIncomplete`/`scannedSessions`/`totalSessions`:
  when the bounded scan did not cover every session it shows “Searched X of Y sessions — results may be
  incomplete” (or a generic note when counts are absent), and Load more preserves the warning; complete/old
  hosts show nothing.

## Shipped in 0.2.118 (this round)

- **Save/Copy pictures** — the full-screen image viewer adds **Save** (MediaStore `Pictures/DSH Android` on
  Android 10+, SAF document picker on 8–9, IS_PENDING/cleanup, unique names; MIME/extension resolved from the
  file bytes or declared MIME, never the filename) and **Copy** (read-scoped FileProvider URI on the clipboard),
  with missing-cache re-download, busy states and clear success/failure toasts. No new permissions.
- **Search usability fix** — tool-result images made snapshots slow enough that the old 3 s whole-request
  budget returned empty `truncated` results. Search now reads DSH text-only semantic documents (snapshot scan
  fallback), uses an 8 s budget with 4-way bounded concurrency, keeps up to 50 matches per session so one busy
  session cannot crowd out others, and reports additive `scanIncomplete`/`scannedSessions`/`totalSessions`.
- **Host plugin 0.2.7** — includes the tool-result image mapping and search fix.

## Host plugin 0.2.6 (this round, no app update needed)

- **Tool-result pictures** — tool results can contain real image/file content blocks (Android screenshots, generated
  artifacts). The host now maps those as message attachments, includes them in the session attachment index, and
  the existing app renders them inline with thumbnails/full-screen viewer; the same verified read-back route
  streams the bytes. A live Clash-session screenshot (`m13_state.png`, 1280×720, 736,103 B) was listed and
  downloaded as a valid PNG through the API after reload.
- **Host plugin version** — bumped to 0.2.6 so Diagnostics/health reflects the change. No APK update is required:
  reopen a session (or let it refresh) after the host reload to see tool images.
- **Workspace/remote Markdown images (0.2.121 + host 0.3.0)** — local `![alt](path)` links become synthetic attachments when inside the session cwd or `artifacts.roots`; public http(s) links become `ext_` attachments served by an opt-in host proxy (strict public-IP SSRF policy, DNS pinning, redirect revalidation, 10 MB/timeout/magic limits). Absolute local paths outside configured roots and non-public URLs are still not fetched.

## Hotfix in 0.2.115

- **Startup crash fixed** — AppState called `startRemote()` from its `init` block before three delegated
  state properties (`lastGoalChange`, `presetRoster`, `presetsLoading`) were initialized. On a paired
  host (`isLive`), the first assignment threw an NPE and the app crash-looped on every launch (reported
  from 0.2.113, Android 16). Those properties are now declared before `init` with a construction-order
  warning; the init path only touches fields that are already initialized.
- This is the likely root cause of the recent “app keeps crashing after update” lockout; safe mode (0.2.113)
  remains the recovery path if a future bad build still fails, and recovery links (0.2.111) restore pairing
  if app data was cleared.

· **Scope:** native Android client in `app/src/main`; the host side is `host-plugin/`.

**Method:** every `shipped` line below was checked in the Kotlin sources at the commit above (grep/read).
Work that exists only in the working tree is listed as **in progress**, never as shipped. This tracker was
written without building or running the app on a device.

**Legend:** `shipped` = code present and reachable from the UI · `partial` = present with a named gap ·
`in progress` = uncommitted working-tree work · `planned` = not implemented (several need new host endpoints,
see [`mobile-feature-ideas.md`](mobile-feature-ideas.md) "Host API gaps").

## Shipped, by area

| Area | What is in the app | Known gaps |
|---|---|---|
| Host pairing / Tailscale | Welcome screen: pair desktop host, pair cloud/headless host, or use demo data. Manual pairing = label + base URL + single-use 6-digit code → `POST /pair/complete`; "Test host URL" health probe before pairing. **Tailscale one-tap sign-in (0.2.112)**: on a Tailscale Serve URL, Serve forwards the tailnet identity (`Tailscale-User-Login`) and `POST /pair/tailscale` mints a device token with no 6-digit code; the Welcome screen and Settings → Hosts “Add host” both have a "Sign in with Tailscale" path with methods-aware hints (add-host stores/selects the profile in place and leaves onboarding untouched), and missing identity/disabled hosts fall back cleanly to code/recovery. **Recovery access (0.2.111)**: while paired, Settings → Security generates a single-use 256-bit recovery link/code (stored only as a SHA-256 hash in the host's atomic state file) and tells the user to save it in a password manager; after clearing app data/reinstalling, Welcome → "Restore with recovery code" (or an OS `dshmobile://recover` link) mints a new device token without any desktop code, consumes/rotates the old code and shows the new link once. Hosts screen: add, switch, rename, remove, test, per-host version/health. Tokens per host, AES-256-GCM encrypted with Android Keystore. **Paired devices (0.2.107)**: Settings → Security lists paired devices with platform/paired/last-seen and a "This device" tag, and revokes another device after confirmation (revocation is immediate/persisted and closes its SSE stream; host self-revoke is refused). Live/Connecting/Offline/Demo pills, connection banner + Reconnect, "updated HH:mm". Connection diagnostics screen with live `/health` probe and copy. Any reachable base URL works — Tailscale Serve is a deployment choice, not app code. | Tailscale auto-pair trusts the Serve-injected identity header, so the **tailnet ACL is the boundary** (default enabled; restrict with `pairing.tailscaleLogin` or disable with `pairing.tailscaleAutoPair:false`); it requires a Tailscale Serve version that forwards identity headers and a logged-in Tailscale app, otherwise falls back to a code/recovery link. Recovery code is pairing-equivalent authority while unconsumed (treat like a password; shown once, rotates, no TTL/audit); **QR pairing (0.4.0)**: the host serves `http://127.0.0.1:3543/pair` ("Connect a phone" page, QR + 6-digit fallback + expiry countdown, strict CSP, vendored MIT QR encoder); the app scans it with CameraX + ML Kit's bundled barcode model, pairs from the scanned payload, and answers `dshmobile://pair?...` links with the same prefilled form (recovery links still support copy/paste as before); all paired devices are equally trusted and can revoke each other or rotate recovery (no admin role/re-auth); the current device cannot revoke itself; cleartext still allowed (`usesCleartextTraffic="true"`, no network-security config); no separate "hide content in recents" toggle (tied to app lock). |
| Crash-loop safe mode | Launcher **Safe mode** shortcut + `dshmobile://safe-mode`, plus a Settings → Diagnostics “Safe mode” row, open a minimal recovery screen that never starts the normal shell (0.2.113/0.2.114): Start app normally, Clear caches only, Reset appearance/notification settings (explicit allowlist), Share crash log, or open Android App info. A crash recorder (3 crashes/10 min, synchronous write, idempotent handlers) routes a crash-looping launch before AppState/Compose; pairing, drafts, queued prompts and app lock are always preserved. The screen forces a dark platform theme, uses fallback views and announces status via a polite live region with TalkBack content descriptions. | Safe mode is recovery UX, not a guarantee: corrupt Keystore/app-lock/resource state can still break the normal app, Clear storage from Android settings still deletes pairing (recovery link is the backstop), no device-level forced-crash/TalkBack test, and the shortcut competes with other launcher shortcuts. |
| Sessions / chat | Cache-first session list seeded from the last snapshot (`HostSnapshotCache`); date buckets Today/Yesterday/Earlier; pinned section; workspace chips; **delegation badges (0.2.125)** show internal child-session counts/working in list rows and Session Info; **Plan action/sheet (0.2.127)** shows the current agent plan with x/y progress; **Team board (0.2.129)** shows Agent Teams members and active tasks from Session Info; **Trajectory journal (0.2.130)** exposes newest-first raw-event summaries with Load older; **Files browser (0.2.131)** lists/previews workspace files read-only with credential denylist; **Share message as image (0.2.132)** renders a PNG card with redaction/caps and opens the share sheet; **Background jobs (0.2.133)** shows running/finished tool jobs read-only from Session Info; **Accent theme packs (0.2.134)** add six persisted accent palettes in Settings → Appearance; **two-pane adaptive layout (0.2.135)** shows a persistent SessionRail + chat on windows ≥960 dp; **context-window meter (0.2.136)** shows pressure/breakdown/warnings in Session Info; **scheduled prompts (0.2.128)** appear in the transcript with an accessible “Scheduled” label and copy/transcript markers while staying out of previews/search; status filters working/idle/unread (persisted); session sort activity/title/status (persisted, 0.2.52); search over title/preview/workspace/local alias; hidden-sessions manager; long-press actions (pin, continue working, local rename, hide, copy session ID, open); create-session sheet with workspace + **host agent-preset roster (0.2.101)** (real preset ids, default/broken handling, legacy fallback chips); transcript fetch (100 messages) with cache-first offline seeding (`HostTranscriptCache`, 30 sessions/host) and a "Load earlier messages" page control for long transcripts (0.2.46); tool cards + tool activity sheet, **tool-result output viewer (0.2.120)**: capped text output from `tool.output` opens a monospace selectable sheet with Copy/Share and a truncation note; interrupt; one-tap retry of a failed send; per-session draft persistence (`DraftStore`) and host-accepted attachment draft persistence (`PendingAttachmentStore`, 0.2.68); prompt templates + recent prompt history + saved snippets; **skills browser (0.2.123)** lists the host session skills and prefills “Use the <name> skill: ” without auto-sending; in-session find; jump-to-latest; message actions (copy/share/quote/read aloud); inline message clock labels for settled user and assistant rows (0.2.54); long assistant replies collapse behind Show more/less with a 440 dp preview (0.2.64); **chat text size Small/Default/Large/X-Large** scales message text only (0.2.70); **wrap code blocks** toggle switches fenced code from horizontal scrolling to soft wrapping (0.2.73); continuous on-device dictation with live partials and auto-restart (0.2.39), plus a Prefer offline recognition toggle that requests an on-device speech model (0.2.45) and a **recognition-language picker** (system default, en-US/UK, es, fr, de, it, pt-BR, ja, ko, zh-CN, hi; 0.2.63); full-resolution camera capture through a FileProvider URI (0.2.50) and file attach; **GOAL banner** with Set goal/Resume/Pause and 20 s refresh (0.2.80–0.2.84), updated immediately by `goal.changed` SSE (0.2.85); demo mode with mock sessions/approval/usage; **session-list goal phase badge** from the optional summary goal (0.2.87); **host session management (0.2.100)**: rename a live session on the host, archive/unarchive ordinary sessions with an Archived screen, and archived rows disappear from the normal list everywhere. | No wake-word/always-listening mode (dictation only while the mic is active); no host delete; host rename requires a live session and local display-name aliases still take precedence; archive refuses sessions with active work and cannot stop running work; no model/thinking/preset list from the host; no starter prompts; no diff cards, artifact viewer or session templates; attachments disabled in demo mode. |
| Streaming / SSE | One app-scoped SSE loop (`RemoteRepository`); `GET /events?since=<lastEventId>` cursor replay; backoff 1 s → 15 s; 90 s stream read timeout with 20 s host heartbeat; foreground stale-socket watchdog (~75 s); resume/network/VPN auto-heal with a 2 s storm guard; rolling connection event log (network changes, reconnect reasons, stream errors) in Connection diagnostics; cached transcripts remain readable offline while the reconnect runs; `ping` heartbeat updates liveness; `host.status` handled; snapshot + transcript refetch after a reconnect/cursor gap; optimistic local user row cleared when the host echoes it; idempotent upsert by message id; `message.completed` triggers a detail refetch; approval/question/resolve events applied when a host sends them; `goal.changed` updates the GOAL banner immediately (0.2.85). | No WebSocket/polling fallback for buffering tunnels; no foreground-service SSE; no explicit "host can no longer replay" detection beyond refetch; stale-stream assumptions depend on the host heartbeat (old hosts without pings reconnect every 75–90 s while foreground). |
| Model / thinking | Chat top-bar model chip + “Model & thinking” dialog backed by `GET /models`, `GET/PUT /sessions/{id}/model` (0.2.108): current selection/source, provider groups with per-model thinking efforts, failed providers annotated/disabled, truncated note, stale-effort sanitization, save is live-session only; a prominent callout warns that changing the model also becomes the DSH default for new sessions. | No per-session-only mode (DSH saves the choice as the host default); cold sessions cannot be changed; no catalog caching between dialogs and no SSE refresh; the model picker was not device-tested. |
| Host queue | Chat “Queue · N, Waiting on the host” strip backed by `GET/PATCH /sessions/{id}/queue` (0.2.109): lists live inbox prompts with target/attachment counts, text edit (blocked for attachment prompts), remove with confirmation, steer-into-running-turn (only while working, confirmed), cleared local-vs-host distinction, 100-item cap note, vanished-item refresh, cold/unsupported states. | Host queue only for live sessions; no enqueue from this panel, no reorder, attachment prompts cannot be edited, no live polling/SSE, and any paired device can modify queued prompts. |
| Conversation fork | Chat message action “Fork from here” when the host message carries a durable seq, plus session-sheet “Fork conversation” for the latest completed turn (0.2.110): confirmations explain copied prefix vs later messages; after success the app refreshes the list and opens the new session (or shows its id if not listed yet); `POST /sessions/{id}/fork` returns the new id, warns on workspace-attach failure, and never activates/deletes the source. | Forks copy prefix history only (no live agent/goal/schedules/policy/queue/drafts); no fork browser/lineage UI, no delete-of-fork shortcut, no idempotency for duplicate taps, and only host messages with seq can fork from a point. |
| Approvals / questions / inbox | Real-time host bridges: **approvals and questions are answerable from the phone by default** (Allow once/Deny and option/**free-text** answers, "Other…" custom answers; first whole answer wins against the desktop; `approvals.mobileAnswer`/`questions.mobileAnswer:false` restore read-only), **per-session approval policy Ask/Never (0.2.106)** from the session sheet with a Never confirmation (Never auto-rejects actions that need approval for unattended runs; writes are live-session only), lost-race notices, Inbox across sessions with "Inbox · N waiting" count, notification actions for approvals. | No deny reason/note, no danger-flag highlighting, no "answer later"; a partial multi-question batch keeps the agent waiting until every question is answered; an "Other…" answer that exactly matches an option label is recorded as that option; desktop prompt can remain visible after a phone answer; policy changes are not pushed live or audited beyond the durable DSH event. |
| Schedules / reminders | Schedules screen from the session list: host catalog list with title, session, instruction preview, status, kind+summary, next/last run; create one-off "After N minutes" or "Daily at HH:MM" (device IANA timezone sent to the host) in a chosen visible session; **edit title/instruction and after/daily timing (0.2.99)**, plus a "Schedule this prompt" chat action; delete with confirmation; unsupported hosts show an explanatory empty state. | No pause/run-now, no "every/weekly/cron/at" timing editing (title/instruction only), no per-schedule timezone picker; list is not grouped/filtered by session; createdAt/updatedAt are best-effort null. |
| Usage / balance | `GET /usage` dashboard: DeepSeek balance card (available/insufficient), low-credit warning, token totals (uncached input, output, cache read, cache write), per-session token list sorted by total tokens, copy summary, share CSV (`text/csv`, 15-column table), refresh/retry; **low-credit alert threshold at $5/$10/$20 with a once-per-day notification (0.2.62)**; usage refresh on app launch when alerts are enabled and every 30 s while the Usage screen is open (0.2.72); **approximate today's tokens** from the growth of host totals since the first sync today (0.2.75); mock usage in demo mode. | No per-turn cost, context meter, or daily spend breakdown; alerts only fire when usage is fetched (launch/Usage screen/30 s refresh), not as a background balance poll. |
| Notifications / background sync | Channels `turn-finished`, `input-needed`, `usage-alerts` and `goal-alerts` (plus `background-sync`); notifications posted from the live SSE state while the process is alive; **inline question reply (0.2.116)**: question notifications show the prompt and a RemoteInput “Reply” action (free-form, 2,000 chars), answered by a non-exported receiver through the encrypted host profile without opening the app; action is withheld while app lock is on, lock-screen public version never exposes the prompt, stale/duplicate answers map to “Already answered”, failures offer Open inbox; **optional ntfy push (0.2.105)**: Settings configures a host-published ntfy topic (https; optional token), and the host sends short approval/question/goal/turn pushes with inbox/session deep links; deep links `dshmobile://session/<id>`, `…/inbox`, `…/approval/<id>/allow\|deny`, `…/new`, `…/usage`; optional foreground `BackgroundSyncService` poll when the app is closed; Settings diagnostics with permission state, test notification, clear notifications, and the **last successful sync time / last error (0.2.76)**; **quiet-hours window** (from/until pickers) suppresses turn and input notifications while active; **per-session notification mute** from the session long-press sheet (0.2.55), shown as a "muted" tag on the session row (0.2.65); **no duplicate alert while the Activity is foreground** — foreground Compose monitors own the alert and background sync suppresses itself through the shared foreground flag (0.2.59); **2+ alerts are grouped under a summary that opens the Inbox (0.2.65)**. | Inline reply is unavailable while app lock is enabled (deliberate lock bypass protection), failures aren’t queued/retried, and an 8.5 s send timeout can race a request the host already received; ntfy is opt-in and requires the ntfy app subscribed to the topic; host pushes cannot honor app-local **foreground** state (quiet hours and per-session mutes are synced to host 0.3.3; possible duplicate buzz); no FCM; background poll interval is configurable 20 s / 1 min / 5 min (change applies from the next cycle); no badge sync. |
| Theming / app lock / security | "Hadal Mint" dark + "Hadal Day" light themes plus an **AMOLED pure-black dark variant (0.2.58)**, persisted; JetBrains Mono UI; optional app lock through the system device-credential prompt (PIN/pattern/password, biometrics where the OEM provides them), re-locks on background, **configurable re-lock grace period now/15 s/30 s/1 min (0.2.61)**, `FLAG_SECURE` while enabled; **app-wide interface text scale Small/Default/Large/X-Large (0.2.85)**; AES-256-GCM Keystore token storage; last-crash report (build/device/screen/memory) with share/copy; `allowBackup=false`. | No BiometricPrompt-specific screen; no dynamic color; no secret redaction before share; no transport allowlist; no device revocation; no retention/data-purge controls. |
| Markdown | Headings h1–h4, bullet and numbered lists, quotes, horizontal rules, fenced code blocks with per-block copy, **GFM pipe tables with horizontal scrolling (0.2.47)**, **task lists with checked styling (0.2.51)**, inline bold and inline code, auto-linked clickable URLs (link annotations, 0.2.29). | No syntax highlighting; whole message re-parsed on each change (no tail-only streaming parse); no soft-wrap toggle, save-as-file or "explain this block". |
| Attachments / images | SAF multi-file picker; camera capture; chips with streamed upload progress %, cancel and retry; image chips show a small decoded preview (0.2.82); **historical read-back (0.2.96)**: message attachments render as Openable chips, download through `GET /sessions/{id}/attachments/{id}` with a 25 MB cap into the app cache and open via FileProvider (share fallback); **historical image thumbnails (0.2.97)**: bounds-first downsample to ≤512 px/256 KB, LRU ≤24/16 MB, shared cached file and silent icon fallback; **in-app image viewer (0.2.102)**: tapping a historical image opens a dark full-screen viewer with pinch/double-tap zoom, clamped pan, prev/next paging for multiple images, share, loading/retry, while explicit Open still uses the external app; **tool-result images/files (host plugin 0.2.6)**: screenshots and generated image/file blocks inside tool results (e.g. Android screenshots, artifact files) are mapped as message attachments, included in the session attachment index, streamed over the same read-back route and rendered in the chat like user/assistant images; **workspace/Markdown image links (0.2.121)** become synthetic `ws_` attachments when inside the session cwd or configured `artifacts.roots`, served read-only through the same route with containment/magic/caps; **remote http(s) Markdown images (host 0.3.0, opt-in)** become `ext_` attachments fetched on demand by a public-IP-only SSRF-hardened proxy; **Save to Photos (0.2.118)** writes the cached image to MediaStore `Pictures/DSH Android` on Android 10+ (SAF on 8–9) with byte/declared-MIME sniffing and cleanup, and **Copy** puts a read-scoped FileProvider URI on the clipboard, with missing-cache re-download/status toasts; read-back cache pruned to 100 MB/7 days at app start with protected in-use files; 64 MB decoded / 280 MB base64 streaming caps and 8-pending cap enforced client-side with clear errors; prompt with `attachmentIds` (text optional); draft attachment ids restored across restarts. | Only real image/file content blocks are attached; a workspace path or Markdown image link typed in assistant text is still not fetched as an image. No HEIC/AVIF normalization, no clipboard image paste, no crop/annotate, no screen capture, no voice-note attachment; images ≥15 MB stay icon chips, paging is buttons-only (no pager swipe), and the cache prune runs only at app start. |
| Search / filters | Session-list search (title/preview/workspace), workspace and status filters, date buckets; in-session find with match count and previous/next; **global cached-message search** across the transcript cache with snippets and jump-to-session; **host-wide message search (0.2.103)** from the session list: scans visible host sessions (user/assistant text + tool command detail), shows highlighted snippets with session/role/time; **role and date filters (0.2.111)**: User/Assistant/Tool role chips and Any/Today/7/30-day windows with explicit since/until, active-filter summary, undated-message note, stale-result guards, and an old-host notice when the filter echo is missing; **offset pagination + Load more (0.2.117)**: host `offset` (0..500) returns `offset`/`nextOffset`/`hasMore`, the app appends/dedupes pages with stale-page and cursor-advance guards, keeps hits on load-more errors with Retry, and old hosts without pagination behave exactly as before; **honest scan coverage (0.2.119)**: host scanIncomplete/scannedSessions/totalSessions drive a “Searched X of Y sessions — results may be incomplete” note. | Host search is a bounded scan of collected matches (no index/rate limit; ≤500 matches, 200 sessions, 2000 events/session, 8 s budget, ≤50 matches/session; pages can still be truncated and new matches can shift pages); date windows are computed on the phone clock and date-bounded queries drop undated messages; filters are not persisted across app restarts; tapping a hit now jumps to the message via host seq with bounded older-page loading and a brief highlight (2026-10-31/0.2.126); very old hits beyond the 20-page bound show a not-found notice; cached search only covers sessions opened on this device. |
| Share / export | Message copy/share/quote; transcript export as Markdown **text** through the share sheet (0.2.16); transcript **file** share (`.md` via FileProvider, 0.2.38); **PDF transcript export (0.2.119)**: paginated A4 PDF with title/exported time, role + time labels, caps (200 messages/120k chars/80 pages) reported in a Toast, code-point-safe truncation, off-main build and `application/pdf` share via FileProvider; usage CSV share (0.2.38); share-sheet ingest (`SEND` text → draft, `SEND`/`SEND_MULTIPLE` images → attachments, 0.2.35); `dshmobile://` VIEW deep links; crash-log share; usage-summary copy. | No share-as-image card or export bundle; PDF rendering/CJK/emoji were not device-verified, footer is “Page N” without a total, tool rows are omitted, chooser cancel is unobservable, and large transcripts are capped rather than streamed page-by-page; manifest accepts only `text/plain` and `image/*` (no PDFs/other files); no verification of which app sent a share; exported transcript files are not pruned.
| Widget | RemoteViews quick-actions widget: open / new session / usage / inbox, plus a status line ("<session> · <status>", "Open the app to sync") refreshed by the app while it is alive and showing local "N queued", "N waiting" and **"goal <phase>"** suffixes when relevant (0.2.85); taps reuse the deep links. | Not Glance; no camera/mic actions; no update while the process is dead; no status widget; **Quick Settings tiles for New session and Usage (0.2.60)**; static shortcuts only (new session, usage, inbox) plus **up to 4 dynamic pinned-session shortcuts** (0.2.56). |
| Updates | Sideload updater: launch check throttled to 24 h, manual "Check for updates", update-available banner, DownloadManager download with progress, SHA-256 verify, install-permission handoff, package installer; version in About; crash reporting. | Sideload only (no Play track); no automatic publish from CI; no "what's new" notes beyond the update dialog. |
| Local backup | Settings → storage: versioned JSON export/import of local-only data (session aliases, pins/mutes/hidden, drafts, host-accepted attachment drafts, offline queue, snippets, safe presentation/notification prefs) via SAF; import validates schema/size, confirms destructive replace, and never includes host tokens, app lock, caches, or crash logs (0.2.104). | Whole-file replace only (no selective restore); backup is unencrypted/user-managed; host-curated hidden list is mirrored locally; app-lock/background-sync settings deliberately excluded. |

## Shipped in 0.2.114 (this round)

- **Tailscale add-host** — Settings → Hosts “Add host” (empty and saved-host states) and the Settings Hosts
  card now offer “Sign in with Tailscale” alongside desktop-code pairing, plus a recovery-code fallback.
  The flow reuses the one-tap `/pair/tailscale` plumbing, stores/selects the profile through the normal
  encrypted hosting path (duplicate base URL updates in place), and leaves Welcome/onboarding untouched.
- **Settings Safe mode + accessibility** — Settings → Diagnostics has a Safe mode row; the recovery screen now
  forces a dark platform theme, sets an explicit dark backdrop in fallback/last-resort views, announces status
  through a polite live region, and gives each action a TalkBack contentDescription.

## Shipped in 0.2.113 (this round)

- **Crash-loop safe mode** — a launcher **Safe mode** shortcut and `dshmobile://safe-mode` deep link open a
  minimal classic-view screen that does not start the normal AppState/Compose shell: Start app normally,
  Clear caches only, Reset appearance/notification settings (explicit allowlist), Share crash log, or open
  Android App info. Pairing, drafts, queued prompts and app lock are never touched.
- **Crash recorder** — a trivial `Application` records uncaught crashes synchronously (3 crashes/10 min
  window, idempotent handler installs) and `MainActivity` routes into safe mode before `setContent`; a normal
  run clears the counter only after 60 s of resumed use. Platform-theme + fallback views keep the safe screen
  renderable if app resources/theme caused the crash.
- **QA** — `qa/round93-verification.md` (merged from the QA worktree) evaluated the design and residuals.

## Shipped in 0.2.112 (this round)

- **Tailscale one-tap sign-in** — host `GET /pair/methods` (public) and `POST /pair/tailscale` (public)
  mint a device token from the tailnet identity that Tailscale Serve forwards (`Tailscale-User-Login`,
  fallback `Tailscale-User-Name`) — no 6-digit desktop code. Auto-pair is enabled by default, can be
  restricted to one login (`pairing.tailscaleLogin`) or disabled, and returns the usual token/device/host
  payload plus `pairingMethod:"tailscale"`. Missing identity/disabled hosts return clear errors and the app
  falls back to code/recovery.

- **App UI** — Welcome gains “Sign in with Tailscale” (Tailscale Serve URL, methods-aware hints, error
  mapping); existing desktop-code, recovery and demo paths unchanged.

- **Fake host + CI** — auth-exempt methods/auto-pair fixtures with header/allowlist/disabled cases
  (7 checks; full fake suite **169**).

## Shipped in 0.2.111 (this round)

- **Recovery access (no-desktop re-pairing)** — host `GET/POST /recovery` generate/rotate a 256-bit
  single-use recovery code (SHA-256 only, main atomic state file) and public `POST /pair/recovery` redeems
  it for a new device token, rotating the code and returning the next one. Settings → Security shows the
  one-time recovery link with Copy/Share; Welcome → “Restore with recovery code” accepts a pasted URL+code
  or a `dshmobile://recover` deep link, validates 52-char base32 locally, restores the profile like normal
  pairing, and shows the rotated new link once after success.

- **Search filters** — host `/search` accepts comma-separated `role` and inclusive `since`/`until`
  (ISO/epoch), applies them during the scan and echoes `filters`; the phone adds role/date chips, explicit
  local date windows with both bounds, active-filter summaries, an undated-message note, stale-result guards,
  and an “old host ignored filters” notice when the echo is absent.

- **Fake host + CI** — recovery fixtures (8 checks) and extended search filters (13 checks; full fake suite
  **162**).

## Shipped in 0.2.110 (this round)

- **Conversation fork** — host `POST /sessions/{id}/fork` creates a new session from the latest completed
  turn or an exact durable `atSeq`; session messages now carry optional `seq`. The phone adds session-sheet
  “Fork conversation” and message “Fork from here” actions with confirmations, refreshes the list and opens
  the new session, maps unavailable boundaries to “No completed turn to fork from yet”, and warns when the
  host reports a workspace-attach failure.

- **Fake host + CI** — message seq fixtures and a 7-check fork test (full fake suite **150** checks).

## Shipped in 0.2.109 (this round)

- **Host queue panel** — host `GET /sessions/{id}/queue` and `PATCH /sessions/{id}/queue/{itemId}` expose a
  live agent's waiting prompts; edits are text-only (attachment prompts are blocked to avoid dropping
  attachments), removal retires prompt uploads, and steer injects a next-turn prompt into the running turn
  (only while working). The chat screen adds a “Queue · N, Waiting on the host” strip and dialog with edit/
  remove/steer, vanished-item refresh, cold/unsupported states and a clear local-outbox distinction.

- **Fake host + CI** — seeded queue fixtures, edit/remove/steer and cold rules, 9-check queue test
  (full fake suite **143** checks).

## Shipped in 0.2.108 (this round)

- **Model & thinking controls** — host `GET /models` and per-session `GET/PUT /sessions/{id}/model`:
  catalog with provider groups, thinking efforts and redacted provider failures; cold sessions read from the
  durable log without activation; live-only writes validate through DSH and install the selection; the host
  README warns that a change also becomes the DSH default for new sessions. The chat top bar gains a compact
  model chip and a picker dialog (current/source, groups/models/efforts, failures, save, prominent
  default-change callout, stale-effort sanitization, no optimistic chip on failure).

- **Fake host + CI** — catalog + per-session selection fixtures and a 9-check models test (full fake suite
  **134** checks).

## Shipped in 0.2.107 (this round)

- **Paired devices** — host `GET /devices` / `DELETE /devices/{id}` list paired devices (no token material)
  and revoke another device; revocation is awaited/persisted with rollback on write failure, invalidates the
  token immediately, and closes that device's open SSE stream. The phone adds Settings → Security → Paired
  devices with this-device tagging, relative paired/last-seen, and a confirmation before revoking.
- **Fake host + CI** — shared-token device registry, seeded legacy device for token-invalidation coverage,
  and a 6-check devices test (full fake-host suite 125 checks).

## Shipped in 0.2.106 (this round)

- **Per-session approval policy** — host `GET/PUT /sessions/{id}/approval-policy` exposes DSH's
  ask/never policy: Ask allows approval requests; Never auto-rejects approval-requiring actions for
  unattended runs. Cold sessions are read from the durable policy log (never activated); writes need a
  live session. The session long-press sheet gets an Approval policy dialog with a second confirmation
  for Never.
- **Fake host + CI** — seeded ask/never policies, cold-write and validation rules, and an 8-check test.

## Shipped in 0.2.105 (this round)

- **Optional ntfy push** — host `GET/PUT /push` and `POST /push/test` configure host-published
  notifications for approval/question/goal/turn events with inbox/session deep links. The host enforces
  https (loopback http only), blocks literal private/loopback/link-local push servers (SSRF hardening),
  caps 30 notifications/minute, backs off 60 s after two failures, bounds the queue, dedupes, and never
  returns the stored token. Settings → Notifications gets the setup UI (enable/server/topic/generate
  topic/password token/clear token/save/test) with security copy.
- **Search budget honesty** — the 3 s `/search` budget is documented as best-effort (synchronous work can
  overrun; live 3–5.6 s observed), with partial hits/`truncated` as normal.
- **Fake host + CI** — in-memory push config routes and a 7-check test.

## Shipped in 0.2.104 (this round)

- **Local backup** — Settings → storage can export/import a versioned JSON backup of local-only data:
  session aliases, pins/mutes/hidden, drafts, host-accepted attachment drafts, offline queue, snippets,
  and safe presentation/notification preferences. Import streams with a 5 MB cap, validates schema and
  top-level keys, confirms the destructive replace, and never includes host tokens, app lock, caches or
  crash logs.

- **Search budget fix** — the `/search` wall-clock budget now starts before the session list and bounds
  every snapshot read (whole-request semantics); partial hits are returned with `truncated:true`.

- **Catalog refresh** — the 200-idea feature catalog was updated through 0.2.103 (search, schedules,
  session management, presets, attachments/viewer, answering) with the open-gap list corrected.

## Shipped in 0.2.103 (this round)

- **Host-wide message search** — new `GET /search?q=&limit=` scans visible sessions (user/assistant
  text + tool command detail) with bounded sessions/events/hits and a 3 s budget; the phone gains a
  "Search all sessions" screen with debounce, highlighted snippets, session/role/time, truncated note,
  retry/unsupported states, and tap-to-open.
- **Viewer pan fix** — full-screen image pan limits now use the letterboxed fitted content size, so
  portrait/landscape images cannot be dragged off their own pixels.
- **Fake host + CI** — deterministic search fixtures and a 9-check search test.

## Shipped in 0.2.102 (this round)

- **In-app image viewer** — tapping a historical image attachment opens a full-screen viewer with
  pinch zoom (1–6×), double-tap fit/2.5×, clamped pan, previous/next paging with a counter for
  multiple images, loading/retry, and share; the explicit Open action still hands off to the
  external app. Images load from the existing cache with a bounded downsample (2048 px / 24 MB).
- **Fake host** — attachment fixtures upgraded to a 512×512 image plus a second 256×256 image for
  paging (9 attachment checks).

## Shipped in 0.2.101 (this round)

- **Real agent presets** — new host `GET /presets` exposes the configured roster (id, name,
  description, default, broken) without activating anything; the new-session sheet uses those real
  preset ids, preselects the default, disables broken presets, shows the selected description, and
  falls back to legacy chips on older/unsupported hosts. Session creation sends the machine id.
- **Fake host + CI** — seeded preset roster (including a broken preset) and a 5-check test.

## Shipped in 0.2.100 (this round)

- **Host session management** — `POST /sessions/{id}/rename` (live sessions only, clear 400 for cold
  sessions), `POST /sessions/{id}/archive` / `/unarchive` (never stops running work; active sessions are
  refused), and `GET /sessions/archived`; archived rows disappear from the normal list.
- **Phone UI** — long-press gains "Rename on host…" (host title, changes everywhere) and "Archive on
  host…" (confirmation), plus an Archived screen with Unarchive, refresh, empty/unsupported states.
- **Fake host + CI** — rename/archive/archived fixtures and an 8-check session-management test.

## Shipped in 0.2.99 (this round)

- **Schedule editing** — `PATCH /schedules/{id}` (title/instruction plus one timing selector for
  after/daily rules, session-scoped, conflict/error mapping) and an Edit dialog in the schedules screen.
  The dialog sends timing only when the user actually changes it, so renaming cannot reschedule a
  reminder or re-home a foreign-zone daily rule.
- **Schedule this prompt** — the chat composer can schedule the current draft as the instruction for a
  one-off or daily reminder in that session, with a default title and duplicate-submit guard; the draft
  is never cleared.
- **Fake host + CI** — PATCH support and update checks (12 schedule checks).

## Shipped in 0.2.98 (this round)

- **Schedules / reminders** — new host API (`GET /schedules`, `POST /sessions/{id}/schedules`,
  `DELETE /schedules/{id}`) over DSH's schedule service, with visible-session filtering and validation,
  plus a phone Schedules screen: list with next/last run, create one-off "after N minutes" or daily
  HH:MM using the device timezone, and delete with confirmation.
- **Fake host + CI** — seeded schedules, create/list/delete validation, and an 8-check schedules test
  wired into CI.

## Shipped in 0.2.97 (this round)

- **Historical image thumbnails** — image attachment chips now load a bounded async thumbnail (bounds-first
  decode, ≤512 px longest edge / 256 KB, LRU ≤24 entries/16 MB, one in-flight decode per id) using the
  shared read-back cache; failures silently keep the icon chip.
- **Attachment cache trimming** — `cacheDir/attachments` is pruned at app start to 100 MB / 7 days with
  in-use files protected; pruning is silent and never deletes the file being opened.
- **Fake host** — seeded attachment image upgraded to a valid 64×64 PNG with IHDR assertions (8 checks).

## Shipped in 0.2.96 (this round)

- **Attachment read-back** — session messages now expose optional `attachments` metadata and a new
  authenticated `GET /sessions/{id}/attachments/{attachmentId}` serves the bytes (session-scoped refs,
  no host paths; images inline, files as downloads). The phone renders historical file/image chips and
  opens/downloads them via FileProvider with a 25 MB client cap and app-cache reuse.
- **Raw HTTP carrier** — the host server gained a small raw-response path for byte endpoints; JSON/SSE
  are unchanged.
- **Fake host + CI** — seeded image/file attachments and a 7-check read-back test wired into CI.

## Shipped in 0.2.95 (this round)

- **Free-text question answers** — optionless questions now show a text field + Send on the phone, and
  option questions gain an "Other…" custom answer; answers are trimmed/capped at 2000 chars, sent with
  `optionIndex=null` and mapped by the host to DSH `custom`. One in-flight answer per question prevents
  double sends; multi-question batches keep the remaining rows visible.
- **Fake host + CI** — free-text `q_free` fixture and custom/empty/repeat checks (9 question-answer checks).

## Shipped in 0.2.94 (this round)

- **Answer questions from the phone** — the host question bridge now accepts option answers per row,
  emits `question.resolved`, and returns the full `{answers}` batch to the agent once every question in
  the call is answered. Desktop remains a competing answerer and the **first whole answer wins**;
  `questions.mobileAnswer:false` restores the read-only mirror.
- **Lost-race UX for questions** — a 404/409/already-answered response hides only the raced question,
  keeps the rest of a multi-question batch visible, and shows "Already answered on the desktop.";
  disabled/501 shows the read-only copy.
- **Fake host + CI** — question-answering fixture with single and two-question batches (8 checks) wired
  into CI; contract docs updated with batch-partial and free-text caveats.

## Shipped in 0.2.93 (this round)

- **Answer approvals from the phone** — the host approval bridge now races a deferred phone decision
  against the desktop answerer (**first answer wins**) and returns `readOnly:false`; the Inbox, inline
  chat card, notification actions and deep links can Allow once/Deny. `approvals.mobileAnswer:false`
  restores the read-only mirror. Questions stay read-only.
- **Lost-race UX** — when the desktop answered first (404/409/already-answered) the app clears the
  stale card immediately and shows "Already answered on the desktop."; a disabled/501 host shows the
  read-only copy instead of a raw error.
- **Fake host + CI** — mobile-answering approvals fixture (7 checks) wired into CI; docs/contract
  updated (first-answer-wins, security note that any paired device can answer).

## Shipped in 0.2.92 (this round)

- **Mark a goal complete from the phone** — the GOAL banner shows **Complete** for active/paused/blocked
  goals, behind a confirmation dialog; it calls the new host `POST /sessions/{id}/goal/complete` route,
  which transitions the goal to complete/disarmed and emits `goal.changed` operation `complete`.
- **Stale-dialog guard** — the app sends the goal `{id, revision}` it displayed; the host rejects a
  mismatch with `400 invalid_request` instead of completing a different current goal.
- **Fake host + docs** — goal/complete route and transitions covered by fake-host checks (30 goal checks);
  contract docs document the route, optional ref and error rules.

## Shipped in 0.2.91 (this round)

- **Goal objective on the dashboard** — `SessionSummary.goal` now includes `objective` (trimmed, ≤500
  chars); the Goals dashboard shows it as a two-line summary with preview fallback, and `goal.changed`
  keeps it current.
- **Non-live goal summaries** — `GET /sessions` resolves a session's goal from the durable goal session
  projection when no live agent exists, so paused/blocked/completed goals on cold sessions appear too.
  Projection hints may lag until the next background list refresh; activation is still live-only.
- **Catalog refreshed** — `docs/mobile-feature-ideas.md` is confirmed at exactly 200 ideas (1–200,
  no gaps/duplicates) and gained a 0.2.90 implementation-status mapping (98 idea numbers).
- **Fake-host goal objectives** — seeded goal summaries and create/edit now carry objective text; goal
  self-test is 27 checks.

## Shipped in 0.2.90 (this round)

- **Goals dashboard** — a Goals screen reachable from the session list lists every live session with
  a goal, ordered paused/blocked → active → complete, showing title, workspace, preview, phase chip
  and round counters; tapping a row opens that session. Opening a session now closes the dashboard
  first so deep links cannot leave chat hidden behind it.
- **Fake-host multi-goal fixtures** — four seeded sessions (active/paused/complete/blocked) so the
  dashboard and goal list can be exercised offline; fake-host goal self-test now 26 checks.
- **Usage summary copy includes today** — "Today: N tokens since first sync (approximate)" added to
  the copied usage summary.

## Shipped in 0.2.89 (this round)

- **Read-only questions on the phone** — the host mirrors `user-questions/request` through a
  pass-through waterfall listener (desktop answers) and exposes
  `{questions:[{id,sessionId,prompt,options}], supported:true, readOnly:true}`; the app shows read-only
  question cards in the Inbox and inline chat (options listed, **Answer on desktop**) and suppresses
  doomed answer calls.
- **Approval deep-link safety** — `dshmobile://approval/{id}/{decision}` now opens the Inbox instead of
  firing a decision on a read-only host; AppState answer methods are guarded the same way.
- **Fake host + CI** — read-only question fixtures with a 7-check self-test.

## Shipped in 0.2.88 (this round)

- **Read-only approvals on the phone** — the host registers a pass-through `approval/request` waterfall
  listener (desktop remains the only answerer), mirrors pending approvals as
  `{approvals, supported:true, readOnly:true}`, and emits `approval.requested`/`resolved`. The app
  shows them in the Inbox and inline chat card with **Resolve on desktop** instead of Allow/Deny, and
  read-only approval notifications only offer Open inbox.
- **Widget/goal polling cleanup** — the widget now reads the goal phase from the session-list summary
  instead of issuing its own 30 s goal GET.
- **Fake host + CI** — read-only approvals fixtures and an 8-check self-test; goal test 23 checks.

## Shipped in 0.2.87 (this round)

- **Session-list goal badge** — `GET /sessions` rows now carry an optional `goal` summary
  (`phase`, `roundsStarted`, `maxGoalRounds`, or null for non-live/no goal); the app shows a compact
  `goal active/paused/blocked/complete` tag on each row and updates it from `goal.changed`, while
  `session.updated`/detail payloads preserve the badge instead of clearing it.
- **Goal notification toggle** — Settings → Notifications adds "Goal updates" so paused/blocked/
  complete alerts can be turned off independently; default on.
- **Fake host + CI** — fake host session summaries carry the goal field, with 23 goal checks.

## Shipped in 0.2.86 (this round)

- **Goal edit + clear from the phone** — GOAL banner gains Edit (objective and max rounds, validated)
  and Clear (confirmation) when a goal exists; host bridge adds `POST /sessions/{id}/goal/edit` and
  `.../goal/clear` (`ctx.goals.edit`/`clear`, revision CAS, 400/404/501 errors).
- **Goal notifications** — a new `goal-alerts` channel posts when the current goal transitions to
  paused/blocked/complete (never for active/resume/create), deduped per session/revision/phase and
  suppressed while the app is foreground or quiet hours apply.
- **Fake host + CI** — fake host supports goal edit/clear and emits `goal.changed`; its 18-check
  self-test now runs in the GitHub Actions host-plugin-tests job.
- **Independent QA** — qa/round66-verification.md; low findings: contract `goal.changed` SSE event
  not yet listed in docs/mobile-api-v1.md, no revision-monotonic guard between SSE + poll, widget
  poll relies on the app process.

## Shipped in 0.2.85

- **App-wide interface text scale** — Settings → Appearance now has Chat text and Interface text
  rows; the interface scale multiplies the system font scale app-wide, while chat text still applies
  its own nested scale.
- **Widget goal status** — the home-screen widget appends `· goal active/paused/blocked/complete`
  for the most recent session (30 s refresh), preserving queued/waiting suffixes.
- **Instant goal updates** — the host now emits `goal.changed` SSE events for create/edit/resume/
  pause/block/complete/clear, and the app updates its GOAL banner from them instead of waiting for
  the 20 s poll.
- **Share connection diagnostics** — the diagnostics screen can share the host/network/reconnect
  report through the Android share sheet in addition to copying it.
- **Fake-host goal coverage** — tools/fake-host supports GET/POST goal, resume and pause plus
  `goal.changed`, with a seeded paused goal and a self-contained 12-check test.
- **Independent QA** — qa/round65-goal-verification.md records 130/130 host tests and live GET/create
  rejection evidence without mutating the active goal.

## Shipped in 0.2.84

- **Create a goal from the phone** — when a live session has no goal, the GOAL banner shows "Set
  goal"; a dialog captures the objective and max rounds and calls the new
  `POST /sessions/{id}/goal` host route (`ctx.goals.create`). Existing non-complete goals are rejected
  with a clear error; the banner then shows the active goal with Pause.

## Shipped in 0.2.83

- **Offline cache management** — Settings → Storage shows the on-disk transcript cache count/size and
  a confirmed "Clear offline caches" action that deletes cached transcripts and the session-list
  snapshot from the phone (host data is untouched; the next open reloads over the network).

## Shipped in 0.2.82

- **Image attachment thumbnails** — image chips decode a 96 px-bounded preview in the composer
  (never the full-resolution bitmap), kept after upload; non-images keep the paperclip icon. This
  makes camera photos and screenshots recognizable before sending.

## Shipped in 0.2.81

- **Pause goal from mobile** — the GOAL banner now offers Pause while a goal is active and Resume
  while it is paused/blocked/disarmed; the host bridge adds `POST /sessions/{id}/goal/pause` through
  `ctx.goals.pause`.
- **Goal banner auto-refresh** — while a chat is open it re-reads the goal every 20 s, so desktop
  pause/resume and automatic goal rounds update the phone without reopening the session.

## Shipped in 0.2.80

- **Goal controls on mobile** — the chat now shows a GOAL banner (phase, objective, round budget,
  armed/disarmed, blocked reason) and a one-tap **Resume** action for paused/blocked/disarmed goals.
- Host bridge: `GET /sessions/{id}/goal` and `POST /sessions/{id}/goal/resume` call DSH's `ctx.goals`
  service for the live agent, so no desktop UI is needed to see or re-arm a goal. Non-live sessions
  report `live:false`/400; absent goal service reports `supported:false`.
- Verified live against the running host: the current paused goal reads correctly
  (`goal-2654c455…`, 61/120 rounds), and the resume route answers 400 for a non-live session without
  touching the goal. The running host plugin was hot-reloaded with a config marker; no desktop restart.

## Shipped in 0.2.79

- **Cancel an in-flight upload** — removing an attachment chip while it is uploading now cancels
  that upload's coroutine; the HTTP client's `execute` path is cancellable (OkHttp `enqueue` +
  `Call.cancel`), so a slow large upload stops instead of finishing invisibly.
- Upload completion/failure is tracked per chip (`uploadJobs`), and a cancelled upload is not shown
  as a failure.

## Shipped in 0.2.78

- **Attachment upload progress** — the streaming upload body reports byte progress through
  `DshApiClient.uploadAttachment` → repository → AppState → the composer chip, which now shows
  "uploading N%" while a large file is being sent. Progress is monotonic and ends at 100%; the
  existing retry/remove actions are unchanged.

## Shipped in 0.2.77

- **Large-attachment upload fix (OOM)** — the `java.lang.OutOfMemoryError` in `JsonToStringWriter`
  came from building the whole base64 payload as a JSON string (a 43 MB recording needed ~241 MB).
  The body is now a streaming `RequestBody` (`Base64JsonBody`) that writes the JSON prefix, base64
  chunks, and suffix straight to the socket; peak extra memory is a 12 KB chunk. Client cap is
  64 MB decoded / 280 MB base64, and the host accepts 200 MB.
- **Fixed a streaming-encoder corruption bug** — the upstream streaming patch reused one buffer for
  every full 12,288-byte chunk, encoding zeros for all but the final chunk. `Base64JsonBodyTest`
  verifies JSON, exact `contentLength`, chunk boundaries, and decoded bytes; it failed before this
  fix and passes now.

## Shipped in 0.2.76

- **Background-sync health** — the poll loop records the last successful poll time and last error in
  shared prefs; Connection diagnostics shows "last sync" as relative time plus the latest error, so a
  silently dying background watcher is visible.

## Shipped in 0.2.75

- **Today's token estimate** — the Usage dashboard has a TODAY card showing the growth of host token
  totals since the first successful sync today (baseline/date persisted, reset at local midnight or if
  the host counter drops). It is explicitly approximate because the baseline is the first sync, not
  midnight.

## Shipped in 0.2.74

- **Queue reordering** — when two or more prompts are queued, each chip has ↑/↓ controls that move
  it earlier/later in the local queue; the chosen order is persisted and is what flushes as the agent
  goes idle. Cancel, clear and tap-to-edit are unchanged.

## Shipped in 0.2.73

- **Wrap code blocks** — Settings → Appearance toggle; when enabled, fenced code blocks soft-wrap
  to the screen width instead of scrolling horizontally. The setting flows through
  `LocalWrapCodeBlocks` and applies to every message rendered in the chat.

## Shipped in 0.2.72

- **Live usage refresh** — while the Usage dashboard is open it refreshes every 30 seconds (only
  when the host connection is live), keeping token totals and remaining credit current; the
  initial open still refreshes immediately, and the try-again button remains for errors.

## Shipped in 0.2.71

- **Chat text size** — Settings → Appearance has Small / Default / Large / X-Large chips; the choice
  persists and scales message text (user/assistant/system rows) through a local `fontScale` override,
  leaving the composer/header at the normal size.

## Shipped in 0.2.69

- **Widget Inbox action** — a third quick-action opens the Inbox directly; the widget status line
  also appends "· N waiting" for pending approvals/questions (in addition to queued sends).

## Shipped in 0.2.68

- **Attachment draft persistence** — host-accepted attachment ids (name/type/size) are stored per
  session and restored into the composer tray after an app restart, so an unfinished prompt doesn't
  need the file picked or uploaded again. Expired host ids fail at send time; in-progress uploads are
  still not persisted (their bytes would be too large for prefs).

## Shipped in 0.2.67

- **Honest approval capability state** — `DshRepository` now exposes `approvalsSupported` /
  `questionsSupported`; the Inbox shows a desktop-resolution explanation when the host reports the
  bridge unsupported, and Connection diagnostics lists each bridge as available/unsupported.

## Shipped in 0.2.66

- **Continue working action** — long-pressing an idle session offers "Continue working": it opens the
  session and sends a minimal `continue` prompt so a waiting agent can resume without retyping.

## Shipped in 0.2.65

- **Notification grouping** — turn/input alerts carry a shared group key; when 2+ are active the app
  posts a "DSH Android · N updates waiting" summary that opens the Inbox, and removes it when the
  count drops back to one.
- **Muted session indicator** — a "muted" tag on the session row makes the long-press mute state
  visible without opening the action sheet.

## Shipped in 0.2.64

- **Long-message collapse** — settled assistant replies over 1,400 characters render as a clipped
  440 dp preview with Show more / Show less, keeping long plans and tables from dominating the chat;
  streaming replies are never collapsed.

## Shipped in 0.2.63

- **Voice recognition language** — Settings → Voice now opens a language picker (system default plus
  common BCP-47 tags); the chosen tag is passed to the continuous recognizer and persisted, which
  helps when the system language differs from the language you dictate in.

## Shipped in 0.2.62

- **Low-credit alerts** — Settings → Notifications gains a Low balance alert toggle with
  $5 / $10 / $20 thresholds. When usage is refreshed (app launch or Usage screen) and the lowest
  remaining balance falls below the threshold, a once-per-day `usage-alerts` notification deep-links
  to Usage; quiet hours suppress it.

## Shipped in 0.2.61

- **App-lock grace period** — the Security section now has "Lock after now / 15s / 30s / 1m" chips;
  returning within the grace window keeps the app unlocked, while a longer background pause (or a
  process recreation) re-locks it. Immediate locking remains the default.

## Shipped in 0.2.60

- **Quick Settings tiles** — "New DSH session" and "DSH usage" tiles can be added from the Android
  Quick Settings editor; taps reuse the existing `dshmobile://new` / `dshmobile://usage` deep links.

## Shipped in 0.2.59

- **Foreground notification dedupe** — turn/input alerts are not posted while the app Activity is
  visible (foreground monitors handle them in-app), and `BackgroundSyncService` skips its duplicate
  through a shared foreground flag; notifications resume as soon as the app is backgrounded.

## Shipped in 0.2.58

- **AMOLED black theme** — Appearance gains an AMOLED toggle while dark mode is active; backgrounds
  and the lowest surfaces become pure/near black for OLED power savings, with the mint accents and
  layering preserved. The preference persists.

## Shipped in 0.2.57

- **Background queue delivery** — a global idle watcher now flushes locally queued prompts for any
  session when it turns idle, even if that chat is not open; the in-flight guard prevents a double
  send when the visible ChatScreen also flushes it.

## Shipped in 0.2.56

- **Dynamic pinned-session shortcuts** — pinned sessions are published to the launcher as up to
  four long-press app-icon shortcuts using their local display name; tapping one opens that session
  through the existing `dshmobile://session/<id>` deep link.

## Shipped in 0.2.55

- **Per-session notification mute** — the session long-press sheet has Mute/Unmute notifications;
  muted sessions skip turn-finished and input-needed notifications (including background-sync posts)
  while other sessions keep notifying. The set is persisted locally.

## Shipped in 0.2.54

- **Message timestamps** — settled user bubbles and assistant blocks show their `createdAt` clock label
  (from the host's message time) in a subtle trailing label; streaming rows keep only the cursor.

## Shipped in 0.2.53

- **Widget queue badge** — the home-screen widget's status line now appends "· N queued" when the
  most recent session has locally queued prompts, so pending sends are visible without opening the app.

## Shipped in 0.2.52

- **Session sorting** — "sort activity / title / status" chips above the ledger; pinned sessions stay
  on top, the chosen order is persisted, and status mode groups working → unread → idle.

## Shipped in 0.2.51

- **Markdown task lists** — `- [ ]` / `- [x]` items render as a checkbox glyph plus text; completed
  items are tinted with a strikethrough so agent plans are readable at a glance.

## Shipped in 0.2.50

- **Full-resolution camera capture** — the composer camera now launches the system camera via
  `TakePicture` with a cache-backed FileProvider URI, reads the full-size photo back, enforces the
  10 MB decoded limit, and uploads it through the normal attachment tray (no more preview thumbnail).

## Shipped in 0.2.49

- **Notification quiet hours** — Settings → Notifications has a Quiet hours toggle plus from/until
  time pickers (default 22:00–08:00, overnight windows supported). While active, turn-finished and
  input-needed notifications are suppressed; the diagnostic test notification still works.

## Shipped in 0.2.48

- **Local session aliases** — long-press a session → "Rename locally…" to set a per-device display
  name (or Reset it). The alias replaces the title in the session list, chat header and search; the
  host's session title is unchanged.

## Shipped in 0.2.47

- **Markdown tables** — GFM pipe tables (header + separator row) now render as a bordered,
  horizontally scrollable table with a tinted header, computed column widths, inline bold/code/link
  support in cells, and clean fallback to paragraph text when the separator row is malformed.

## Shipped in 0.2.46

- **Transcript pagination** — `GET /sessions/{id}?before=<cursor>` is now used to load older pages:
  chat shows a "Load earlier messages" control at the top when the host returned `nextCursor`, keeps
  the current scroll position by prepending only unseen messages, and hides the control at the end
  of history. Mock/demo mode has no older pages.

## Shipped in 0.2.45

- **Voice: prefer offline recognition** — new Settings → Voice toggle passes
  `EXTRA_PREFER_OFFLINE` to the continuous recognizer, avoiding network/VPN audio stalls when an
  on-device language pack is installed. If the offline language is unavailable, dictation stops with
  a clear message to turn the toggle off; the choice is persisted.

## Shipped in 0.2.44

- **Global cached-message search** — typing at least 2 characters in the session search now also
  searches the offline transcript cache (all sessions opened on this phone) and shows a
  "CACHED MESSAGES" section with the session title, a matched snippet, and jump-to-session. Search
  runs off the main thread with a 250 ms debounce; no host call is needed.

## Shipped in 0.2.43

- **Queue editing + visibility** — tapping a queued prompt without attachments removes it from the
  queue and restores its text to the composer for editing; the session row shows "N queued" while
  messages are waiting. Cancel/clear still work.

## Shipped in 0.2.42

- **Cancelable send queue fix** — messages typed while the agent is working are held locally in a
  persisted **QUEUED** strip with a per-item **X** and **clear**; they are sent one at a time after
  the current turn finishes. This replaces the previous "send immediately to the host queue" path,
  whose previews could not be deleted and could leave stale queued messages behind. Existing local
  queued prompts from older builds now appear in the strip and can be deleted. Prompts already
  accepted by the host's queue cannot be removed by the app (the host API has no such call).

## Shipped in 0.2.41

- **Offline transcript cache** — opening a session seeds the last cached 100-message transcript
  before the network call (`cacheDir/transcripts/<host>/<session>.json`, 30 sessions/host), so a
  drop still shows the conversation; the host fetch replaces it when reachable and refreshes the
  cache. The reconnect banner now says "offline · retrying automatically" with a "Retry now" action.

## Shipped in 0.2.40

- **Connection forensics** — Connection diagnostics now shows a rolling event log (network transport
  changes, reconnect reasons, stream errors, foreground/background transitions), categorised
  `/health` failures (DNS/TLS/timeout/refused), and a warning when a Tailscale Serve URL is paired
  while Android doesn't report a VPN. Copy diagnostics includes the event log.

## Shipped in 0.2.39

- **Network/Tailscale resilience** — foreground-resume auto-heal, ConnectivityManager default-network
  and VPN-change recovery, cursor-preserving forced reconnects, 90 s SSE read timeout plus a
  foreground stale-socket watchdog, and reconnect diagnostics (`networkTransport`,
  `reconnectAttempts`, `lastReconnectAt`, `lastStreamEventAt`). See
  [`network-troubleshooting.md`](network-troubleshooting.md) for Android/Tailscale settings.
- **Continuous voice dictation** — in-app `SpeechRecognizer` with live partial results, final-text
  commits and automatic restart across silence gaps; tap mic to start/stop; runtime `RECORD_AUDIO`
  permission; capped recovery for no-match/network/busy errors. Dictation only, no wake word.

## Shipped in 0.2.38

- **Transcript file share** — `ChatScreen.kt`: "Share transcript file" writes the Markdown transcript to
  `cacheDir/exports` and shares it as a `text/markdown` file via FileProvider.
- **Usage CSV share** — `UsageScreen.kt`: share action and a 15-column CSV (`text/csv` chooser),
  with RFC-4180 quoting and spreadsheet formula-injection hardening.
- **Background sync interval picker** — 20 s / 1 min / 5 min choice in Settings (`AppState.kt`,
  `BackgroundSyncService.kt`, `Sheets.kt`); the service reads the persisted value each cycle.

## Partially shipped / next (honest gaps)

- Real approvals/questions adapter in the host plugin (`501` stub). The phone side is ready.
- Image sharing has no real-sender verification: shared bytes are accepted on MIME type + 10 MB size only.
- Voice: dictation opens the system recognizer dialog (not push-to-talk streaming); read-aloud is one message
  through system TTS — no voice/rate picker, sentence pipeline, `MediaSession` controls or barge-in.
- Transcript search: in-session find only, over loaded messages; no paged host search or global transcript search.
- Attachments on demo mode: deliberately unavailable (mock repository throws `unsupported`; UI shows the hint).
- Attachment read-back / image viewer; HEIC normalization; clipboard image paste; print to PDF.
- Device revocation gap (paired-device revoke shipped in 0.2.107; admin re-auth is still open).
- Message paging for very long transcripts (the API supports `before`/`limit`; the UI loads one page).
- Host capability document (presets, models, thinking levels, tools), context meter, per-turn cost, schedules.
- Push notifications (FCM/ntfy), inline question reply, grouped/quiet notifications, notification badges.
- Diff cards, artifact viewer, workspace file browser, save-to-device.
- Performance work: no baseline profile, startup benchmark or stream render-budget pass in `app/src`.

## Top-25 catalog map

Statuses: `shipped` / `partial` / `planned`. Nothing in the top 25 is entirely absent; "partial" names the gap.

| # | Catalog idea | Status | Notes (verified against source) |
|---|---|---|---|
| 1 | #1 Resumable SSE with cursor replay + snapshot fallback | shipped | `since` cursor, backoff, refetch after gap |
| 2 | #2 Snapshot/delta reconciliation | shipped | upsert by id; refetch after `message.completed` |
| 3 | #141 Cache-first session list | shipped | `HostSnapshotCache` paints the list before refresh |
| 4 | #4 Per-session draft persistence | shipped | `DraftStore`; restored on open; accepted attachment ids also restored from `PendingAttachmentStore` (0.2.68) |
| 5 | #3 Incremental markdown + code copy | partial | blocks + per-block copy; no tables/task lists, no tail-only parse |
| 6 | #5 Real stop/interrupt control | partial | Stop calls `/interrupt`; session flips idle locally, no "stopping…" proof |
| 7 | #54 Approval card | partial | command + allow/deny; no note, copy or danger highlighting |
| 8 | #55 Question card | partial | option buttons only; free-text answers not exposed |
| 9 | #57 Approvals + questions inbox | shipped | Inbox screen + "Inbox · N waiting" (needs host bridge for live data) |
| 10 | #8 Message action sheet | partial | copy/share/quote/read aloud; no select-text |
| 11 | #10 Jump-to-latest streaming | shipped | follow mode + "↓ Latest" pill |
| 12 | #6 Client send queue | shipped | local, persisted queue while working with per-item cancel, tap-to-edit, move-up/down reordering, "clear", and a queued count on the session row; one message is sent when any session goes idle, even if its chat is closed |
| 13 | #7 Retry last prompt | partial | one-tap retry for a failed send/upload, not a re-run of the last accepted turn |
| 14 | #12 Attachment tray with preflight | shipped | chips, remove, retry, 8-pending cap, 10 MB preflight |
| 15 | #23 Camera → upload → prompt | partial | `TakePicturePreview` thumbnail only, no full-resolution capture |
| 16 | #44 Create session + preset picker | partial | workspace + 3 hard-coded presets; no host preset list, no starter prompts |
| 17 | #43 Workspace switcher | shipped | workspace chips filter the list and set the create target |
| 18 | #75 In-session find | partial | loaded messages only; no highlights inside rich text |
| 19 | #113 Keystore token storage | shipped | AES-256-GCM key in Android Keystore |
| 20 | #95 Deep-link notifications + inline approve | partial | deep links work; Allow/Deny actions open the app instead of deciding without it; no scroll to the exact message |
| 21 | #135 Share-sheet ingest | partial | text + images only; no PDFs/other files; no sender verification |
| 22 | #161 Quick-actions home widget | partial | open/new/usage; no camera or mic action |
| 23 | #48 Local pin/star sessions | shipped | local pin to top; no separate star, no aliases, no host pinning |
| 24 | #148 Sync status strip | shipped | Live/Connecting/Offline pills, reconnect bar, "updated HH:mm" |
| 25 | #182 Pairing doctor | partial | pre-pairing URL test + diagnostics screen; no Tailscale, clock-skew or token-validity checks |

## Verification notes and limits

- Verified by reading `app/src/main` (Kotlin + manifest + resources) and `host-plugin/lib`; release/version from
  `app/build.gradle.kts` and `git log`. Only 0.2.x versions are referenced.
- 0.2.39 build/lint PASS; independent QA (`qa/round27-verification.md`) verified background→resume auto-heal
  to Live without tapping Reconnect, airplane-mode recovery, permission-denial dictation, and no crashes.
  Connection diagnostics was then exercised on-device showing the recent-events log (`Reconnect: network change`,
  `Network: Wi-Fi available`).
- 0.2.41 build/lint PASS; offline transcript seeding is source-reviewed and compile-tested, but the actual
  airplane-mode/offline-open path was not exercised on the emulator (its virtual host route stays reachable).
- 0.2.42 build/lint PASS; on emulator-5562, a working-session prompt was queued locally, cancelled via
  the X, then a second prompt was queued and only that one appeared (no first-message backlog); the
  test prompts were both cancelled and never delivered to the host.
- 0.2.44 build/lint PASS; cached-message search is compile/source verified but the search UI was not
  exercised on-device because the shared emulator disconnected before the smoke test.
- 0.2.45 build/lint PASS; on emulator-5562 the new Settings → Voice toggle rendered and persisted.
  Actual offline speech recognition was not exercised (the emulator speech service availability is
  unreliable); the error path is source-reviewed.
- 0.2.46 build/lint PASS; the pagination control is compile/source verified, but the current live sessions
  reported no `nextCursor` during the smoke test, so an actual older page was not fetched on-device.
- 0.2.47 build/lint PASS; the markdown table parser/renderer is compile/source verified only (no table
  message was present in a live session during the smoke run).
- 0.2.48 build/lint PASS; local-alias persistence/UI is compile/source verified only (the shared emulator
  disconnected before the long-press rename smoke test).
- 0.2.49 build/lint PASS; quiet-hours window math is source-reviewed, but no real overnight notification
  suppression was exercised (the shared emulator was unavailable during the smoke run).
- 0.2.50 build/lint PASS; the full-resolution camera path is compile/source verified only — the system
  camera flow was not exercised on the emulator.
- 0.2.51 build/lint PASS; task-list parsing/rendering is compile/source verified only (no task-list
  message in the live session during the smoke run).
- 0.2.52 build/lint PASS; on emulator-5562 the title sort reordered the ledger alphabetically and
  persisted (`session_sort=TITLE` in preferences).
- 0.2.53 build/lint PASS; the widget queue suffix is compile/source verified only (no widget host was
  placed on the emulator during the smoke run).
- 0.2.54 build/lint PASS; message-clock rendering is compile/source verified only (the emulator was
  asleep during the screenshot attempt).
- 0.2.55 build/lint PASS; the per-session mute toggle and notification gate are compile/source verified
  only (no notification was generated from a muted session during the smoke run).
- 0.2.56 build/lint PASS; dynamic shortcut publication is compile/source verified only — the emulator
  disconnected before the launcher shortcut list could be inspected.
- 0.2.57 build/lint PASS; background queue delivery is compile/source verified only — exercising it
  would have delivered a test prompt into a live user session, so it was not run.
- 0.2.58 build/lint PASS; the AMOLED toggle/color scheme is compile/source verified only (emulator
  unavailable for a screenshot during the smoke run).
- 0.2.59 build/lint PASS; foreground dedupe is compile/source verified only — no foreground turn
  transition was generated during the smoke run.
- 0.2.60 build/lint PASS after switching the API 34+ path to the PendingIntent overload; the tiles are
  manifest/service verified only (not added to a live Quick Settings panel in the smoke run).
- 0.2.61 build/lint PASS; the lock grace period is compile/source verified only — enabling app lock
  requires a secure device credential, which the test emulator did not have.
- 0.2.62 build/lint PASS; low-credit alerting is compile/source verified only — the live balance was
  above every threshold, so no low-balance notification was triggered.
- 0.2.63 build/lint PASS; the recognition-language picker is compile/source verified only (speech
  recognition needs a real device/engine to observe the language change).
- 0.2.64 build/lint PASS; collapse/show-more is compile/source verified only — the live transcript used
  for the smoke run had no >1,400-character settled assistant reply.
- 0.2.65 build/lint PASS; notification grouping and the muted tag are compile/source verified only —
  the smoke run did not generate two simultaneous alerts.
- 0.2.66 build/lint PASS; Continue working is compile/source verified only — triggering it would have
  started a real agent turn in a live session.
- 0.2.67 build/lint PASS; the unsupported-bridge notice is compile/source verified only (the host
  still reports 501, so the Inbox fallback is reachable but was not screenshotted).
- 0.2.68 build/lint PASS; restoring host-accepted attachments after a process restart is
  compile/source verified only (the smoke run did not background/reopen mid-attachment).
- 0.2.69 build/lint PASS; the widget Inbox action and waiting suffix are compile/source verified only
  (no widget host was placed on the emulator during the smoke run).
- 0.2.70 build/lint PASS; chat text sizing is compile/source verified only (no device screenshot was
  taken for the larger scale).
- 0.2.72 build/lint PASS; the 30 s Usage auto-refresh is compile/source verified only (the smoke run
  did not keep the dashboard open long enough to observe two refreshes).
- 0.2.73 build/lint PASS; code wrapping is compile/source verified only (no device screenshot compared
  the wrapped and scrolling modes).
- 0.2.74 build/lint PASS; queue reordering is compile/source verified only (no two-item queue was
  reordered against a live host in the smoke run).
- 0.2.75 build/lint PASS; the TODAY token estimate is compile/source verified only (the baseline/card
  were not compared against a fresh midnight rollover).
- 0.2.76 build/lint PASS; last-sync/error display is compile/source verified only (background sync was
  not left running across a poll failure in the smoke run).
- 0.2.78: full `testDebugUnitTest` PASS (69 tests; Base64JsonBodyTest now also checks monotonic
  progress), `assembleDebug` + `lintDebug` PASS. The percentage was not observed over a real 43 MB
  Tailscale upload.
- 0.2.79: full `testDebugUnitTest` PASS (69 tests), `assembleDebug` + `lintDebug` PASS; the
  upload-cancel path was not exercised against a live 43 MB transfer (unit/compile verified).
- 0.2.80: host-plugin `node --test` **125/125 PASS**; app `testDebugUnitTest` **71 tests, 0 failures**;
  `assembleDebug` + `lintDebug` PASS. Live GET goal verified on the running host; live Resume was not
  pressed (it would re-arm this session's goal), but the route and adapter resume path are unit-tested.
- 0.2.81: host-plugin `node --test` **126/126 PASS**; app tests/build/lint PASS. Live host verified:
  GET goal (active/armed, 62/120) and the pause route answers 400 for a non-live session; the active
  goal was not paused by the test.
- 0.2.82: app tests/build/lint PASS; the thumbnail decoder is compile/source verified only (no image
  attachment was picked on a device during the smoke run).
- 0.2.83: app tests/build/lint PASS; cache stats/clear is compile/source verified only (destructive
  path was not exercised on a device).
- 0.2.84: host-plugin `node --test` **127/127 PASS**; app tests/build/lint PASS; live route reloaded.
  Live create probe on the active session correctly returned `400 A goal already exists…` without
  changing it; a non-live session returned 400 `no live agent`.
- 0.2.85: app `testDebugUnitTest` **72 tests, 0 failures**; host-plugin `node --test` **130/130 PASS**;
  fake-host goal test **12/12 PASS**; `assembleDebug` + `lintDebug` PASS. Host plugin hot-reloaded;
  the new `goal.changed` emission is covered by host unit tests but not observed over a live SSE
  stream; interface-scale/widget-goal/share-diagnostics UI are compile-verified only.
- 0.2.86: app `testDebugUnitTest` **79 tests, 0 failures, 1 env-gated skip**; host-plugin
  `node --test` **135/135 PASS**; fake-host goal test **18/18 PASS**; `assembleDebug` + `lintDebug`
  PASS. Host plugin hot-reloaded; live edit/clear probes on a non-live session returned 400 without
  mutating the active goal. Goal edit/clear dialogs, goal notifications and the success paths are
  compile/source verified only (not run on a device; active goal deliberately untouched).
- 0.2.87: app `testDebugUnitTest` **83 tests, 0 failures, 1 env-gated skip**; host-plugin
  `node --test` **138/138 PASS**; fake-host goal test **23/23 PASS**; `assembleDebug` + `lintDebug`
  PASS. Host hot-reloaded and `GET /sessions` live-verified to return the active goal summary
  (active, 67/120). Badge rendering and the goal-alert toggle are compile/source verified only.
- 0.2.89: app `testDebugUnitTest` **91 tests, 0 failures, 1 env-gated skip**; host-plugin
  `node --test` **158/158 PASS**; fake-host goal **23/23**, approvals **8/8**, questions **7/7**;
  `assembleDebug` + `lintDebug` PASS. Host hot-reloaded and live `GET /questions` + `/approvals`
  both verified as `{..., supported:true, readOnly:true}`. No real approval/question was pending, so
  waterfall registration and read-only cards remain fake-ctx/compile-verified only.
- 0.2.90: app `testDebugUnitTest` **96 tests, 0 failures, 1 env-gated skip**; host-plugin
  **158/158**; fake-host goal **26/26**, approvals **8/8**, questions **7/7**;
  `assembleDebug` + `lintDebug` PASS. Goals dashboard and fake-host fixtures are compile/unit
  verified only — no device screenshot and no live multi-goal host.
- 0.2.91: app `testDebugUnitTest` **98 tests, 0 failures, 1 env-gated skip**; host-plugin
  **160/160**; fake-host goal **27/27**, approvals **8/8**, questions **7/7**;
  `assembleDebug` + `lintDebug` PASS. Host hot-reloaded and live `GET /sessions` verified returning
  real goal objectives for six goal sessions; dashboard UI and non-live projection freshness are
  unit/source verified only.
- 0.2.92: app `testDebugUnitTest` **104 tests, 0 failures, 1 env-gated skip**; host-plugin
  **163/163**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**;
  `assembleDebug` + `lintDebug` PASS. Goal/complete was verified by host route tests and a hermetic
  app route test; no live goal was mutated and the Complete dialog was not device-tested.
- 0.2.93: app `testDebugUnitTest` **109 tests, 0 failures, 1 env-gated skip**; host-plugin
  **173/173**; fake-host goal **30/30**, approvals read-only **8/8**, questions **7/7**,
  approvals-answer **7/7**; `assembleDebug` + `lintDebug` PASS. Host hot-reloaded and
  `GET /approvals` verified as `readOnly:false`; no real approval was pending, so the race was
  fake-ctx/app-error-path verified only.
- 0.2.96: app `testDebugUnitTest` **124 tests, 0 failures, 1 env-gated skip**; host-plugin
  **188/188**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **7/7**; `assembleDebug` + `lintDebug` PASS.
  Host hot-reloaded and the attachment route plus session-message metadata were live-checked;
  historical chips/FileProvider open were not device-tested.
- 0.2.97: app `testDebugUnitTest` **131 tests, 0 failures, 1 env-gated skip**; host-plugin
  **188/188**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **8/8**; `assembleDebug` + `lintDebug` PASS.
  Thumbnail decode/IO paths are Android-only and only their pure helpers are JVM-tested; no device
  UI was observed.
- 0.2.98: app `testDebugUnitTest` **140 tests, 0 failures, 1 env-gated skip**; host-plugin
  **197/197**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **8/8**, schedules **8/8**; `assembleDebug` +
  `lintDebug` PASS. Host hot-reloaded and live `GET /schedules` verified on real host reminders
  (one inactive row); invalid create 400 and unknown delete 404; no schedule was created/deleted live.
- 0.2.99: app `testDebugUnitTest` **144 tests, 0 failures, 1 env-gated skip**; host-plugin
  **202/202**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **8/8**, schedules **12/12**; `assembleDebug` +
  `lintDebug` PASS. Host hot-reloaded and live `GET /schedules`/PATCH validation were checked; no live
  schedule was edited or created.
- 0.2.100: app `testDebugUnitTest` **150 tests, 0 failures, 1 env-gated skip**; host-plugin
  **208/208**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **8/8**, schedules **12/12**, session management
  **8/8**; `assembleDebug` + `lintDebug` PASS. Host hot-reloaded: `GET /sessions/archived`
  returned supported:true, cold rename 400, and a temporary archive of the test session was
  immediately restored (verified 0 archived, 15 sessions).
- host 0.3.0: host-plugin `test/*.test.mjs` **307/307**; app still **336 tests, 0 failures, 1 skip**;
  fake-host **175/175 across 18 suites**. Live: Bao-Chinese session exposes 2 `ext_` images;
  `lesson-pinyin.png` downloaded through the host proxy as **36,213-byte PNG** (HTTP 200, image/png, valid magic)
  after reload; local `ws_` image still serves 655,122-byte PNG. No APK change (host-only feature).
- 0.2.136: app `testDebugUnitTest` **477 tests, 0 failures, 1 env-gated skip**; host-plugin
  **345/345**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.13:
  current session returned **surface 583,128 / window 1,048,576 / pressure 690,704 → 65.9%**, breakdown
  system 2,177 / tools 20,041 / messages 580,951 (no sampledAt in the real row). Context meter UI/TalkBack is
  JVM/static tested only (no device).
- 0.2.135: app `testDebugUnitTest` **464 tests, 0 failures, 1 env-gated skip**; host-plugin
  **342/342**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. No live host change this
  round. The two-pane layout/rail/insets/rotation and the selected accent bar are JVM/static tested only — no
  tablet or landscape device pass.
- 0.2.134: app `testDebugUnitTest` **460 tests, 0 failures, 1 env-gated skip**; host-plugin
  **342/342**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.12:
  jobs scanned across 16 sessions and a 200-event trajectory contained **0 provider-token matches**. Accent theme
  rendering/contrast is computed + JVM tested only; no device visual pass.
  **Action required:** a real credential was exposed in session/job history before this fix; rotate/revoke it.

- 0.2.133: app `testDebugUnitTest` **451 tests, 0 failures, 1 env-gated skip**; host-plugin
  **342/342**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.11:
  `GET /sessions/{id}/jobs` returned **2 running / 2560 completed / 0 failed** jobs, **totalJobs 2562**,
  `truncated:true`, with paths/tokens redacted in labels. Jobs UI/TalkBack is JVM/static tested only (no device).
- 0.2.132: app `testDebugUnitTest` **439 tests, 0 failures, 1 env-gated skip**; host-plugin
  **339/339**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. No live host change this
  round. Share-card Canvas rendering, FileProvider chooser and RTL/emoji layout remain device-unverified; helper
  tests cover caps, redaction, code-point truncation, filenames, markdown stripping and FileProvider XML wiring.
- 0.2.131: app `testDebugUnitTest` **424 tests, 0 failures, 1 env-gated skip**; host-plugin
  **339/339**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.10:
  `GET /sessions/{id}/files` listed **28 entries**, omitted `.credentials.yaml`, and `/files/content?path=.credentials.yaml`
  returned **404**; trajectory scan 100 events = 0 absolute paths. File-browser UI/preview/TalkBack are
  JVM/static tested only (no device).
- 0.2.130: app `testDebugUnitTest` **412 tests, 0 failures, 1 env-gated skip**; host-plugin
  **337/337**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.9:
  trajectory returned 20 newest events for this session in ~4.9 s with `hasMore:true` and `nextBeforeSeq`; tool
  summaries visible with credential redaction. UI/TalkBack is JVM/static tested only (no device).
- 0.2.129: app `testDebugUnitTest` **401 tests, 0 failures, 1 env-gated skip**; host-plugin
  **333/333**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.8:
  this session returned **supported:true, 194 completed tasks, 7 agents (all phase active), 0 active tasks**;
  first fetch ~5.7 s because it scans the session log (30 s app cache). Team UI/TalkBack is JVM/static tested
  only (no device).
- 0.2.128: app `testDebugUnitTest` **391 tests, 0 failures, 1 env-gated skip**; host-plugin
  **330/330**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.7:
  the scheduled session transcript now exposes its scheduled prompt (`source:"schedule"`), search q=bot is back
  to 5 hits/14 sessions in ~5.8 s, and scheduled/preview/search exclusion is test-verified. Scheduled label
  layout/TalkBack is JVM/static tested only (no device).
- 0.2.127: app `testDebugUnitTest` **385 tests, 0 failures, 1 env-gated skip**; host-plugin
  **325/325**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.6:
  `GET /sessions/{id}/todos` returned `supported:true` with 4 completed plan items for this session (first fetch
  **~4.2 s** because the host scans the whole session log; the app caches for 30 s). Plan UI/TalkBack is
  JVM/static tested only (no device).
- 0.2.126: app `testDebugUnitTest` **376 tests, 0 failures, 1 env-gated skip**; host-plugin
  **322/322**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.5:
  `/search?q=bot` hits carried real seqs (`m_ev_13394`, …) and session detail carried seqs on all 100 loaded
  messages; the first sampled hit was older than the first page, so jumping it depends on the bounded older-page
  loading (not yet device-tested).
- 0.2.125: app `testDebugUnitTest` **366 tests, 0 failures, 1 env-gated skip**; host-plugin
  **321/321**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.4:
  **6/14 sessions carried delegation counts** (Sword and Seal 35 children, current session **9**, etc.; working 0
  because those subagents are inactive), and no child ids/titles were exposed. Badge layout/TalkBack are
  JVM/static tested only (no device).
- 0.2.124: app `testDebugUnitTest` **360 tests, 0 failures, 1 env-gated skip**; host-plugin
  **319/319**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.3:
  `PUT /push/suppress` round-trip returned exactly `{quietHoursEnabled,quietStartHour,quietEndHour,timeZone,
  timeZoneValid,mutedCount}` and `GET /push` carried it; restored to disabled after the check. App sync/PUT is
  JVM/static tested only (no device/settings round-trip).
- 0.2.123: app `testDebugUnitTest` **351 tests, 0 failures, 1 env-gated skip**; host-plugin
  **315/315**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.2:
  `GET /sessions/{id}/skills` returned **supported:true with 14 real skills** (agent-experience,
  android-ui-automation, cordis-composition-reference, …) for the current session. Skills UI/prefill/cursor and
  TalkBack are JVM/static tested only (no device).
- 0.2.122: app `testDebugUnitTest` **344 tests, 0 failures, 1 env-gated skip**; host-plugin
  **312/312**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live host 0.3.1:
  all 14 visible sessions now carry `agentPreset: cordis`; no forked session existed to show a parent live.
  Live images still work (local `ws_` 655,122 PNG; remote `ext_` 36,213 PNG). Lineage UI/button is
  JVM/static tested only (no device).
- 0.2.121: app `testDebugUnitTest` **336 tests, 0 failures, 1 env-gated skip**; host-plugin
  **297/297**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live: the actual
  Nine Heavens Ascension session now lists two `ws_` image attachments from `C:\Users\Austin\tools\...` and
  `hero-v5b-in-stage.png` downloads as a valid 655,122-byte PNG through the attachment route.
- 0.2.120: app `testDebugUnitTest` **330 tests, 0 failures, 1 env-gated skip**; host-plugin
  **290/290**; fake-host **175/175 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live tool output
  was not produced by a real session after reload; the viewer sheet is JVM/static tested only.
- 0.2.119: app `testDebugUnitTest` **324 tests, 0 failures, 1 env-gated skip**; host-plugin
  **288/288**; fake-host **172/172 across 18 suites**; `assembleDebug` + `lintDebug` PASS. PDF rendering,
  CJK/emoji pagination and sharing are JVM/static tested only (no device render); live search coverage was
  verified against host 0.2.7 (`q=bot` 14/14, `q=the` 11/14 with the incomplete flag).
- 0.2.118: app `testDebugUnitTest` **311 tests, 0 failures, 1 env-gated skip**; host-plugin
  **288/288**; fake-host **172/172 across 18 suites**; `assembleDebug` + `lintDebug` PASS. Live search
  after reload: `q=bot` 5 hits, all 14 sessions scanned, 5.5 s; `q=the` 5 hits, 11/14 scanned, 4.2 s,
  incomplete signalled; save/copy MediaStore/SAF/clipboard is JVM/static tested only (no device).
- 0.2.117: app `testDebugUnitTest` **300 tests, 0 failures, 1 env-gated skip**; host-plugin
  **283/283**; fake-host **172/172 across 18 suites** (search **16**); `assembleDebug` + `lintDebug`
  PASS. Pagination was verified by host/fake/JVM tests; the live host was not reloaded with it, and the
  Load more UI was not device-tested.
- 0.2.116: app `testDebugUnitTest` **288 tests, 0 failures, 1 env-gated skip**; host-plugin
  **280/280**; fake-host **169/169 across 18 suites**; `assembleDebug` + `lintDebug` PASS.
  Inline reply is JVM/static tested; lock-screen RemoteInput and TalkBack behavior were not
  device-verified.
- 0.2.115: app `testDebugUnitTest` **279 tests, 0 failures, 1 env-gated skip**; host-plugin
  **280/280**; fake-host **169/169 across 18 suites**; `assembleDebug` + `lintDebug` PASS. The fix is
  construction-order logic verified by build/JVM tests and the reported stack trace; it was not reproduced
  on a physical device in this round.
- 0.2.114: app `testDebugUnitTest` **279 tests, 0 failures, 1 env-gated skip**; host-plugin
  **280/280**; fake-host **169/169 across 18 suites**; `assembleDebug` + `lintDebug` PASS.
  Safe-mode contrast/TalkBack and the add-host Tailscale path were code/JVM tested only; no device
  verification was performed.
- 0.2.113: app `testDebugUnitTest` **278 tests, 0 failures, 1 env-gated skip**; host-plugin
  **280/280**; fake-host **169/169 across 18 suites** (tailscale 7, recovery 8, search 13, fork 7,
  queue 9, models 9, devices 6, approval-policy 8, push 7, presets 5, sessions 8, schedules 12,
  attachments 9, goal 30, approvals 8, questions 7, answers 7+9); `assembleDebug` + `lintDebug` PASS.
  Safe mode is JVM/static checked only: no device or forced-crash run was performed.
- 0.2.112: app `testDebugUnitTest` **268 tests, 0 failures, 1 env-gated skip**; host-plugin
  **280/280**; fake-host tailscale **7/7**, recovery **8/8**, search **13/13**, fork **7/7**, queue
  **9/9**, models **9/9**, devices **6/6**, approval-policy **8/8**, push **7/7**, presets **5/5**,
  sessions-management **8/8**, schedules **12/12**, attachments **9/9**, goal **30/30**,
  approvals **8/8**, questions **7/7**, answers **7+9**; `assembleDebug` + `lintDebug` PASS.
  Auto-pair was not exercised with a real Tailscale Serve identity; no live pairing/mutation performed.
- 0.2.111: app `testDebugUnitTest` **262 tests, 0 failures, 1 env-gated skip**; host-plugin
  **277/277**; fake-host recovery **8/8**, search **13/13**, fork **7/7**, queue **9/9**, models **9/9**,
  devices **6/6**, approval-policy **8/8**, push **7/7**, presets **5/5**, sessions-management **8/8**,
  schedules **12/12**, attachments **9/9**, goal **30/30**, approvals **8/8**, questions **7/7**,
  answers **7+9**; `assembleDebug` + `lintDebug` PASS. Recovery live endpoints were not exercised with a
  real code; the restore/deep-link UI was not device-tested.
- 0.2.110: app `testDebugUnitTest` **240 tests, 0 failures, 1 env-gated skip**; host-plugin
  **265/265**; fake-host fork **7/7**, queue **9/9**, models **9/9**, devices **6/6**, approval-policy
  **8/8**, push **7/7**, search **9/9**, presets **5/5**, sessions-management **8/8**, schedules
  **12/12**, attachments **9/9**, goal **30/30**, approvals **8/8**, questions **7/7**, answers **7+9**;
  `assembleDebug` + `lintDebug` PASS. Fork routes were not exercised live (no session was created); the UI
  was not device-tested.
- 0.2.109: app `testDebugUnitTest` **234 tests, 0 failures, 1 env-gated skip**; host-plugin
  **262/262**; fake-host queue **9/9**, models **9/9**, devices **6/6**, approval-policy **8/8**, push
  **7/7**, search **9/9**, presets **5/5**, sessions-management **8/8**, schedules **12/12**,
  attachments **9/9**, goal **30/30**, approvals **8/8**, questions **7/7**, answers **7+9**;
  `assembleDebug` + `lintDebug` PASS. Queue reads and cold rules were verified live after reload; no PATCH
  was performed, so edit/remove/steer and the dialog were not device-tested.
- 0.2.108: app `testDebugUnitTest` **223 tests, 0 failures, 1 env-gated skip**; host-plugin
  **256/256**; fake-host models **9/9**, devices **6/6**, approval-policy **8/8**, push **7/7**, search
  **9/9**, presets **5/5**, sessions-management **8/8**, schedules **12/12**, attachments **9/9**,
  goal **30/30**, approvals **8/8**, questions **7/7**, answers **7+9**; `assembleDebug` + `lintDebug` PASS.
  Model catalog/cold-fold were verified live after reload; no PUT was performed, so live selection and the
  dialog were not device-tested.
- 0.2.107: app `testDebugUnitTest` **209 tests, 0 failures, 1 env-gated skip**; host-plugin
  **244/244**; fake-host devices **6/6**, approval-policy **8/8**, push **7/7**, search **9/9**,
  presets **5/5**, sessions-management **8/8**, schedules **12/12**, attachments **9/9**,
  goal **30/30**, approvals **8/8**, questions **7/7**, answers **7+9** (full fake suite **125**);
  `assembleDebug` + `lintDebug` PASS. Device flows are host/app tested; live revoke and the UI were not
  exercised on a device.
- 0.2.106: app `testDebugUnitTest` **200 tests, 0 failures, 1 env-gated skip**; host-plugin
  **239/239**; fake-host approval-policy **8/8**, push **7/7**, search **9/9**, presets **5/5**,
  sessions-management **8/8**, schedules **12/12**, attachments **9/9**, goal **30/30**,
  approvals **8/8**, questions **7/7**, answers **7+9**; `assembleDebug` + `lintDebug` PASS.
  Policy is host/app tested; the dialog and live write path were not device-tested.
- 0.2.105: app `testDebugUnitTest` **192 tests, 0 failures, 1 env-gated skip**; host-plugin
  **236/236**; fake-host push **7/7**, search **9/9**, presets **5/5**, sessions-management **8/8**,
  schedules **12/12**, attachments **9/9**, goal **30/30**, approvals **8/8**, questions **7/7**,
  answers **7+9**; `assembleDebug` + `lintDebug` PASS. Push config/validation is unit/I-O tested;
  real ntfy delivery and phone subscriptions were not exercised live.
- 0.2.104: app `testDebugUnitTest` **182 tests, 0 failures, 1 env-gated skip**; host-plugin
  **223/223**; fake-host search **9/9**, presets **5/5**, session management **8/8**, schedules
  **12/12**, attachments **9/9**, goal **30/30**, approvals **8/8**, questions **7/7**, answers
  **7+9**; `assembleDebug` + `lintDebug` PASS. Backup is JVM tested only (SAF UI not device-tested);
  Live `/search` re-measured after reload: `release` 3.24 s/2 hits, `bot` 2.98 s/50 hits, both truncated.
- 0.2.103: app `testDebugUnitTest` **171 tests, 0 failures, 1 env-gated skip**; host-plugin
  **221/221**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **9/9**, schedules **12/12**, session management
  **8/8**, presets **5/5**, search **9/9**; `assembleDebug` + `lintDebug` PASS. Host hot-reloaded
  and live `GET /search` measured below; viewer/search UI paths were not device-tested.
- 0.2.102: app `testDebugUnitTest` **163 tests, 0 failures, 1 env-gated skip**; host-plugin
  **214/214**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **9/9**, schedules **12/12**, session management
  **8/8**, presets **5/5**; `assembleDebug` + `lintDebug` PASS. Viewer zoom/paging logic is
  JVM-tested; decode/render and gestures were not device-tested.
- 0.2.101: app `testDebugUnitTest` **156 tests, 0 failures, 1 env-gated skip**; host-plugin
  **214/214**; fake-host goal **30/30**, approvals **8/8**, questions **7/7**, approvals-answer
  **7/7**, questions-answer **9/9**, attachments **8/8**, schedules **12/12**, session management
  **8/8**, presets **5/5**; `assembleDebug` + `lintDebug` PASS. Host hot-reloaded and live
  `GET /presets` verified against the real roster; no session was created to test the preset id.
- Still not runtime-verified: live approvals/questions, push, real-device streaming,
  real Gallery-sender image share, transcript file sharing to an actual receiving app, and a real
  Tailscale/VPN hand-off on a phone.
- Known nits: exported transcript files are not pruned; interval changes apply from the next poll cycle;
  cached transcripts are refreshed only when the session is reopened, and the cache is not encrypted.
