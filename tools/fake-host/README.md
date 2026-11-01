# Fake host (development only)

An in-memory, **zero-dependency** Node implementation of
[`docs/mobile-api-v1.md`](../../docs/mobile-api-v1.md), used to exercise the real
Android client HTTP + SSE code paths before the desktop host plugin exists.

> **Dev only.** The fake host is deliberately insecure: fixed pairing code,
> fixed bearer token, wildcard bind and no persistence. Never expose it outside
> your development machine and never point a real device you care about at it
> over an untrusted network.

Files:

| File | Purpose |
| --- | --- |
| `server.mjs` | The fake host (Node 22+, ESM, built-ins only) |
| `smoke.mjs` | End-to-end smoke test against a **running** fake host (run it in legacy approvals mode, see below) |
| `goal-test.mjs` | Self-contained goal API + SSE test (starts/stops its own fake host on a random loopback port) |
| `approvals-readonly-test.mjs` | Self-contained read-only approvals + SSE fixture test; also verifies legacy-mode smoke compatibility |
| `approvals-answer-test.mjs` | Self-contained mobile-answering approvals test (allow/deny outcomes, SSE approval.resolved, repeat 404, read-only 501, legacy smoke) |
| `questions-readonly-test.mjs` | Self-contained read-only questions + SSE fixture test; also verifies legacy-mode smoke compatibility |
| `questions-answer-test.mjs` | Self-contained mobile question-answering test (single + two-question batch + free-text custom answers, batch progress, SSE question.resolved, 404/501, legacy smoke) |
| `attachments-test.mjs` | Self-contained seeded-attachment + tool-output test (Message.attachments shape, raw-byte downloads, optional tool.output present/absent/capped, 404/401) |
| `schedules-test.mjs` | Self-contained in-memory schedules test (list shape, create, PATCH update/timing, delete, validation 400, unknown 404, 401) |
| `sessions-management-test.mjs` | Self-contained session rename/archive/unarchive + archived-list test (list/detail title, visibility, 400/404/401) |
| `presets-test.mjs` | Self-contained agent-presets test (sorted fixture shape, default/defaultId, broken flag, session create with preset, 401) |
| `search-test.mjs` | Self-contained message-search test (seeded phrases, snippets, case-insensitive tool detail, newest-first, limit/offset pagination, 400/401) |
| `push-test.mjs` | Self-contained ntfy push-config test (defaults, validation 400, redacted hasToken persistence, synthetic /push/test, 401) |
| `approval-policy-test.mjs` | Self-contained per-session approval-policy test (seeded ask/never, PUT persistence, invalid 400, unknown 404, cold live:false, 401) |
| `devices-test.mjs` | Self-contained paired-devices test (currentDeviceId, seeded legacy, revoke legacy -> its token 401, current 400, unknown 404, 401) |
| `models-test.mjs` | Self-contained model catalog + per-session selection test (providers/efforts/default, seeded custom, PUT persistence, invalid 400, unknown 404, cold 400, 401) |
| `queue-test.mjs` | Self-contained session-queue test (seeded next-turn/next-step items, edit/remove/steer, invalid 400, unknown item/session 404, cold 400, 401) |
| `fork-test.mjs` | Self-contained session-fork test (message seqs, latest/atSeq fork responses, list refresh, invalid atSeq 400, unknown 404, 401) |
| `recovery-test.mjs` | Self-contained recovery-pairing test (configured/unconfigured GET, seeded/generated single-use codes + rotation, invalid 401/400, auth rules) |
| `tailscale-test.mjs` | Self-contained Tailscale auto-pair test (methods shape, header pairing, missing/disabled/allowlist 400/403, auth-exempt) |
| `README.md` | This file |

## Requirements

- Node.js 22 or newer. On this machine the bundled runtime is
  `C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe`.
- No `npm install`: the server and smoke test only use Node built-ins.

## Run the server

From the repo root (PowerShell):

```powershell
$node = 'C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
& $node tools\fake-host\server.mjs
```

From `tools\fake-host`:

```powershell
cd tools\fake-host
$node = 'C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
& $node .\server.mjs
```

Default bind is `0.0.0.0:3543`. Startup prints the pairing code, token, and the
emulator URL. Stop it with `Ctrl+C`.

Port override (both forms work):

```powershell
$env:PORT = '3643'; & $node tools\fake-host\server.mjs
& $node tools\fake-host\server.mjs --port 3643
```

### Where the Android app points

| Client | Base URL | Notes |
| --- | --- | --- |
| Android emulator | `http://10.0.2.2:3543` | `10.0.2.2` is the AVD alias for the host loopback |
| Phone on the same Wi-Fi | `http://<pc-lan-ip>:3543` | find the IP with `Get-NetIPAddress -AddressFamily IPv4`; allow Node through Windows Firewall for **private** networks (inbound TCP 3543) |
| Phone over Tailscale (later, real plugin) | `https://<pc>.<tailnet>.ts.net` | see [`docs/remote-access.md`](../../docs/remote-access.md); the fake host also works behind `tailscale serve --bg 3543` |

CORS is not needed: the Android client is a native HTTP client, not a browser.

## Pairing in fake mode

The fake host always issues the code **`482913`** and the token **`dev-token`**.
Pairing starts with `POST /mobile/v1/pair/new`, which the real host only accepts
from loopback but the fake host accepts from any peer so an emulator/phone can
call it directly.

```powershell
$base = 'http://127.0.0.1:3543'
curl.exe -s -X POST "$base/mobile/v1/pair/new"
# { "code": "482913", "expiresAt": "...", "qrPayload": "dshmobile://pair?..." }

curl.exe -s -X POST -H 'Content-Type: application/json' `
  -d '{"code":"482913","deviceName":"Pixel 9","platform":"android"}' `
  "$base/mobile/v1/pair/complete"
# { "token": "dev-token", "deviceId": "dev_...", "host": {...}, "workspaces": [...] }
```

Every other endpoint requires `Authorization: Bearer dev-token`.

## Smoke test

The smoke test does not start the server; start the fake host first, then in a
second terminal. Because the default host is **read-only for approvals**, the
smoke test's allow/deny flow needs the legacy approvals simulation enabled:

```powershell
$node = 'C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
$env:FAKE_APPROVALS_READONLY = '0'; $env:FAKE_QUESTIONS_READONLY = '0'
& $node tools\fake-host\server.mjs                                       # terminal 1
& $node tools\fake-host\smoke.mjs                                        # terminal 2
```

Against a non-default port use `--base-url` (or `SMOKE_BASE_URL`):

```powershell
& $node tools\fake-host\smoke.mjs --base-url http://127.0.0.1:3643
```

It prints one `PASS`/`FAIL` line per check and exits `0` only if every check
passes. In legacy approvals mode the expected result is:

```text
23 passed, 0 failed in ~4s
SMOKE_EXIT=0
```

Coverage: health, unauthenticated 401s, pairing (`pair/new`, bad code → 400,
`pair/complete`), host/workspaces, sessions list/detail/pagination/create,
SSE connect (`host.status` + snapshot), prompt streaming (`message.delta`
chunks → `message.completed` → idle `session.updated`), approval raise/deny/allow
(legacy mode, including the `running` → `done` tool-row upsert), interrupt,
questions list/answer, SSE `since=` replay, **SSE envelope framing** (below),
`paired=true`, and 404s.

The read-only approvals self-test below already covers legacy compatibility: it
spawns a `FAKE_APPROVALS_READONLY=0` host and runs this smoke test against it, so
CI does not need a separate legacy smoke run.

## Goal endpoints (fake GoalService)

The fake host now exposes the in-memory goal surface used by the phone's goal
banner, mirroring `host-plugin/lib/dsh-adapter.js` `mapGoalView()`.

**Seeds:** four sessions carry distinct goals with realistic round counters so a
Goals dashboard / goal-filter UI can be exercised offline; `s_release` and
`s_economy` stay goalless (the goal test uses them as create/no-goal fixtures),
and sessions created through `POST /sessions` also start goalless. Restarting the
fake host reseeds this state.

| Session | Phase | roundsStarted / maxGoalRounds | Objective | activation | Notes |
| --- | --- | --- | --- | --- | --- |
| `s_android` | `paused` | 3 / 8 | Make the device stream recover cleanly after screen rotation | `disarmed` | original paused-banner fixture (`goal_0001`) |
| `s_search` | `active` | 7 / 20 | Make session search respect renames without a restart | `armed` | active dashboard row (`goal_0002`) |
| `s_worktree` | `complete` | 15 / 15 | Clean up stale worktrees and keep the dirty branch | `disarmed` | terminal: `resume`/`complete` return `400`, `clear` works (`goal_0003`) |
| `s_notes` | `blocked` | 4 / 10 | Draft the v0.4.7 release notes | `disarmed` | carries `blockedReason: {code, message}`; `resume` re-arms (`goal_0004`) |
| `s_release`, `s_economy` | — | — | — | — | goalless (`goal: null`) |

