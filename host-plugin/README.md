# DSH Mobile Host (`dsh-mobile-host`)

DSH Host plugin that implements **DSH Mobile API v1** (`docs/mobile-api-v1.md`):
a loopback-only HTTP + SSE server that lets the DSH Mobile Android app pair with
this desktop, list sessions, read transcripts, upload attachments, send prompts,
interrupt a running turn, and follow live events. Subagent/teammate ("internal")
sessions are hidden from the phone by default, so the list only shows sessions
the user created.

It never exposes DSH's own web server, launch token, session-log paths, or any
shell/file-write surface: the phone has its **own** bearer-token auth, issued by
a one-time pairing code.

```
Android app ── HTTPS ──> Tailscale Serve ──> 127.0.0.1:3543 (this plugin)
                                              │
                                              └── DSH Host services
                                                  sessionController / sessionQuery
                                                  workspaceRegistry / attachments / fileUploads
                                                  event feed
```

## Package layout

| Path | Purpose |
|---|---|
| `package.json` | DSH bundle manifest (`dsh.bundle.patch → ./cordis.patch.yml`), ESM, no runtime deps |
| `cordis.patch.yml` | Inserts the Host row `mobile-api` (`port: 3543`, `host: 127.0.0.1`) |
| `lib/index.js` | Host plugin entry: `apply(ctx, config)` starts/stops the server inside a Cordis effect; never throws |
| `lib/dsh-adapter.js` | **Only** DSH-aware module: service lookups, `sessionController`/`sessionQuery`/`attachments`/`fileUploads` calls, DSH events → SSE bus |
| `lib/pairing.js` | 6-digit one-time codes, 5-min TTL, per-IP failure rate limit, token issue + sha256 hash store |
| `lib/attachments.js` | Pending-attachment table (device+session bound, 24 h TTL), base64 validation, memory bounds |
| `lib/router.js` | Pure request → response router (bearer auth, routes, validation, error shapes) |
| `lib/server.js` | `node:http` carrier: body limits, JSON, SSE stream + heartbeat |
| `lib/events.js` | Replay-ring event bus + SSE envelope framing |
| `lib/mappers.js` | DSH Session/event → contract JSON, and mobile prompt → DSH `PromptContentPart[]` |
| `lib/schedules.js` | Pure schedule body validation + catalog → mobile row mapping (no DSH access) |
| `lib/sessions.js` | Pure session-management validation (rename title normalization) |
| `lib/presets.js` | Pure agent-preset roster merge/sanitize/sort (no DSH access) |
| `lib/search.js` | Pure message-search query/limit validation, bounded event scan and snippet shaping |
| `lib/models.js` | Pure model-catalog capping/sanitization and durable selection folding |
| `lib/queue.js` | Pure live-inbox queue mapping and PATCH action validation |
| `lib/push.js` | Optional ntfy push: config validation/persistence, SSRF checks, event mapping, bounded publisher with dedupe/backoff |
| `lib/config.js` | Defaults + validation (loopback bind is enforced, not just defaulted) |
| `lib/store.js` | Atomic JSON state store for device-token hashes |
| `test/*.test.mjs` | `node --test` suites; no DSH process, no network required |
| `locale/{en,zh}.json`, `icon.svg` | Bundle display metadata for the Plugin Manager inventory |

## Endpoint status

Fully implemented:

| Method | Path | Notes |
|---|---|---|
| GET | `/mobile/v1/health` | public; `{ok, version, apiVersion, paired}`, no secrets |
| POST | `/mobile/v1/pair/new` | loopback **and** not reverse-proxied; returns code/expiresAt/qrPayload |
| POST | `/mobile/v1/pair/complete` | public; code → token, deviceId, host info, workspaces |
| GET | `/mobile/v1/pair/methods` | public; `{desktopCode:true, recovery:true, tailscaleAutoPair, tailscaleLoginRequired}` |
| POST | `/mobile/v1/pair/tailscale` | public; trusts Tailscale Serve `Tailscale-User-Login`/`-Name` → same token/device shape + `pairingMethod:"tailscale"`, `identity`; `400` missing identity, `403` disabled/not allowed, `429` rate-limited |
| POST | `/mobile/v1/pair/recovery` | public; `{code, deviceName?, platform?}` → `{token, device, recoveryCode, createdAt, host, workspaces}`; invalid `401`, rate-limited `429` |
| GET | `/mobile/v1/recovery` | bearer; `{supported:true, configured, createdAt?}` — never the code or hash |
| POST | `/mobile/v1/recovery` | bearer; rotates → `{code, createdAt}` (returned once, save it now) |
| GET | `/mobile/v1/host` | bearer; name/version/workspaces |
| GET | `/mobile/v1/devices` | bearer; `{supported:true, devices:[{deviceId, deviceName, platform, pairedAt, lastSeenAt}], currentDeviceId}` (no token hashes/secrets) |
| DELETE | `/mobile/v1/devices/{deviceId}` | bearer; revoke another paired device → `{ok:true, revoked:true}`; self-revoke `400`, unknown `404`, missing management `501` |
| GET | `/mobile/v1/presets` | bearer; `{presets:[{id, name?, description?, isDefault, broken?}], supported, defaultId?}` agent-preset roster |
| GET | `/mobile/v1/models` | bearer; sanitized/capped model catalog `{default, groups, failures, truncated}`, `501` when unavailable |
| GET | `/mobile/v1/sessions/{id}/model` | bearer; `{supported, live, selection:{provider,model,reasoningEffort?}, source:"session"\|"default"}` (cold reads fold the log, no activation) |
| PUT | `/mobile/v1/sessions/{id}/model` | bearer; `{provider, model, reasoningEffort?}`; live-only (`400` cold), validated by DSH (`session/model-unavailable` → `400`), `501` when unavailable |
| GET | `/mobile/v1/sessions/{id}/queue` | bearer; live inbox `{supported, live, items:[{itemId, text, target, attachmentCount, createdAt?}]}`; cold → empty items |
| PATCH | `/mobile/v1/sessions/{id}/queue/{itemId}` | bearer; `{action:"edit"\|"remove"\|"steer", text?}` → `{accepted:true}`; live-only, edit text 1..20000, remove/steer must omit text |
| GET | `/mobile/v1/sessions/{id}/skills` | bearer; `{supported, skills:[{name, description}]}` user-invocable skills for the session (cold-safe catalog read, sorted/capped, no paths); `supported:false` when the catalog is absent |
| GET | `/mobile/v1/sessions/{id}/todos` | bearer; latest agent plan `{supported, updatedAt?, counts:{pending,inProgress,completed}, todos:[{content,status}]}` from the newest `todo/write` event (cold snapshot read, sanitized/capped); `supported:false` when none |
| GET | `/mobile/v1/sessions/{id}/team` | bearer; folded Agent Teams board `{supported, counts:{pending,inProgress,blocked,completed}, members:[{name,phase}], tasks:[{id,subject,status,blockedBy?}]}` from the latest `team/task`/`team/member` events (cold snapshot read, sanitized/capped, no internal ids); `supported:false` when none |
| GET | `/mobile/v1/sessions/{id}/trajectory?limit=100&beforeSeq=` | bearer; compact newest-first event journal `{supported, events:[{seq,time,type,summary,status?}], nextBeforeSeq, hasMore}` (allowlisted one-line summaries, no raw payloads; `limit` 1..200, `beforeSeq` exclusive); `supported:false` when no events |
| GET | `/mobile/v1/sessions/{id}/jobs` | bearer; folded background jobs `{supported, counts:{running,completed,failed}, jobs:[{id,kind,label,status,startedAt?,finishedAt?,durationMs?,detail?}], truncated, totalJobs}` from latest `job/registered`/`job/settled` events (cold snapshot, sanitized/redacted, running first, cap 50); `supported:false` when none |
| GET | `/mobile/v1/sessions/{id}/context` | bearer; context-window pressure `{supported, surfaceTokens?, contextWindow?, pressureTokens?, percent?, breakdown?:{systemTokens?,toolsTokens?,messageTokens?}, sampledAt?}` from the read-only DSH projection cache; `supported:false` when absent/unreadable |
| POST | `/mobile/v1/sessions/{id}/fork` | bearer; `{atSeq?}` → `{sessionId, forkedFrom, atSeq?, warning?}`; branches the durable prefix into a new session (source untouched) |
| GET | `/mobile/v1/push` | bearer; `{supported, enabled, server, topic, hasToken, suppression:{quietHoursEnabled,quietStartHour,quietEndHour,timeZone,timeZoneValid,mutedCount}}` — the token is never returned |
| PUT | `/mobile/v1/push` | bearer; `{enabled, server?, topic?, token?}` persists the ntfy config (https-only; enabled requires a topic) |
| PUT | `/mobile/v1/push/suppress` | bearer; partial `{quietHoursEnabled?, quietStartHour?, quietEndHour?, timeZone?, mutedSessionIds?}` persists quiet hours/mutes atomically with the push config and returns the public suppression summary |
| POST | `/mobile/v1/push/test` | bearer; sends one test notification → `{ok:true}`; `502` with a short message when the server rejects it |
| GET | `/mobile/v1/search?q=&limit=&offset=&role=&since=&until=` | bearer; bounded host-wide message search with optional role/date filters + offset pagination → `{hits:[…], supported, truncated, offset, nextOffset, hasMore, filters?}` |
| GET | `/mobile/v1/sessions` | bearer; user sessions only (internal + curated-hidden + stale-blank sessions excluded); titles, preview, workspace id, live/status, per-row `goal` summary and optional aggregate `delegation:{total,working}` |
| GET | `/mobile/v1/sessions/hidden` | bearer; `{ids, updatedAt}` curated hidden list (v0.2.5) |
| PUT | `/mobile/v1/sessions/hidden` | bearer; `{hiddenIds}` replaces the curated list (≤500 ids), writes the hidden file |
| GET | `/mobile/v1/sessions/archived` | bearer; `{sessions, supported}` archived session summaries (visible sessions only, no goal enrichment) |
| POST | `/mobile/v1/sessions/{id}/rename` | bearer; `{title}` (trimmed 1..200) → `{ok:true, title, seq?}`; requires the conversation to be open on the host |
| POST | `/mobile/v1/sessions/{id}/archive` | bearer; `{ok:true, archived:true}` via the workspace controller (no `stopActivity`) |
| POST | `/mobile/v1/sessions/{id}/unarchive` | bearer; `{ok:true, archived:false}` |
| GET | `/mobile/v1/sessions/{id}/approval-policy` | bearer; `{supported, live, policy:"ask"\|"never"}` (live reads DSH, cold folds the durable log) |
| PUT | `/mobile/v1/sessions/{id}/approval-policy` | bearer; `{policy:"ask"\|"never"}`; requires a live session (cold → `400`), no approval service → `501` |
| GET | `/mobile/v1/sessions/{id}?limit=&before=` | bearer; summary (includes optional aggregate `delegation:{total,working}`) + paged contract messages + `nextCursor` |
| POST | `/mobile/v1/sessions` | bearer; `{workspaceId?, preset?}` → `{session}` |
| POST | `/mobile/v1/sessions/{id}/attachments` | bearer; `{name, mimeType, dataBase64}` → `{attachment}` (v0.2.2) |
| GET | `/mobile/v1/sessions/{id}/attachments/{attachmentId}` | bearer; raw bytes + `Content-Type`/`Content-Disposition` for an attachment referenced by that session's messages (v0.2.96) |
| GET | `/mobile/v1/sessions/{id}/files?path=` | bearer; read-only directory listing under the session cwd `{supported, path, parent, entries:[{name,kind,path,bytes?,mimeType?,modifiedAt?}], truncated, entryCount}` (500 entries, dirs first; credential/secret path denylist) |
| GET | `/mobile/v1/sessions/{id}/files/content?path=` | bearer; text preview `{name,path,bytes,mimeType,text,truncated}` (≤1 MB read, ≤200k chars); binary/oversize → `415 unsupported` |
| GET | `/mobile/v1/sessions/{id}/files/raw?path=` | bearer; raw file bytes ≤25 MB with extension `Content-Type`, exact `Content-Length`, `Content-Disposition` inline (attachment for active HTML/SVG/XML/JS/CSS), `nosniff`, private cache; too large → `413` |
| POST | `/mobile/v1/sessions/{id}/prompt` | bearer; `{text?, attachmentIds?}` → `{accepted, runId}` (queue mode) |
| POST | `/mobile/v1/sessions/{id}/interrupt` | bearer; `sessionController.cancel` → `{ok:true}` |
| GET | `/mobile/v1/sessions/{id}/goal` | bearer; `{supported, live, goal\|null}` for the session's current DSH goal |
| POST | `/mobile/v1/sessions/{id}/goal` | bearer; `{objective, maxGoalRounds?}` creates a goal when none is current |
| POST | `/mobile/v1/sessions/{id}/goal/resume` | bearer; re-arms the current goal for a live agent and returns the updated goal |
| POST | `/mobile/v1/sessions/{id}/goal/pause` | bearer; pauses the active goal for a live agent and returns the updated goal |
| POST | `/mobile/v1/sessions/{id}/goal/complete` | bearer; marks the current goal complete (active/paused/blocked → complete, disarmed) and returns the updated goal |
| POST | `/mobile/v1/sessions/{id}/goal/edit` | bearer; `{objective?, maxGoalRounds?}` edits the current goal (live agent required); returns the updated goal |
| POST | `/mobile/v1/sessions/{id}/goal/clear` | bearer; clears the current goal (durable tombstone); returns `{supported, live, goal:null}` |
| GET | `/mobile/v1/approvals` | bearer; mirror `{approvals, supported:true, readOnly}` (`readOnly:false` when `approvals.mobileAnswer`); rows `{id, sessionId, title, description, command, createdAt}` |
| POST | `/mobile/v1/approvals/{id}` | bearer; `{decision:"allow"\|"deny"}` → `{ok:true, approvalId, outcome}`; first answer wins (`404` when already answered, `501` when mobile answering is disabled) |
| GET | `/mobile/v1/questions` | bearer; mirror `{questions, supported:true, readOnly}` (`readOnly:false` when `questions.mobileAnswer`); rows `{id, sessionId, prompt, options, createdAt}` |
| POST | `/mobile/v1/questions/{id}` | bearer; `{answer, optionIndex?}` → `{ok:true, questionId, answered, total, complete}`; whole batch answers once every row is answered (`404` when settled/already answered, `501` when disabled) |
| GET | `/mobile/v1/schedules` | bearer; `{schedules, supported}` visible sessions only, sorted by next run; timestamps best-effort |
| POST | `/mobile/v1/sessions/{id}/schedules` | bearer; `{title, instruction, afterSeconds?\|everySeconds?\|dailyTime?\|at?}` (exactly one) → `{supported:true, schedule}` |
| DELETE | `/mobile/v1/schedules/{id}` | bearer; resolves the session binding from the catalog → `{ok:true, deleted:true}`; `404` unknown/invisible |
| PATCH | `/mobile/v1/schedules/{id}` | bearer; `{title?, instruction?, afterSeconds?\|everySeconds?\|dailyTime?\|at?, timeZone?}` (≥1 change, ≤1 timing) → `{supported:true, updated:true, changed, schedule}`; `404` unknown/invisible |
| GET | `/mobile/v1/events?since=` | bearer; SSE with replay/resync and 20 s `ping` |

Goal controls use the DSH `ctx.goals` service (`dsh-goal`). They require the
session's live agent; a paused/unloaded session returns `live:false` and a
resume/pause/complete/create/edit/clear attempt returns `400 invalid_request`
until the conversation is open on the host. A goal can only be created when no
non-complete goal is current (resume/complete/clear it first). `goal/edit`
accepts objective and/or round cap (at least one; `maxGoalRounds` 1..10000) and
compares the current revision, so a concurrent change answers `400
invalid_request` with the DSH `GOAL_STALE_REVISION` message instead of applying.
`goal/pause`, `goal/complete`, and `goal/clear` compare the current revision the
same way (a stale ref keeps the DSH message and usually settles the next poll).
`goal/complete` transitions active/paused/blocked → complete and disarms
automatic continuation; `goal/clear` removes the current goal (a durable
tombstone is kept), after which a new goal can be created. The phone shows a
GOAL banner in the chat with Set goal/Edit/Resume/Pause/Complete/Clear, so goals
can be managed without the desktop UI. Goal mutations are also pushed
immediately over SSE as `goal.changed` (see "SSE semantics"), so the banner
updates without waiting for the 20 s poll.

