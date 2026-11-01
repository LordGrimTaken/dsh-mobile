# DSH Mobile API v1

Status: **draft, v0.2 target**. Owner: Lead. Consumers: Android client (`app/`), host plugin (`host-plugin/`), fake host (`tools/fake-host/`).

The phone talks to a **host plugin installed in the desktop DSH profile**. The plugin runs its own HTTP server on `127.0.0.1:3543` (loopback only) and is exposed to the phone by Tailscale:
`https://<pc>.<tailnet>.ts.net` → `http://127.0.0.1:3543` (Tailscale Serve terminates TLS).

The plugin never exposes DSH's own web server or launch token. It has its own device-token auth.

## Transport

- HTTP/1.1, JSON UTF-8 (`Content-Type: application/json`).
- Server-Sent Events for streams: `GET /mobile/v1/events` with `Accept: text/event-stream`, last event id as `since` query param.
- All durations are ISO-8601 UTC timestamps.

## Authentication

- `Authorization: Bearer <deviceToken>` on every endpoint **except** `GET /health`, `GET /pair/methods`, `POST /pair/complete`, `POST /pair/recovery` and `POST /pair/tailscale`.
- `POST /pair/new` is additionally restricted to loopback peer addresses (the desktop calls it).
- Device tokens are random 32-byte base64url values. The host stores only a SHA-256 hash plus device metadata.
- Failed pairing attempts are rate limited (5/minute per IP, code valid 5 minutes, one use).

**Device management.** `GET /devices` lists paired devices (id, name, platform, paired/last-seen times,
never token material) and the calling device's id; `DELETE /devices/{id}` revokes another device. Revocation
is awaited and persisted, removed devices immediately fail auth (including their open SSE stream), and a
persistence failure rolls back and returns 500. Self-revocation is refused (400). Any paired device is trusted
equally and can revoke any other; there is no admin role, re-auth, or audit log.

## Pairing flow

1. Desktop (a helper script, later a settings panel) calls the loopback endpoint:

   `POST /mobile/v1/pair/new` → `200`
   ```json
   {
     "code": "482913",
     "expiresAt": "2026-10-30T10:05:00Z",
     "qrPayload": "dshmobile://pair?base=https%3A%2F%2Fpc.tailnet.ts.net&code=482913&host=pc"
   }
   ```

2. Phone (manual entry first, QR scanner later) calls:

   `POST /mobile/v1/pair/complete`
   ```json
   { "code": "482913", "deviceName": "Pixel 9", "platform": "android", "publicKey": "optional-base64url" }
   ```
   → `200`
   ```json
   {
     "token": "…base64url…",
     "deviceId": "dev_01J…",
     "host": { "name": "pc", "apiVersion": 1, "version": "0.2.0" },
     "workspaces": [
       { "id": "ws_1", "name": "DeepSeek Harness", "path": "C:\\.dsh" }
     ]
   }
   ```

3. Phone stores `{baseUrl, token, deviceId}` locally and reconnects with the bearer token.

**Recovery pairing.** A paired phone can generate a long-lived, single-use recovery code while still paired:
`GET /recovery` (bearer) → `{supported, configured, createdAt?}` (never the code/hash); `POST /recovery`
(bearer) rotates and returns `{code, createdAt}` once. After clearing app data or reinstalling, the phone
redeems it without a desktop code via `POST /pair/recovery` (public, like `/pair/complete`) body
`{code, deviceName?, platform?}` → `{token, device, recoveryCode, ...}`; success consumes and rotates the
code, returning the next one to save. The code is 256 bits (52 base32 chars, spaces/dashes ignored), stored
only as a SHA-256 hash in the main atomic state file, compared timing-safely, rate-limited per IP, and works
even with zero devices paired. Treat it like a password; it is pairing-equivalent authority until consumed.

**Tailscale one-tap pairing.** `GET /pair/methods` (public) → `{desktopCode, recovery, tailscaleAutoPair,
tailscaleLoginRequired}`. When the host is reached through Tailscale Serve, Serve may forward the request's
tailnet identity (`Tailscale-User-Login`, falling back to `Tailscale-User-Name`); `POST /pair/tailscale`
(public) `{deviceName?, platform?}` mints a normal device token from that identity and returns the same shape
as `/pair/complete` plus `pairingMethod:"tailscale"` and `identity` — no 6-digit code. The host only trusts the
header because it binds `127.0.0.1` behind the Serve proxy; the **tailnet ACL is the trust boundary**. If the
header is missing (host not behind Serve or Tailscale too old) the route returns 400
`tailscale_identity_missing` and clients fall back to a desktop code or recovery link. Optional config
`pairing.tailscaleAutoPair:false` disables it and `pairing.tailscaleLogin:"you@example.com"` restricts it to one
login. It is rate-limited with the other pairing methods.

## Sessions

`GET /mobile/v1/sessions` returns **user-created sessions only**. Internal sessions — those
created by Agent Team tools/subagents — are hidden by default so they never clutter the phone;
they continue to exist and run inside their parent session. Detection uses the session header
(`origin: "subagent"`, `parentSessionId`, `delegationDepth`), the `subagent` projection, and a
documented bare-UUID id fallback. Their SSE events are suppressed as well. Set
`sessions.hideInternal: false` in the plugin config to list everything; `GET /sessions/{id}` keeps
working for an internal session if addressed directly.

