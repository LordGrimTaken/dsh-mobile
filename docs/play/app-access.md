# Google Play App access instructions

Reviewers need a way to inspect the app without a private DeepSeek Harness host.

## Demo mode

1. Install and open DSH Mobile.
2. On the welcome screen choose the **demo** option (or continue without pairing).
3. The app loads in-memory sample sessions, messages and approvals; all core screens are reachable.

## Host pairing (if the reviewer wants the real flow)

A host is a PC running DeepSeek Harness with the DSH Mobile host plugin on loopback, exposed through
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
