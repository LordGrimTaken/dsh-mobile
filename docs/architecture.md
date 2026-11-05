# Architecture

DSH Android is a two-part system: a native Android client and a small host plugin that runs inside the
user's DeepSeek Harness desktop profile. There is no developer-operated backend.

```text
┌─────────────────────┐      HTTPS (Tailscale Serve or LAN)      ┌───────────────────────────┐
│ Android app         │ ───────────────────────────────────────▶ │ DSH Android host plugin   │
│ Kotlin + Compose    │            REST + SSE                    │ inside DeepSeek Harness   │
│ no cloud, no account│ ◀─────────────────────────────────────── │ Node.js / Cordis          │
└─────────────────────┘                                          └───────────────────────────┘
                                                                              │
                                                                  DSH session controller,
                                                                  files, jobs and query APIs
```

## Android app (`app/`)

- **UI:** Kotlin and Jetpack Compose, Material 3, a terminal-first design system with dark, light and
  AMOLED palettes and adaptive two-pane layouts.
- **State:** a host-scoped state layer keeps session lists, transcripts, approvals, questions, usage and
  files in sync with the Mobile API.
- **Transport:** REST for commands and snapshots, Server-Sent Events (SSE) for live updates. The client
  reconnects automatically and falls back to cached summaries when the host is unavailable.
- **Storage:** host profiles, encrypted device tokens, drafts, queued prompts, preferences and cached
  transcripts are stored locally. Tokens use Android Keystore-backed encryption where available.
- **No account system:** the user pairs a device directly with a host. There is no DSH cloud account.

## Host plugin (`host-plugin/`)

- Runs inside DeepSeek Harness and binds to `127.0.0.1:3543`.
- Implements the stable **Mobile API v1** contract in [`mobile-api-v1.md`](mobile-api-v1.md).
- Issues short-lived pairing codes and per-device bearer tokens; tokens are stored hashed on the host.
- Fans out session updates over SSE and exposes REST endpoints for actions.
- Redacts credential-like values and host filesystem paths before data leaves the host.
- Hides internal/subagent sessions from the phone by default.

## Fake host (`tools/fake-host/`)

A dependency-free in-memory implementation of the Mobile API used by unit/integration tests and UI
development. It is not a production host.

## Pairing and trust flow

1. The host generates a one-time pairing code.
2. The app posts the code to the host over the configured base URL.
3. The host returns a device token; the app stores it encrypted.
4. Every API request carries the device bearer token.
5. The user can revoke devices on the host at any time.

The host port is loopback-only. Remote access is expected to go through a private tunnel such as
Tailscale Serve, which provides TLS and tailnet identity.

## Release channels

- **Google Play:** package `com.dsh.unofficial.app`, Play App Signing, no APK installer permission and
  no self-updater.
- **GitHub preview:** debug-signed APK with an optional in-app update checker and release feed.
- **Local debug:** package `dev.dsh.mobile`, built from source with the included Gradle wrapper.

## Related documents

- [Mobile API v1](mobile-api-v1.md)
- [Setup guide](setup.md)
- [Security policy](../SECURITY.md)
- [Repository README](../README.md)