**Host message search.** `GET /search?q=&limit=&role=&since=&until=` scans visible (non-hidden, non-internal,
non-archived) session messages for a case-insensitive substring over human user text, assistant text,
and tool command detail. Bounds: q 2–200 chars, limit 1–100 (default 50), at most 200 newest-activity
sessions, 2000 events per session, 500 collected hits, and an **8-second whole-request** wall-clock budget
(the list and every snapshot read are bounded by it). Hits are newest-first with a ~200-char single-line
snippet; `truncated:true` means a bound stopped the scan and any hits collected so far are returned. Scheduled deliveries are transcript-only and are excluded from search (their self-identifying framing text is filtered without snapshot reads). Each hit
may also carry an optional integer `seq` (the DSH event sequence) that pairs with the same `seq` on
session-detail messages so clients can jump to the exact message; it is omitted when unknown and never
guessed. There is no index or per-device rate limit yet, so callers should not poll it aggressively.
The 8 s budget is best-effort and the scan reads text-only semantic documents with bounded concurrency;
callers should treat `truncated`/`scanIncomplete` plus partial hits as normal. Optional `role` (comma-separated
`user|assistant|tool`) and inclusive `since`/`until` (ISO-8601 or epoch ms) filters are applied during the
scan; when any filter is set the response echoes `filters` (older hosts ignore the params and omit the echo,
which clients must detect rather than presenting results as filtered). Date-bounded queries exclude messages
without timestamps. Optional `offset` (0..500, applied after newest-first ordering/filters, before `limit`)
paginates the bounded scan: the response carries `offset`, `nextOffset` (`offset + hits.length` when
`hasMore`, else `null`) and `hasMore` (more collected matches beyond the page, or the scan was truncated and
the page returned hits). `truncated` still means the scan cap/budget stopped collection; clients should use
`hasMore`/`nextOffset` to stop and an empty page always ends pagination. Additive `scanIncomplete`, `scannedSessions` and `totalSessions` report whether the bounded scan covered every visible session; at most 50 matches are kept per session so a busy recent session cannot crowd out older ones.

**Session fork.** `POST /sessions/{id}/fork` creates a new session from the source's latest completed
turn (or an explicit `atSeq` durable event sequence). The new session inherits the copied prefix history,
parent metadata and workspace; the source is untouched and never deleted. Session detail messages carry an
optional `seq` so the app can request a precise boundary. Hidden/internal/unknown sources are 404, invalid
or unavailable boundaries 400, absent service 501. A workspace-attach failure is reported as a 200 with the
created `sessionId` and `warning:"workspace-attach-failed"` (the child exists). Forks do not copy live agent
state, goals, schedules, approval policy, queue items, drafts or attachments not in the prefix.

**Host queue.** `GET /sessions/{id}/queue` lists prompts waiting in a live agent's inbox
(`next-turn` then `next-step`; text, attachment count, optional createdAt; max 100). `PATCH
/sessions/{id}/queue/{itemId}` edits text (text-only: attachments would be lost), removes the
item (also retires its prompt uploads), or steers a next-turn item into the running turn. Cold
sessions answer `{live:false, items:[]}` and refuse writes (400) without being activated; unknown
items are 404. Any paired device can see or modify queued prompts (trusted-device model).

**Models and thinking.** `GET /models` returns the host model catalog (provider groups, per-model
reasoning/thinking efforts, isolated provider failures with redacted short messages, truncation flag) and
the deployment default. `GET /sessions/{id}/model` reports a session's selection from the durable
`model/selection`/`request/header` log without activating a cold session (`source` says whether it is a
session override or the host default). `PUT` is live-session only: it validates provider/model/effort through
DSH, installs the selection for the session's next request, and — DSH behavior — saves it as the default for
new sessions in the background. Hidden/internal/unknown sessions are 404; cold PUT is 400.

**Approval policy.** `GET/PUT /sessions/{id}/approval-policy` reads or changes a session's DSH
approval policy (`ask` = approvals may be requested; `never` = actions that need approval are
automatically rejected, for unattended runs). Writes require a live agent (cold sessions answer 400;
they are never activated); cold reads use the durable `approval/policy` log with `ask` as the fallback.
This is independent of whether approvals can be answered from the phone (`approvals.mobileAnswer`).

**ntfy push (optional).** `GET/PUT /push` configure host-published notifications through an ntfy topic
(default server `https://ntfy.sh`; https required, plain http only for loopback). The host posts short
approval/question/goal/turn summaries with `dshmobile://inbox` or `dshmobile://session/<id>` click targets;
the phone must have the ntfy app subscribed to the same topic. The topic is effectively a secret, the
optional access token is stored in plaintext at rest on the host and never returned by GET, delivery is
best-effort (timeout/backoff/30-per-minute cap), and host pushes honor app-local **quiet hours and per-session
mutes** once synced through `PUT /push/suppress` (host 0.3.3); app-local foreground state is still not visible
to the host, and `/push/test` intentionally bypasses suppression. No answer actions are offered from push.