All four seeded goals appear in `GET /sessions` rows via the compact
`{phase, roundsStarted, maxGoalRounds, objective}` summary; pause/resume/edit
mutations on the seeded sessions update those summaries immediately (edit can
change the objective text too).

### Goal summary on `GET /sessions`

Each `SessionSummary` returned by `GET /sessions` (and by the session detail and
`session.updated` snapshots, which reuse the same summary mapper) now carries a
compact goal summary:

```json
"goal": {
  "phase": "paused",
  "roundsStarted": 3,
  "maxGoalRounds": 8,
  "objective": "Make the device stream recover cleanly after screen rotation"
}
```

`goal` is `null` when the session has no goal (seeded goalless sessions, fresh
sessions, and after a successful goal clear). It reflects the in-memory goal
state immediately after `goal` create/edit/pause/resume/clear — `phase`,
`roundsStarted`, `maxGoalRounds` and the `objective` text are included; fetch
`GET /sessions/{id}/goal` for the full view (id, revision, activation,
timestamps, blockedReason). Example:

```powershell
curl.exe -s -H $auth "$base/mobile/v1/sessions"   # inspect sessions[].goal
```

Goal view shape (`goal` is `null` when the session has no goal):

```json
{
  "id": "goal_0001",
  "objective": "Make the device stream recover cleanly after screen rotation",
  "phase": "paused",              // active | paused | blocked | complete
  "maxGoalRounds": 8,             // 0 = unlimited/default
  "roundsStarted": 3,
  "revision": 2,
  "activation": "disarmed",       // armed | disarmed
  "createdAt": "2026-10-31T07:00:00Z",
  "updatedAt": "2026-10-31T06:00:00Z"
  // "blockedReason": { "code": "...", "message": "..." } only when blocked
}
```

| Request | Behaviour |
| --- | --- |
| `GET /sessions/{id}/goal` | `200 {"supported":true,"live":true,"goal":<view>\|null}`; `404 not_found` for an unknown session. Read-only. |
| `POST /sessions/{id}/goal` `{objective, maxGoalRounds?}` | Creates a goal (`phase:"active"`, `activation:"armed"`, `revision:1`, `roundsStarted:0`, trimmed objective, default `maxGoalRounds:0`). `400 invalid_request` for an empty/too-long objective, `maxGoalRounds` outside `1..10000`, or when a **non-complete** goal already exists; `404` for an unknown session. |
| `POST /sessions/{id}/goal/pause` | `active` → `paused`, `activation:"disarmed"`, `revision+1`. `400 invalid_request` when the goal is not active; `404` when the session has no goal. |
| `POST /sessions/{id}/goal/resume` | `paused`/`blocked`/`disarmed` → `active`, `activation:"armed"`, `blockedReason` cleared, `revision+1`. `400` when already active or complete; `404` when no goal. |
| `POST /sessions/{id}/goal/complete` `{id?, revision?}` | `active`/`paused`/`blocked` → `complete`, `activation:"disarmed"`, `blockedReason` cleared, `revision+1`; optional `id`/`revision` must match the current goal (stale revision → `400 invalid_request`); `400` when already complete; `404` when no goal. GET /sessions summary reflects `phase:"complete"`. |
| `POST /sessions/{id}/goal/edit` `{objective?, maxGoalRounds?}` | Edits the current goal: at least one field required, objective trimmed and `1..4000` chars, `maxGoalRounds` `1..10000`; phase and activation are unchanged, `revision+1`. `400 invalid_request` for an empty body/invalid values; `404` when the session has no goal/unknown session. |
| `POST /sessions/{id}/goal/clear` | Deletes the current goal: returns `200 {"supported":true,"live":true,"goal":null}`. `404` when the session has no goal/unknown session. A new goal can then be created (revision resets to 1). |

Every mutation broadcasts one **replayable** SSE `goal.changed` frame. Per the
framing below, `data:` carries the full envelope and the payload lives in
`envelope.data`:

```json
event: goal.changed
id: ev_000042
data: {"id":"ev_000042","type":"goal.changed","at":"2026-10-31T10:00:00Z","sessionId":"s_release","data":{"sessionId":"s_release","operation":"created","goal":{...}}}
```

`operation` is `"created"`, `"paused"`, `"resumed"`, `"edit"`, `"complete"` or
`"clear"`; `goal` is the updated goal view (`null` for `"clear"`). Because the
event is replayable, a client reconnecting with `GET /events?since=ev_...` also
receives it.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'

# seeded paused goal
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_android/goal"

# no goal yet
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_release/goal"

# create, pause, resume (watch SSE for goal.changed)
curl.exe -s -X POST -H $auth -H $json -d '{"objective":"Cut the release","maxGoalRounds":5}' "$base/mobile/v1/sessions/s_release/goal"
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/goal/pause"
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/goal/resume"
curl.exe -s -X POST -H $auth -H $json -d '{"revision":3}' "$base/mobile/v1/sessions/s_release/goal/complete"

# edit fields (revision+1; phase/activation unchanged), then clear entirely
curl.exe -s -X POST -H $auth -H $json -d '{"objective":"Cut the release today","maxGoalRounds":8}' "$base/mobile/v1/sessions/s_release/goal/edit"
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/goal/clear"
```

**Self-contained test** (starts the fake host itself on a free loopback port,
runs 30 checks including all SSE goal.changed operations and the session-list
goal summary, then stops it):

```powershell
node tools/fake-host/goal-test.mjs
```

Expected: `[goal-test] 30 passed, 0 failed` / `[goal-test] OK`, exit `0`.
It also runs in CI (`Host plugin tests` job, step **Run fake-host goal tests**).

Fake-mode limitation: `supported`/`live` are always `true` for known sessions;
there is no real GoalService behind them, so rounds never advance on their own,
no `blocked` transition is produced automatically, and revisions change on
pause/resume/edit/complete. The `goal/complete` route explicitly simulates the
user completing a goal (`phase:"complete"`, disarmed, revision+1).

## Read-only approvals (default mode)

The fake host now mirrors a host where approvals can be **viewed but not
resolved** on the phone; the decision must happen on the desktop. This is the
default (`FAKE_APPROVALS_READONLY=1`).

`GET /mobile/v1/approvals` returns the documented list plus the read-only flags:

```json
{
  "approvals": [
    {
      "id": "ap_readonly_seed",
      "sessionId": "s_release",
      "title": "Permission needed: deploy release",
      "description": "The agent wants to run a release command in your workspace (read-only demo).",
      "command": "pwsh -File tools/publish.mjs --patch",
      "createdAt": "2026-10-31T10:00:00Z"
    }
  ],
  "supported": true,
  "readOnly": true
}
```

- One pending deploy approval (`ap_readonly_seed`) is **seeded** at startup so
  the app/QA can exercise the read-only banner immediately.
- A deploy/publish/push/delete prompt still raises a fresh
  `approval.requested` SSE event and lists the approval, but it is also
  read-only.
- The seeded approval is recorded in the SSE replay log: connect with
  `GET /events?since=ev_000000` to receive its `approval.requested` fixture.
- `POST /mobile/v1/approvals/{id}` always returns **501**
  `{"error":{"code":"unsupported","message":"read-only; resolve on desktop"}}`
  for known, live and unknown ids alike. `approval.resolved` is therefore not
  emitted in this mode.
- **Approval modes** (`FAKE_APPROVALS_READONLY`, optionally plus
  `FAKE_APPROVALS_ANSWER`):
  - **default read-only** (`READONLY=1`): seeded `ap_readonly_seed`, every POST
    answers `501` `"read-only; resolve on desktop"`, no `approval.resolved`.
  - **legacy simulation** (`READONLY=0`, `ANSWER` unset): the previous
    allow/deny demo flow that also appends session messages (used by
    `smoke.mjs`); seeds no approval; GET reports `readOnly:false`.
  - **mobile answering** (`READONLY=0` + `ANSWER=1`): GET reports
    `readOnly:false`, two pending approvals are seeded, POST resolves one
    (`{ok:true, approvalId, outcome:"allowed-once"|"rejected"}`), emits
    `approval.resolved`, and repeat/unknown ids answer `404` (see below).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/approvals"
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"decision":"allow"}' "$base/mobile/v1/approvals/ap_readonly_seed"
# -> 501 {"error":{"code":"unsupported","message":"read-only; resolve on desktop"}}
```

