# Foreground service dataSync declaration

**App:** DSH Android Unofficial Harness (com.dsh.unofficial.app)
**Build shown:** 0.3.1 (versionCode 147)
**Permission:** FOREGROUND_SERVICE_DATA_SYNC
**Service type:** dataSync
**Video:** [dsh-background-sync-demo.mp4](assets/dsh-background-sync-demo.mp4)
**SHA-256:** $sha

## Play Console answers

On **Review release → Foreground service permissions**, select:

- **Network processing → Other**

Do not select backup/restore, media transcoding, importing/exporting, or the other “Other” option.

Paste this description:

> DSH Android Unofficial Harness is a remote client for a self-hosted DeepSeek Harness host. Its optional
> background sync service is off by default and starts only after the user enables
> **Settings → Notifications → Background sync**. It runs a foreground service with the persistent
> notification “DSH background sync — Watching for turns, approvals, and questions” and polls only
> the user's paired host for newly completed turns, pending approvals, and pending questions, then
> posts local notifications. It transfers only small session status/approval/question metadata — no
> media, backups, imports, exports, or file transfer. The service stops when the user disables
> Background sync, removes the host profile, or clears app data.

## What the video shows

The recording is from the 0.3.1 app running on an Android 14 emulator, with the real host connection:

1. **Settings → Notifications**, with **Background sync** off.
2. Background sync switched on; the app starts the foreground service.
3. Notification shade shows the persistent notification:
   **DSH background sync — Watching for turns, approvals, and questions**.
4. Background sync switched off.
5. The persistent notification is removed.

No session titles, prompts, hostnames, tokens or other personal data are shown.

## Uploading to Play

Play usually expects a reviewable URL. Upload dsh-background-sync-demo.mp4 to Google Drive
(anyone with the link) or YouTube Unlisted and paste that URL into the declaration page. Do not use
a one-time file host; the review link must stay available until the review finishes.