**Push suppression.** `PUT /push/suppress` stores `quietHoursEnabled`, `quietStartHour`/`quietEndHour`
(0–23), an IANA `timeZone`, and up to 500 `mutedSessionIds` alongside the push config. Quiet hours are
evaluated in the configured zone with the local hour (DST-safe; start inclusive, end exclusive,
`start > end` crosses midnight, `start === end` means no window) and suppress all event pushes; muted ids
suppress session-scoped approval/question/turn pushes, while goal/usage pushes are only quiet-suppressed.
The public summary `{quietHoursEnabled, quietStartHour, quietEndHour, timeZone, timeZoneValid, mutedCount}`
is returned by PUT and additively on GET `/push` (no muted-id list, no token).

**Agent presets.** `GET /presets` returns the configured preset roster (read-only, sorted by order then
id) with `isDefault`, optional `name`/`description`, and `broken:true` for presets that failed to
activate. `defaultId` is included when known. The phone sends the chosen preset **id** as `preset` in
`POST /sessions`; an absent preset service answers `{presets:[], supported:false}`.

**Session skills.** `GET /sessions/{id}/skills` lists the user-invocable skills visible to that session's
composition (name + description only; no paths/host data), read through the host skill catalog without
activating a cold agent. Names are sanitized/deduped/sorted and capped at 100, descriptions at 500 chars;
valid name-only entries keep `description:""`. Hidden/internal sessions answer 404; an absent or failing
catalog answers `{supported:false, skills:[]}` rather than an error.

**Context pressure.** `GET /sessions/{id}/context` reads the persisted DSH projection cache read-only and returns only finite token counts plus `percent = clamp(pressureTokens/contextWindow×100)` rounded to one decimal (breakdown buckets and an optional sample time). Missing/malformed/non-regular cache entries return `supported:false`; hidden/internal sessions 404; no agent is activated and no prompts/titles/paths are exposed.

**Background jobs.** `GET /sessions/{id}/jobs` folds the latest `job/registered`/`job/settled` per job id into running-first rows with counts, timestamps/duration and compact detail. Labels/details are credential- and absolute-path-redacted (provider prefixes, TOKEN/SECRET/PASSWORD/API_KEY assignment values, JWTs, long base64; `<redacted>` before length caps); owner/tool-call ids are omitted. No kill/control endpoint exists. Visible sessions only, snapshot read with no agent activation.

**Workspace files.** Read-only `GET /sessions/{id}/files`, `/files/content`, `/files/raw` expose files under the session cwd by relative path: dirs-first listings (500 cap + `truncated`/`entryCount`), text previews (≤1 MB / 200k chars), raw bytes ≤25 MB. Absolute paths, traversal, ADS, symlinks and a credential denylist are rejected with generic errors; hidden/internal sessions 404 and no agent is activated.

**Trajectory journal.** `GET /sessions/{id}/trajectory` returns a newest-first, seq-paginated compact journal (turns/steps, visible user/assistant text, tool calls/results, goal/todo/team/schedule summaries; internal context is `[context] <kind>` with no body; unknown types type-only). Summaries are limited to 300 chars and credential-like values are redacted; no raw payloads or attachment bytes. `limit` 1–200 (default 100), `beforeSeq` exclusive; 501 only when history is unavailable, `supported:false` for a readable session with no eligible events.

**Agent Teams board.** `GET /sessions/{id}/team` folds the latest `team/task` and `team/member` events: active tasks (pending/in progress/blocked) and member name/phase; completed tasks are counts-only. Descriptions, owner ids, member ids and write scopes are never exposed, and only visible sessions are readable without activating an agent.

**Session plan/todos.** `GET /sessions/{id}/todos` returns the latest `todo/write` snapshot as `{supported, updatedAt?, counts, todos:[{content,status}]}` with statuses `pending|in_progress|completed`. Content is sanitized/capped at 500 chars, at most 50 rows, unknowns map to `pending`; `supported:false` means no plan yet. Only visible sessions are readable; the host reads the snapshot without activating an agent (first read can be slow on large logs).

**Curated hidden list.** A persisted set of session ids (default
`<stateFile dir>/hidden-sessions.json`, override `sessions.hiddenFile`) is excluded from the list
and SSE exactly like internal sessions, but the sessions remain fully addressable (detail, prompt,
attachments). Manage it with `GET`/`PUT /mobile/v1/sessions/hidden`. This is how a user curates
their list without deleting anything; it is device-agnostic and survives host restarts.

**Host rename/archive.** `POST /sessions/{id}/rename` is deliberately live-session-only (DSH's rename
path resumes a session, so cold sessions answer `400 invalid_request` instead of being activated).
`archive`/`unarchive` use the workspace controller and never send `stopActivity`: a session with active
work answers `400 invalid_request`; ordinary visible sessions only (hidden/internal → 404). Archived
sessions disappear from `GET /sessions`, stay addressable by detail/prompt, and are listed by
`GET /sessions/archived` (same summary shape, no goal enrichment).

**Delegation counts (host 0.3.4).** `delegation` is an additive aggregate `{total, working}` of internal direct child sessions (archived children excluded; `working` best-effort from the raw list `running` flag). It is omitted when there are none or the count cannot be determined; only numbers are exposed, never child ids/titles/prompts.

**Lineage/preset (host 0.3.1).** `parentSessionId` is exposed only when the parent exists and is not
hidden/internal/archived (self-parents are dropped); `agentPreset` follows the latest
`agent-preset/selected` event, then the creation header, and is omitted when unknown. Both fields are
absent (not null) on old hosts/rows.