**Self-contained test** (spawns a read-only host, pairs, checks the seeded
approval + SSE replay fixture + 501 decisions + a live prompt fixture, then
spawns a legacy-mode host and runs the existing smoke test against it):

```powershell
node tools/fake-host/approvals-readonly-test.mjs
```

Expected: `[approvals-test] 8 passed, 0 failed` / `[approvals-test] OK`, exit
`0` (the embedded smoke run reports `23 passed, 0 failed`). It runs in CI
(`Host plugin tests` job, step **Run fake-host approvals tests**).

### Mobile-answering approvals mode

For post/race behavior the fake host can let the phone answer approvals:
start it with `FAKE_APPROVALS_READONLY=0` **and** `FAKE_APPROVALS_ANSWER=1`.

```powershell
$env:FAKE_APPROVALS_READONLY = '0'; $env:FAKE_APPROVALS_ANSWER = '1'
& $node tools/fake-host/server.mjs
```

- `GET /approvals` -> `{approvals: [...], supported:true, readOnly:false}` with
  two seeded pending approvals: `ap_answer_seed` (allow target, `s_release` deploy)
  and `ap_answer_deny` (deny target, `s_android` push).
- `POST /approvals/{id}` `{decision:"allow"|"deny"}` ->
  `200 {"ok":true,"approvalId":"…","outcome":"allowed-once"|"rejected"}`; the
  approval is removed and a replayable `approval.resolved` SSE event is emitted.
- A second POST for the same id (or any unknown id) -> `404 not_found`.
- No legacy session simulation is applied in this mode (no synthetic messages).
- The default read-only host still answers `501`; legacy mode remains
  `READONLY=0` with `ANSWER` unset.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"decision":"allow"}' "$base/mobile/v1/approvals/ap_answer_seed"
# -> 200 {"ok":true,"approvalId":"ap_answer_seed","outcome":"allowed-once"}
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"decision":"allow"}' "$base/mobile/v1/approvals/ap_answer_seed"
# -> 404 {"error":{"code":"not_found","message":"No such approval: …"}}
```

**Self-contained test** (spawns an answer-mode host, pairs, checks
`readOnly:false` + seeded approvals, allow -> `allowed-once` + SSE
`approval.resolved` + 404 on repeat, deny -> `rejected` + 404, unknown id 404,
then a read-only host still answering 501 and a legacy host running the existing
smoke test):

```powershell
node tools/fake-host/approvals-answer-test.mjs
```

Expected: `[approvals-answer-test] 7 passed, 0 failed` / OK, exit `0`
(embedded legacy smoke reports `23 passed, 0 failed`).

## Read-only questions (default mode)

Questions mirror the approvals read-only surface: the phone can show a pending
question but the answer belongs on the desktop. This is the default
(`FAKE_QUESTIONS_READONLY=1`).

`GET /mobile/v1/questions` returns the documented list plus the read-only flags:

```json
{
  "questions": [
    {
      "id": "q_1",
      "sessionId": "s_android",
      "prompt": "Which approach should I take?",
      "options": ["Keep the stream alive across rotation", "Restart the stream cleanly"],
      "createdAt": "2026-10-31T09:00:00Z"
    }
  ],
  "supported": true,
  "readOnly": true
}
```

- One pending question (`q_1`, session `s_android`, two options) is seeded at
  startup so the app/QA can exercise the read-only question banner immediately.
- The seeded question is recorded in the SSE replay log as
  `question.requested`; connect with `GET /events?since=ev_000000` to receive
  that fixture.
- `POST /mobile/v1/questions/{id}` always returns **501**
  `{"error":{"code":"unsupported","message":"read-only; resolve on desktop"}}`
  for known and unknown ids alike. `question.resolved` is therefore not emitted
  in this mode.
- **Question modes** (`FAKE_QUESTIONS_READONLY`, optionally plus
  `FAKE_QUESTIONS_ANSWER`):
  - **default read-only** (`READONLY=1`): seeded `q_1`, every POST answers `501`
    `"read-only; resolve on desktop"`.
  - **legacy simulation** (`READONLY=0`, `ANSWER` unset): the previous answer
    simulation that also appends a session message (used by `smoke.mjs`); GET
    reports `readOnly:false`.
  - **mobile answering** (`READONLY=0` + `ANSWER=1`): GET reports
    `readOnly:false`, the single `q_1` plus the two-question
    `q_batch_1`/`q_batch_2` batch and the free-text `q_free` are seeded, and POST
    answers report `{answered,total,complete}` progress (see below).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/questions"
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"answer":"Keep the stream alive across rotation","optionIndex":0}' `
  "$base/mobile/v1/questions/q_1"
# -> 501 {"error":{"code":"unsupported","message":"read-only; resolve on desktop"}}
```

**Self-contained test** (spawns a read-only host, pairs, checks the seeded
question + envelope/shape + SSE replay fixture + 501 answers, then spawns a
legacy-mode host and runs the existing smoke test against it):

```powershell
node tools/fake-host/questions-readonly-test.mjs
```

Expected: `[questions-test] 7 passed, 0 failed` / `[questions-test] OK`, exit
`0` (the embedded smoke run reports `23 passed, 0 failed`). It runs in CI
(`Host plugin tests` job, step **Run fake-host questions tests**).

### Mobile-answering questions mode

For mobile answering/race behavior start the host with
`FAKE_QUESTIONS_READONLY=0` **and** `FAKE_QUESTIONS_ANSWER=1`:

```powershell
$env:FAKE_QUESTIONS_READONLY = '0'; $env:FAKE_QUESTIONS_ANSWER = '1'
& $node tools/fake-host/server.mjs
```

- `GET /questions` -> `{questions: [...], supported:true, readOnly:false}`.
  Seeds: the single `q_1` (`s_android`), the two-question batch
  `q_batch_1`/`q_batch_2` (`s_release`), and the **free-text** `q_free`
  (`s_economy`) whose `options` is `[]`.
- `POST /questions/{id}` `{answer, optionIndex?}` ->
  `200 {"ok":true,"questionId":"…","answered":n,"total":m,"complete":bool}`;
  the answered row disappears and a replayable `question.resolved` SSE event is
  emitted.
- Batch progress: answering the first batch question returns
  `answered:1,total:2,complete:false` and keeps the remaining row; answering the
  second returns `answered:2,total:2,complete:true`. The single/free-text
  questions return `answered:1,total:1,complete:true`.
- **Custom answers:** any non-empty `answer` is accepted. For `q_free`
  (`options: []`) it is the only way to answer; for an option question an answer
  that matches no option is still accepted as a custom answer (`selected []`).
  An empty/whitespace-only answer returns `400 invalid_request`; an
  out-of-range `optionIndex` stays `400`.
- A repeat POST for an answered id (or any unknown id) -> `404 not_found`.
- The default read-only host still answers `501`; legacy mode remains
  `READONLY=0` with `ANSWER` unset.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"answer":"stable","optionIndex":0}' "$base/mobile/v1/questions/q_batch_1"
# -> 200 {"ok":true,"questionId":"q_batch_1","answered":1,"total":2,"complete":false}
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"answer":"yes, same commit","optionIndex":0}' "$base/mobile/v1/questions/q_batch_2"
# -> 200 {"ok":true,"questionId":"q_batch_2","answered":2,"total":2,"complete":true}
# free-text question: any non-empty custom answer (no optionIndex)
curl.exe -s -X POST -H $auth -H 'Content-Type: application/json' `
  -d '{"answer":"Ship it with a short changelog note."}' "$base/mobile/v1/questions/q_free"