Each `GET /sessions` row also carries the session's current goal summary, so the
phone can show goal state across sessions without a per-session goal GET:

```json
"goal": {
  "objective": "Ship the release",
  "phase": "active",
  "roundsStarted": 3,
  "maxGoalRounds": 120
}
// or `null`
```

`objective` is trimmed and capped at 500 chars (`''` when absent); `phase` is
`active | paused | blocked | complete`. The summary is available for **cold
(non-live) sessions too**: every `sessionController.list` row carries the durable
`goal` session projection (`projections.values.goal`, the cached wire value of
DSH's goal projection — `{goal: GoalSnapshot, roundsStarted, createdAt,
updatedAt} | null`), which the host maps without creating an agent or reading a
session log. For live sessions the authoritative live read
(`ctx.agents.get(id)` + `ctx.goals.get(agent)`) overrides the projection, with
the projection as fallback if that read is unavailable or fails. The projection
can lag until the next background list refresh (same staleness posture as
titles/previews). `goal` is `null` when the session has no current goal (no
create yet, or after a clear tombstone) or when the goal projection is absent
from the composed profile. Process-local activation (`armed`/`disarmed`) is
deliberately absent from the DSH projection, so it cannot be derived for
non-live sessions — this summary does not expose it. The goal routes are
unchanged.

## Session lineage + agent preset (v0.3.1)

`GET /sessions` rows (and therefore any client-side pinned list built from them),
`GET /sessions/archived` rows, and `GET /sessions/{id}` summaries gain two
optional, additive fields:

```json
{
  "id": "session-…",
  "parentSessionId": "session-…",
  "agentPreset": "coder"
}
```

- `parentSessionId` is the DSH `header.parentSession` (list rows use DSH
  `SessionSummary.parentSessionId`) and is **only** exposed when that parent is
  currently visible/readable to the phone by the same access rule used everywhere
  (existence, not hidden, not internal, not archived). Unknown parents, hidden or
  internal subagent sessions, archived parents and a parent id equal to the
  child's own id are omitted rather than exposed. Only the id is included (never
  the parent title) — the client resolves the parent from its own list. If the
  parent access check itself fails, the field is omitted and the summary is still
  served (`200`), never a `500`.
- `agentPreset` is the current agent-preset id: the latest DSH
  `agent-preset/selected` event (`data.agentPreset`, a string) wins for a log
  snapshot, otherwise the creation preset from the session header. List rows use
  the `agentPreset` session projection when present (including an explicit `null`
  clear); when that projection is absent, the host falls back to a preset learned
  from an earlier detail/snapshot read. If neither source exists the field is
  omitted.
- **Omitted, never `null`**, for both fields. Existing fields and their meanings
  are unchanged. Archived-session lineage is deliberately stricter than the list
  rule: archived parents are omitted (their raw id must not leak through a child
  row). Parented/subagent sessions are themselves excluded from the archived list
  by the existing internal-session privacy rule, so archived rows generally carry
  `agentPreset` but not `parentSessionId`.
- There is no separate host “pinned” endpoint: the app's pinned list is a
  client-side projection of the same `GET /sessions` rows, so it inherits exactly
  these fields and visibility filtering.

## Session skills (v0.3.2)

`GET /mobile/v1/sessions/{id}/skills` (bearer) lists the **user-invocable** skills
visible to one session composition:

```json
{
  "supported": true,
  "skills": [
    { "name": "office-docx", "description": "Create, read, edit and validate Word documents." },
    { "name": "office-xlsx", "description": "Read, create, edit and validate Excel workbooks." }
  ]
}
```

- Reads DSH's `ctx.get('sessionSkillCatalog').list({ sessionId }, signal)` — the
  documented **non-activating** catalog path (it observes the Session and its
  preset scope; it never creates/resumes an Agent or mutates the session).
- Only `name` and `description` are returned. `path`, `whenToUse` and
  `modelInvocable` stay host-side; absolute/relative skill paths, ports, hosts and
  other environment data are never exposed.
- Sanitization: names are trimmed and must be non-empty, ≤120 chars, without
  control characters or path separators; descriptions are whitespace-collapsed,
  trimmed, and capped at **500 chars**. Entries with a valid name are kept even
  when the description is empty/missing (`description: ""`); only blank/unsafe
  names are omitted. Names dedupe case-insensitively (first wins), results sort by
  name and cap at **100**.
- Visible-session policy applies: unknown, hidden and internal/subagent sessions
  answer `404` before the catalog is read.
- If the catalog service is absent, throws, or reports no registry/provider, the
  endpoint answers `200 {supported:false, skills:[]}` (never a 500 or a leaked
  error path); a DSH `session/not-found` maps to `404`. The read is side-effect
  free: no prompt, queue, model, skill-body load or session mutation is performed.
- Missing bearer token → `401`, as with every authenticated route.

## Session todos / plan (v0.3.6)

`GET /mobile/v1/sessions/{id}/todos` (bearer) exposes the agent's current plan
from the **latest** `todo/write` event in the session log:

```json
{
  "supported": true,
  "updatedAt": "2026-10-30T09:40:03Z",
  "counts": { "pending": 1, "inProgress": 1, "completed": 1 },
  "todos": [
    { "content": "Write the endpoint", "status": "completed" },
    { "content": "Add tests", "status": "in_progress" },
    { "content": "Document it", "status": "pending" }
  ]
}
```

- The **latest** `todo/write` event by log order wins (an empty later write clears
  the plan); older writes are ignored. No such event → `200
  {supported:false, counts:{pending:0,inProgress:0,completed:0}, todos:[]}`.
- Statuses are `pending | in_progress | completed`; unknown/legacy statuses map
  to `pending` (never dropped). `counts` are computed after sanitization/caps and
  always equal the returned `todos`.
- Content is controls→spaces, whitespace-collapsed, trimmed, capped at **500**
  chars; empty contents are omitted; items keep plan order, are **not deduped**,
  and the response caps at **50 items**. `updatedAt` is the latest event time
  (omitted when unavailable). No host paths or internal session ids are exposed.
- Visible-session policy: unknown/hidden/internal sessions answer `404` before
  the log read. The route only reads `sessionQuery.readSession` and never creates
  or resumes an Agent; missing history support → `501 unsupported`. Missing
  bearer token → `401`.

## Team board (v0.3.8)

`GET /mobile/v1/sessions/{id}/team` (bearer) exposes the Agent Teams board folded
from the session log:

```json
{
  "supported": true,
  "counts": { "pending": 1, "inProgress": 1, "blocked": 1, "completed": 2 },
  "members": [{ "name": "Worker One", "phase": "working" }],
  "tasks": [
    { "id": "task-191", "subject": "Implement the endpoint", "status": "in_progress" },
    { "id": "task-192", "subject": "Write tests", "status": "blocked", "blockedBy": ["task-191"] }
  ]
}
```

- Folding: the **latest `team/task` event per task id** and the **latest
  `team/member` event per member id** by log order win. A later blank/unsafe
  revision removes that entry rather than resurrecting stale data.
- `status` is `pending | in_progress | blocked | completed`; unknown/legacy
  statuses map to `pending` (never dropped). `counts` cover **all folded valid
  tasks** (including completed) and always match the data.
- Only **active** tasks (pending / in_progress / blocked) are listed, ordered by
  newest `revision` first (log order breaks ties), capped at **50**; completed
  tasks contribute only to `counts.completed` and are never listed. Members are
  listed most-recently-updated first, capped at **50**.
- Sanitization: member names/subjects are controls→spaces, whitespace-collapsed,
  trimmed, capped at **120 / 200** chars; blank or unsafe entries are dropped.
  Member phase is sanitized and capped at 60 chars.
- **Privacy:** only `id`, `subject`, `status`, `blockedBy` (board task ids) and
  member `name`/`phase` leave the host — descriptions, owner ids, write scopes,
  member ids, provider/context and every other internal field are omitted. No host
  paths or internal session ids are exposed.
- No `team/task`/`team/member` event → `200 {supported:false, counts:{…0},
  members:[], tasks:[]}`.
- Visible-session policy: unknown/hidden/internal sessions answer `404` before
  the log read. Snapshot read only — no Agent is created or resumed. Missing
  bearer token → `401`; missing history support → `501 unsupported`.

## Trajectory journal (v0.3.9)

`GET /mobile/v1/sessions/{id}/trajectory?limit=100&beforeSeq=<seq>` (bearer)
returns a compact, newest-first journal of the session's raw events for a
Trajectory tab:

```json
{
  "supported": true,
  "events": [
    { "seq": 42, "time": "2026-10-30T09:41:12Z", "type": "tool/result", "summary": "Pwsh: all 128 tests passed", "status": "done" },
    { "seq": 41, "time": "2026-10-30T09:41:02Z", "type": "tool/call", "summary": "Pwsh: npm test" }
  ],
  "nextBeforeSeq": 41,
  "hasMore": true
}
```

- Every snapshot event with a durable non-negative `seq` is included; events are
  ordered newest-first with strictly decreasing `seq`. `time` is ISO when the
  event has a usable time.
- Summaries are one sanitized line (controls→spaces, whitespace collapsed,
  trimmed, ≤**300** chars) from an allowlisted mapping:
  - `turn/start|end` → turn number, end reason; `step/start|end` → step number,
    end reason;
  - `user/message` → text for visible human/scheduled prompts; any other internal
    source becomes `[context] <kind>` with **no body**;
  - `assistant/message` → text; `tool/call` → tool name + compact argument
    detail; `tool/result` → tool name/output or `failed`, with compact
    `status:"done"|"failed"`;
  - `goal/change` → phase/status/action only; `todo/write` → pending/in-progress/
    completed counts; `team/task` → board id + subject + status; `team/member` →
    name + phase; schedule events → short type suffix;
  - unknown types → the sanitized event type only.
- **Credential + path redaction:** before the 300-char cap every summary fragment
  runs a general credential pass: `Bearer …`/`Authorization:` values; any
  assignment key containing `TOKEN`/`SECRET`/`PASSWORD`/`PASSWD`/`API_KEY`/
  `ACCESS_KEY`/`PRIVATE_KEY` (e.g. `CLOUDFLARE_API_TOKEN=…`, `_API_TOKEN: "…"`);
  provider prefixes (`cfat_`, `cf_`, `ghp_`/`gho_`/`ghs_`/`ghu_`,
  `github_pat_`, `glpat-`, `xox[baprs]-`, `sk-`, `pk_`, `rk_`, `npm_`, `ya29.`,
  `AIza`, `AKIA`, `ASIA`) and JWT `eyJ…` shapes; plus long (80+ chars)
  base64-looking blobs → `<redacted>`. Then absolute Windows drive/UNC paths and
  POSIX paths under host roots (`/home`, `/Users`, `/root`, `/etc`, `/var`,
  `/tmp`, `/opt`, `/mnt`) become `<path>`. Normal tool names and commands stay
  usable (relative paths/URLs are not touched). Both passes run before
  truncation, so a secret/path cannot survive by straddling the boundary.
- **Privacy:** no raw payloads, internal prompts, attachment bytes, provider/owner
  ids, write scopes, member/agent ids or host paths are copied; only `seq`,
  `time`, `type`, `summary` and compact `status` leave the host. Tool details and
  visible message text are sanitized summaries, not raw payloads.
- Pagination: `limit` 1..**200** (default 100); `beforeSeq` is an optional
  exclusive non-negative integer; invalid values → `400 invalid_request`.
  `hasMore` is true when older events remain; `nextBeforeSeq` is the oldest
  returned seq when advancing (null otherwise). Paging is stable because it is a
  pure function of the event seqs.
- A readable session with no eligible events → `200 {supported:false, events:[],
  nextBeforeSeq:null, hasMore:false}`. `501 unsupported` is returned **only** when
  the history service itself is unavailable/fails — these two cases are distinct.
- Visible-session policy: unknown/hidden/internal sessions answer `404` before the
  log read. Snapshot read only — no Agent is created, resumed or mutated. Missing
  bearer token → `401`.

## Workspace file browser (v0.3.10)

Three GET-only, read-only endpoints browse files under the session's durable cwd
(the snapshot header `cwd`; no Agent activation, no writes/mutations):

- `GET /sessions/{id}/files?path=<relative>` →
  `{supported:true, path, parent, entries:[{name, kind:"dir"|"file", path, bytes?, mimeType?, modifiedAt?}], truncated, entryCount}`.
  `path` is workspace-relative (`""`/`"."` = cwd); `parent` is `""` at the root,
  otherwise the parent relative path. Dirs come first, then case-insensitive
  names; the response caps at **500 entries**, with `truncated:true` and
  `entryCount` (the visible entry total) when the cap is hit. Symlink entries are
  never listed. File `bytes`/`mimeType` (extension-based, octet-stream fallback)/
  `modifiedAt` are display metadata only.
- `GET /sessions/{id}/files/content?path=<relative>` → text preview
  `{name, path, bytes, mimeType, text, truncated}`. Only text-ish extensions
  (`text/*`, json/yaml/toml/xml, md, source/config/log files) are readable; the
  read is capped at **1 MB** and the returned text at **200,000 chars**
  (`truncated:true` when cut). Binary files and files over 1 MB answer
  **`415` with `{error:{code:"unsupported"}}`** — never a giant body. NUL bytes
  make a file binary regardless of extension.
- `GET /sessions/{id}/files/raw?path=<relative>` → raw bytes for a regular file
  ≤ **25 MB** with extension-based `Content-Type`, exact `Content-Length`,
  `X-Content-Type-Options: nosniff` and `Cache-Control: private, max-age=60`.
  `Content-Disposition` is `inline` for inert files but **`attachment`** for
  active types (html/htm/svg/xml/js/mjs/cjs/jsx/css). Files over 25 MB answer a
  JSON **`413`**. Directories are `400`; missing/outside targets are `404`.

**Security model**
- `path` must be workspace-relative. Absolute POSIX paths, Windows drive paths,
  UNC roots, alternate data streams (any `:`), `..`/`.` segments, empty segments,
  control/Windows-forbidden characters, trailing dot/space aliases and Windows
  reserved device names (CON/PRN/AUX/NUL/COM1-9/LPT1-9, extension-tolerant) are
  rejected with generic `400 invalid_request`.
- The session cwd is `realpath`-resolved; the requested path is `realpath`-resolved
  and must stay contained inside that real cwd. Immediately before reading, the
  target is **re-realpath-validated and opened with `fs.open` (+`O_NOFOLLOW` where
  supported), then `fstat`/read from that descriptor** — never `readFile(path)` on
  the earlier path — closing the TOCTOU symlink-swap window. Symlink escapes,
  missing files, non-regular entries and disappearing targets answer a generic
  `404`; only regular files/dirs are exposed.
- **Default sensitive-path denylist (defense-in-depth, not a content scanner):**
  any path segment is placed on the denylist when, case-insensitively, it is
  `.git`, `.ssh`, `.aws`, `.gnupg`; its basename is `.env`/`.env.*`,
  `.credentials*`, `credentials`/`credentials.*`, `secrets`/`secrets.*`,
  `id_rsa`, `id_dsa`, `id_ecdsa`, `id_ed25519`, `.npmrc`, `.pypirc` or `.netrc`;
  its extension is `pem`, `key`, `pfx`, `p12`, `keystore`, `kdbx` or `token`; or
  its basename contains `secret` anywhere. Listings omit denied entries and
  direct `/files`, `/files/content`, `/files/raw` guesses answer a generic `404`
  (nested paths included). The same check runs on the **canonical realpath
  target**, so an innocently-named symlink cannot alias a denied file/directory.
  Ordinary source/config files such as `.json`, `.yaml`,
  `package.json`, `README.md` and `.gitignore` stay visible/readable. This is a
  filename denylist only: a secret inside an otherwise ordinary file is not
  detected, and the broad `*secret*` rule may hide harmless names (documented
  trade-off).
- JSON never carries a host path (only the relative `path`/`name`, file metadata);
  raw responses carry only a sanitized filename in `Content-Disposition`. Errors
  are generic; nothing is written, renamed or deleted.
- Visibility policy: unknown/hidden/internal sessions answer `404` before any file
  read; missing/blank cwd or an unavailable cwd root → `404`; an unavailable
  history service → `501`. Bearer auth is required; other HTTP methods are not
  routed.

## Background jobs (v0.3.11)

`GET /mobile/v1/sessions/{id}/jobs` (bearer) exposes background tool jobs folded
from the session log:

```json
{
  "supported": true,
  "counts": { "running": 1, "completed": 1, "failed": 1 },
  "jobs": [
    { "id": "pwsh-3", "kind": "pwsh", "label": "Still running", "status": "running", "startedAt": "2026-10-30T09:40:06Z" },
    { "id": "pwsh-2", "kind": "shell", "label": "Build failed", "status": "failed", "startedAt": "2026-10-30T09:40:02Z", "finishedAt": "2026-10-30T09:40:05Z", "durationMs": 3000, "detail": "exit 1" }
  ],
  "truncated": false,
  "totalJobs": 2
}
```

- Folding: the **latest `job/registered` or `job/settled` event per `jobId`** by
  log order wins. `job/registered` → `status:"running"`; a settled event carries
  its normalized status: `completed|success|succeeded|done|ok` → `completed`,
  `failed|failure|error|errored` → `failed`, `stopped|cancelled|canceled|
  aborted|killed|interrupted` → `stopped`; any other settled status is sanitized
  (controls collapsed, credentials/paths redacted) then preserved lower-cased and
  capped (`unknown` when absent) — never sliced raw. Counts: `running`,
  `completed` (including stopped/unknown terminal jobs), `failed`. Counts cover
  **all** folded jobs, not only the listed page.
- Listing: running jobs first (most recently active first), then finished jobs
  newest-finished first, capped at **50**. When the display cap is hit the
  additive **`truncated:true`** and **`totalJobs`** (total folded jobs) fields let
  the app explain why counts exceed the list; both are always present.
  `startedAt`/`finishedAt` are ISO when supplied, and `durationMs` is derived
  when both are valid. Job `id` is included (e.g. `pwsh-…`) for a future control
  surface; `owner` and tool-call ids are never exposed.
- Sanitization: `kind`/`label` controls→spaces, whitespace-collapsed and capped
  at **240** chars; `detail` at **120**; all three run the shared general
  credential pass (env/assignment keys containing
  TOKEN/SECRET/PASSWORD/PASSWD/API_KEY/ACCESS_KEY/PRIVATE_KEY, Cloudflare/GitHub/
  GitLab/Slack/OpenAI/npm/Google/AWS prefixes, JWTs, Bearer/Authorization, long
  base64 → `<redacted>`) **and absolute-path redaction** (Windows drive/UNC and
  POSIX `/home|/Users|/root|/etc|/var|/tmp|/opt|/mnt` → `<path>`) so job commands
  keep their words but host paths are not exposed. No raw payloads, owner ids or
  tool-call ids are copied.
- No `job/registered`/`job/settled` event → `200 {supported:false,
  counts:{running:0,completed:0,failed:0}, jobs:[], truncated:false, totalJobs:0}`.
- Visible-session policy: unknown/hidden/internal sessions answer `404` before the
  log read. Snapshot read only — never activates/mutates an Agent or Session;
  missing history service → `501`; missing bearer token → `401`.

## Context pressure (v0.3.13)

`GET /mobile/v1/sessions/{id}/context` (bearer) exposes the session's
context-window pressure from DSH's persisted projection cache:

```json
{
  "supported": true,
  "surfaceTokens": 1000,
  "contextWindow": 2000,
  "pressureTokens": 500,
  "percent": 25,
  "breakdown": { "systemTokens": 100, "toolsTokens": 200, "messageTokens": 700 },
  "sampledAt": "2026-10-30T09:40:05Z"
}
```

- Source: read-only `record.rows.contextPressure.val` /
  `record.rows.contextBreakdown.val` in the DSH `session_projcache/sessions`
  file for this session (configured `usage.projectionsDir`, `DSH_HOME`, the state
  file's `.dsh` cache, or `/data/dsh`). The cache entry is `lstat`-checked and
  must be a **regular file** (symlinks/junctions and other entry types are
  rejected, degrading to `supported:false`). No Agent/Session is activated or
  mutated.
- Only finite non-negative integers are exposed, individually omitted when
  unknown: `surfaceTokens` (pressure row, else breakdown row), `contextWindow`
  (positive), `pressureTokens`, and optional breakdown
  `systemTokens`/`toolsTokens`/`messageTokens`. `percent` requires both
  `contextWindow` and `pressureTokens`: it is
  `clamp(0, 100, pressureTokens / contextWindow * 100)` rounded to one decimal.
  `sampledAt` comes from `pressure.sampledAt` or `record.updatedAt` when numeric;
  **`record.updatedAt` is the projection-cache write/update time, not a live
  sample** — clients should display it as approximate (no separate approx field
  is needed).
- Missing file, missing rows, unreadable/malformed JSON, or no usable numeric
  fields → `200 {supported:false}` (never a 500); malformed/unreadable cache
  files are logged at most once per session, **without paths or payloads**, and
  the warning bookkeeping is bounded to the **500 most recently warned
  sessions** (oldest entries are evicted, so a long-lived process cannot grow it
  without bound; an evicted session may warn again). No prompts, titles, model
  text or filesystem paths are returned.
- Visible-session policy: unknown/hidden/internal sessions answer `404` before
  any cache read; missing bearer token → `401`; only `GET` is routed.

## Delegation counts (v0.3.4)

Visible session summaries (`GET /sessions`, `GET /sessions/archived` where the raw
rows are available, and `GET /sessions/{id}`) can carry an additive aggregate:

```json
"delegation": { "total": 2, "working": 1 }
```

- `total` counts **internal direct child sessions** of that parent (raw
  `sessionController.list` rows whose `parentSessionId` equals the parent id and
  which match the existing internal-session markers: `origin:'subagent'`,
  non-empty `parentSessionId`, subagent projection or the bare-id heuristic).
  Grandchildren belong to their direct child and are **not** counted in the
  grandparent. **Archived children are excluded** — they must not inflate a live
  parent's agent count.
- `working` is a **best-effort snapshot** of children whose raw DSH list row has
  `running === true`; it is not a live push stream and can lag by the list-refresh
  interval (~5 s). It is clamped to `total`.
- The field is **omitted** when the parent has no countable internal direct
  children, when the session is hidden or internal itself, when the raw child
  list cannot be read, or when the current archive state cannot be determined
  (counting archived children could inflate the number). Counts are aggregate
  numbers only — no child ids, titles, prompts, workspace paths or other metadata
  are exposed, and internal child rows never become visible in any list.
- Counts are computed from `sessionController.list` and the snapshot/log context
  already needed for the summary; no Agent is created or activated. Detail reads
  reuse a short-lived (5 s) raw-list cache; list reads compute the map together
  with the list payload.
- Archived-parent semantics are best-effort: an archived parent can still show
  counts of its currently non-archived internal direct children; archived children
  themselves are never counted and are not otherwise made visible.

## Approval mirror (optional mobile answering)

DSH dispatches `approval/request` as a **waterfall**. The host registers one
listener that records each pending request (bounded to 32, oldest dropped
silently) and publishes SSE `approval.requested`. With `approvals.mobileAnswer:
true` (default) it also races a deferred phone decision against the desktop
chain:

- `next()` starts immediately, so the desktop UI remains a full answerer.
- **First answer wins**: a desktop answer returns its outcome unchanged and
  later phone decisions answer `404 not_found`; a phone Allow/Deny returns that
  outcome to the agent immediately, and the desktop promise is left to settle in
  the background (failures are logged, and no duplicate resolution is emitted).
- `request.signal` abort settles the request as `cancelled`.
- Exactly one `approval.resolved` is published per request. Pending rows are
  in-memory only (cleared on plugin dispose), hidden/internal sessions are never
  mirrored, and mirroring/racing failures are logged and swallowed so the agent
  approval path is never broken.
- With `approvals.mobileAnswer: false` the listener is a pure pass-through
  observer (read-only mode) and phone decisions answer `501 unsupported`.

`GET /mobile/v1/approvals` returns the pending rows plus `supported:true` and
`readOnly` (`false` when mobile answering is enabled; clients use it to decide
whether to render Allow/Deny):

```json
{
  "approvals": [
    {
      "id": "ap_<uuid>",
      "sessionId": "session-…",
      "title": "Permission needed: Bash",
      "description": "Publish the release?",
      "command": "pwsh -File tools/publish.mjs --patch",
      "createdAt": "2026-10-31T12:00:00Z"
    }
  ],
  "supported": true,
  "readOnly": false
}
```

`POST /mobile/v1/approvals/{id}` with `{"decision":"allow"}` or
`{"decision":"deny"}` answers:

```json
{ "ok": true, "approvalId": "ap_…", "outcome": "allowed-once" }
```

`outcome` is `allowed-once` for allow and `rejected` for deny. Unknown or
already-answered ids answer `404 not_found`; a bad decision answers `400
invalid_request`; with mobile answering disabled the route answers `501
unsupported`. `id` is plugin-generated and stable while the request is pending;
`title`/`description` come from the DSH request (`toolName`, localized
`displayReason`/`reason`); `command` is best-effort recovered from the preceding
`tool/call` session event and is `''` when unavailable.

**Security / behaviour:** enabling mobile answering means **any paired device
can allow or deny host tool approvals** — that is the point of the toggle. The
phone answer is final even if the desktop prompt is still visible; a later
desktop action is ignored (logged only). The list is poll-based, so the phone
needs a fresh `GET /approvals` before deciding, and no push notification is
sent. Set `approvals.mobileAnswer: false` to restore the read-only mirror.

## Question mirror (optional mobile answering)

`user-questions/request` is a **whole-call batch**: DSH expects exactly one answer
item per question in the call. The host registers one listener that records a row
per question (cap 32 across all pending calls, oldest rows dropped silently) and
publishes SSE `question.requested`. With `questions.mobileAnswer: true` (default)
the mirror also races the desktop chain against **completion of the phone batch —
first whole answer wins**:

- `next()` starts immediately, so the desktop UI remains a full answerer.
- The phone answers rows one at a time via `POST /questions/{id}`. Each answer
  removes that row and publishes `question.resolved` for it; the call's
  `{answers}` batch is returned to the agent only once **every** question in the
  original call has been answered from the phone (`complete:true`). Answer items
  are `{id: <original question id>, selected:[label]}` or
  `{id, selected:[], custom:<text>}`.
- A desktop answer received first wins: all remaining rows are removed, later
  phone answers answer `404 not_found`, and the desktop `{answers}` is returned
  unchanged. A completed phone batch is returned immediately; the desktop promise
  is left to settle in the background (failures logged, no duplicate events).
- `request.signal` abort settles the call as `{answers: []}` (DSH has no
  cancelled question outcome) and resolves the rows.
- With `questions.mobileAnswer: false` the listener is a pure pass-through
  observer (read-only mode) and phone answers answer `501 unsupported`.

`GET /mobile/v1/questions` returns the pending rows plus `supported:true` and
`readOnly` (`false` when mobile answering is enabled):

```json
{
  "questions": [
    {
      "id": "q_<uuid>:0",
      "sessionId": "session-…",
      "prompt": "Approach: Which approach should I take?\nPick a lane before I continue.",
      "options": ["Ship now", "Wait for the page"],
      "createdAt": "2026-10-31T12:00:00Z"
    }
  ],
  "supported": true,
  "readOnly": false
}
```

One row is emitted per DSH question item; `id` is `q_<uuid>:<index>` (stable for
the call's lifetime), `prompt` concatenates `header`, `question` and `detail` as
available, and `options` are the option labels (`[]` for free-form). Attribution
falls back to the Cordis scoped agent when the request carries no `agent`.
Pending rows are in-memory only (cleared on plugin dispose) and hidden/internal
sessions are not mirrored.

`POST /mobile/v1/questions/{id}` accepts `{"answer":"Ship now","optionIndex":0}`
(`optionIndex` wins when valid) or `{"answer":"free text"}` (a matching option
label is selected case-insensitively, otherwise the text becomes DSH's
`custom`). It answers:

```json
{ "ok": true, "questionId": "q_<uuid>:0", "answered": 1, "total": 2, "complete": false }
```

Unknown/settled/already-answered rows answer `404 not_found`; an out-of-range
`optionIndex` or an empty free-text `answer` answers `400 invalid_request`; with
mobile answering disabled the route answers `501 unsupported`.

**Security / partial-answer caveat:** enabling mobile answering means **any paired
device can answer host questions** (that is the point of the toggle). The phone
batch must be completed for the answer to reach the agent — partially answered
calls stay pending (the answered rows disappear from the list) and the desktop
can still answer first. The list is poll-based (no push notification). Set
`questions.mobileAnswer: false` to restore the read-only mirror.

## Schedules / reminders (v0.2.98)

DSH schedules (`ctx.get('schedule')`) are reminders bound to one Session; the
catalog read and deletion never activate the session. The phone gets
list/create/edit/delete:

```http
GET /mobile/v1/schedules
200 {
  "schedules": [
    {
      "id": "schedule-1",
      "sessionId": "session-…",
      "sessionTitle": "…",
      "title": "Standup",
      "instruction": "Ping me",
      "status": "active",
      "schedule": {
        "kind": "daily",
        "summary": "daily at 09:30 Europe/Berlin",
        "nextRunAt": "2026-10-31T08:30:00Z",
        "lastRunAt": null
      },
      "createdAt": "2026-10-30T10:00:00Z",
      "updatedAt": "2026-10-30T10:00:00Z"
    }
  ],
  "supported": true
}
```

- `schedule.kind` is the DSH rule kind (`after | at | every | daily | weekly |
  cron`); `summary` is a human-readable timing; `nextRunAt` is the next durable
  target (`null` for inactive rows); `lastRunAt` is the last delivery receipt.
- Only **visible** sessions are listed: curated-hidden, auto-hidden and
  internal/subagent bindings are filtered exactly like `/sessions`. Strings are
  trimmed/capped; rows are sorted by next run time (`null` last), then id.
- `createdAt`/`updatedAt` are **best-effort**: DSH's catalog types carry no
  timestamps, so the adapter folds the owning session's `schedule/change`
  events (create/dispatch) and reports `null` when that history is unreadable.
- Service absent → `{schedules: [], supported: false}`.

`POST /mobile/v1/sessions/{id}/schedules` body
`{title, instruction, afterSeconds? | everySeconds? | dailyTime? | at?}` with
**exactly one** timing selector:

| Selector | Range/format |
|---|---|
| `afterSeconds` | integer 1..31536000 (one-shot delay) |
| `everySeconds` | integer 300..31536000 (DSH fixed-rate floor) |
| `dailyTime` | `HH:MM` 24-hour; optional `timeZone` (default `UTC`) |
| `at` | ISO-8601 timestamp |

`title` 1..200 chars, `instruction` 1..4000 chars. The host maps it to DSH
`create(sessionId, {title, prompt, after_seconds|every_seconds|daily|at})`,
requires an existing visible session, and answers `{supported:true, schedule}`
(same row shape as the list). Errors: `400 invalid_request` for validation and
DSH schedule-input failures (`invalid_prompt`, `invalid_rule`, `not_future`, …),
`404 not_found` for unknown/hidden sessions, `400 invalid_request` for
internal/subagent sessions, `501 unsupported` when the service is absent.

`DELETE /mobile/v1/schedules/{id}` resolves the owning session from the visible
catalog, calls DSH `delete({id, sessionId})`, and answers
`{ok:true, deleted:true}`; unknown/invisible schedules answer `404 not_found`.

`PATCH /mobile/v1/schedules/{id}` edits an active reminder with
`{title?, instruction?, afterSeconds? | everySeconds? | dailyTime? | at?,
timeZone?}` — at least one change, at most one timing selector, same limits as
create. It resolves the binding from the visible catalog (unknown/invisible →
`404`), builds the exact DSH
`update({id, sessionId, expected, title?, prompt?, change?})` request (the
catalog entry's complete record is the CAS `expected` value), and answers
`{supported:true, updated:true, changed, schedule}`. `changed` is `false` for
DSH's no-op edit. DSH timing changes have no `after` variant, so an
`afterSeconds` edit is translated to an absolute `{kind:'at'}` target
(`now + afterSeconds`); `everySeconds` → `{kind:'every'}`, `dailyTime` →
`{kind:'daily'}` (optional `timeZone`, default `UTC`), `at` → `{kind:'at'}`.
DSH misses map to `404 not_found` (`schedule_not_found`/`schedule_ended`); input
and conflict failures answer `400 invalid_request` with the DSH message; absent
service answers `501 unsupported`.

## Attachments (v0.2.2)

Upload once per attachment, then reference the returned id in a prompt:

```http
POST /mobile/v1/sessions/{id}/attachments
{ "name": "report.pdf", "mimeType": "application/pdf", "dataBase64": "JVBERi0xLjQK..." }

200 { "attachment": { "attachmentId": "att_…", "name": "report.pdf",
                      "mimeType": "application/pdf", "bytes": 182734, "kind": "file" } }
```

Then (text may be empty when attachments are present):

```http
POST /mobile/v1/sessions/{id}/prompt
{ "text": "Summarise this", "attachmentIds": ["att_…"] }
```

How it maps onto DSH (verified against the installed 0.2.1-rc.5 contracts):

1. **Image** (`image/png|jpeg|webp|gif`): validated and durably committed with
   `ctx.attachments.saveImage({data, mediaType, name})` — host image policy
   errors surface as `invalid_request` with the policy message. The canonical
   base64 bytes are kept in host memory because a DSH image prompt part carries
   raw bytes (`{type:'image', mediaType, data, name?}`); the session controller
   re-admits them when the prompt is sent (content-addressed storage dedupes).
2. **File** (everything else): committed byte-for-byte with
   `ctx.attachments.admitEncodedFile({data, name})` and additionally staged with
   `ctx.fileUploads.uploadStream({sessionId, data, name})`, which yields the
   `receiptId` that the documented DSH file prompt part requires
   (`{type:'file', receiptId}`); the session controller resolves the receipt and
   binds it on prompt admission.
3. The prompt content is finally passed to
   `sessionController.prompt({requestId, sessionId, mode:'queue', content})`.

Limits and lifecycle (contract values, configurable):

- one attachment per request; body ≤ 15 MB, `dataBase64` ≤ 14 MB encoded,
  decoded payload ≤ 10 MB;
- image policy is DSH's (`attachments.saveImage` decodes/validates the raster);
  files are accepted byte-for-byte;
- ids are bound to the **pairing device and target session**, are single-use
  (consumed by a successful prompt) and expire after **24 h**;
- at most 8 attachments per prompt, 32 pending attachments, 64 MB pending image
  data can be configured; exceeding those returns `invalid_request`;
- foreign/expired/unknown ids answer `404 not_found` (uniform on purpose: a
  phone learns nothing about ids it does not own);
- pending uploads live in **host memory only** (image bytes must be re-sent when
  the prompt is admitted). A plugin reload/restart discards them: the client
  should re-upload and retry.
- If a host profile lacks the attachment capability, uploads answer
  `501 unsupported` (images) or `501 unsupported` (files, when `fileUploads` is
  absent); the rest of the API keeps working.
- Uploading to a **subagent/internal session** answers `400 invalid_request`
  with "open the parent session instead" before any DSH attachment write; the
  underlying DSH staging error (if one still occurs) is logged and mapped to a
  documented 4xx, never a generic 500 (QA defect O3).

### Message attachment metadata and read-back (v0.2.96)

Session-detail messages gain an optional `attachments` array (omitted when
empty) populated from the DSH image/file content blocks of human user and
assistant messages:

```json
{
  "id": "m_…", "role": "user", "text": "Look at this",
  "attachments": [
    { "id": "att_…", "kind": "image", "name": "shot.png", "mimeType": "image/png",
      "bytes": 1234, "width": 64, "height": 32 },
    { "id": "att_…", "kind": "file", "name": "report.pdf",
      "mimeType": "application/pdf", "bytes": 555 }
  ]
}
```

`id` is the DSH durable attachment id; `name` defaults to `Image`/`File`;
`mimeType` is the image's `mediaType` or, for files (whose DSH ref carries only a
name and size), a best-effort extension mapping with
`application/octet-stream` fallback. Text/tool rows are unchanged.

The authenticated read-back route streams the exact stored bytes:

```http
GET /mobile/v1/sessions/{id}/attachments/{attachmentId}
200 Content-Type: image/png
    Content-Disposition: inline; filename="shot.png"
    Content-Length: 1234
```

Refs are derived from **that session's own visible messages** (the same mapping
path as session detail), never from a global id map or the filesystem; an id that
the session does not reference answers `404 not_found`. Images are read with the
attachment service's verifying `readImage` (bounded by the DSH image policy);
files stream through `readFileStream` in bounded chunks with no full-file buffer.
`Content-Disposition` is sanitized to prevent header injection, and no host path
is ever returned. `400 invalid_request` is used for a malformed id; `501
unsupported` when the host process has no attachment service. Range requests are
not supported.

## Scheduled prompts in the transcript (v0.3.7)

DSH delivers a scheduled prompt as a `user/message` event with
`data.source.kind === 'schedule'`. These deliveries are now part of the visible
transcript:

- They map to a normal `role:"user"` message (same ids, order and pagination as
  other messages), with text extracted as usual and all normal user-message
  behavior (image/file `attachments`, workspace/remote image refs) applied.
- They carry one additive marker: `source:"schedule"`. Ordinary human user
  messages (`source.kind` absent or `'user'`) and legacy source-less messages do
  **not** get a `source` field, so the existing shape is unchanged.
- All other non-human user sources stay hidden exactly as before
  (`runtime-context`, `skill-catalog`, `time-context`, `agent-message`, `goal`,
  `subagent-settled`, `compact-checkpoint`, `tool-*` reminders,
  `agent-instructions`, `file-change`, …).
- **Transcript-only split:** scheduled prompts are visible **only** in the
  session transcript and its live SSE row. Session-list previews, the
  `SessionSummary.title` first-user fallback, and host-wide `/search` remain
  **strict human-only** (`source.kind` absent/`'user'`) so scheduler instructions
  or operational details cannot leak into list previews, search snippets or
  notifications. Internal synthetic sources remain excluded from all of those.
  On the semantic search fast path (where documents carry no `source.kind`) the
  scheduler's own framing (`[SCHEDULE REMINDER]` / `This is a scheduled message
  from the user`) is filtered out before matching without reading snapshots; see
  the search contract for the documented heuristic caveat.
- **Attachments:** the attachment collector follows the visible-transcript rule,
  so image/file refs and workspace/remote image refs carried by a scheduled
  message are discoverable/servable for the delivered transcript (same read
  route/visibility as human-message attachments), while no attachment metadata is
  added to list/search results by this feature.
- The live SSE `message.added` mapping uses the same visible-transcript mapper, so
  a scheduled delivery appears in the open chat with the same `source:"schedule"`
  marker. No new endpoint is added; hidden/internal session rules are unchanged.

## Tool result output (v0.2.8)

Tool rows (`role: "tool"`, id `m_<toolCallId>`) can now carry the tool result's
**text output** so the phone can inspect what a tool returned (desktop parity)
without loading image/file bytes:

```json
{
  "id": "m_call_1", "role": "tool", "text": "",
  "createdAt": "2026-10-30T09:40:05Z",
  "tool": {
    "name": "Bash", "detail": "pwsh -File tools/test.ps1", "state": "done",
    "output": "Tests passed\n128 assertions"
  }
}
```

- `tool.output` is `extractText` over the tool-result `message.content` text
  blocks only; image/file blocks and attachment bytes are excluded (they remain
  in the separate `attachments` array). Newlines inside text blocks are kept.
- Capped at **8,000 characters**; longer output keeps the first 8,000 and
  appends the exact marker `… (output truncated)`. Empty/image-only results omit
  `output` entirely (no empty string).
- The field is present in **both** session detail (`GET /sessions/{id}`) and the
  live SSE `message.added` tool row, so a running tool's output appears without
  reloading the session. `tool.name`/`detail`/`state` are unchanged, and result
  text never leaks into `message.text` or non-tool rows.

## Workspace image links (v0.2.9)

Messages can reference local images with Markdown `![alt](path)`. The host turns
those references into synthetic image attachments so the existing phone
thumbnail/full-screen viewer works, without exposing host paths:

```markdown
Here is the render: ![chart](reports/chart.png)
Absolute artifacts: ![logo](C:\artifacts\logo.gif)
```

- **Resolution:** relative paths are confined to the session cwd; absolute paths
  are served **only** when they resolve inside an operator-configured
  `artifacts.roots` directory. `file:`, `data:` and other URLs are ignored;
  `http(s)://` links are ignored unless the opt-in remote proxy below is enabled.
  Only visible user/assistant/tool-result **text blocks** are parsed (JSON/tool
  arguments, image/file blocks and attachment bytes never become references).
- **Public attachment metadata** (session detail and live SSE `message.added`):
  `{ "id": "ws_<sha256-prefix>", "kind": "image", "name": "<basename>",
  "mimeType": "image/png|jpeg|webp|gif", "bytes": <size> }`. The id is a hash of
  the canonical path and `name` is the basename only; no absolute/relative host
  path appears in the JSON. The same `ws_…` id is readable through the normal
  attachment route: `GET /sessions/{id}/attachments/{ws_id}` returns the bytes
  inline with `Content-Type`, exact `Content-Length`, `Content-Disposition:
  inline`, `Cache-Control: private, max-age=300`, and
  `X-Content-Type-Options: nosniff`.
- **Config:**

  ```json
  { "artifacts": { "roots": ["C:\\artifacts", "D:\\renders"] } }
  ```

  Default is empty (relative cwd images still work; no absolute file can be
  served). Entries must be absolute, unique after normalization, and non-UNC/
  device paths; an invalid entry fails startup config validation instead of
  guessing a policy.
- **Safety guards:** percent-escapes are decoded; `..`, UNC (`\\server\share`,
  `//host/share`), device (`\\?\C:\…`), drive-relative (`C:foo`), ADS
  (`file.png:stream`) and trailing dot/space segments are rejected; containment
  uses realpath after symlink resolution (so a symlink cannot escape cwd/a root)
  and `path.relative` boundaries (so `C:\foo` never matches `C:\foobar`); only
  existing regular `.png/.jpg/.jpeg/.webp/.gif` files with matching magic bytes,
  non-zero size and ≤25 MB are exposed. At most **8** Markdown matches are
  processed per message text; every synthetic ref carried by the visible messages
  is collected (the detail builder and this collection pass must agree, otherwise
  shown attachments would 404). Served files are re-realpath'ed and re-opened + fstat'ed/read from the
  same handle (TOCTOU hardening), and absolute refs are rejected if their
  configured root was removed from the current config.
- **Visibility:** synthetic workspace-image reads are only available for
  sessions the phone can see; hidden/internal/archived sessions return `404`
  (intentional hardening, consistent with hidden-session filtering). Missing or
  no-longer-allowed files also return `404` with a short generic message; error
  bodies never echo host paths.
- Existing DSH image/file attachments, pending uploads and their metadata are
  unchanged; workspace refs are additive synthetic entries on the same
  `attachments` array.

## Remote image proxy (v0.3.0)

Opt-in, **disabled by default**. When enabled, Markdown `![alt](https://…)`
image links in visible **user and assistant** text become synthetic attachments
proxied by the host. The phone never contacts the remote origin; bytes are
fetched only when the attachment URL is opened (session list/detail mapping is
metadata-only).

```json
{
  "artifacts": {
    "remote": {
      "enabled": true,
      "maxBytes": 10485760,
      "timeoutMs": 8000,
      "ttlMs": 600000,
      "cacheEntries": 32
    }
  }
}
```

- **Contract:** identical public metadata to workspace images but
  `id: "ext_<sha256(normalized URL) prefix>"`, `kind: "image"`,
  `name: <basename only>`, `mimeType: image/png|jpeg|webp|gif`, `bytes: 0` (the
  real size is known only after a read). The remote URL is kept only in the
  host-internal ref and never appears in public metadata or error messages.
  The bytes are served by the normal attachment route with `Content-Type`,
  exact `Content-Length`, `Content-Disposition: inline`,
  `Cache-Control: private, max-age=<ttl>` and
  `X-Content-Type-Options: nosniff`.
- **Default OFF:** disabled config emits no `ext_*` attachments and every
  `ext_*` read is a generic `404`; enabling the proxy is an explicit operator
  decision. `artifacts.remote.maxBytes` is capped at 25 MB (default 10 MB),
  timeout 1–30 s (default 8 s), TTL 1–60 min (default 10 min) and cache entries
  1–64 (default 32); invalid/out-of-range values fall back to the defaults.
- **SSRF model (critical):**
  - Only `http`/`https`, ports 80/443, no userinfo, no fragments, and an image
    extension (`.png/.jpg/.jpeg/.webp/.gif`; **no SVG**). Query strings are kept
    in the id/URL but never stripped or reinterpreted.
  - Every hostname is resolved with `dns.lookup({all:true})`; **if any** answer
    is loopback, private, link-local, ULA, multicast, documentation, benchmarking,
    CGNAT, reserved, 6to4 with a private embedded IPv4, NAT64 local-use,
    IPv4-mapped IPv6, Teredo, ORCHID or `3fff::/20`, the request is refused.
    Literal IP hosts are classified directly. Only a public unicast address is
    accepted.
  - The validated address is pinned through a custom `lookup` and `Host`/TLS
    SNI stay the original hostname, so DNS rebinding cannot connect elsewhere.
  - At most **3 redirects**, each re-parsed against the same URL policy and
    re-resolved/re-classified before following; redirect bodies are abandoned
    immediately after headers.
  - GET only, no cookies/auth forwarded, normal User-Agent, fixed timeout, and
    streaming aborts once `maxBytes` is exceeded. A global **4-fetch concurrency
    cap**, bounded in-memory cache (TTL; ≤32 entries/≤64 MB default) and a short
    negative cache prevent fetch storms/retries.
  - Response body must be non-empty and match the extension's magic bytes; a
    declared `Content-Type` must exactly match that magic type (or be absent).
    Fetches happen only on attachment read, never while listing/details.
- **Privacy rule:** remote refs are harvested **only from visible user and
  assistant text**, never from tool-result text or tool arguments. A remote URL
  in a tool result cannot cause host egress when a session is viewed; local
  `ws_*` refs in tool results keep working. Hidden/internal sessions return
  `404` for `ext_*` reads.
- **Limitations:** public origins only (no intranet/private images), no SVG, no
  authenticated/protected images, URL must end in an allowlisted image
  extension, redirect chains >3 fail, and the cache is per host process. The
  proxy can still reveal the host's egress IP/timing to a public origin (a
  privacy consideration the operator accepts by enabling it).

## Internal (subagent/teammate) sessions (v0.2.4)

DSH stores subagent/Agent-Team-teammate sessions in the same corpus as user
sessions. Their ids are **bare UUIDs** (`351ca07c-…`), while ordinary sessions
are minted as `session-<uuid>`, and their persisted `SessionHeader` carries
`origin: 'subagent'`, `parentSession`, and `delegationDepth`. The mobile API
hides them by default (`sessions.hideInternal: true`):

- `GET /sessions` filters them out (and skips their title/preview log reads for
  speed);
- the SSE stream never forwards `session.updated`, `message.*`, or
  `session.removed` events for them (live Session headers, list rows, and the
  hidden-id cache from earlier lists are all used as markers);
- `GET /sessions/{id}` still works if addressed directly, so a deep link from
  the desktop app is not broken;
- `hideInternal: false` restores the old behaviour (show everything).

Detection markers, in priority order (all verified against the live profile):

1. list row `origin === 'subagent'` or a non-empty `parentSessionId`
   (`SessionSummary` exposes both from the header);
2. list row/projection `projections.values.subagent.identity` — live projection
   caches carry `{ identity: { mode, label, seq } }` for subagent sessions and
   `{}` for top-level sessions;
3. a live Session header with `origin: 'subagent'`, `parentSession`, or
   `delegationDepth > 0` (`session/event`, `agent/assistant-stream`);
4. documented heuristic fallback: an id that does not start with `session-`
   (bare UUID) is internal. Ordinary sessions — including ones created by the
   mobile app via `POST /sessions` (`sessionController.create` mints
   `session-<uuid>`) — always pass this test.

Live measurement (2026-10-30, 114 persisted session logs): 34 top-level
(`session-*`) and 80 subagent (bare UUID) sessions; every bare-id session had
`origin:'subagent'`/`parentSession` and a `subagent.identity` projection row.
The plugin's own persisted list cache held 113 rows (33 top-level, 80 bare), so
after filtering the phone should show **~33 of ~112** sessions (about 79 hidden).

## Curated hidden sessions + stale-blank auto-hide (v0.2.5)

Internal filtering only removes subagent/teammate sessions. For the user's own
leftover test/agent sessions (which look exactly like normal user sessions) the
host keeps a **persisted hidden-id list** and also auto-hides stale blank ones:

- **Hidden file**: JSON next to the pairing state file —
  `<dirname(config.stateFile)>/hidden-sessions.json`, overridable with
  `sessions.hiddenFile`. Shape:
  `{ "version": 1, "updatedAt": "…", "ids": ["session-…", …] }`.
  Missing/corrupt = empty set (warned). Reads are lazy with an mtime+size cache
  (1 s throttle), so polling `/sessions` or SSE does not cause an fs storm; an
  external edit is picked up without a reload.
- **Management API** (bearer): `GET /mobile/v1/sessions/hidden` →
  `{ids, updatedAt}`; `PUT /mobile/v1/sessions/hidden` with
  `{"hiddenIds": ["session-…"]}` replaces the list, writes the file atomically,
  de-duplicates ids, validates `session-<id>` strings, and caps the set at 500
  (invalid input → `400 invalid_request`). The app can build "Hide session" on
  this later.
- **Auto-hide**: a session with an empty/`Untitled session` title, an empty
  preview, not working, and `updatedAt` older than `sessions.hideBlankAfterMs`
  (default 1 h, `0` disables) is hidden from the list but **not persisted**.
  Cold starts only trust DSH's own `blank` flag or already-resolved title/preview
  knowledge, so a real session is never hidden just because enrichment has not
  run yet.
- Hidden ids are excluded from `GET /sessions`, from SSE forwarding, and from
  list enrichment, exactly like internal sessions. `GET /sessions/{id}`,
  prompts, and attachment uploads keep working for them (they are only absent
  from the list).

Live target (2026-10-30): 34 user sessions remained after internal filtering;
the Lead writes the 19 curated leftovers into the hidden file → **~15 sessions
visible** (the 5 blank ones are also auto-hidden by the 1 h rule if the file is
not written yet).

## Session management: rename + archive (v0.3.0)

- `POST /mobile/v1/sessions/{id}/rename` body `{title}` (trimmed, 1..200) →
  `{ok:true, title, seq?}`. DSH's rename path resumes a Session, and this plugin
  deliberately never activates a cold conversation from the phone: the session
  must already have a **live agent** on the host (open it on the desktop).
  Without one the route answers `400 invalid_request` with that explanation.
  DSH `session/title-invalid` → `400`, unknown session → `404`; only ordinary
  visible sessions are eligible (hidden → 404, internal/subagent → 400). A
  successful rename refreshes the host's list title cache immediately.
- `POST /mobile/v1/sessions/{id}/archive` / `.../unarchive` →
  `{ok:true, archived:true|false}`. Uses
  `workspaceController.archiveSession({sessionId})` /
  `unarchiveSession({sessionId})`; the phone **never** sends `stopActivity`, so
  archiving a session with running work is refused (`400 invalid_request`)
  instead of silently stopping host work. Only ordinary visible sessions;
  hidden/internal/unknown → `404`. The archive set is durable in the DSH
  workspace registry (survives restarts) and hides the session from Workspace
  grouping surfaces; unarchive restores its recorded position.
- `GET /mobile/v1/sessions/archived` → `{sessions:[SessionSummary],
  supported:true}` using the same summary mapper as the list (no goal
  enrichment); hidden/internal sessions are always filtered, unreadable archived
  logs are skipped, rows sort by `updatedAt` desc. `supported:false` when neither
  the workspace controller nor a registry archive projection is available.
- Archived sessions are also excluded from the normal `GET /sessions` result
  (best-effort archive-set cache refreshed ~5 s; mutations from this host update
  it immediately). Direct detail/prompt endpoints keep working for archived
  sessions, exactly like hidden sessions.
- `GET /mobile/v1/sessions/{id}/approval-policy` → `{supported, live, policy}`
  where **`policy` is `ask` (default) or `never` (unattended)**. Live sessions
  read DSH `approval.effectivePolicy(agent.session)`; cold sessions are **never
  activated** — their durable `approval/policy` log fold is used with `ask` as
  the documented fallback. Unknown/hidden/internal → `404`.
- `PUT /mobile/v1/sessions/{id}/approval-policy` body `{policy}` → the same
  shape. Writes need a **live** agent (`approval.setPolicy`); a cold session
  answers `400 invalid_request` ("open the session first"), an invalid policy is
  `400`, and a missing approval service is `501`. The endpoint never resumes a
  session.

## Agent preset roster (v0.3.1)

`GET /mobile/v1/presets` (bearer) exposes the real preset roster so the
create-session sheet does not need hard-coded names:

```json
{
  "presets": [
    { "id": "default", "name": "Default", "description": "General work",
      "isDefault": true },
    { "id": "focused", "name": "Focused", "isDefault": false, "broken": true }
  ],
  "supported": true,
  "defaultId": "default"
}
```

- Merges `agentPresets.list()` (id/name/description/order/broken) with
  `remoteExportList()` (authoritative `isDefault` marker) and the `defaultId`
  getter; rows sort by `order` (missing last), then id. Broken presets remain in
  the roster with `broken:true` so the app can disable them; the diagnostic
  reason itself is never exposed.
- `name` is trimmed/capped at 200 chars and `description` at 1000; ids stay
  opaque strings. No module paths, composition rows, or host files are returned.
- Service absent → `{presets:[], supported:false}`. Read-only: never activates an
  Agent or Session. The phone can pass a preset id as `preset` when creating a
  session (`POST /sessions` forwards it to DSH).

## Host-wide message search (v0.3.13)

`GET /mobile/v1/search?q=<text>&limit=50&offset=0&role=&since=&until=` (bearer)
searches transcripts the phone has not opened. Only sessions visible in
`/sessions` are scanned (hidden/auto-hidden/internal and archived sessions are
excluded); unreadable sessions are skipped.

```json
{
  "hits": [
    { "sessionId": "session-…", "sessionTitle": "…", "messageId": "m_…",
      "role": "user|assistant|tool", "snippet": "…needle…",
      "seq": 42, "createdAt": "2026-10-30T09:41:12Z" }
  ],
  "supported": true,
  "truncated": false,
  "offset": 0,
  "nextOffset": 2,
  "hasMore": true,
  "scanIncomplete": false,
  "scannedSessions": 14,
  "totalSessions": 14,
  "filters": {
    "role": ["assistant", "tool"],
    "since": "2026-10-30T09:40:00.000Z",
    "until": "2026-10-30T09:50:00.000Z"
  }
}
```

- Case-insensitive substring over human user text, assistant text, and
  `tool/call` command details (`toolDetail(arguments)`, role `tool`). Injected
  agent context, scheduled prompts, tool results, reasoning and attachment bytes
  are not searched. The fast semantic-document reader (`sessionQuery.filterEvents`)
  extracts the same text/tool-call fields (no image payloads, no replay
  validation) and is used directly, including for user-message documents — no
  snapshot verification is performed. Semantic documents do not carry the
  user-message `source.kind`, so scheduler deliveries are excluded by their
  self-identifying framing (`[SCHEDULE REMINDER]` or
  `This is a scheduled message from the user`, case-insensitive substring).
  This is a documented heuristic: a human prompt that literally contains that
  framing would also be omitted from search (it still appears in the transcript).
  The raw snapshot fallback uses the strict human predicate.
- **Jump-to-message seq:** each hit may carry the optional integer `seq` — the
  durable DSH event sequence. It comes from the semantic document (`seq` on the
  `sessionQuery.filterEvents` path) or the raw event on the snapshot fallback.
  It is omitted when the sequence cannot be determined (never guessed); the hit
  `messageId` and ordering/meaning are unchanged (`m_ev_<seq>` fallback ids stay
  as before). The phone can combine `sessionId` + `seq` with the same optional
  `seq` present on session-detail messages (see "Session fork + message seq") to
  scroll to the matching message.
- `q` must be 2..200 chars (trimmed); `limit` 1..100 (default 50). Hits sort
  newest-first; `snippet` is a single-line ~200-char window around the first
  match with ellipses; `truncated:true` means a **scan cap/budget** stopped the
  scan before it was exhaustive (independent of pagination). `scanIncomplete`
  is the explicit honesty signal: it is true when budget/caps stopped the scan
  before every visible session was covered, and `scannedSessions`/`totalSessions`
  report the progress. An empty `hits` with `scanIncomplete:true` means “not
  scanned exhaustively”, **not** “no matches anywhere”.
- **Pagination** (`offset`, optional):
  - `offset` is an integer **0..500** (default 0); invalid/negative/too large →
    `400`. It is applied **after** newest-first ordering and **before** slicing
    to `limit`, so a fixed query/data yields stable pages. The response always
    echoes `offset`.
  - `hasMore` is true when at least one more collected match exists beyond the
    page, **or** when the bounded scan was truncated *and this page returned at
    least one hit*. `nextOffset` is `offset + hits.length` when `hasMore` is
    true, else `null`.
  - An offset beyond the collected matches returns an empty page with
    `hasMore:false` and `nextOffset:null` (never `nextOffset === offset`, so a
    load-more loop cannot spin). `truncated` is unaffected by pagination.
  - No `offset` keeps the previous response shape plus the additive
    `offset`/`nextOffset`/`hasMore`/`scanIncomplete`/`scannedSessions`/
    `totalSessions` fields.
- **Filters** (optional; no filters preserves the exact unfiltered response):
  - `role=user|assistant|tool` — comma-separated list (max 3, duplicates
    collapsed, echoed in canonical order). Any other role → `400`.
  - `since` / `until` — inclusive bounds on the hit's `createdAt`; accept
    ISO-8601 (`2026-10-30T09:40:00Z`, offsets accepted and echoed as UTC ISO) or
    epoch milliseconds. Invalid timestamps or `since > until` → `400`.
    **When any date bound is set, matches whose event has no timestamp are
    excluded** (the else-branch is unchanged and includes them).
  - When at least one filter is set, the response adds
    `filters:{role?:[…], since?:ISO, until?:ISO}` echoing the applied values;
    without filters the response shape is byte-for-byte the previous one.
  - Filters are applied during the scan, so caps/`truncated` reflect filtered
    work (a filtered request may scan the same budgets but collect fewer hits).
  - Offset pagination applies to the filtered, newest-first result set.
- Cost bounds per request: at most **200 visible sessions** (newest activity
  first), **2000 events per session** on the snapshot fallback, **500 collected
  matches**, and an **8 s whole-request wall-clock budget**. The budget starts
  *before* the session-list work; session reads run in chunks of **4** (bounded
  concurrency) and every read is raced against the remaining time (the snapshot
  fallback also receives an abort signal). The scan stops when the budget
  expires, returns already-found hits, and reports `scanIncomplete:true`.
  Search reads are text-only: the preferred semantic reader does not materialise
  tool-result images; only if it is unavailable/fails does the adapter read
  `sessionQuery.readSession` snapshots (no arbitrary host files, no paths
  returned). **Offset paginates this bounded scan**: very old/long searches can
  still be truncated by the caps or budget, and pages only cover the collected
  match set.
- Missing/unreadable session store → `{hits:[], supported:false, offset,
  nextOffset:null, hasMore:false, scanIncomplete:false, scannedSessions:0,
  totalSessions:0}`. Search never activates or resumes a Session.
- Examples:

  ```text
  GET /mobile/v1/search?q=release&role=user,assistant&limit=20
  GET /mobile/v1/search?q=release&limit=20&offset=20     # next page
  GET /mobile/v1/search?q=release&limit=20&offset=40&role=assistant
  GET /mobile/v1/search?q=test&since=2026-10-30T00:00:00Z&until=2026-10-31T00:00:00Z
  GET /mobile/v1/search?q=patch&since=1761800000000      # epoch ms
  ```

## Push notifications (ntfy) (v0.3.4)

The host can publish short notifications to an [ntfy](https://ntfy.sh) topic so
the phone alerts while the app is closed. Disabled by default; no third-party
account is required for a public topic.

### Setup

1. Install the ntfy app (or use the ntfy web UI) and subscribe to a topic.
   Choose a **long, unguessable topic** (for example 20+ random
   `[A-Za-z0-9_-]` characters): anyone who knows the topic can publish to it and
   read its history on a public server.
2. Pair the phone with this host, then enable push from the app or directly:

   ```bash
   curl -X PUT http://127.0.0.1:3543/mobile/v1/push \
     -H "Authorization: Bearer <device-token>" \
     -H "Content-Type: application/json" \
     -d '{"enabled":true,"topic":"my-long-random-topic"}'
   ```

   `server` defaults to `https://ntfy.sh`; a self-hosted server is allowed
   (https, or plain http only for explicit `localhost`/`127.0.0.1`/`[::1]`).
   For a protected topic, add `"token":"tk_..."`; the token is stored locally in
   plain text and sent as `Authorization: Bearer` on each publish.
3. `POST /mobile/v1/push/test` sends one test notification. Failures surface as
   `502` with a short reason; the token is never echoed.

### Events published

| Event | Title | Deep link |
|---|---|---|
| `approval.requested` | `Approval needed` (priority 4, warning) | `dshmobile://inbox` |
| `question.requested` | `Input needed` (priority 4) | `dshmobile://inbox` |
| `goal.changed` pause/block/complete | `Goal paused` / `Goal blocked` / `Goal complete` | `dshmobile://session/<id>` |
| turn completion (`turn/end`) | `Turn finished` (5 s dedupe per session) | `dshmobile://session/<id>` |

Every publish sends ntfy headers `Title`, `Priority`, `Tags`, `Click` and a short
plain-text body. Deep links are limited to inbox and session views — pushes never
carry answer/approval actions. Notifications contain only a short title/summary,
never transcripts, tool output, paths, tokens, or headers.

### Quiet hours + session mutes (v0.3.3)

The host can honor the phone's quiet/mute preferences for ntfy pushes. State is
persisted with the push config and survives restarts:

```json
{
  "quietHoursEnabled": true,
  "quietStartHour": 22,
  "quietEndHour": 7,
  "timeZone": "America/New_York",
  "mutedSessionIds": ["session-…"]
}
```

- `PUT /mobile/v1/push/suppress` accepts a **partial** object (missing fields keep
  their current values; first defaults are `disabled`, 22:00–07:00, empty zone,
  no mutes, so quiet hours cannot engage until a valid IANA `timeZone` is set).
  It returns `{quietHoursEnabled, quietStartHour, quietEndHour, timeZone,
  timeZoneValid, mutedCount}`
  (never the token). `GET /mobile/v1/push` adds the same object as `suppression`.
- Validation: hours are integers 0..23, `timeZone` must be a valid IANA zone
  (checked with `Intl.DateTimeFormat`), quiet hours cannot be enabled without a
  zone, muted ids must match the session-id shape, dedupe, and are capped at
  **500** (`400 invalid_request` on any failure).
- **Quiet window:** local hour is evaluated in the configured zone through
  `Intl` (so DST transitions use the runtime's rules, not a fixed UTC offset).
  Start is inclusive and end is exclusive; `start > end` crosses midnight
  (e.g. 22→7 covers 22:00–23:59 and 00:00–06:59); `start === end` means **no
  quiet window**. While quiet, **all** event pushes are suppressed (approval,
  question, goal, turn completion). The explicit
  `POST /push/test` diagnostic bypasses suppression and always attempts delivery.
- **Mutes:** session-scoped pushes (approval, question, turn completion) are
  suppressed when their session id is in `mutedSessionIds`. Goal/usage pushes
  intentionally carry no mutable session identity, so mute never drops them —
  only quiet hours can (this keeps goal/usage notifications consistent). A
  suppressed notification is dropped before the dedupe window and
  rate-limit budget are consumed.
- Suppression state is atomic with the rest of the push state in
  `<dirname(stateFile)>/push-config.json` under `suppression`; the push token
  remains plain text at rest (documented below) and is never included in the
  suppression summary.

### Security and privacy (read this)

- **The topic is effectively a secret**: use a random topic, ideally with an ntfy
  access token on a protected/self-hosted server. A public topic's history is
  readable by anyone who guesses/knows it.
- **The token is stored in plain text** at
  `<dirname(stateFile)>/push-config.json` (next to the pairing state) and is
  never returned by `GET /push`, echoed in errors, or logged. Prefer a
  public-topic setup without a token when possible.
- **SSRF guard**: `server` must be `https://` by default. Plain `http://` is
  accepted only for an explicit local host (`localhost`, `127.0.0.1`, `[::1]`);
  URLs containing credentials, paths/queries/fragments, or literal
  loopback/private/link-local IP targets are rejected. A hostname that *resolves*
  to a private address cannot be detected synchronously — only configure servers
  you trust.
- **The host honors host-persisted quiet hours and session mutes** through
  `PUT /push/suppress` (see above). Purely app-local state (foreground
  suppression, per-app notification channels) still cannot be observed by the
  host; while the app is open, ntfy pushes may duplicate in-app alerts. Clear or
  disable quiet/mute state, or disable push with `PUT /push {"enabled":false}`,
  when those duplicates matter.
- **Delivery limits**: 5 s fetch timeout; bounded queue (50, further
  notifications dropped and logged); a circuit breaker pauses publishing for
  60 s after 2 consecutive failures; a rolling cap of 30 notifications/minute;
  per-event dedupe and a 5 s per-session turn rate limit. Failures are logged and
  never block the event fan-out. No retry storm: queued work is dropped while the
  circuit is open.

## Models + thinking effort (v0.3.6)

> **Important — changing a model also changes the default for new sessions.**
> `PUT /sessions/{id}/model` is not session-only: DSH saves the accepted
> selection as the host's default model selection in the background, so later
> sessions start with it. The request succeeds even if that background save
> fails (DSH logs it). Use the UI's "default" label to make this visible.

- `GET /mobile/v1/models` → `{supported:true, default:{provider,model,reasoningEffort?},
  groups:[{id,name,models:[{id,name,description?,reasoning?:{efforts:[{id,name,
  description?}],defaultEffort?}}]}], failures:[{id,name,message}],
  truncated:boolean}`. Only documented fields are copied (no provider credentials
  or API keys), strings are trimmed/capped, and lists are capped at **50 groups,
  100 models/group, 20 efforts/model and 50 failures**; `truncated:true` reports
  any cap hit. **Provider failure text is never forwarded**: `failures[].message`
  is the fixed generic `Provider unavailable`, and provider id/name are
  credential-redacted and capped (raw provider errors can contain keys, Bearer
  headers or URL userinfo). Missing `sessionController.modelCatalog` → `501`.
- `GET /mobile/v1/sessions/{id}/model` → `{supported, live, selection,
  source:"session"|"default"}`. Cold sessions are **never activated**: the
  durable `model/selection` (ModelSelection) / `request/header` config
  (`EpochHeader.config`) fold is used; without a logged selection the host
  default (`agentDefaultModel.currentSelection()`) is returned with
  `source:"default"` (`selection` may be `null` when neither is available). The
  fold is verified against the installed DSH event types; re-check it live after
  a reload. Unknown/hidden/internal → `404`.
- `PUT /mobile/v1/sessions/{id}/model` body `{provider, model,
  reasoningEffort?}` → `{supported:true, live:true, selection}` after DSH's
  `sessionController.selectModel` and an installed-selection read-back. Writes
  are **live-only** (cold → `400` "open it on the host first", never resumes);
  the live agent is re-checked immediately before the call, but DSH's
  `selectModel` resolves the agent itself, so a session that detaches in the tiny
  gap between that last check and the call is a documented residual race (the
  adapter never calls it when no agent is present). Invalid/missing fields →
  `400`; unavailable model → `400` with DSH's short message; missing service →
  `501`. Selecting a model **also becomes the host default for new sessions**
  (DSH saves it in the background; a save failure is logged by DSH and does not
  fail the request). "Thinking" controls are modelled as `reasoningEffort`.

## Live queue / prompt inbox (v0.3.7)

A live agent keeps prompts it has not claimed yet in its inbox; the phone can
inspect and mutate them.

- `GET /mobile/v1/sessions/{id}/queue` → `{supported, live,
  items:[{itemId, text, target:"next-turn"|"next-step", attachmentCount,
  createdAt?}]}`. Live items are `agent.inbox.nextTurn` first, then `nextStep`;
  `text` joins the message's text blocks, `attachmentCount` counts image/file
  blocks, and `createdAt` is only present when the message carries a timestamp
  (DSH `MessageBase` itself has none). The list is capped at **100 items**.
  Cold sessions are **never activated**: `{supported:true, live:false, items:[]}`;
  no `agents` service → `supported:false`; unknown/hidden/internal → `404`.
- `PATCH /mobile/v1/sessions/{id}/queue/{itemId}` body `{action, text?}` →
  `{accepted:true}` via `sessionController.updateQueue` with the exact DSH action
  shape: `edit` → `{kind:"edit",content:[{type:"text",text}]}` (text trimmed,
  1..20000 chars), `remove` → `{kind:"remove"}`, `steer` → `{kind:"steer"}`.
  `edit` requires text; `remove`/`steer` reject any `text`. Writes are
  **live-only** (cold → `400` "open the session first"; the adapter re-checks
  `agents.get` immediately before the call because DSH's `updateQueue` itself
  resolves a cold agent); unknown item → `404`; `steer` is only valid for a
  next-turn item while the agent is running (`400` short message); hidden/
  internal/unknown → `404`; no service → `501`.
- **Security/behaviour**: queue edits change what the live agent will actually
  see and do next. `remove` also retires DSH's prompt uploads for that item;
  `steer` immediately injects the item into the running turn. The phone can only
  mutate a queue through a live host session — nothing is activated on the host
  by reads, and no other client's transcript is exposed beyond that session's
  own pending prompt text.

## Session fork + message seq (v0.3.8)

- `POST /mobile/v1/sessions/{id}/fork` body `{atSeq?}` → `{sessionId,
  forkedFrom, atSeq?, warning?}`. The fork copies the durable prefix up to the
  cut into a **new** session (DSH records `parentSession` metadata and attaches
  the same workspace); the source session is untouched and never deleted. With
  no `atSeq`, DSH picks the latest completed-turn prefix; an explicit `atSeq`
  must be a non-negative safe integer identifying an existing event boundary.
  Forking is cold-safe (DSH reads the source via `sessionQuery.observeSession`;
  it does not activate the source agent). Hidden/internal/unknown → `404`;
  invalid boundary or `session/fork-unavailable` → `400` with a short message;
  missing service → `501`; other failures → `500`. If the child session was
  created but workspace attach failed, the response is still `200` with
  `warning:"workspace-attach-failed"` and the new `sessionId` so the phone can
  open it (documented partial success). No host paths are returned.
- Session detail messages (user/assistant/tool) now carry an optional durable
  `seq` (the event sequence used as a fork boundary). The field is omitted when
  the event has no sequence, so existing message ids/fallbacks and list payloads
  stay backward compatible; the phone can send a message's `seq` as `atSeq`.
- **Security/behaviour**: fork only branches history the paired device can
  already read; it does not grant access to hidden/internal sessions (404), does
  not delete or mutate the source, and errors never include workspace paths.

## Headless / cloud (`dsh web` CLI profile)

The plugin is **not desktop-only**. It runs unchanged inside a headless
`dsh --profile web` process (no Electron UI); the phone then talks to that host
through Tailscale or any TLS proxy. Verified PoC (2026-10-30, this Windows build
launched via Electron-as-node; the same commands work for the npm CLI on Linux):

```powershell
# 1) isolated DSH home (never touch the desktop profile/home)
$poc = 'C:\dev\dsh-mobile\build\cloud-poc'
$pocHome = "$poc\dsh-home"; New-Item -ItemType Directory -Force -Path $pocHome
$env:ELECTRON_RUN_AS_NODE = '1'          # Windows launcher only; npm `dsh` needs nothing
$env:DSH_HOME = $pocHome
$exe = 'C:\.dsh\DeepSeek Harness.exe'
$bin = 'C:\.dsh\resources\app.asar\dsh\node_modules\@deepseek-ai\dsh\lib\bin.js'

# 2) initialize the shipped `web` profile template
& $exe $bin --profile web --help

# 3) `dsh plugin add` shells out to pnpm: put the bundled pnpm on PATH
New-Item -ItemType Directory -Force -Path "$poc\bin" | Out-Null
@(
  '@echo off',
  '"C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe" "C:\.dsh\resources\runtime\primary-runtime\dependencies\pnpm\bin\pnpm.cjs" %*'
) | Set-Content "$poc\bin\pnpm.cmd" -Encoding ascii
$env:Path = "$poc\bin;$env:Path"
& $exe $bin plugin --profile web add 'C:\dev\dsh-mobile\host-plugin'
# → adds "dsh-mobile-host": "link:…/host-plugin" to dependencies and appends
#   "dsh-mobile-host" to package.json dsh.profile.bundles + a node_modules link.
#   NO hand-editing of the profile package.json is required on this build.

# 4) port/state override in the isolated profile's cordis.patch.yml
#    ($pocHome\profiles\web\cordis.patch.yml) — id-targeted config replaces the
#    bundle row config entirely, so repeat host/port/stateDir/publicBaseUrl:
@'
- id: mobile-api
  config:
    port: 3544
    host: 127.0.0.1
    hostName: cloud-poc
    stateDir: C:/dev/dsh-mobile/build/cloud-poc/dsh-home/state
    publicBaseUrl: http://127.0.0.1:3544
'@ | Set-Content "$pocHome\profiles\web\cordis.patch.yml" -Encoding ascii
& $exe $bin --profile web --dump-config   # verify the composed mobile-api row

# 5) boot headless (prints the web UI URL with its one-time ?token=)
& $exe $bin --profile web --no-open --port 8091 --host 127.0.0.1
# 6) verify from another shell
Invoke-RestMethod http://127.0.0.1:3544/mobile/v1/health
Invoke-RestMethod -Method Post http://127.0.0.1:3544/mobile/v1/pair/new
# ... pair/complete → token, then GET /host, /sessions, POST /sessions, SSE.
```

PoC evidence: `/health` = `{"ok":true,"version":"0.2.5","apiVersion":1,"paired":false}`;
pair/new → code + `dshmobile://pair?…host=cloud-poc`; pair/complete → token,
deviceId, `host:{name:"cloud-poc",apiVersion:1,version:"0.2.5"}`; `GET /sessions`
`[]`; `POST /sessions` created `session-<uuid>` (no workspace/cwd in an isolated
home); `prompt` accepted (`accepted:true` + runId); SSE emits `host.status` then
`session.updated` envelopes; the web UI root (with the boot-printed `?token=`)
returns 200 HTML with `__DSH_BOOT__`.

Cloud caveats (required config outside `host-plugin/`):

- **Isolate state**: the plugin's default `stateDir` is `~/.dsh-mobile`, so on a
  machine that also runs the desktop app set `stateDir` (as above) or the two
  hosts share pairing/hidden state. The session-list cache is now scoped next to
  `stateFile` (no more shared temp cache between profiles).
- **Pairing from the cloud host**: `/pair/new` is loopback-only *and* rejects
  reverse-proxy headers, so mint the code locally (SSH `curl -X POST
  http://127.0.0.1:3544/mobile/v1/pair/new`); the phone uses `pair/complete`
  through Tailscale. Set `publicBaseUrl` so the QR payload carries the public URL.
- **Model access**: a fresh isolated home has no LLM credentials. Session
  creation/prompt admission and the API work, but model turns (streaming
  deltas) need the profile's provider key/account configured; `hostName` should
  be set to a stable public name for `/host` and the QR payload.
- Linux/npm CLI: run `dsh` from the npm install with `DSH_HOME` set; no native
  modules or Windows-only APIs are used by the plugin (path matching is
  platform-aware, everything else is Node built-ins).

## Develop and test

There are no npm installs and no external runtime dependencies. Use the bundled
Node 24 (the `node` on PATH may be older):

```powershell
cd C:\dev\dsh-mobile\host-plugin
C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe --test "test/*.test.mjs"
```

Current: **119 tests, 119 pass, 0 fail** (pairing lifecycle, rate limiting, auth
401/403, session list/detail, internal-session hiding + SSE filtering, curated
hidden file + management API + mtime refresh, stale-blank auto-hide boundaries,
headless/Linux helpers (platform-aware path matching, profile-scoped list cache),
prompt accept, attachments: base64/storage/bounds/binding/expiry/consumption,
image+file upload happy paths, prompt with empty text + attachments, O3 staging
error mapping, pagination, SSE framing/replay/heartbeat, error shapes, adapter
event fan-out, full plugin start/pair/persist/stop lifecycle, load-safe
activation). Tests bind ephemeral loopback ports only and never touch the real
profile.

## Install / reload (do not install during development)

`plugin_manager` action `install_bundle` with
`target` = `C:\dev\dsh-mobile\host-plugin` (absolute package directory).
`install_bundle` performs package linking and bundle selection; never edit the
profile's `package.json`/`cordis.patch.yml` by hand. Read the returned
`application`/`warnings`; the Host row id is `mobile-api`. Verify with
`cordis_inspect_query` host `Config.listConfigs` for the `dsh-mobile-host` entry,
then `POST http://127.0.0.1:3543/mobile/v1/pair/new`.

Port `3543` must be free (stop `tools/fake-host` first); if the bind fails the
plugin logs the error and stays loaded without a server. Activation is
load-safe: a bad row config, failed bind, or attachment-service problem is
logged and never thrown out of `apply`.

**Live reload note (v0.2.5):** the profile currently holds a *linked* install of
this directory, but a running Host process keeps the JavaScript modules it
loaded. This release (curated hidden list + management API + blank auto-hide) is
therefore only live after the Lead triggers the plugin reload/HMR config touch
(or restarts the desktop app). After reload, write/pass the initial
`hidden-sessions.json` (or `PUT /mobile/v1/sessions/hidden`) with the 19 curated
ids; the file lives next to `config.stateFile` and is loaded lazily, so no
restart is needed for later edits. No file change in this directory is picked up
by an already-running module instance.

## Generate a pairing code locally

The pairing code is only issued by the loopback endpoint, so run it on the
desktop. Directly (not through Tailscale, which is rejected on purpose):

```powershell
Invoke-RestMethod -Method Post http://127.0.0.1:3543/mobile/v1/pair/new
# code       : 482913
# expiresAt  : 2026-10-30T10:05:00Z
# qrPayload  : dshmobile://pair?base=https%3A%2F%2Fpc.tailnet.ts.net&code=482913&host=pc
```

Enter the 6-digit code on the phone (`POST /mobile/v1/pair/complete`) or scan the
QR payload once the app has a scanner. Codes are one-time and expire in 5 minutes.

## Configuration (`cordis.patch.yml` row `config:`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | set `false` to keep the row loaded but off |
| `host` | `127.0.0.1` | validated loopback only (`::1`, `localhost` accepted) |
| `port` | `3543` | Tailscale Serve target port |
| `hostName` | `os.hostname()` | shown in `/host` and the QR payload |
| `publicBaseUrl` | `''` | e.g. `https://pc.tailnet.ts.net`; only fills `qrPayload.base` |
| `stateFile` | `<stateDir>/host-state.json` | persisted hashed device records |
| `pairing.codeTtlMs` | `300000` | code lifetime (5 min) |
| `pairing.maxFailedAttemptsPerMinute` | `5` | per-IP failure budget |
| `pairing.maxDevices` | `32` | oldest records are dropped beyond this |
| `pairing.tailscaleAutoPair` | `true` | trust Tailscale Serve identity headers for one-tap pairing; only explicit `false` disables |
| `pairing.tailscaleLogin` | `''` | optional case-insensitive Tailscale login allowlist; empty = any tailnet user |
| `sse.heartbeatMs` | `20000` | `ping` interval |
| `sse.bufferSize` | `500` | replay ring size for `since` |
| `sessions.defaultLimit` / `maxLimit` | `100` / `500` | transcript page size |
| `sessions.previewLimit` | `20` | newest sessions given a one-line preview (bounded log reads) |
| `sessions.hideInternal` | `true` | hide subagent/teammate sessions from `/sessions` and SSE (`false` shows everything) |
| `sessions.hiddenFile` | `<stateFile dir>/hidden-sessions.json` | curated persisted hidden-id file (v0.2.5) |
| `sessions.hideBlankAfterMs` | `3600000` | auto-hide Untitled/blank sessions older than this (0 disables) |
| `approvals.mobileAnswer` | `true` | allow the phone to answer approvals (`readOnly:false`, POST Allow/Deny); `false` = read-only mirror (POST `501`) |
| `questions.mobileAnswer` | `true` | allow the phone to answer question batches (`readOnly:false`, POST rows until complete); `false` = read-only mirror (POST `501`) |
| `attachments.maxBytes` | `10485760` | decoded per-attachment limit (10 MB) |
| `attachments.maxBase64Bytes` | `14680064` | encoded `dataBase64` limit (14 MB) |
| `attachments.ttlMs` | `86400000` | pending attachment lifetime (24 h) |
| `attachments.maxPerPrompt` | `8` | attachment ids per prompt |
| `attachments.maxPendingCount` | `32` | pending (unprompted) attachments |
| `attachments.maxPendingBytes` | `67108864` | pending image base64 bytes kept in memory |
| `artifacts.roots` | `[]` | absolute directories allowed for local Markdown image links; empty = cwd-relative only |
| `artifacts.remote` | `{enabled:false,…}` | opt-in remote image proxy (`enabled`, `maxBytes` ≤25 MB, `timeoutMs`, `ttlMs`, `cacheEntries`); disabled = no remote refs/reads |
| `http.maxBodyBytes` | `15728640` | request body cap (fits a 14 MB base64 upload) |

## Recovery-code pairing (v0.3.10)

A long-lived **single-use recovery code** lets the phone pair again after the
app is cleared/uninstalled or replaced — without a desktop-generated 6-digit
code.

- In Settings (or via the API), `POST /mobile/v1/recovery` (bearer) creates or
  rotates the code. It is returned **once** as a grouped base32 string such as
  `ABCD-EFGH-…` (13 groups of 4 = **52 chars / 256 bits of entropy** from 32
  random bytes; spaces/dashes/case are ignored on input). Save it in a password
  manager; `GET /mobile/v1/recovery` only reports `{supported, configured,
  createdAt}` and never returns the code or its hash.
- To pair a new/cleared phone, `POST /mobile/v1/pair/recovery` (no bearer, like
  `pair/complete`) with `{code, deviceName?, platform?}`. On success the host
  consumes the old code, mints a device token (same device-token model as normal
  pairing, maxDevices enforced), **rotates the recovery code**, and returns
  `{token, device, recoveryCode, createdAt, host, workspaces}` — store the new
  `recoveryCode`; the old one is dead immediately. This works even when **no**
  devices are paired (a saved code can bootstrap access).
- Only a **SHA-256 hash** of the recovery code is stored in the **same atomic
  pairing state file** as the device hashes, so a recovery rotation and the new
  device are persisted in one write (no orphan device / replayable code window;
  on write failure the in-memory change is rolled back and the request fails).
  Backward compatible: old devices-only files load fine, and a legacy
  `<stateFile>.recovery.json` sidecar is read for migration and removed on the
  next successful state write. Recovery codes have no expiry; they are
  single-use and auto-rotate on every successful use.
- `POST /mobile/v1/pair/recovery` is **public** (no bearer, exactly like
  `pair/complete`); bearer auth starts after it in the router.
- Invalid/absent/used codes answer `401 invalid_code` (counted per IP against the
  same pairing limiter; repeated failures answer `429 rate_limited`). Short
  malformed bodies answer `400 invalid_request`.
- **Security**: treat the recovery code like a password — anyone who has it can
  pair while it is unconsumed. Rotate it immediately if it may have leaked; use
  device revocation to remove lost phones. It is not a replacement for keeping
  the desktop available, and it does not bypass hidden/internal session rules.

## One-tap Tailscale pairing (v0.3.11)

When the host is reached through Tailscale Serve, Serve adds identity headers to
proxied requests. The phone can then pair with one tap, no desktop code:

- `GET /mobile/v1/pair/methods` (public) advertises what is available:
  `{desktopCode:true, recovery:true, tailscaleAutoPair, tailscaleLoginRequired}`.
  The app should only show the one-tap button when `tailscaleAutoPair` is true.
- `POST /mobile/v1/pair/tailscale` (public, no bearer) body
  `{deviceName?, platform?}` trusts the `Tailscale-User-Login` header (fallback
  `Tailscale-User-Name`, both trimmed). Success mints a normal device token and
  returns the `pair/complete`-style payload plus
  `pairingMethod:"tailscale"` and the trimmed `identity`. Errors:
  `403 forbidden` when `pairing.tailscaleAutoPair` is `false` or the login is not
  on the configured allowlist; `400 invalid_request` ("Tailscale identity is
  missing; open this host through Tailscale Serve") when no identity header is
  present; `429 rate_limited` when that IP exhausts the shared pairing failure
  budget. `maxDevices` applies like every other pairing path.
- Configuration: `pairing.tailscaleAutoPair` (default **true**) and
  `pairing.tailscaleLogin` (optional, case-insensitive exact login allowlist).
  No desktop action or extra env var is required.
- **Security**: the identity is trusted only because the HTTP server binds
  loopback and Tailscale Serve is the only reachable proxy in front of it; the
  **Tailscale ACL is the real boundary** — restrict who can reach the serve port
  and set `pairing.tailscaleLogin` when you want a second lock. Direct tailnet
  clients cannot hit the loopback port, so they cannot supply these headers
  themselves; a misconfigured proxy/port-forward that exposes the loopback
  listener would defeat this (residual risk, documented). Tokens are never
  logged; only the identity string is echoed in the pairing response.

## Security notes

- **Loopback bind is enforced**, not just defaulted: `0.0.0.0`/LAN addresses are
  refused at config resolution. TLS is terminated by Tailscale Serve.
- **`/pair/new` behind a proxy**: every Tailscale request appears to come from
  `127.0.0.1`, so loopback alone is insufficient. Requests carrying
  `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Real-IP`,
  `Forwarded`, or any `Tailscale-User-*`/`Tailscale-Funnel-*` header get
  `403 forbidden`. Only a direct desktop call can mint a code.
- **Device tokens**: 32 random bytes (base64url), sent only once in the
  `pair/complete` response. Only the SHA-256 hash is stored (JSON state file) and
  compared timing-safely. `/health` reports a boolean `paired`, never secrets.
- **Recovery codes**: 32 random bytes rendered as 52 base32 chars (256-bit
  entropy); only the SHA-256 hash is stored **in the main atomic pairing state
  file** (timing-safe compare), GET never returns it, and each use is single-use
  + auto-rotating. A legacy `.recovery.json` sidecar is migrated into the main
  file on the next write. Like a password, anyone holding an unconsumed code can
  pair; rotate immediately if leaked. Failures are rate-limited per IP.
- **Tailscale identity**: `Tailscale-User-*` headers are trusted only because the
  server is loopback-bound and Tailscale Serve is the front proxy; restrict the
  Serve port with Tailscale ACLs and set `pairing.tailscaleLogin` for a second
  lock. The allowlist is case-insensitive; token responses never log the token;
  revoked devices need to re-pair through a current viable method. A proxy/port
  misconfiguration that exposes the loopback listener would let clients forge
  headers (residual risk).
- **Device management**: any paired device can list and revoke other paired
  devices with its bearer token. The list exposes only `deviceId`, `deviceName`,
  `platform`, `pairedAt` and `lastSeenAt` — never token hashes, public keys or
  addresses. The current device cannot revoke itself (`400 invalid_request`);
  revoking another device takes effect immediately (its next request is `401`
  and any already-open SSE stream for it is closed by the host), the reduced list
  is persisted **before** the HTTP response, and a persistence failure rolls the
  removal back and answers `500` so a crash cannot resurrect the device after a
  restart. `lastSeenAt` is in-memory metadata (updated on requests, `null` after a
  reload) and the host keeps **no revocation audit log**; the model treats every
  paired device as equally trusted (no admin/role/re-auth), so protect pairing
  tokens accordingly.
- **Pairing codes**: 6 digits, one use, 5-minute expiry, failed attempts limited
  to 5/minute per IP (`429 rate_limited`).
- **Internal sessions are hidden by default**: the phone list and SSE stream
  never contain subagent/teammate sessions; the adapter also skips their log
  reads. `sessions.hideInternal: false` is the documented escape hatch. The
  filter is privacy-oriented (a typo keeps it on) and does not restrict direct
  `GET /sessions/{id}` lookups.
- **Approval answering** (`approvals.mobileAnswer`, default on): any paired
  device can allow or deny host tool approvals from the phone; the first answer
  wins and a desktop prompt may remain visible until it is answered there (that
  later answer is ignored). Set `approvals.mobileAnswer: false` to restore the
  read-only mirror (POST answers `501`), and treat pairing tokens as high-trust
  while enabled. Pending approvals are polled, not pushed, and capped at 32 rows
  in memory (a row dropped by the cap can no longer be answered from the phone).
- **Approval policy `never` is unattended mode**: switching a session to `never`
  auto-rejects every action that would otherwise ask for approval (DSH's
  `approval.effectivePolicy` short-circuits to `rejected`), so tools may fail
  instead of prompting. `ask` is the default; a cold session's policy can be read
  but only a live session can be changed from the phone (no silent agent start).
- **Question answering** (`questions.mobileAnswer`, default on): any paired
  device can answer host questions; DSH requires every question in a call exactly
  once, so the phone must answer **all** rows of a batch for the answers to reach
  the agent (one `POST /questions/{id}` per row). Partially answered batches stay
  pending and the desktop can still answer first; a desktop answer removes the
  remaining rows. Set `questions.mobileAnswer: false` to restore the read-only
  mirror (POST answers `501`).
- **Schedules**: paired devices can create/delete reminders bound to visible
  sessions; a schedule's `instruction` is later injected into the owning
  session by DSH, so schedule creation is prompt authority. Internal/hidden
  bindings are rejected or 404; catalog reads and deletion never activate a
  session; timestamps are derived read-only from the session log.
- **Session management**: rename requires the conversation to be open on the
  host (cold sessions are never activated by the phone) and is limited to
  ordinary visible sessions; archive/unarchive change DSH's durable workspace
  archive set only for ordinary visible sessions, and the phone never asks the
  host to stop running work (`stopActivity` is never sent, active sessions are
  refused with `400`). Archived sessions remain directly addressable for detail
  and prompts, exactly like hidden sessions.
- **Attachments**: every upload requires a paired device, a valid session, and
  validated canonical base64; ids are device+session bound and single-use; the
  API never returns DSH attachment paths. Pending data is memory-bounded so a
  phone cannot grow host memory without sending prompts. Internal sessions cannot
  receive uploads (`400 invalid_request`). Read-back is authenticated and
  session-scoped: only attachment ids referenced by that session's visible
  messages are served, files stream without buffering, and filenames are
  sanitized before becoming `Content-Disposition` headers.
- **Error hygiene**: unknown failures become `500 internal` with a generic
  message; details are logged only.
- No CORS, no write/shell endpoints (the file browser is read-only inside the
  session cwd), no DSH launch token, no session-log paths in responses. The state
  file contains device names and hashes only; protect it as you would any
  credential store.
- `GET /events` requires a bearer token; replay and resync snapshots never carry
  an `id:` that could corrupt a client's cursor.

## SSE semantics (implemented exactly as documented)

- SSE `data:` is **always** the full envelope JSON
  `{id?, type, at, sessionId?, data}`; `event:` mirrors `envelope.type`.
- Replayable events carry `id: ev_000001`; per-connection `host.status` and
  `ping` frames carry an envelope but no `id:`.
- On connect: `host.status`. With `since` found in the replay ring: replay later
  events only. With an unknown `since`: `host.status` + one `session.updated`
  snapshot per session (no ids) and the client refetches details.
- Assistant streaming: `message.delta` frames carry a **stream id**
  `m_<attemptId>`; `message.completed` finalizes that id. The durable assistant
  message appears in session detail after the turn commits.
- Tool rows use `id: m_<toolCallId>` in both session detail and SSE, so a
  `message.added` upserts the same row from `running` to `done`/`failed`. The
  done/failed row carries the capped `tool.output` (8,000 chars + truncation
  marker) from the tool result's text blocks; see "Tool result output".
- `session.updated`/`session.removed` come from DSH `api-session/*` events;
  message events come from the post-commit `session/event` feed.
- `approval.requested` / `approval.resolved` come from the approval mirror (data
  = one Approval row / `{approvalId}`, envelope `sessionId` set; exactly one
  resolved per request whether the desktop or the phone answers first);
  hidden/internal sessions are never mirrored.
- `question.requested` / `question.resolved` come from the question mirror (data
  = one Question row / `{questionId}`, envelope `sessionId` set); one
  requested/resolved pair is emitted per question item, and the whole-call
  `{answers}` batch resolves only after every row is answered from the phone.
- `goal.changed` — data `{sessionId, operation, goal|null}` — is emitted for
  every committed DSH `goal/change` session event. `operation` is one of
  `create | edit | pause | resume | complete | block | clear`; `goal` uses the
  same JSON as `GET /sessions/{id}/goal` (`id, objective, phase, maxGoalRounds,
  roundsStarted, revision, activation, createdAt, updatedAt, blockedReason?`)
  and is `null` for `clear` tombstones. `activation` is process-local: it is
  taken from the live goal view when that still describes the event revision,
  otherwise the durable event carries none and it defaults to `disarmed`.
  Hidden/internal sessions are suppressed exactly like other events.

## Verified DSH integration (read-only inspect, installed 0.2.1-rc.5)

- Services: `sessionController.list/create/prompt/cancel`,
  `sessionQuery.readSession/readTitleSnapshots/filterSessions`,
  `workspaceRegistry.list`, optional `agents`, events `session/event`,
  `agent/assistant-stream`, `api-session/{added,status,activity,removed}`.
- Goals: `ctx.goals.get/create/resume/pause` and the durable `goal/change`
  session event (`GoalChangeMeta` from `@deepseek-ai/dsh-goal` 0.2.1-rc.5:
  `operation` + full `GoalSnapshot` or a `clear` tombstone), forwarded as SSE
  `goal.changed` (revision/rounds/phase from the event, activation from the
  live view when available).
- Attachments: `ctx.attachments.saveImage`, `admitEncodedFile`,
  `readImage`, `readFileStream`, `isAttachmentError`, `imageLimits`;
  `ctx.fileUploads.uploadStream`; prompt content parts
  `{type:'text'|'image'|'file'}` as declared by
  `@deepseek-ai/dsh-api-session-controller` (`resolvePromptFileReceipts` →
  `fileUploads.resolve` → `attachments.admitPromptContent` → `bindPrompt`).
  Message attachment metadata is read from the image/file content blocks; the
  read-back route reduces them to service refs, never host paths.
- Schedules: `ctx.get('schedule').catalog/create/update/delete`; catalog entries
  carry the DSH record union (`after|at|every|daily|weekly|cron`, `title`,
  `prompt`, `scheduledAt`), `sessionId`, `status` and `lastDelivery`; `update`
  takes the task binding, the complete observed record as `expected`, and any
  combination of `title`/`prompt`/`change` (timing union `at|every|daily|weekly|
  cron` with `schedule_not_found|schedule_ended|schedule_conflict` misses); the
  owning session's `schedule/change` events supply best-effort timestamps.
- Session management: `sessionController.rename({sessionId,title})` is used only
  on the live-session path (`session/title-invalid`/`session/not-found` mapped);
  the Workspace controller's `archiveSession({sessionId})` /
  `unarchiveSession({sessionId})` mutate DSH's durable archive set (no
  `stopActivity`), read for the list from a `workspaceRegistry.archivedSessionIds`
  projection when present or the first `workspaceController.follow()` baseline
  otherwise.
- Approval policy: `ctx.get('approval').effectivePolicy(agent.session)` (session
  override ?? configured default `ask`) for live reads, and
  `setPolicy(agent, policy)` for live writes (DSH appends a durable
  `approval/policy` event and injects a switch notice); cold reads fold the
  session log's `approval/policy` events, and writes never resume a session.
- Agent presets: `ctx.get('agentPresets').list()` (declared presets including
  `order`/`broken` diagnostics), `remoteExportList()` →
  `{presets:[{id,name?,description?,broken?,isDefault}]}` and the `defaultId`
  getter; the roster merge is read-only and never activates an Agent.
- Search: reuses the visible session-list path (cached mobile list built from
  `sessionController.list`) plus `sessionQuery.readSession` snapshots; matching,
  snippets, query/limit validation and cost bounds live in the pure
  `lib/search.js` (`searchEvents`), with no extra DSH service.
- Push: no DSH service — the publisher subscribes to the plugin's own SSE event
  bus for `approval.requested`/`question.requested`/`goal.changed`, plus an
  adapter hook after `turn/end`; delivery uses the global `fetch` with bounded
  queue, timeout, dedupe/backoff and the ntfy headers documented above.
- Models: `sessionController.modelCatalog()` (provider-grouped catalog, default,
  isolated provider failures), `sessionController.selectModel({sessionId,
  provider, model, reasoningEffort?})` (live-only; DSH validates via
  `llm.resolveCallConfig`, appends `model/selection`, and saves the default in the
  background), `agentDefaultModel.currentSelection()` for the default fallback,
  and `sessionQuery.readSession` snapshots to fold a cold session's
  `model/selection` / `request/header` selection without activation.
- Live queue: `agents.get(sessionId).inbox` (`nextTurn`/`nextStep` user messages,
  mapped to text/attachments) for reads, and
  `sessionController.updateQueue({sessionId, itemId, action})` for edits (exact
  `{kind:"edit",content:[{type:"text",text}]}` / `{kind:"remove"}` /
  `{kind:"steer"}` shapes; DSH errors `session/queue-item-not-found`,
  `session/steer-unavailable`, `session/attachment-invalid`). `updateQueue` can
  resolve a cold agent itself, so the adapter double-checks `agents.get` and
  never calls it for a cold session.
- Session fork: `sessionController.fork({sessionId, atSeq?})` returns the new
  session id (DSH observes the source cold-safely, validates the boundary,
  builds a seed + `parentSession` metadata, and attaches the workspace); errors
  `session/fork-unavailable`, `session/not-found`, `gateway/bad-request`, and
  `session/workspace-attach-failed` (child already created → 200 + `warning`).
  Mapped messages carry the event `seq` (`buildMessages`) as the fork boundary.
- Internal-session markers: `SessionSummary.origin` / `parentSessionId`
  (list fields from the persisted `SessionHeader`), `projections.values.subagent`
  (`session-projection-cache` rows), live `Session.header.origin` /
  `parentSession` / `delegationDepth` from the event feed, plus the live corpus
  shape (`session-<uuid>` vs bare UUID). Verified by decompressing the 114 live
  `.zstd` session logs and reading the projection cache.
- The adapter degrades rather than throwing when a service is absent: data
  endpoints then answer `501 unsupported`, while `/health`, pairing and
  `/host` keep working.

## Known gaps / risks

- **Internal-session detection is marker-based, not a DSH API contract.** The
  positive markers (`origin`/`parentSessionId`/`subagent` projection/header) are
  authoritative where present; a bare-UUID id fallback is the documented
  heuristic. `sessions.hideInternal: false` restores unfiltered behaviour.
- **File staging errors are mapped to `400 invalid_request`** (with the DSH
  reason in the message and the full cause in host logs) because the documented
  API has no finer 4xx for "this session cannot receive uploads". Defect O3 is
  covered by a bare-UUID regression test.
- **Approvals answer first-come-first-served** when `approvals.mobileAnswer` is
  on (`readOnly:false`): any paired device may allow/deny from the phone, the
  phone answer is final even if the desktop prompt is still visible, and the
  list is poll-based (no push notification). At most 32 pending rows are kept in
  memory (oldest dropped silently over the cap and no longer phone-answerable),
  `command` is best-effort from the tracked `tool/call` event (`''` when
  unavailable), and `approval.resolved` is emitted exactly once when either
  answerer settles. Set `approvals.mobileAnswer: false` for desktop-only
  answering.
- **Questions answer first-come-first-served** when `questions.mobileAnswer` is
  on (`readOnly:false`): any paired device may answer rows, but DSH requires
  every question in a call exactly once, so the whole mobile batch must be
  completed for it to reach the agent — a partial batch stays pending (answered
  rows disappear and emit `question.resolved`) until the remaining rows are
  answered or the desktop answers the whole call first. At most 32 pending rows
  are kept in memory (oldest dropped silently and no longer answerable), `prompt`
  is text concatenated from `header`/`question`/`detail`, `options` are the
  option labels (`[]` for free-form), and free text that does not match a label
  is sent as DSH's `custom`. Set `questions.mobileAnswer: false` for desktop-only
  answering.
- **Pending attachments are in-memory**: plugin reload/desktop restart loses
  them (the phone should re-upload). Image bytes are retained (up to
  `attachments.maxPendingBytes`) until prompted, expired, or unloaded.
- **Schedules are list/create/edit/delete only** (no toggle/run-now yet).
  PATCH sends DSH the complete observed record as the CAS `expected` value, so a
  concurrent desktop edit answers `400 invalid_request` (conflict) and an
  already-delivered one-shot answers `404` (`schedule_ended`); `afterSeconds`
  edits become absolute `at` targets; `createdAt`/`updatedAt` are best-effort
  from the owning session's `schedule/change` events and `null` when that log is
  unreadable; `dailyTime` defaults to `UTC` unless `timeZone` is supplied;
  hidden/internal schedule bindings stay in the DSH catalog but are absent from
  the phone list and cannot be edited/deleted through it; inactive one-shot rows
  are listed with `nextRunAt: null`.
- **Session rename is live-only**: a cold session is never resumed just to rename
  it from the phone (`400 invalid_request` explains this — open it on the
  desktop); DSH's title/unknown errors map to `400`/`404`; titles are trimmed and
  capped at 200 chars and the list title cache is refreshed after a successful
  rename. **Archive cannot stop running work**: `stopActivity` is never sent, so
  an active session answers `400 invalid_request`; archive/unarchive are limited
  to ordinary visible sessions (hidden/internal → `404`), the archive set is
  durable across restarts, and the normal `/sessions` list hides archived rows via
  a best-effort cache that can lag a desktop-side archive change by ~5 s.
  `GET /sessions/archived` uses the same summary mapper (no goal enrichment),
  filters hidden/internal ids, skips unreadable logs, and returns
  `supported:false` when no archive projection is available.
- **Approval policy writes are live-only**: a cold session's policy is folded
  from its durable `approval/policy` events (with `ask` as the fallback when the
  log is unreadable or has no policy event — a process-level non-ask default is
  not visible cold) but it cannot be switched from the phone (`400` says to open
  it); `never` auto-rejects approval-requiring actions instead of prompting;
  missing approval service → GET `supported:false`, PUT `501`; hidden/internal/
  unknown → `404`; no silent agent/session activation.
- **Preset roster is display metadata only**: broken presets are reported as
  `broken:true` (the activation diagnostic is stripped, never leaked); `order`
  comes from `list()` and is missing when only the remote roster is available
  (then rows sort by id); names/descriptions are trimmed and capped; no
  composition module rows or paths are exposed; a preset present in only one of
  `list()`/`remoteExportList()` is merged from whichever source still has it;
  the read never activates an Agent or Session. The phone passes a preset id as
  `preset` to `POST /sessions`; that existing route forwards it to DSH.
- **Model selection is live-only and also changes the default**: a cold session's
  selection is folded from `model/selection`/`request/header` (fallback
  `source:"default"`, possibly `null` if no default service) but cannot be
  changed from the phone (`400` asks to open it); selecting a model in a live
  session also saves it as the host default for new sessions (DSH background
  write; failure is logged by DSH, not the request); the catalog is
  sanitized/capped at 50 groups × 100 models × 20 efforts × 50 failures with
  `truncated`; provider failure **text is never forwarded** (generic
  `Provider unavailable` plus redacted provider id/name); unavailable models
  answer `400` with DSH's short message; the live agent is checked twice but a
  detach in the final gap before DSH's internal resolve remains a documented
  residual race; cold reads never activate an agent. The cold fold mirrors the
  installed `ModelSelection` / `EpochHeader.config` types; verify it against a
  live host after reload.
- **Queue management is live-only and small-surface**: reads show only the live
  agent's pending prompt text/attachment count (no source metadata beyond that),
  capped at 100 items; `createdAt` is usually absent because DSH `MessageBase`
  carries no timestamp; edits are text-only and 1..20000 chars; `remove` also
  retires DSH prompt uploads; `steer` only applies to next-turn items while the
  agent is running; `updateQueue` may resolve a cold session, so the adapter
  double-checks live before calling and cold requests are `400`; hidden/internal
  and unknown items answer `404`; no separate queue audit log exists.
- **Fork is prefix-copy, not undo/delete**: the new session contains the copied
  history up to `atSeq` (or the latest completed turn), keeps `parentSession`
  metadata, and never modifies or removes the source; `atSeq` must be an
  existing boundary (`400` otherwise); workspace-attach failure returns a
  partial-success `200` with `warning:"workspace-attach-failed"` and the child id;
  message `seq` is optional (absent when the log event has no sequence) and is
  the only new detail-message field; fork does not activate a cold source agent.
- **Search is a bounded local scan, not an index**: at most 200 recent visible
  sessions × 2000 fallback events are inspected under an **8 s whole-request**
  budget with **4-way bounded concurrency**; the preferred path reads
  `sessionQuery.filterEvents` semantic text documents (no tool-result image
  payloads, no replay validation), falling back to full snapshots only if that
  reader is unavailable/fails. Older/very long searches can still be cut off by
  the caps/budget — `truncated:true` plus the explicit
  `scanIncomplete`/`scannedSessions`/`totalSessions` fields report it, and
  partial hits are still returned. The semantic-document path may include
  agent-injected user context (its documents do not carry the user source), and
  its message ids fall back to `m_ev_<seq>`; the snapshot fallback keeps
  human-only user filtering and original message ids. Tool-call command details
  are still searched; tool results, reasoning and attachment bytes are not;
  snippets are single-line/collapsed and case-insensitive; hidden/internal/
  archived sessions never contribute; unreadable sessions are counted as
  scanned but skipped. Pagination is offset-based over the collected match set
  (no cross-session dedupe beyond message ids); role/date filters are applied
  during the scan (inclusive date bounds); a date-bounded query excludes hits
  whose event has no timestamp, which is the documented trade-off, and `filters`
  is echoed only when at least one filter is set (the unfiltered response shape
  is unchanged apart from the additive pagination/scan fields).
- **Push notifications are optional and best-effort**: the host publishes to
  ntfy (a third-party service by default) on approval/question/goal/turn events,
  but cannot know the app's quiet hours, foreground state or per-session mute,
  so pushes may duplicate in-app alerts and ignore app-local preferences; the
  topic is effectively a secret and the optional access token is stored in
  plaintext next to the pairing state (never returned by GET); server targets are
  SSRF-filtered synchronously (https, no credentials/private literals, localhost
  http exception) but DNS rebinding cannot be ruled out; delivery is best-effort
  with a 5 s timeout, 50-item queue, 60 s circuit breaker after 2 failures and a
  30/min cap; deep links are limited to inbox/session views (no push actions).
- **Uploading an image persists a durable DSH image reference immediately**
  (policy validation), and the prompt admission stores it again by content
  address — deduplicated, but the upload-time object exists even if no prompt is
  ever sent.
- **File prompts require `ctx.fileUploads`**; if a profile composes the
  attachment service without file uploads, file uploads answer `501 unsupported`
  while images/text keep working.
- **Attachment read-back is session-scoped and MIME-best-effort**: only ids
  referenced by the addressed session's human/assistant messages are served, so
  an attachment that was compacted out of the visible message path becomes
  `404 not_found` until its message is visible again. File MIME types are derived
  from the filename (DSH file refs carry no media type), unknown extensions fall
  back to `application/octet-stream`, images are bounded by the DSH image policy,
  files stream without a Range/HEAD path, and names are sanitized.
- **`needs_input` is never reported in `SessionSummary.status`** (only
  `working`/`idle`) even though a pending approval/question is visible through its
  own endpoint and can be answered from the phone when mobile answering is on.
- Synthetic `agent.inject()` context (file-change notices, AGENTS.md, skill
  content, goal continuations) is recorded by DSH as user-role events; the
  mapper filters those out. Human prompts (`source.kind` absent/`'user'`) are
  shown in the transcript and drive previews/titles/search; scheduler deliveries
  (`source.kind === 'schedule'`) are shown in the transcript only (with
  `source:"schedule"`) and never feed previews/titles/search.
- `SessionSummary.title` prefers the session-title projection; it falls back to
  the first **human** user prompt (scheduled deliveries excluded; or "Untitled
  session" beyond `sessions.previewLimit`).
- `SessionSummary.workspaceId` is `null` when DSH cannot map the session's cwd to
  a registered workspace.
- **Curated hiding is list-only and operator-owned**: hidden ids are stored next
  to the pairing state file (not in the DSH profile); deleting the file unhides
  everything. Auto-hidden blank sessions are recomputed per list refresh and are
  never persisted. Both only affect the phone list/SSE — a hidden session's
  detail, prompts, and attachments still work if addressed directly.
- Previews trigger a bounded number of full log reads (`previewLimit`, newest
  sessions first), and each `session.updated` refresh reads that session's log to
  recompute its row; raise `previewLimit` only if you accept the extra disk I/O.
- A committed assistant message is announced to SSE clients through its
  `message.delta`/`message.completed` stream id; if the client was disconnected
  for the whole stream, it appears on the next session-detail fetch.
- No TLS, CORS, rate limiting beyond pairing, push notifications, or
  device-management UI (out of scope).
- The attachment paths were verified against the installed Harness contracts but
  have **not** been exercised against a live DSH process yet; the first reload
  should smoke-test upload → prompt for one image and one PDF.
- Dev-tool note: `node --test test/` (directory form) is rejected by Node 24 —
  use the glob form shown above.