**Stale blank auto-hide.** Sessions with an empty/`Untitled session` title, an empty preview, not
working, and older than `sessions.hideBlankAfterMs` (default 1 h; `0` disables) are omitted from the
list without being persisted. Newly created blank sessions stay visible during the grace period.

Session summary:
```json
{
  "id": "s_01J…",
  "title": "Cut a patch release",
  "workspaceId": "ws_1",
  "status": "working",              // "working" | "needs_input" | "idle"
  "preview": "All 128 tests passed…",
  "updatedAt": "2026-10-30T09:41:12Z",
  "unread": false,
  "live": true,                     // agent currently attached in this host process
  "parentSessionId": "s_01H…",      // optional; omitted unless parent exists and is visible
  "agentPreset": "cordis",          // optional; latest agent-preset selection when known
  "delegation": { "total": 9, "working": 0 }, // optional; internal direct child sessions
  "goal": {                         // optional; null when no current goal is available
    "objective": "Cut the 0.3 release", // trimmed, <=500 chars, "" when unknown
    "phase": "active",              // "active" | "paused" | "blocked" | "complete"
    "roundsStarted": 67,
    "maxGoalRounds": 120
  }
}
```
The optional `goal` summary is resolved at request time and is intentionally not cached with the
session-list snapshot, so goal create/edit/clear is visible on the next list request. Live sessions use the
authoritative goal service; non-live sessions use the durable goal session projection, which can lag by up to
the next background list refresh. Activation (`armed`/`disarmed`) is process-local and only available through
the per-session goal route. Live transitions also arrive as `goal.changed` SSE events (see SSE events below).

Message:
```json
{
  "id": "m_01J…",
  "seq": 13394,                     // optional; DSH event sequence for jump-to-message
  "role": "user",                   // "user" | "assistant" | "system" | "tool"
  "source": "schedule",             // optional; scheduled delivery (transcript-only marker)
  "text": "…",                      // concatenated assistant text / user text; empty for tool rows
  "createdAt": "2026-10-30T09:41:12Z",
  "streaming": false,
  "tool": { "name": "Bash", "detail": "pwsh -File tools/test.ps1 -Jobs 2", "state": "done",
            "output": "…" }, // optional; tool-result text, capped at 8,000 chars + marker
  "attachments": [                   // optional; omitted when empty
    { "id": "att_…", "kind": "image", "name": "shot.png", "mimeType": "image/png",
      "bytes": 1234, "width": 64, "height": 32 },
    { "id": "att_…", "kind": "file", "name": "report.pdf",
      "mimeType": "application/pdf", "bytes": 555 }
  ]
}
```
Tool states: `"running" | "done" | "failed"`. Messages may also carry synthetic image attachments with `ws_…` ids when the host is configured with `artifacts.roots` and the text references a local image inside the session cwd or one of those roots, or `ext_…` ids for public http(s) Markdown image links when the opt-in `artifacts.remote.enabled` proxy is on. They use the same attachment route and never expose host paths/URLs in metadata. `tool.output` is the tool result's text blocks only
(image/file blocks stay in `attachments`), first 8,000 characters followed by `… (output truncated)`;
the field is omitted for empty or image-only results. File MIME is best-effort from the filename extension; images
use DSH's media type.

Schedule:
```json
{
  "id": "schedule-…",
  "sessionId": "session-…",
  "sessionTitle": "Cut a patch release",
  "title": "Check the release build",
  "instruction": "Check CI and summarize the failures.",
  "status": "active",                      // "active" | "inactive"
  "schedule": {
    "kind": "daily",                       // after|at|every|daily|weekly|cron
    "summary": "Every day at 09:00 (America/New_York)",
    "nextRunAt": "2026-11-01T13:00:00Z",
    "lastRunAt": null
  },
  "createdAt": null,                       // best-effort; DSH has no record timestamps
  "updatedAt": null
}
```
Create accepts exactly one of `afterSeconds`, `everySeconds`, `dailyTime` (HH:MM plus optional IANA
`timeZone`), or `at` (ISO-8601), with `title` (≤200) and `instruction` (≤4000); `dailyTime` defaults to
UTC when `timeZone` is omitted. Schedules are visible only for sessions visible to the app (hidden and
internal sessions are omitted); delete resolves the session binding from the catalog. PATCH updates the
title/instruction and, for `after`/`daily` rules, one timing selector (other kinds allow text edits only);
there is no pause/run-now endpoint yet.