# -> 200 {"ok":true,"questionId":"q_free","answered":1,"total":1,"complete":true}
```

**Self-contained test** (spawns an answer-mode host, pairs, checks
`readOnly:false` + seeded rows (single/batch/free-text), single answer,
free-text custom answer + empty-answer 400, partial/final batch progress +
SSE `question.resolved`, repeat/unknown 404, a read-only host still answering
501, and a legacy host running the existing smoke test):

```powershell
node tools/fake-host/questions-answer-test.mjs
```

Expected: `[questions-answer-test] 9 passed, 0 failed` / OK, exit `0`
(embedded legacy smoke reports `23 passed, 0 failed`). It runs in CI
(`Host plugin tests` job, step **Run fake-host question answer tests**).

## Seeded attachments and byte downloads

The seeded session `s_release` carries a user message with three attachment
fixtures (the fake host has no upload endpoint; these bytes are read-only
seeds):

```json
"attachments": [
  { "id": "att_seed_image", "kind": "image", "name": "screenshot.png",
    "mimeType": "image/png", "bytes": 3904, "width": 512, "height": 512 },
  { "id": "att_seed_image_2", "kind": "image", "name": "diagram.png",
    "mimeType": "image/png", "bytes": 1163, "width": 256, "height": 256 },
  { "id": "att_seed_file", "kind": "file", "name": "release-checklist.txt",
    "mimeType": "text/plain", "bytes": 53 }
]
```

`Message.attachments` is optional and follows the frozen host shape
`[{id,kind:"image"|"file",name,mimeType,bytes,width?,height?}]`. Download the raw
bytes with an authenticated route:

```http
GET /mobile/v1/sessions/{id}/attachments/{attachmentId}
Authorization: Bearer dev-token
```

- `200` with the stored `Content-Type` (e.g. `image/png`, `text/plain`),
  `Content-Length`, and `Content-Disposition` (`inline; filename="…"` for
  images, `attachment; filename="…"` for files). The images are valid PNGs: a
  512x512 patterned screenshot (3,904 B, IHDR `512x512`) and a second 256x256
  diagram (1,163 B, IHDR `256x256`) so multiple-image paging can be exercised;
  the file bytes are `Release checklist\n- tests passed\n- changelog drafted\n`.
- `404 not_found` for an unknown attachment id **and** for a known attachment
  requested under the wrong session id.
- `401 unauthorized` without a valid bearer token (the route is authenticated
  like the rest of the mobile API).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_release"
curl.exe -s -H $auth -o screenshot.png "$base/mobile/v1/sessions/s_release/attachments/att_seed_image"
curl.exe -s -H $auth -o diagram.png "$base/mobile/v1/sessions/s_release/attachments/att_seed_image_2"
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_release/attachments/att_seed_file"
# -> Release checklist / - tests passed / - changelog drafted
```

**Self-contained test** (spawns a fake host, pairs, checks the seeded
image+image+file metadata shape and dimensions, downloads the 512x512 PNG and
the second 256x256 PNG (signature/content-length/IHDR dimensions) plus the text
file (exact bytes), verifies the seeded tool-output cases (capped text-only
output, absent field, preserved through paging), then verifies
unknown/wrong-session 404s and the 401 without auth):

```powershell
node tools/fake-host/attachments-test.mjs
```

Expected: `[attachments-test] 12 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host attachment tests**).

### Tool output

Tool messages may carry an optional `tool.output`: the **text-only** result of
the tool call. The fake host keeps the **first 8,000 characters**, then appends
the exact host marker `… (output truncated)` **after** the cap (so the total may
exceed 8,000; no newline is required), and omits the field entirely when the
result is empty or image/file-only. The seeded `s_economy` `Edit` tool row
carries a long output (8,000 chars + marker) while the `s_release` `Bash` tool
row has no `output`, so both cases are exercised. `GET /sessions/{id}` returns
the field as-is in the message's `tool` object, and it is preserved by message
paging (`?limit=&before=`).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_economy"
# -> messages[].tool.output (string; first 8000 chars + "… (output truncated)"
#    when longer; absent on tool rows without output)
```

## Schedules

The fake host exposes an **in-memory** schedules API. Two fixtures are seeded so
the list is populated at startup:

- `sch_seed_daily` — active **daily** schedule on `s_release` (`09:00`,
  `nextRunAt` + `lastRunAt`).
- `sch_seed_once` — **paused** one-off (`kind:"at"`) schedule on `s_economy` with
  a future `nextRunAt`.

```json
{
  "schedules": [
    {
      "id": "sch_seed_daily",
      "sessionId": "s_release",
      "sessionTitle": "Cut a patch release",
      "title": "Daily release status",
      "instruction": "Summarize the release status and list any failing checks.",
      "status": "active",
      "schedule": {
        "kind": "daily",
        "summary": "Every day at 09:00",
        "nextRunAt": "2026-11-01T13:00:00.000Z",
        "lastRunAt": "2026-10-30T13:00:00.000Z"
      },
      "createdAt": "2026-10-26T13:00:00.000Z",
      "updatedAt": "2026-10-30T13:00:00.000Z"
    }
  ],
  "supported": true
}
```

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/schedules` | `200 {schedules:[Schedule], supported:true}`, sorted by `nextRunAt`; rows include `sessionTitle`. |
| `POST /mobile/v1/sessions/{id}/schedules` | Body `{title, instruction}` plus **exactly one** of `afterSeconds` (`1..86400`), `everySeconds` (`60..604800`), `dailyTime` (`HH:MM`), `at` (future ISO date-time). Returns the created active row with its computed `schedule.kind/summary/nextRunAt`; `400 invalid_request` for bad/missing values, `404` for an unknown session. |
| `PATCH /mobile/v1/schedules/{id}` | Body `{title?, instruction?, afterSeconds?\|everySeconds?\|dailyTime?\|at?, timeZone?}`; at least one of title/instruction/timing is required and at most one timing field. Updates the row in place, recomputes `schedule.kind/summary/nextRunAt`, preserves `id`/`sessionId`/`sessionTitle`/`status`/`createdAt` and bumps `updatedAt`; unknown id `404`, empty/no-op or invalid values `400 invalid_request`. `timeZone` is accepted and stored internally (daily `nextRunAt` still uses the host's local timezone). |
| `DELETE /mobile/v1/schedules/{id}` | `200 {ok:true, deleted:true}`; `404 not_found` for an unknown id. |

Schedules live in memory only (a restart reseeds the two fixtures). All routes
require the bearer token.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -H $auth "$base/mobile/v1/schedules"
curl.exe -s -X POST -H $auth -H $json `
  -d '{"title":"Quick check","instruction":"Check the build in 30s.","afterSeconds":30}' `
  "$base/mobile/v1/sessions/s_android/schedules"
curl.exe -s -X PATCH -H $auth -H $json `
  -d '{"title":"Quick check (updated)","dailyTime":"07:30"}' `
  "$base/mobile/v1/schedules/sch_0001"
curl.exe -s -X DELETE -H $auth "$base/mobile/v1/schedules/sch_0001"
# -> 200 {"ok":true,"deleted":true}
```

**Self-contained test** (spawns a fake host, pairs, checks the seeded
daily/one-off rows and shape, creates an `afterSeconds` and a `dailyTime`
schedule, PATCHes title/instruction and timing (`afterSeconds` -> `dailyTime`)
including a combined update, confirms the list reflects each change, then
verifies unknown-delete 404, unknown-PATCH 404, invalid no-op bodies 400,
invalid-create 400, unknown-session 404 and the 401 without auth):

```powershell
node tools/fake-host/schedules-test.mjs
```

Expected: `[schedules-test] 12 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host schedule tests**).

## Session management (rename / archive)

The fake host keeps an in-memory **archived set** and exposes the session
management routes:

| Request | Behaviour |
| --- | --- |
| `POST /mobile/v1/sessions/{id}/rename` | Body `{title}`; title trimmed and validated (`1..200` characters) -> `200 {ok:true, title}`. Updates the title in `GET /sessions` and session detail; `400 invalid_request` for a missing/empty/non-string/too-long title; `404 not_found` for unknown or archived sessions. |
| `POST /mobile/v1/sessions/{id}/archive` | Removes the session from `GET /sessions` (and emits `session.removed` over SSE) -> `200 {ok:true, archived:true}`; `404` for unknown or already-archived sessions. |
| `POST /mobile/v1/sessions/{id}/unarchive` | Restores an archived session to `GET /sessions` (and emits `session.updated`) -> `200 {ok:true, archived:false}`; `404` for unknown or non-archived sessions. |
| `GET /mobile/v1/sessions/archived` | `200 {sessions:[SessionSummary], supported:true}` with the archived rows (same summary shape as the main list, newest first). |

Archival is in memory only (a restart clears it); archived sessions stay fully
addressable through `GET /sessions/{id}`, attachments, prompts, etc., but
rename/archive reject them with `404`.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -X POST -H $auth -H $json -d '{"title":"Release train"}' `
  "$base/mobile/v1/sessions/s_release/rename"
