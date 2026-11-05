# Security policy

DSH Android is a preview project that runs a local agent client. Please treat it as security-sensitive software.

## Reporting a vulnerability

Open a **private GitHub security advisory** on this repository rather than a public issue for anything that could expose a host, a device token, session content, or a way to bypass pairing. Include the app version, host-plugin version, Android version and reproduction steps. Do not include real device tokens, pairing codes, API keys or private session content in the report.

There is no bug-bounty or guaranteed response time; this is a volunteer preview project.

## What is in scope

- The Android app (`app/`) and its local storage, pairing and update flow.
- The host plugin (`host-plugin/`), Mobile API, authentication, redaction and file/attachment handling.
- The release feed and update verification flow.

## Out of scope

- Security of the DeepSeek Harness desktop product itself.
- Exposing the loopback host port directly to the public internet, running untrusted reverse proxies, or sharing device tokens/pairing codes.
- Custom builds signed with untrusted keys.
- The debug-signed APK being treated as a production-signed app.

## Security model and limitations

- The host plugin binds to `127.0.0.1` only; remote access should use a private tunnel such as Tailscale Serve. The app has no cloud backend.
- Pairing codes are short-lived and one-time; device tokens are issued per device and stored hashed on the host.
- Subagent/internal sessions are hidden from the mobile list by default.
- Redaction is defence-in-depth, not a guarantee: agent-authored text can contain sensitive material that patterns do not catch. Do not paste secrets into prompts.
- The preview APK is debug-signed. It is suitable for testing, not for distributing as a trusted production application.
- Session content is stored on your host and phone. Local backups and crash reports may contain session text; treat them as sensitive.