### Endpoints

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/mobile/v1/health` | — | `{ok, version, apiVersion, paired: bool}` (no secrets) |
| GET | `/mobile/v1/host` | — | `{name, version, workspaces: [...]}` |
| GET | `/mobile/v1/devices` | — | `{supported, devices: [{deviceId, deviceName, platform, pairedAt, lastSeenAt}], currentDeviceId}` (no token material) |
| GET | `/mobile/v1/models` | — | `{supported, default, groups: [{id, name, models: [{id, name, description?, reasoning?: {efforts, defaultEffort?}}]}], failures, truncated?}` |
| GET | `/mobile/v1/sessions/{id}/model` | — | `{supported, live, selection: {provider, model, reasoningEffort?}, source: "session"\|"default"}` |
| PUT | `/mobile/v1/sessions/{id}/model` | `{provider, model, reasoningEffort?}` | `{supported, live, selection}` (live sessions only; also becomes the default for new sessions) |
| GET | `/mobile/v1/sessions/{id}/queue` | — | `{supported, live, items: [{itemId, text, target, attachmentCount, createdAt?}]}` |
| GET | `/mobile/v1/sessions/{id}/skills` | — | `{supported, skills: [{name, description}]}` (visible sessions; no agent activation; `supported:false` when no registry) |
| GET | `/mobile/v1/sessions/{id}/context` | — | `{supported, surfaceTokens?, contextWindow?, pressureTokens?, percent?, breakdown?:{systemTokens?,toolsTokens?,messageTokens?}, sampledAt?}` (read-only projection cache; visible sessions) |
| GET | `/mobile/v1/sessions/{id}/jobs` | — | `{supported, counts:{running,completed,failed}, truncated, totalJobs, jobs:[{id,kind,label,status,startedAt?,finishedAt?,durationMs?,detail?}]}` (latest job events; redacted text; visible sessions) |
| GET | `/mobile/v1/sessions/{id}/files?path=` | — | `{supported, path, parent, truncated, entryCount, entries:[{name,kind,path,bytes?,mimeType?,modifiedAt?}]}` (read-only, relative to session cwd, credential denylist) |
| GET | `/mobile/v1/sessions/{id}/files/content?path=` | — | `{name,path,bytes,mimeType,text,truncated}` text preview (415 for binary/oversize) |
| GET | `/mobile/v1/sessions/{id}/files/raw?path=` | — | raw bytes ≤25 MB (active types attachment disposition) |
| GET | `/mobile/v1/sessions/{id}/trajectory?limit=100&beforeSeq=` | — | `{supported, events:[{seq,time?,type,summary,status?}], nextBeforeSeq, hasMore}` (newest-first compact journal; credentials redacted; visible sessions; no activation) |
| GET | `/mobile/v1/sessions/{id}/team` | — | `{supported, counts:{pending,inProgress,blocked,completed}, members:[{name,phase}], tasks:[{id,subject,status,blockedBy?}]}` (latest team events; active tasks only; visible sessions; no activation) |
| GET | `/mobile/v1/sessions/{id}/todos` | — | `{supported, updatedAt?, counts: {pending, inProgress, completed}, todos: [{content, status}]}` (latest `todo/write`; visible sessions; no agent activation) |
| PATCH | `/mobile/v1/sessions/{id}/queue/{itemId}` | `{action: "edit"\|"remove"\|"steer", text?}` | `{accepted:true}` (live sessions only) |
| POST | `/mobile/v1/sessions/{id}/fork` | `{atSeq?}` | `{sessionId, forkedFrom, atSeq?, warning?}` (new session with copied prefix; source untouched) |
| DELETE | `/mobile/v1/devices/{deviceId}` | — | `{ok:true, revoked:true}`; self-revoke 400, unknown 404 |
| GET | `/mobile/v1/sessions` | — | `{sessions: [SessionSummary]}` (archived rows excluded) |
| GET | `/mobile/v1/presets` | — | `{presets: [{id, name?, description?, isDefault, broken?}], supported: bool, defaultId?}` |
| POST | `/mobile/v1/sessions/{id}/rename` | `{title}` | `{ok:true, title, seq?}` (live sessions only) |
| POST | `/mobile/v1/sessions/{id}/archive` | — | `{ok:true, archived:true}` (refuses active work) |
| GET | `/mobile/v1/sessions/{id}/approval-policy` | — | `{supported, live, policy:"ask"\|"never"}` |
| PUT | `/mobile/v1/sessions/{id}/approval-policy` | `{policy}` | same shape (live sessions only; `never` auto-rejects approval-requiring actions) |
| POST | `/mobile/v1/sessions/{id}/unarchive` | — | `{ok:true, archived:false}` |
| GET | `/mobile/v1/sessions/archived` | — | `{sessions: [SessionSummary], supported: bool}` |
| GET | `/mobile/v1/search?q=<text>&limit=50&offset=0&role=&since=&until=` | — | `{hits: [...], supported, truncated, offset, nextOffset, hasMore, scanIncomplete, scannedSessions, totalSessions, filters?}` |
| GET | `/mobile/v1/push` | — | `{supported, enabled, server, topic, hasToken, suppression?}` (token never returned; suppression = `{quietHoursEnabled, quietStartHour, quietEndHour, timeZone, timeZoneValid, mutedCount}`) |
| PUT | `/mobile/v1/push` | `{enabled, server?, topic?, token?}` | same public shape; token `null`/omitted keeps, `""` clears |\n| PUT | `/mobile/v1/push/suppress` | `{quietHoursEnabled?, quietStartHour?, quietEndHour?, timeZone?, mutedSessionIds?}` | suppression summary; partial merge; hours 0–23, IANA zone required when enabled, muted ids capped 500 |
| POST | `/mobile/v1/push/test` | — | `{ok:true}`; 400 when not enabled, 502 when the push server rejects it (bypasses suppression) |
| GET | `/mobile/v1/usage` | — | `{generatedAt, totals, sessions: [SessionUsage], balance?, balanceError?}` |
| GET | `/mobile/v1/sessions/hidden` | — | `{ids: [session-id], updatedAt}` |
| PUT | `/mobile/v1/sessions/hidden` | `{hiddenIds: [session-id]}` | `{ids, updatedAt}` (validates `session-<id>` strings, de-dupes, max 500) |
| GET | `/mobile/v1/sessions/{id}?limit=100&before=<messageId>` | — | `{session, messages: [Message], nextCursor}` |
| GET | `/mobile/v1/sessions/{id}/attachments/{attachmentId}` | — | raw bytes (`Content-Type`, `Content-Length`, `Content-Disposition`); `att_…`, synthetic `ws_…` workspace images or opt-in `ext_…` remote http(s) image ids; 404 unknown/wrong session/hidden/disabled |
| GET | `/mobile/v1/schedules` | — | `{schedules: [Schedule], supported: bool}` |
| POST | `/mobile/v1/sessions/{id}/schedules` | `{title, instruction, afterSeconds?|everySeconds?|dailyTime?|at?, timeZone?}` | `{supported:true, schedule: Schedule}` (exactly one timing) |
| PATCH | `/mobile/v1/schedules/{id}` | `{title?, instruction?, afterSeconds?|everySeconds?|dailyTime?|at?, timeZone?}` | `{supported:true, updated:true, changed:bool, schedule: Schedule}` (at least one change; at most one timing) |
| DELETE | `/mobile/v1/schedules/{id}` | — | `{ok:true, deleted:true}` |
| POST | `/mobile/v1/sessions` | `{workspaceId, preset?}` | `{session}` |
| POST | `/mobile/v1/sessions/{id}/prompt` | `{text, attachmentIds?}` | `{accepted: true, runId}` |
| POST | `/mobile/v1/sessions/{id}/attachments` | `{name, mimeType, dataBase64}` | `{attachment: Attachment}` |
| POST | `/mobile/v1/sessions/{id}/interrupt` | — | `{ok: true}` |
| GET | `/mobile/v1/sessions/{id}/goal` | — | `{supported, live, goal: Goal\|null}` |
| POST | `/mobile/v1/sessions/{id}/goal` | `{objective, maxGoalRounds?}` | `{supported, live, goal: Goal}` (requires no current non-complete goal) |
| POST | `/mobile/v1/sessions/{id}/goal/resume` | — | `{supported, live, goal: Goal}` (requires a live agent) |
| POST | `/mobile/v1/sessions/{id}/goal/pause` | — | `{supported, live, goal: Goal}` (requires an active goal) |
| POST | `/mobile/v1/sessions/{id}/goal/complete` | `{id?, revision?}` optional | `{supported, live, goal: Goal}` (active/paused/blocked → complete) |
| POST | `/mobile/v1/sessions/{id}/goal/edit` | `{objective?, maxGoalRounds?}` | `{supported, live, goal: Goal}` (at least one field) |
| POST | `/mobile/v1/sessions/{id}/goal/clear` | — | `{supported, live, goal: null}` |
| GET | `/mobile/v1/approvals` | — | `{approvals: [Approval], supported: true, readOnly: false}` (phone can answer by default; `readOnly:true` when disabled) |
| POST | `/mobile/v1/approvals/{id}` | `{decision: "allow"|"deny", note?}` | `200 {ok:true, approvalId, outcome}`; `501 unsupported` only when mobile answering is disabled |
| GET | `/mobile/v1/questions` | — | `{questions: [Question], supported: true, readOnly: false}` (phone can answer by default; `readOnly:true` when disabled) |
| POST | `/mobile/v1/questions/{id}` | `{answer, optionIndex?, note?}` | `200 {ok:true, questionId, answered, total, complete}`; `501 unsupported` only when disabled |
| GET | `/mobile/v1/events?since=<eventId>` | — | SSE stream |

Approval:
```json
{ "id": "ap_01J…", "sessionId": "s_01J…", "title": "Permission needed: run command",
  "description": "…", "command": "pwsh -File tools/publish.mjs --patch",
  "createdAt": "…" }
