# DSH Android privacy policy

**Effective date:** 2026-11-05
**App:** DSH Android Unofficial Harness (`com.dsh.unofficial.app` on Google Play; sideload builds use `dev.dsh.mobile`)
**Project:** https://github.com/LordGrimTaken/dsh-mobile

DSH Android is a client for a **DeepSeek Harness host that you run yourself**. It does not use a
developer-operated cloud service, account system, advertising network or analytics service.

## What the app does

The app connects directly to the host URL and device token you configure, usually a desktop on your
own network or reachable through a private tunnel such as Tailscale. Session content, prompts,
attachments, approvals, files and settings are exchanged only between your phone and the host you
chose.

## Data the developer collects

**None.** The developer does not collect, receive, sell, share or have access to your sessions,
prompts, files, device identifiers or usage data. There is no analytics, crash-reporting server,
advertising SDK or tracking SDK in the app.

## Data stored on your device

The app stores the following locally so it can work:

- host profiles and an encrypted device token (Android Keystore-backed where supported);
- drafts, prompt snippets, queued prompts, pinned/muted/hidden session preferences;
- cached session summaries and transcripts for offline/background display;
- local settings (appearance, notifications, app lock);
- local crash reports, written only to the app's private storage so the app can offer Safe mode or a
  share action; they are never uploaded automatically.

You can remove this data by clearing the app's storage or uninstalling it. Removing a host profile
removes its local token. Revoke device tokens on the host when retiring a phone.

## Permissions

- **Internet / network state** — connect to your host and detect connectivity.
- **Notifications** — alerts for completed turns, approvals and questions.
- **Microphone** — optional voice dictation; the app records only while you hold the dictation UI
  and sends the resulting text through the normal prompt path. Audio is not stored by the app.
- **Foreground service (data sync)** — optional background checks for approvals/turns when enabled;
  a persistent notification is shown.
- **Install unknown apps** — only in the sideload/APK build, for the optional self-updater. This
  permission is **removed from the Google Play build**.

## Network connections

The Play build connects only to the host URL you configure. The sideload build also checks a GitHub
release feed for updates; the Play build does not use that updater. The app may open links you tap
in the UI in your browser or share sheet.

Host connections should use HTTPS (for example Tailscale Serve). HTTP is supported for local
development and private networks but is not recommended over untrusted networks.

## Children

DSH Android is a developer tool and is not directed at children.

## Changes

If the app's data practices change, this policy will be updated in the repository and the effective
date changed. Material changes will also be noted in the release notes.

## Contact

Privacy questions: open an issue at
https://github.com/LordGrimTaken/dsh-mobile/issues
