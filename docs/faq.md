# Frequently asked questions

## Is this an official DeepSeek app?

No. DSH Android is an independent community project. It is not affiliated with or endorsed by DeepSeek.
DeepSeek Harness and DeepSeek are trademarks of their respective owners.

## Do I need an account?

No. There is no DSH account, login or subscription. The app pairs with a host that you run yourself.

## Can I try it without a computer?

Yes. On first launch, tap **Try demo data** in the setup guide (or **Use demo data** on the welcome
screen). Demo mode loads sample sessions and settings and never contacts a host.

## Is my session data sent anywhere?

No developer-operated server receives it. The app talks directly to the host URL you configure. The
host is normally on your own computer or a server you control. See the [privacy policy](../PRIVACY.md).

## Why does the app need a host?

The phone is a client; DeepSeek Harness runs on the computer that executes your agent sessions. The
host plugin provides the Mobile API that the app talks to. This keeps sessions and files under your
control instead of syncing them to a third-party cloud.

## What is the difference between the Play build and the GitHub APK?

| | Google Play | GitHub sideload |
| --- | --- | --- |
| Package | `com.dsh.unofficial.app` | `dev.dsh.mobile` |
| Signing | Play App Signing with the project upload key | Debug-signed |
| APK installer permission | Removed | Present for the optional self-updater |
| Update feed | Disabled | GitHub Releases |
| Intended use | Normal installation | Testing, development, direct builds |

Do not install one over the other; the package names and signing keys differ.

## Which Android versions are supported?

Android 8.0 (API 26) and newer.

## Does it work without Tailscale?

Yes. Any base URL the phone can reach works: a LAN address during development, a cloud host with
HTTPS, or a private tunnel. Tailscale Serve is recommended for remote access because it provides TLS
and tailnet-only reachability without opening a port.

## How do pairing codes work?

The host creates a short-lived, single-use six-digit code. Enter the host URL and code in the app; the
host returns a device token that the app stores encrypted. Codes expire after five minutes.

## Can I use several hosts?

Yes. Add or switch hosts in **Settings → Hosts**. Each host has its own device token.

## Why do notifications stop when the app is closed?

Enable **Settings → Notifications → Background sync**. It runs a foreground service with a persistent
notification and periodically checks the paired host for completed turns, approvals and questions.
Android battery optimization may still delay checks; exclude DSH Android if needed.

## How do I update?

- **Play build:** updates come through Google Play.
- **GitHub preview:** download the latest preview APK from Releases; updates must be signed with the
  same key. Play and GitHub builds are not interchangeable.

## How do I report a bug or security issue?

Open a GitHub issue for normal bugs. For anything that could expose a host, token or session content,
use a private security advisory as described in [SECURITY.md](../SECURITY.md). Never paste real tokens,
pairing codes or private session content into an issue.

## Can I contribute?

Yes. See [CONTRIBUTING.md](../CONTRIBUTING.md). Small, focused pull requests with tests are easiest to
review.