```
Question:
```json
{ "id": "q_01J…", "sessionId": "s_01J…", "prompt": "Which approach?",
  "options": ["Ship now", "Wait for the page"], "createdAt": "…" }
```

## Goals

DSH's goal service (`ctx.goals` / `dsh-goal`) owns the per-session goal lifecycle
(`active|paused|blocked|complete`) and the continuation round counter. The host bridge exposes the
current goal and a resume action so the phone can see and re-arm a stopped goal:

```http
GET /mobile/v1/sessions/{id}/goal
```
```json
{ "supported": true, "live": true,
  "goal": { "id": "goal-…", "objective": "…", "phase": "paused",
            "maxGoalRounds": 120, "roundsStarted": 61, "revision": 6,
            "activation": "disarmed", "createdAt": "…", "updatedAt": "…" } }
```

- `supported` is false when the host process has no `goals` service (older host plugin/row).
- `live` is false when the session has no live agent; this route returns `goal: null` in that case because the
  goal service requires the live agent. `GET /sessions` can still surface a possibly-stale goal summary for a
  non-live session from the durable goal projection (see Sessions above).
- `phase` is one of `active`, `paused`, `blocked`, `complete`; `activation` is `armed` or `disarmed`.
  `blocked` goals additionally carry `blockedReason: {code, message}`.
- `POST /mobile/v1/sessions/{id}/goal` creates a goal with `{objective, maxGoalRounds?}`. The host
  rejects it with `400 invalid_request` when a non-complete goal already exists for the session or
  when the session has no live agent.
- `POST /mobile/v1/sessions/{id}/goal/resume` re-arms the current goal (no body); it returns the same
  envelope with the new `phase`/`activation`/`revision`. A non-live session answers `400
  invalid_request`; a session with no current goal answers `404 not_found`.
- `POST /mobile/v1/sessions/{id}/goal/pause` pauses an active goal (no body) with the same response
  envelope and error rules; the phone shows Resume or Pause depending on the current phase.
- `POST /mobile/v1/sessions/{id}/goal/complete` marks an active/paused/blocked goal complete and
  disarms it. The body is optional: `{id?, revision?}` guards against acting on a stale dialog and a
  mismatch answers `400 invalid_request` with a stale-revision message. Already-complete or terminal
  goals answer `400 invalid_request`; no current goal answers `404 not_found`.
- `POST /mobile/v1/sessions/{id}/goal/edit` changes the objective and/or max rounds (at least one
  field); revision conflicts and invalid transitions answer `400 invalid_request`.
- `POST /mobile/v1/sessions/{id}/goal/clear` clears the current goal (durable tombstone) and answers
  `{supported:true, live:true, goal:null}`; a new goal may then be created.

## Usage

`GET /mobile/v1/usage` aggregates the `tokenUsage` projection of every session and optionally
reports the remaining DeepSeek API credit:

```json
{
  "generatedAt": "2026-10-30T17:20:00Z",
  "totals": {
    "uncachedInputTokens": 12345, "outputTokens": 6789,
    "cacheReadTokens": 23456, "cacheWriteTokens": 0, "totalTokens": 42590
  },
  "sessions": [
    {
      "sessionId": "s_01J…", "title": "Cut a patch release",
      "updatedAt": "2026-10-30T09:41:12Z",
      "uncachedInputTokens": 100, "outputTokens": 20,
      "cacheReadTokens": 300, "cacheWriteTokens": 0
    }
  ],
  "balance": {
    "available": true,
    "balances": [
      { "currency": "USD", "totalBalance": "12.34",
        "grantedBalance": "2.00", "toppedUpBalance": "10.34" }
    ]
  },
  "balanceError": null
}
```

`balance` is `null` when the host process has no `DEEPSEEK_API_KEY` in its environment; a failed
lookup is reported in `balanceError` without failing the endpoint. Totals come from the local
session projections, so they work for both account and API-key model routes and survive host
restarts. The key is read from the host environment only, never stored or returned.

## Attachments (v0.2.2)

Upload one attachment, then reference its id in a prompt. The host maps it onto the DSH attachment services (`attachments.admitEncodedFile` for files, `attachments.saveImage` for images) and includes the durable reference as a prompt content part.

`POST /mobile/v1/sessions/{id}/attachments`
```json
{ "name": "report.pdf", "mimeType": "application/pdf", "dataBase64": "JVBERi0xLjQK..." }
```
→ `200`
```json
{ "attachment": { "attachmentId": "att_01J…", "name": "report.pdf",
                  "mimeType": "application/pdf", "bytes": 182734, "kind": "file" } }