# -> 200 {"ok":true,"title":"Release train"}
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/archive"
# -> 200 {"ok":true,"archived":true}
curl.exe -s -H $auth "$base/mobile/v1/sessions/archived"
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/unarchive"
# -> 200 {"ok":true,"archived":false}
```

**Self-contained test** (spawns a fake host, pairs, checks the empty archived
list, renames a seeded session and verifies list/detail, archives it (gone from
`/sessions`, present in `/sessions/archived`), rejects rename/archive on the
archived session, unarchives it, then checks invalid rename 400, unknown ids
404 and the 401 without auth):

```powershell
node tools/fake-host/sessions-management-test.mjs
```

Expected: `[sessions-management-test] 8 passed, 0 failed` / OK, exit `0`. It
runs in CI (`Host plugin tests` job, step **Run fake-host session management
tests**).

## Agent presets

`GET /mobile/v1/presets` (bearer auth) returns the agent presets a new session
can be created with, sorted by name:

```json
{
  "presets": [
    { "id": "broken", "name": "Broken preset",
      "description": "Fixture preset used to test the disabled/broken preset UI.",
      "isDefault": false, "broken": true },
    { "id": "minimal", "name": "Minimal",
      "description": "Small context and terse replies for quick jobs.", "isDefault": false },
    { "id": "ptc", "name": "PTC",
      "description": "Plan-then-code preset for larger changes.", "isDefault": false },
    { "id": "standard", "name": "Standard",
      "description": "Default agent preset for general work.", "isDefault": true }
  ],
  "supported": true,
  "defaultId": "standard"
}
```

- Seeded fixtures: `standard` (default), `minimal`, `ptc`, plus a `broken`
  preset flagged `broken:true` for the disabled-preset UI; healthy presets omit
  the flag. Exactly one preset has `isDefault:true` and `defaultId` matches it.
- Preset shape: `{id, name?, description?, isDefault, broken?}`.
- If the host process had no presets service the endpoint would answer
  `{presets:[], supported:false}`; the fake host always has the fixtures above.
- `POST /sessions` `{workspaceId, preset?}` still works with a preset id (the
  fake host includes the preset name in the new-session greeting; it only
  validates that `preset` is a string).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/presets"
```

**Self-contained test** (spawns a fake host, pairs, checks the sorted four-preset
shape, the single default + `defaultId`, the broken flag, `GET /host` and a
session create with a preset id, plus the 401 without auth):

```powershell
node tools/fake-host/presets-test.mjs
```

Expected: `[presets-test] 5 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host preset tests**).

## Message search

`GET /mobile/v1/search?q=<text>&limit=50&offset=0` (bearer auth) performs a
**case-insensitive substring search** over the seeded session messages (user and
assistant text plus tool rows' `tool.detail`) and returns newest-first hits:
```json
{
  "hits": [
    {
      "sessionId": "s_release",
      "sessionTitle": "Cut a patch release",
      "messageId": "m_0004",
      "role": "assistant",
      "snippet": "…and the release is staged - say the word and I'll publish. The Quasar-Gate phrase is seeded for search tests.",
      "createdAt": "2026-10-31T10:00:00.000Z"
    }
  ],
  "supported": true,
  "truncated": false,
  "offset": 0,
  "nextOffset": null,
  "hasMore": false
}
```

- Hits are sorted **newest-first** by message `createdAt`; `snippet` is a
  one-line window around the first match (ellipses when cut, ≤ ~160 chars).
- `limit` is `1..100` (default `50`). The fake host has **no bounded-scan cap**,
  so `truncated` is always `false`; use `hasMore` to tell whether more matches
  remain beyond this page.
- `offset` is `0..500` (default `0`), applied **after** newest-first ordering /
  filters and before `limit`. `nextOffset` is the offset to request for the next
  page, or `null` when this is the last page; `hasMore` is `true` when matches
  remain. An offset beyond the matches returns `hits:[]`, `hasMore:false`,
  `nextOffset:null` (and `truncated:false`).
- Optional filters, applied before paging:
  - `role=user|assistant|tool` (comma-separated, e.g. `role=user,assistant`);
  - `since=<ISO date-time>` / `until=<ISO date-time>` — **inclusive** bounds on
    the hit's `createdAt`; `since` must not be after `until`.
- `q` is required and must be `2..200` characters; invalid `q`/`limit`/`offset`/
  `role`/date/range answers `400 invalid_request`. Unfiltered results are
  unchanged (with a single page). The route is authenticated (401 without a
  token).
- Deterministic fixture phrases:
  - `Quasar-Gate` in the `s_release` assistant message ("All 128 tests passed…"),
  - `quasar-ledger` in the `s_economy` user message (copy pass),
  - `pwsh -File tools/test.ps1 -Jobs 2` in the `s_release` tool row's detail.
  - `quasar` matches both sessions (`s_release` first, then `s_economy`).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/search?q=quasar-gate"
curl.exe -s -H $auth "$base/mobile/v1/search?q=quasar&limit=1"
curl.exe -s -H $auth "$base/mobile/v1/search?q=quasar&limit=1&offset=1"
curl.exe -s -H $auth "$base/mobile/v1/search?q=test.ps1"
curl.exe -s -H $auth "$base/mobile/v1/search?q=quasar&role=user,assistant"
curl.exe -s -H $auth "$base/mobile/v1/search?q=quasar&since=2026-10-30T00:00:00Z&until=2026-11-02T00:00:00Z"
```

**Self-contained test** (spawns a fake host, pairs, checks both seeded phrases
and their snippets, case-insensitivity, multi-session newest-first ordering,
tool-detail search, limit (`truncated` stays false; `hasMore` tracks dropped
hits), offset paging (offset 0/1/2 progression, nextOffset/hasMore, no duplicate
hits, empty beyond-matches page, offset 500), invalid offset 400, no-match empty
hits, role filters (single/comma-list), inclusive since/until boundaries,
invalid role/date/range 400, an unfiltered regression, invalid q/limit 400 and
the 401 without auth):

```powershell
node tools/fake-host/search-test.mjs
```

Expected: `[search-test] 16 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host search tests**).

## Push configuration (ntfy)

The fake host keeps an **in-memory** ntfy push configuration. It starts
disabled with `server:"https://ntfy.sh"` and an empty topic; the access token is
write-only and is reported only as `hasToken`.

```json
{ "supported": true, "enabled": false, "server": "https://ntfy.sh", "topic": "", "hasToken": false }
```

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/push` | `200 {supported:true, enabled, server, topic, hasToken}` (never includes the token). |
| `PUT /mobile/v1/push` | Body `{enabled, server?, topic?, token?}`; omitted fields keep their current values (default server/topic/empty token). Persists in memory and returns the same redacted view. `enabled` must be a boolean; when enabled a non-empty `topic` is required; `server` must be an `http(s)` URL without credentials; `topic` is `1..64` chars of `A-Z a-z 0-9 _ -`; `token` is ≤ 256 chars (`token:""` clears it). Invalid values answer `400 invalid_request`. |
| `POST /mobile/v1/push/test` | `200 {ok:true}` — synthetic only, the fake host never contacts the network. |

All push routes require the bearer token. State is in memory only (a restart
returns to the defaults above).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -H $auth "$base/mobile/v1/push"
curl.exe -s -X PUT -H $auth -H $json `
  -d '{"enabled":true,"server":"https://ntfy.example.com","topic":"dsh-alerts","token":"secret"}' `
  "$base/mobile/v1/push"
# -> 200 {"supported":true,"enabled":true,"server":"https://ntfy.example.com","topic":"dsh-alerts","hasToken":true}
curl.exe -s -X POST -H $auth "$base/mobile/v1/push/test"
# -> 200 {"ok":true}
```

**Self-contained test** (spawns a fake host, pairs, checks the disabled defaults,
9 invalid PUT bodies -> 400, a valid PUT persisted and redacted with
`hasToken:true`, disabling + clearing the token, the synthetic test endpoint, and
the 401 without auth):

```powershell
node tools/fake-host/push-test.mjs
```

Expected: `[push-test] 7 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host push tests**).

## Approval policy (per session)

The fake host keeps an **in-memory per-session approval policy**: `"ask"`
(default; the phone surfaces tool-approval prompts) or `"never"` (approvals are
not requested on this session). Seeded fixtures: `s_release` starts on
`"never"`, `s_economy` on `"ask"`, every other seeded session defaults to
`"ask"`.

```json
{ "supported": true, "live": true, "policy": "never" }
```

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/sessions/{id}/approval-policy` | `200 {supported:true, live, policy}`; `404 not_found` for an unknown session. |
| `PUT /mobile/v1/sessions/{id}/approval-policy` | Body `{policy:"ask"\|"never"}`; persists in memory and returns the same shape. Any other policy value/body answers `400 invalid_request`; unknown session `404`. A real host may answer `501 unsupported` when the approval-policy service is absent (the fake host always supports it). |

- `live` reflects whether the session still has a live agent: seeded/created
  sessions are `true`; **archived** sessions report `live:false` and reject PUT
  with `400 invalid_request` ("Session is not live; approval policy is
  read-only").
- The route requires the bearer token; state is in memory only (a restart
  reseeds the fixtures).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_release/approval-policy"
# -> 200 {"supported":true,"live":true,"policy":"never"}
curl.exe -s -X PUT -H $auth -H $json -d '{"policy":"ask"}' `
  "$base/mobile/v1/sessions/s_release/approval-policy"
