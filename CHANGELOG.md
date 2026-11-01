# Changelog

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