```
- `kind`: `"file"` or `"image"` (image media types: png/jpeg/webp/gif).
- Limits: defaults are base64 body ≤ 14 MB (≈10 MB decoded) and request body ≤ 15 MB; this workspace's desktop profile overrides attachments to **200 MB decoded / 280 MB base64** and `http.maxBodyBytes` to 200 MB for large screen recordings. The Android client streams its base64 body (no whole-payload String) and enforces a **64 MB decoded** upload cap; one attachment per request; images are validated by the host's attachment policy and the error is surfaced as `invalid_request` with the policy message. Files are accepted byte-for-byte.
- Host caps: **8 attachments per prompt** (extra ids are rejected with `invalid_request`), **32 pending attachments** per host (capacity pre-checked).
- Pending uploads are held **in host memory**: they are lost if the host process restarts, and the client must re-upload. Each upload MUST be followed by a prompt within 24 h or it expires.
- The attachment id is bound to the pairing device and the target session, and expires after 24 h or when consumed by a prompt.
- Uploads addressed to an **internal/subagent session** are rejected with `400 invalid_request` (open the parent session instead); this also avoids the old opaque 500 for internal ids.
- Attachments are referenced by a prompt, not by a separate send call:
  `POST /mobile/v1/sessions/{id}/prompt` with `{ "text": "Summarise this", "attachmentIds": ["att_01J…"] }`.
- Prompt text may be empty when at least one attachment is present.
- Attachment read-back (0.2.96) is read-only: the byte route serves only refs still present in that
  session's visible human/assistant messages. Images are `inline`; files use
  `Content-Disposition: attachment` so a text/HTML payload cannot render in a browser that opens the URL.
  No Range/HEAD; compaction or message removal makes an attachment 404.


## SSE events

`GET /mobile/v1/events?since=<eventId>` responds `text/event-stream`. Every frame has this shape:

```
event: message.delta
id: ev_000123
data: {"id":"ev_000123","type":"message.delta","at":"2026-10-30T10:00:00Z","sessionId":"s_1","data":{"sessionId":"s_1","messageId":"m_9","text":"All 128 "}}