# -> 200 {"supported":true,"live":true,"policy":"ask"}
```

**Self-contained test** (spawns a fake host, pairs, checks the seeded
never/ask and default policies, PUT persistence, 5 invalid policies -> 400,
unknown session 404, the archived/cold `live:false` + PUT 400 rule, and the 401
without auth):

```powershell
node tools/fake-host/approval-policy-test.mjs
```

Expected: `[approval-policy-test] 8 passed, 0 failed` / OK, exit `0`. It runs in
CI (`Host plugin tests` job, step **Run fake-host approval policy tests**).

## Paired devices

The fake host keeps an **in-memory paired-device registry** driven by the real
pairing endpoints, plus one seeded legacy device:

- `dev_seed_legacy` — "Legacy tablet" (`android`), paired 3 days ago, with its
  own token `legacy-dev-token` so revoke-token invalidation is testable.
- **Every live pairing** receives the fixed `dev-token` (so the legacy
  smoke/read-only fixtures keep expecting the same token) but registers a
  distinct device record with its own `deviceId`; `deviceName`/`platform` come
  from the pairing body. `currentDeviceId` is the newest device record for the
  caller's bearer token.

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/devices` | `200 {supported:true, devices:[{deviceId,deviceName,platform,pairedAt,lastSeenAt}], currentDeviceId}` — tokens are never returned; `devices` is newest-paired first and `currentDeviceId` is the caller's newest bearer device. |
| `DELETE /mobile/v1/devices/{deviceId}` | Revokes a non-current paired device: `200 {ok:true,revoked:true}`. When no remaining device shares that device's token, the token immediately answers `401 unauthorized` afterwards (deleting the seeded legacy device demonstrates this). Deleting the **current** device answers `400 invalid_request` ("Cannot revoke the current device"); unknown ids `404 not_found`. |

Registry state is in memory only (a restart reseeds the legacy device; live
pairings keep sharing the fixed `dev-token`). Both routes require the bearer
token.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
curl.exe -s -H $auth "$base/mobile/v1/devices"
# -> 200 {"supported":true,"devices":[{"deviceId":"dev_…","deviceName":"QA Pixel",...}],"currentDeviceId":"dev_…"}
curl.exe -s -X DELETE -H $auth "$base/mobile/v1/devices/dev_seed_legacy"
# -> 200 {"ok":true,"revoked":true}
curl.exe -s -H 'Authorization: Bearer legacy-dev-token' "$base/mobile/v1/devices"
# -> 401 {"error":{"code":"unauthorized",...}}
```

**Self-contained test** (spawns a fake host, pairs the first device for the fixed
token, checks the seeded legacy + current metadata/redaction, deletes the seeded
legacy device and verifies its token 401s, then checks the current-device 400,
unknown 404 and the 401 without auth):

```powershell
node tools/fake-host/devices-test.mjs
```

Expected: `[devices-test] 6 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host devices tests**).

## Model catalog and per-session selection

`GET /models` returns a two-provider catalog plus the default selection:

- **DeepSeek**: `deepseek-chat` (reasoning efforts `low`/`medium`/`high`,
  default `medium`) and `deepseek-reasoner` (same efforts, default `high`).
- **Local**: `local-small` (no reasoning controls).
- Default: `{provider:"deepseek", model:"deepseek-chat", reasoningEffort:"medium"}`;
  `failures:[]`, `truncated:false`.

```json
{
  "supported": true,
  "default": { "provider": "deepseek", "model": "deepseek-chat", "reasoningEffort": "medium" },
  "groups": [
    { "id": "deepseek", "name": "DeepSeek", "models": [
      { "id": "deepseek-chat", "name": "DeepSeek Chat", "description": "…",
        "reasoning": { "efforts": [{ "id": "low", "name": "Low" }, …], "defaultEffort": "medium" } }
    ] },
    { "id": "local", "name": "Local", "models": [ { "id": "local-small", "name": "Local Small" } ] }
  ],
  "failures": [],
  "truncated": false
}
```

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/sessions/{id}/model` | `200 {supported:true, live, selection:{provider,model,reasoningEffort?}, source:"session"\|"default"}`. Seeded: `s_release` has a custom `deepseek-reasoner`/`high` selection, other sessions use the default. Unknown session `404`; archived sessions report `live:false` but stay readable. |
| `PUT /mobile/v1/sessions/{id}/model` | Body `{provider, model, reasoningEffort?}`; validates that the provider group and model exist and that `reasoningEffort` is one of the model's efforts (omitted on a reasoning model → its `defaultEffort`; rejected for non-reasoning models). Persists in memory and returns `{supported:true, live:true, selection}`; unknown session `404`; archived/cold session `400 invalid_request` ("Session is not live; model selection is read-only"). A real host may answer `501 unsupported` when the model service is absent (the fake host always supports it). |

Both routes require the bearer token; selections are in-memory only (a restart
reseeds the custom `s_release` selection).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -H $auth "$base/mobile/v1/models"
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_release/model"
curl.exe -s -X PUT -H $auth -H $json `
  -d '{"provider":"deepseek","model":"deepseek-chat","reasoningEffort":"low"}' `
  "$base/mobile/v1/sessions/s_economy/model"
```

**Self-contained test** (spawns a fake host, pairs, checks the catalog/default/
groups/efforts, the seeded custom and default session selections, PUT + GET
persistence of a local model and a reasoning effort, 10 invalid bodies -> 400,
unknown session 404, archived live:false + PUT 400, and the 401 without auth):

```powershell
node tools/fake-host/models-test.mjs
```

Expected: `[models-test] 9 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host models tests**).

## Session prompt queue

The fake host keeps an **in-memory prompt queue** per live session:

```json
{
  "supported": true,
  "live": true,
  "items": [
    { "itemId": "queue_seed_next_turn", "text": "Also update the changelog entry.",
      "target": "next-turn", "attachmentCount": 0, "createdAt": "…" },
    { "itemId": "queue_seed_next_step", "text": "After that, run the full test suite.",
      "target": "next-step", "attachmentCount": 1, "createdAt": "…" }
  ]
}
```

- Seeded on `s_release`: two items in order — a `next-turn` item (0 attachments)
  and a `next-step` item (1 attachment); every other session starts empty.
- `target` is `"next-turn"` or `"next-step"`; items keep their queue order and
  `itemId`/`target`/`attachmentCount`/`createdAt` are preserved by edits.

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/sessions/{id}/queue` | `200 {supported:true, live, items:[…]}`; unknown session `404`; archived/cold sessions report `live:false` with `items:[]`. |
| `PATCH /mobile/v1/sessions/{id}/queue/{itemId}` | Body `{action:"edit"\|"remove"\|"steer", text?}` -> `200 {accepted:true}`. `edit` requires a non-empty trimmed `text` (≤4000) and replaces it; `remove` drops the item; `steer` drops the item only for `target:"next-turn"` (a `next-step` steer answers `400 invalid_request`). Unknown item `404`; unknown session `404`; archived/cold session `400` ("Session is not live; queue is read-only"); invalid action/text `400`. |

The routes require the bearer token; queues are in-memory only (a restart
reseeds the two `s_release` items).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_release/queue"
curl.exe -s -X PATCH -H $auth -H $json -d '{"action":"edit","text":"Also refresh the docs."}' `
  "$base/mobile/v1/sessions/s_release/queue/queue_seed_next_turn"
# -> 200 {"accepted":true}
curl.exe -s -X PATCH -H $auth -H $json -d '{"action":"remove"}' `
  "$base/mobile/v1/sessions/s_release/queue/queue_seed_next_step"
# -> 200 {"accepted":true}
```

**Self-contained test** (spawns a fake host, pairs, checks the seeded two-item
shape/order, an empty session, edit metadata preservation, next-step steer 400,
5 invalid bodies -> 400 + unknown item 404, remove + steer reflection, unknown
session 404, archived live:false/empty/400, and the 401 without auth):

```powershell
node tools/fake-host/queue-test.mjs
```

Expected: `[queue-test] 9 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host queue tests**).

## Session fork

