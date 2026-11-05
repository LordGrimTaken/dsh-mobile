# Setting up DSH Android

This guide covers installing the app, trying demo mode, connecting a desktop or cloud host, using
Tailscale, recovering a host, and fixing the most common connection problems.

If you only want to look at the interface, start with **Demo mode** — no host or account is required.

---

## 1. Install the app

### Google Play

The Play package is `com.dsh.unofficial.app`, listed as **DSH Android Unofficial Harness**. The Play
build removes the sideload APK installer permission and the in-app self-updater.

### Sideload preview

Download the latest preview APK from **[GitHub Releases](https://github.com/LordGrimTaken/dsh-mobile/releases)**.
Preview builds are debug-signed, so an in-app update only works when the new build uses the same signing
certificate. If you switch between locally built and published debug APKs, uninstall once and reinstall.

---

## 2. Try demo mode

1. Open the app.
2. On the first-run setup guide, tap **Try demo data**. If you have already dismissed the guide, tap
   **Use demo data - no host or login** on the welcome screen.
3. Explore the sample sessions, approvals, plans and settings.

Demo mode stores only local sample data. It does not contact a host. Pair a real host later from
**Settings → Hosts**.

---

## 3. Install the host plugin

The host plugin lives in [`host-plugin/`](../host-plugin/README.md). It runs inside the DeepSeek Harness
desktop profile.

1. Copy or install `host-plugin/` as a DSH profile bundle.
2. Enable the plugin and restart the DeepSeek Harness desktop app.
3. The plugin listens on `127.0.0.1:3543` only. Do **not** expose that port directly to the internet.
4. Open the host helper to create a pairing code when you are ready to connect the phone.

The host plugin owns pairing codes, device tokens, event fan-out and redaction. It does not expose the
DeepSeek Harness web server or its launch token.

---

## 4. Connect a desktop host

1. Make sure the desktop host is running and the phone can reach it:
   - same Wi-Fi/LAN during development, or
   - a private tailnet such as Tailscale (recommended for remote access).
2. On the desktop, open the DSH Android host helper and choose **Pair a phone**. A six-digit code is
   shown; it expires after five minutes and can be used once.
3. In the app, tap **Pair your desktop host**.
4. Enter:
   - **Host URL** — for example `http://192.168.1.20:3543` on a LAN, or the HTTPS URL from
     Tailscale Serve.
   - **Pairing code** — the six-digit code from the host.
5. Tap **Pair host**. The phone receives a device token, stored encrypted on the phone.
6. The host appears under **Settings → Hosts**. You can add, switch or remove hosts there.

> Never pair over plain HTTP on the public internet. Use HTTPS, a private tailnet, or a LAN you trust.

---

## 5. Connect a cloud host

A cloud host is useful when the computer that runs DeepSeek Harness is not always on.

1. Run DeepSeek Harness with the host plugin on a server you control.
2. Put an HTTPS reverse proxy in front of the host, or use Tailscale Funnel/Serve. Use a real
   certificate; the app needs an `https://` base URL.
3. Create a pairing code from the server's host helper or admin flow.
4. In the app, tap **Connect to a cloud host**.
5. Enter the HTTPS base URL and the six-digit code. Use **Test host URL** if needed, then pair.
6. The host is saved as **Cloud** and behaves like a desktop host.

---

## 6. Connect with Tailscale

Tailscale is optional. It is the simplest way to reach a host outside your LAN without opening a port.

1. Install Tailscale on the phone and on the host, and sign both into the same tailnet.
2. On the host, enable **Tailscale Serve** for the DSH Android port and copy the HTTPS URL.
3. In the app, tap **Sign in with Tailscale**.
4. Paste the Tailscale Serve URL and tap **Sign in**. No six-digit code is needed when the host
   advertises tailnet auto-pairing.
5. If the host does not support auto-pairing, close the dialog and use the pairing-code flow instead.

Detailed walkthrough: [Remote access with Tailscale](remote-access.md).

---

## 7. Recover a host after reinstalling

1. Before clearing app data or uninstalling, open **Settings → Security → Recovery access** and copy
   the recovery link.
2. Reinstall the app and choose **Restore with recovery code** on the welcome screen.
3. Paste the recovery link, or enter the host URL and recovery code.
4. After restoring, a fresh recovery link is shown once. Store it safely.

Recovery links are secrets. Do not share them or commit them to a repository.

---

## 8. Troubleshooting

### The phone cannot reach the host

- Confirm the host helper is running.
- On the same LAN, open `http://<host-lan-ip>:3543/mobile/v1/health` from the phone browser.
- If the host is remote, confirm Tailscale is connected on both devices and that Tailscale Serve is
  running on the host.
- Do not expose the host port directly to the public internet.

### Pairing code rejected or expired

Pairing codes are single-use and expire after five minutes. Create a new code on the host and retry.

### “Host URL rejected”

Use a complete base URL, including `http://` or `https://`. Do not append `/mobile/v1`; the app adds
the API path itself.

### Notifications not arriving

- Enable notification permission for DSH Android.
- For background alerts while the app is closed, turn on **Settings → Notifications → Background
  sync** and allow the foreground-service notification.
- On Android, battery optimization may delay background checks; exclude DSH Android from aggressive
  battery saving if needed.

### See also

- [Network troubleshooting](network-troubleshooting.md)
- [FAQ](faq.md)
- [Security policy](../SECURITY.md)
