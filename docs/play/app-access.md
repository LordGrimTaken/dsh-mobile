# Google Play App access instructions

Reviewers need a way to inspect the app without a private DeepSeek Harness host.

## Demo mode

1. Install and open DSH Android.
2. On the welcome screen choose the **demo** option (or continue without pairing).
3. The app loads in-memory sample sessions, messages and approvals; all core screens are reachable.

## Host pairing (if the reviewer wants the real flow)

A host is a PC running DeepSeek Harness with the DSH Android host plugin on loopback, exposed through
a private tunnel. The app pairs with a short-lived code:
1. On the desktop host, create a pairing code.
2. In the app, enter the host base URL and the code.
3. The host issues a device token; the app stores it encrypted.

No public test host is provided because the app intentionally has no cloud service and the host is
designed to be loopback-only.

## Notes for review

- No account, login, subscription or in-app purchase is required.
- No ads or analytics.
- The app's data stays on the device and the user-configured host.
- The Play build has no self-updater and no APK installation permission.
- If a screen asks for a host, use demo mode to proceed.

## Play Console App access form

The app has **no account or login system**, but full self-hosted functionality requires a host
pairing. Declare **some functionality as restricted** and give reviewers the demo path; do **not**
answer "no restrictions" because the reviewer will try the host/Tailscale options and see an
authentication screen.

1. Play Console → **App content → App access** (the "Sign in details" item on the setup dashboard).
2. Select **Some functionality is restricted** (or the equivalent "My app has restrictions" option).
3. Restriction type: **Other** — not login credentials.
4. Review instructions to paste:

> No account, username, password, membership, location or payment is required. On first launch a
> setup guide opens automatically; tap **Try demo data** at the top. If the guide is closed, the
> first option on the welcome screen is **Use demo data - no host or login**. This loads sample
> sessions, messages, approvals, plans and all core screens. Do not use **Sign in with Tailscale** or
> **Pair your desktop host**; those are optional self-hosted connection methods. Live host
> features are unavailable in demo mode, but no credentials are needed to review the interface
> and permissions.

5. Save, then return to **Publishing overview** and **resubmit the release for review**.

If Play requires a separate "login credentials" field, put "Not applicable — no account system;
use the demo instructions above."