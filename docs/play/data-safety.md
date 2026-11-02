# Google Play Data safety answers (draft)

Complete the Play Console form from the shipped Play build, not from the sideload APK. This is a
draft rationale for the current design (no developer-operated backend).

## Data collection and sharing

| Question | Answer for this app |
| --- | --- |
| Does your app collect or share any of the required user data types? | **No** |
| Is all collected data encrypted in transit? | Not applicable (no collection by the developer) |
| Do you provide a way for users to request data deletion? | No account/data held by developer. Users can clear app storage, remove hosts, revoke tokens on the host, or uninstall. |
| Do you have a privacy policy? | Yes — `PRIVACY.md` (public URL in the Play listing) |
| Independent security review? | No |

## Why "no collection"

- The app has no developer-operated backend, analytics SDK, crash-reporting SDK, advertising SDK or
  tracking SDK.
- Session content and prompts go directly to the host URL configured by the user.
- Crash reports are written to the app's private files and are only shared when the user explicitly
  taps a share action.
- Local preferences, drafts, caches and encrypted host tokens stay on the device.
- The Play build has no sideload updater and makes no GitHub release-feed request.

## Permissions rationale

- Network: connect to the user's host.
- Notifications: approval/question/turn alerts (optional).
- Microphone: optional voice dictation; only while the user activates dictation; the resulting text
  follows the normal prompt path.
- Foreground service (data sync): optional background approval/turn checks, with a visible
  notification.
- Install unknown apps: **not present in the Play build** (removed in the release manifest); present
  only in the sideload build for the optional self-updater.

## If admin-permission forwarding is added later

If a desktop helper is added to forward elevated actions to the phone, update this form and the
privacy policy before shipping that build. The Play app must not request or bypass OS administrator
permissions itself; any privileged desktop helper is a separate, explicitly installed component and
all approvals must be user-initiated, time-limited and auditable.