```
- `data:` **always** carries the full event envelope as JSON; the per-type payload lives in `envelope.data`.
- The SSE `event:` line mirrors `envelope.type`; the JSON `type` field is authoritative for parsing.
- `id:` is present for replayable/persisted events (the cursor for `since`). Per-connection frames (`host.status`, `ping`) carry an envelope too but **no** `id:` line.

Event envelope:
```json
{ "id": "ev_000123", "type": "message.delta", "at": "…", "sessionId": "s_1", "data": { … } }
```
Event types:
- `session.updated` — data: `SessionSummary`
- `session.removed` — data: `{sessionId}`
- `message.added` — data: `{sessionId, message: Message}`
- `message.delta` — data: `{sessionId, messageId, text}` (append semantics)
- `message.completed` — data: `{sessionId, messageId}`
- `approval.requested` / `approval.resolved` — data: `Approval` / `{approvalId}`
- `question.requested` / `question.resolved` — data: `Question` / `{questionId}`
- `goal.changed` — data: `{sessionId, operation, goal: Goal|null}`; operation is `create|edit|pause|resume|complete|block|clear`, goal is null for clear. Replayable; activation is the live value when it matches the event revision, else `disarmed`.
- `host.status` — data: `{connected: true}` (sent on connect; no `id:`)
- `ping` — heartbeat every 20s, empty data, no `id:`

The client reconnects with `since` set to the last `id` it saw; the host replays events after that cursor if it still has them, otherwise sends `host.status` + a full `session.updated` snapshot per session and the client refetches details.

## Errors

```json
{ "error": { "code": "unauthorized", "message": "Missing or invalid device token" } }
```
Codes: `unauthorized` (401), `forbidden` (403), `not_found` (404), `invalid_code` (400), `rate_limited` (429), `invalid_request` (400), `unsupported` (501), `internal` (500).

Successful state-changing calls (`prompt`, `interrupt`, `approvals/{id}`, `questions/{id}`, `sessions` create) return **200** with their documented JSON body.

## v0.2 scope

In: health, pairing, host/workspaces, sessions list, session detail, prompt, interrupt, approvals, questions, SSE events, **attachments (v0.2.2)**.
Out (deferred): diffs/file viewer, terminal, device management UI, push notifications (FCM/ntfy integration comes with the notification workstream).

## Host plugin rules

- Bind `127.0.0.1` only; never a wildcard/LAN address.
- **Pairing endpoint behind a proxy:** via Tailscale Serve every remote request appears to originate from `127.0.0.1`, so a loopback check alone is not enough. `POST /pair/new` must additionally reject requests carrying proxy/identity headers (`X-Forwarded-For`, `X-Forwarded-Host`, `Forwarded`, any `Tailscale-User-*`/`Tailscale-Funnel-*` header) with `403 forbidden`. Direct desktop calls carry none. A local admin secret replaces this heuristic in v0.3.
- Never expose the DSH launch token, session log paths, shell/file-write endpoints.
- Keep DSH-facing logic behind a thin adapter so the HTTP/pairing/store layer is unit-testable with Node built-ins (`node --test`), no external deps required.
- The plugin package must follow the DSH bundle layout (`package.json` + `cordis.patch.yml` + `lib/index.js`) so `plugin_manager install_bundle` can install it later. Do not install it into the user's profile as part of development.
- Contract notes: `SessionSummary.workspaceId` may be `null` if the host cannot resolve a workspace; tool rows use `message.id = m_<toolCallId>` and SSE `message.added` upserts by id; assistant streaming uses a `message.delta`/`message.completed` stream id that may not appear as a durable message until the session detail is refetched.
- `GET /approvals` observes the `approval/request` waterfall without claiming it. With
  `approvals.mobileAnswer: true` (the default) it returns `readOnly:false` and the phone may answer via
  `POST /approvals/{id}`; the desktop remains a competing answerer and **the first answer wins**. A phone
  answer is returned to the agent immediately while any desktop prompt may stay visible until answered
  there (that later answer is ignored). When mobile answering is disabled, the endpoint is a read-only
  mirror (`readOnly:true`) and POST answers `501 unsupported`. Any paired device can answer approvals,
  so treat the device token as authority; set `approvals.mobileAnswer: false` to restore desktop-only.
- `GET /questions` observes the `user-questions/request` waterfall. With `questions.mobileAnswer: true`
  (the default) it returns `readOnly:false` and the phone may answer one question row per POST; the
  desktop remains a competing answerer and **the first whole answer wins**. Each answered row disappears
  and emits `question.resolved`; the agent receives the batch (`{answers}`) only when every question in
  the call has been answered, so a partially answered multi-question call leaves the agent waiting. A
  free-text question is answered with `answer` as its `custom` value. When disabled, the endpoint is a
  read-only mirror (`readOnly:true`) and POST answers `501 unsupported`. Any paired device can answer
  questions, so `questions.mobileAnswer: false` restores desktop-only answering.

## Fake host

`tools/fake-host/server.mjs` implements this contract with in-memory demo data, bound to `0.0.0.0:3543` for development, including simulated streaming replies, an approval when a prompt contains `deploy`/`publish`/`push`, and SSE events. The Android app can point at `http://10.0.2.2:3543` from an emulator or `http://<pc-lan-ip>:3543` from a phone on the same Wi-Fi to exercise the real client paths before the plugin exists.