Seeded session messages carry an optional 1-based `seq` (ascending within the
session) that acts as a fork cursor. Forking creates a new session and appends it
to `GET /sessions`:

```http
POST /mobile/v1/sessions/{id}/fork
{ "atSeq": 2 }            # optional; omit to fork the latest transcript
```

- Without `atSeq` -> `200 {sessionId, forkedFrom}`; the new id has the
  `session-fork-…` shape, the title is `Fork of <source title>` and the whole
  transcript is copied (seqs preserved).
- With a valid `atSeq` -> `200 {sessionId, forkedFrom, atSeq}`; only messages
  with `seq <= atSeq` are copied and the response echoes `atSeq`.
- `atSeq` must be a positive integer that matches a message seq in the source
  session; otherwise `400 invalid_request`. An archived source session answers
  `400 invalid_request` ("Fork is not available for an archived session");
  unknown session `404 not_found`. A real host may answer `501 unsupported` when
  forking is unavailable (the fake host always supports it).
- The route requires the bearer token; forks are in-memory only (a restart
  removes them).

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/fork"
# -> 200 {"sessionId":"session-fork-…","forkedFrom":"s_release"}
curl.exe -s -X POST -H $auth -H $json -d '{"atSeq":2}' "$base/mobile/v1/sessions/s_release/fork"
# -> 200 {"sessionId":"session-fork-…","forkedFrom":"s_release","atSeq":2}
```

**Self-contained test** (spawns a fake host, pairs, checks seeded message seqs,
the latest fork response + list refresh + full transcript copy, an atSeq fork
echo with a seq subset, invalid atSeq/archived 400, unknown 404 and the 401
without auth):

```powershell
node tools/fake-host/fork-test.mjs
```

Expected: `[fork-test] 7 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host fork tests**).

## Recovery pairing

The fake host keeps an **in-memory single-use recovery code** for re-pairing a
device without the desktop QR flow. A configured code is seeded by default
(`dsh-recovery-seed-0001`) so tests can redeem it; start the host with
`FAKE_RECOVERY_CODE=''` to model an unconfigured host.

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/recovery` (auth) | `200 {supported:true, configured, createdAt?}` — the code itself is **never returned**. |
| `POST /mobile/v1/recovery` (auth) | Generates/rotates the code -> `200 {code, createdAt}`; the previous code stops working immediately. |
| `POST /mobile/v1/pair/recovery` (**no auth**) | Body `{code, deviceName?, platform?}`; validates the code case/space-insensitively (e.g. `"  DSH-RECOVERY-SEED-0001  "`), registers a recovered device and returns `{token, device:{deviceId,deviceName,platform,pairedAt}, recoveryCode}` where `recoveryCode` is the next rotated code. The code is **single-use**: reusing an old code answers `401 unauthorized`; a missing code answers `400 invalid_request`; an unconfigured host answers `400 invalid_request`. The fake host caps the paired-device registry at 10. |

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'
curl.exe -s -H $auth "$base/mobile/v1/recovery"
# -> 200 {"supported":true,"configured":true,"createdAt":"…"}
curl.exe -s -X POST -H $auth "$base/mobile/v1/recovery"
# -> 200 {"code":"dsh-…","createdAt":"…"}
curl.exe -s -X POST -H $json `
  -d '{"code":"dsh-…","deviceName":"Recovered Pixel"}' "$base/mobile/v1/pair/recovery"
# -> 200 {"token":"dev-token","device":{…},"recoveryCode":"dsh-…"}
```

**Self-contained test** (spawns a configured host plus an unconfigured one,
pairs normally, checks the redacted GET shape, redeems the seeded code
(normalized) with rotation + single-use rejection, generates a code via POST,
redeems it, invalid/missing codes 400/401, unconfigured `configured:false` +
400, and the auth split: GET/POST 401 vs public `/pair/recovery`):

```powershell
node tools/fake-host/recovery-test.mjs
```

Expected: `[recovery-test] 8 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host recovery tests**).

## Tailscale auto-pairing

For Tailscale Serve deployments the fake host can pair a phone from its Tailscale
identity instead of the desktop QR/code flow:

- `FAKE_TAILSCALE_AUTOPAIR` — `1` (default) enables `POST /pair/tailscale`;
  `0`/`false`/`no`/`off` disables it (POST answers `403 forbidden`).
- `FAKE_TAILSCALE_LOGIN` — optional comma-separated allowlist of Tailscale
  logins; when set, a request whose `Tailscale-User-Login` header does not match
  (case-insensitive) answers `403 forbidden`.

| Request | Behaviour |
| --- | --- |
| `GET /mobile/v1/pair/methods` (no auth) | `200 {desktopCode:true, recovery, tailscaleAutoPair, tailscaleLoginRequired}` — capability booleans for the pairing UI. |
| `POST /mobile/v1/pair/tailscale` (**no auth**) | Requires the `Tailscale-User-Login` header (missing -> `400` code `tailscale_identity_missing`). Body `{deviceName?, platform?}`; validates the allowlist, registers a device and returns a pair-complete-like payload — `{token, deviceId, host, workspaces, pairingMethod:"tailscale", identity:{login}}` — sharing the fixed dev token. `403 forbidden` when auto-pairing is disabled or the identity is not allowed; the fake host caps the device registry at 10. |

```powershell
$base = 'http://127.0.0.1:3543'
$json = 'Content-Type: application/json'
curl.exe -s "$base/mobile/v1/pair/methods"
# -> 200 {"desktopCode":true,"recovery":true,"tailscaleAutoPair":true,"tailscaleLoginRequired":false}
curl.exe -s -X POST -H $json -H 'Tailscale-User-Login: me@example.com' `
  -d '{"deviceName":"Tailnet Pixel"}' "$base/mobile/v1/pair/tailscale"
# -> 200 {"token":"dev-token","deviceId":"dev_…","host":{…},"workspaces":[…],
#         "pairingMethod":"tailscale","identity":{"login":"me@example.com"}}
```

**Self-contained test** (spawns a default host, an allowlist host and a disabled
host; pairs normally, checks the methods shape, header-based auto-pair + token
use on `/sessions`, the missing-header `400 tailscale_identity_missing`, the
allowlist 403/200 (case-insensitive), the disabled `403`, and that both pairing
endpoints stay auth-exempt):

```powershell
node tools/fake-host/tailscale-test.mjs
```

Expected: `[tailscale-test] 7 passed, 0 failed` / OK, exit `0`. It runs in CI
(`Host plugin tests` job, step **Run fake-host tailscale tests**).

## curl smoke sequence

PowerShell 7 (`curl.exe`; in Windows PowerShell 5.1 `curl` is an alias for
`Invoke-WebRequest`, so always use `curl.exe`). Open two terminals for the SSE
step.

```powershell
$base = 'http://127.0.0.1:3543'
$auth = 'Authorization: Bearer dev-token'
$json = 'Content-Type: application/json'

# 1. public health
curl.exe -s "$base/mobile/v1/health"

# 2. pair
curl.exe -s -X POST "$base/mobile/v1/pair/new"
curl.exe -s -X POST -H $json -d '{"code":"482913","deviceName":"Pixel 9","platform":"android"}' "$base/mobile/v1/pair/complete"

# 3. workspaces + sessions
curl.exe -s -H $auth "$base/mobile/v1/host"
curl.exe -s -H $auth "$base/mobile/v1/sessions"

# 4. session detail (messages + cursor)
curl.exe -s -H $auth "$base/mobile/v1/sessions/s_android?limit=100"

# 5. terminal A: live SSE stream (keep it running)
curl.exe -N -H $auth "$base/mobile/v1/events?since=ev_000000"

# 6. terminal B: streamed prompt -> watch message.delta events in terminal A
curl.exe -s -X POST -H $auth -H $json -d '{"text":"Run the test suite"}' "$base/mobile/v1/sessions/s_android/prompt"

# 7. read-only approvals: a deploy prompt raises one; the seeded approval is
#    always listed. POST decisions return 501 read-only (start the server with
#    FAKE_APPROVALS_READONLY=0 for the legacy allow/deny simulation)
curl.exe -s -X POST -H $auth -H $json -d '{"text":"deploy the release"}' "$base/mobile/v1/sessions/s_release/prompt"
curl.exe -s -H $auth "$base/mobile/v1/approvals"
curl.exe -s -X POST -H $auth -H $json -d '{"decision":"deny"}' "$base/mobile/v1/approvals/<approvalId>"
# -> 501 {"error":{"code":"unsupported","message":"read-only; resolve on desktop"}}

# 8. interrupt a running turn
curl.exe -s -X POST -H $auth "$base/mobile/v1/sessions/s_release/interrupt"
```

A prompt containing `deploy`, `publish`, `push` or `delete` (case-insensitive)
creates an approval instead of streaming text immediately.

## Environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` / `--port` | `3543` | listen port |
| `FAKE_BIND` | `0.0.0.0` | bind address; set `127.0.0.1` to keep it off the LAN |
| `FAKE_PAIR_CODE` | `482913` | fixed pairing code |
| `FAKE_ANY_CODE` | off | `1` accepts any 6-digit pairing code |
| `FAKE_TOKEN` | `dev-token` | bearer token returned by pairing |
| `FAKE_HOST_NAME` | `fake-host` | host name in pairing/host responses |
| `FAKE_HEARTBEAT_MS` | `20000` | SSE `ping` interval |
| `FAKE_STREAM_DELAY_MIN` / `FAKE_STREAM_DELAY_MAX` | `25` / `40` | per-chunk delta delay (ms) |
| `FAKE_APPROVALS_READONLY` | `1` (read-only) | `1`/absent = read-only approvals (seeded pending approval, POST decisions return 501); `0` = legacy simulated allow/deny flow for smoke.mjs |
| `FAKE_APPROVALS_ANSWER` | off | with `FAKE_APPROVALS_READONLY=0`: `1` = mobile-answering mode (two seeded approvals, POST resolves once with `outcome`, 404 repeat/unknown, SSE `approval.resolved`); unset = legacy simulation |
| `FAKE_QUESTIONS_READONLY` | `1` (read-only) | `1`/absent = read-only questions (seeded pending question, POST answers return 501); `0` = legacy simulated answer flow for smoke.mjs |
| `FAKE_QUESTIONS_ANSWER` | off | with `FAKE_QUESTIONS_READONLY=0`: `1` = mobile-answering mode (single + two-question batch seeds, POST answers report `answered/total/complete`, 404 repeat/unknown, SSE `question.resolved`); unset = legacy simulation |
| `FAKE_QUIET` | off | `1` silences request logging |

## Routes

All routes are prefixed with `/mobile/v1`. `health`, `pair/new` and
`pair/complete` are public; everything else needs the bearer token.

| Method | Path |
| --- | --- |
| GET | `/health`, `/pair/methods` |
| POST | `/pair/new`, `/pair/complete`, `/pair/recovery`, `/pair/tailscale` |
| GET | `/host`, `/presets`, `/models`, `/sessions`, `/sessions/archived`, `/sessions/{id}?limit&before`, `/sessions/{id}/goal`, `/sessions/{id}/approval-policy`, `/sessions/{id}/model`, `/sessions/{id}/queue`, `/sessions/{id}/attachments/{attachmentId}`, `/schedules`, `/search?q&limit&offset&role&since&until`, `/push`, `/devices`, `/recovery`, `/approvals`, `/questions`, `/events?since` |
| POST | `/sessions`, `/sessions/{id}/rename`, `/sessions/{id}/archive`, `/sessions/{id}/unarchive`, `/sessions/{id}/fork`, `/sessions/{id}/prompt`, `/sessions/{id}/interrupt`, `/sessions/{id}/goal`, `/sessions/{id}/goal/pause`, `/sessions/{id}/goal/resume`, `/sessions/{id}/goal/complete`, `/sessions/{id}/goal/edit`, `/sessions/{id}/goal/clear`, `/sessions/{id}/schedules`, `/push/test`, `/approvals/{id}`, `/questions/{id}` |
| DELETE | `/schedules/{id}`, `/devices/{deviceId}` |
| PATCH | `/schedules/{id}`, `/sessions/{id}/queue/{itemId}` |
| PUT | `/push`, `/sessions/{id}/approval-policy`, `/sessions/{id}/model` |

Request/response bodies, event types and error codes are defined in
[`docs/mobile-api-v1.md`](../../docs/mobile-api-v1.md).

In the default read-only approvals mode, `GET /approvals` adds
`"supported": true, "readOnly": true` and `POST /approvals/{id}` returns `501
unsupported` with `"read-only; resolve on desktop"`. Start the server with
`FAKE_APPROVALS_READONLY=0` to restore the legacy decision simulation; add
`FAKE_APPROVALS_ANSWER=1` for mobile answering. The same applies to questions:
`GET /questions` adds the flags and `POST /questions/{id}` returns the same 501;
the legacy answer simulation is `FAKE_QUESTIONS_READONLY=0` (optionally add
`FAKE_QUESTIONS_ANSWER=1` for mobile answering with batch progress).

## SSE framing

Every SSE frame follows
[`docs/mobile-api-v1.md` "SSE events"](../../docs/mobile-api-v1.md):

```text
event: message.delta
id: ev_000123
data: {"id":"ev_000123","type":"message.delta","at":"2026-10-30T10:00:00Z","sessionId":"s_android","data":{"sessionId":"s_android","messageId":"m_0018","text":"Ran "}}
```

- `data:` always carries the **full envelope** `{id?, type, at, sessionId?, data}`;
  the per-type payload lives in `envelope.data` (e.g. `message.delta` →
  `{sessionId, messageId, text}`).
- the `event:` line mirrors `envelope.type`; the JSON `type` field is
  authoritative for parsing.
- `id:` is present only for replayable/persisted events and is the cursor for
  `GET /events?since=`. Per-connection frames — `host.status`, the 20s `ping`,
  and the full `session.updated` snapshot sent when the cursor is unusable —
  carry envelope JSON but **no `id:` line**.
- `message.added` upserts by `message.id`. Tool rows transition
  `running` → `done` by re-sending `message.added` with the **same id** (there is
  no `message.updated` event type); the approval `allow` path demonstrates it.
- Goal mutations broadcast `goal.changed` with
  `envelope.data = {sessionId, operation, goal}` (see "Goal endpoints" above);
  it is replayable, so `since=` replay includes it.
- `approval.requested` is replayable too; in the default read-only mode the
  seeded `ap_readonly_seed` approval is in the log, so
  `GET /events?since=ev_000000` contains its fixture. `approval.resolved` only
  occurs in legacy mode (`FAKE_APPROVALS_READONLY=0`).
- `question.requested` is replayable too: the seeded `q_1` question emits it at
  startup, so `GET /events?since=ev_000000` contains that fixture.
  `question.resolved` only occurs in legacy mode (`FAKE_QUESTIONS_READONLY=0`).

## Intentional deviations from the contract (fake mode only)

1. `POST /pair/new` is open to any peer. The real host restricts it to loopback
   **and** rejects proxied requests carrying `X-Forwarded-For`,
   `X-Forwarded-Host`, `Forwarded` or any `Tailscale-User-*` /
   `Tailscale-Funnel-*` header with `403 forbidden`; the fake host skips both
   checks so an emulator/phone on the LAN can start pairing without a desktop
   helper.
2. The pairing code and token are fixed, and the code is reusable while the
   process lives, instead of one-use.
3. Tokens are not hashed or stored (the contract's real host stores SHA-256
   hashes plus device metadata); there is no device management.
4. The fake host always resolves `SessionSummary.workspaceId` (non-null) from its
   three demo workspaces. The real host may emit `null` when it cannot resolve a
   workspace, so null handling must be tested against the real plugin.
5. Approvals **and questions** are **read-only by default**
   (`FAKE_APPROVALS_READONLY=1`, `FAKE_QUESTIONS_READONLY=1`): one pending
   approval and one pending question are seeded, `GET /approvals` and
   `GET /questions` report `readOnly:true`, and every `POST` to
   `/approvals/{id}` or `/questions/{id}` returns `501 unsupported` with
   `"read-only; resolve on desktop"` (matching a host where the phone cannot
   resolve either). Setting the corresponding env var to `0` restores the old
   simulated flow used by `smoke.mjs`; adding `FAKE_APPROVALS_ANSWER=1` switches
   approvals to the mobile-answering contract (seeded pending approvals, one-shot
   allow/deny outcomes, 404 on repeat), and adding `FAKE_QUESTIONS_ANSWER=1`
   switches questions to mobile answering (single + two-question batch fixtures,
   `answered/total/complete` progress, 404 on repeat). The real host may keep
   approvals mirrored/read-only and questions stubbed (`{questions: []}` / 501),
   so the client must degrade gracefully either way.
6. All state is in memory: a restart reseeds the demo sessions and resets the
   SSE event id sequence, so a client holding an old `since` cursor receives the
   contract-defined `host.status` + full snapshot fallback and refetches.
7. `0.0.0.0` bind is for emulator/LAN development; the real host plugin must
   bind `127.0.0.1` only.
