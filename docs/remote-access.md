# Remote access: phone → desktop over Tailscale

How to reach the desktop DSH Mobile host from a phone **without opening a LAN
port or the public internet**, using Tailscale Serve for TLS and identity.

> **Status: preview.** The Android client now has real networking: it
> pairs with a host over the mobile API and follows sessions over SSE (including
> approvals and questions). The repo also ships the host plugin
> ([`host-plugin/`](../host-plugin/README.md), preview), but it is **not installed
> into the desktop DSH profile by default** — activating it is a separate step,
> and the desktop app must be restarted afterwards.
>
> Tailscale becomes relevant **once the host plugin is installed** and you want
> the phone to reach it from outside the LAN. Until then, point the app at the
> development fake host over the LAN — emulator `http://10.0.2.2:3543`, phone
> `http://<pc-lan-ip>:3543` — or rehearse this entire guide against the fake host.

## How it fits together

```text
┌─────────────┐   HTTPS 443        ┌──────────────────┐   HTTP 127.0.0.1:3543   ┌─────────────────┐
│ Android app │ ─────────────────▶ │  Tailscale Serve │ ──────────────────────▶ │ DSH Mobile host │
│ (Tailscale) │  <pc>.<tailnet>    │  (TLS + proxy)   │   loopback only         │ plugin          │
│             │     .ts.net        │  on the PC       │                         │                 │
└─────────────┘                    └──────────────────┘                         └─────────────────┘
```

- The host plugin binds **`127.0.0.1:3543` only** (contract requirement). It has
  its own device-token auth; it never exposes the DSH web server or launch token.
- Tailscale Serve terminates TLS with a certificate for
  `https://<pc>.<tailnet>.ts.net` and proxies to `http://127.0.0.1:3543`.
- The base URL the phone stores is `https://<pc>.<tailnet>.ts.net` (no port).

## 1. Install Tailscale on Windows (the PC that runs the host plugin)

1. Download and install from <https://tailscale.com/download/windows>.
2. Sign in with the Tailscale account that owns your tailnet. Make sure
   **Run unattended / Start Tailscale on login** is enabled so the host is
   reachable after a reboot.
3. Open a **new** PowerShell window (the installer adds `tailscale` to `PATH`)
   and verify:

   ```powershell
   tailscale version
   tailscale status
   tailscale ip -4
   ```

   `tailscale status` lists the machine's MagicDNS name
   (`<pc>.<tailnet>.ts.net`) and the other devices in the tailnet.

## 2. Install Tailscale on the Android phone

1. Install **Tailscale** from the Play Store (or F-Droid) on the phone.
2. Sign in with the **same Tailscale account/identity**, then enable the VPN
   toggle so the app shows **Connected**.
3. Confirm the PC appears in the phone's device list. Exclude Tailscale from
   Android battery optimization if the connection drops while the app is in the
   background.
4. Back on the PC, `tailscale status` should now list the phone as a peer.

## 3. Enable MagicDNS and HTTPS certificates (tailnet settings)

In the Tailscale admin console:

1. **MagicDNS** — <https://login.tailscale.com/admin/dns> → enable **MagicDNS**.
   This is what makes `https://<pc>.<tailnet>.ts.net` resolvable on the phone.
2. **HTTPS Certificates** — same DNS page → enable **HTTPS Certificates**.
   Tailscale Serve needs this to obtain a publicly-trusted `*.ts.net`
   certificate for the PC's name.
   - Optional sanity check on the PC:
     `tailscale cert <pc>.<tailnet>.ts.net` (it caches the certificate locally).

Both settings are tailnet-wide; if the console buttons are greyed out, you need
one of the tailnet owners/admins.

## 4. Run the host plugin on loopback

The host plugin ships in this repo at [`host-plugin/`](../host-plugin/README.md)
(preview), but it is **not installed into the desktop DSH profile by default**.
Install it with `plugin_manager` `install_bundle` rather than copying files (see
the plugin README's "Install later" section), then restart the desktop app so
the `mobile-api` row is loaded. Once running, it must listen on
**`127.0.0.1:3543`** and nothing else. Verify locally:

```powershell
curl.exe -s http://127.0.0.1:3543/mobile/v1/health
# {"ok":true,"version":"...","apiVersion":1,"paired":false}
```

### Rehearse the setup with the fake host

The fake host implements the same contract without installing the plugin, and it
is the quickest way to exercise the whole phone → host path. For a **LAN
rehearsal** (the preview app has real networking), leave the fake host on
its default `0.0.0.0` bind and point the app at `http://<pc-lan-ip>:3543`
(emulator: `http://10.0.2.2:3543`). For a **Tailscale rehearsal**, bind it
loopback-only so it matches the real plugin:

```powershell
$node = 'C:\.dsh\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
$env:FAKE_BIND = '127.0.0.1'
& $node tools\fake-host\server.mjs
```

Then continue with the `tailscale serve` step below. The fake host answers on
`https://<pc>.<tailnet>.ts.net/mobile/v1/health` with
`"paired":false` until you pair; its dev token is `dev-token` and its code is
`482913` (see the [fake host README](../tools/fake-host/README.md)).

## 5. Expose the port with Tailscale Serve

On the PC, in PowerShell:

```powershell
tailscale serve --bg 3543
tailscale serve status
```

- `--bg` runs Serve in the background (persists across terminal closes; remove
  with `tailscale serve --bg off`, or `tailscale serve reset` to clear all
  Serve config).
- `tailscale serve status` prints the URL, e.g.
  `https://pc.tailnet-abc123.ts.net` → `http://127.0.0.1:3543`, and marks it
  **tailnet only**.
- The first request may take a few seconds while the certificate is provisioned.

**Use `serve`, never `funnel`.** `tailscale funnel` publishes the host to the
public internet; Serve keeps it inside your tailnet.

### Verify end to end

From the **phone's browser** (Tailscale connected), open:

```text
https://<pc>.<tailnet>.ts.net/mobile/v1/health
```

You should see the health JSON (it is the only contract endpoint besides
`pair/complete` that does not require a bearer token). If that works, the phone
can reach the host.

## 6. Pair the phone

The pairing endpoint is **loopback-only on the real host** (anti-abuse: only the
desktop may create a pairing code). Run the helper **on the PC**, not the phone:

```powershell
curl.exe -s -X POST http://127.0.0.1:3543/mobile/v1/pair/new
```

The host also rejects pair requests that look proxied — anything carrying
`X-Forwarded-For`, `X-Forwarded-Host`, `Forwarded`, or a `Tailscale-User-*` /
`Tailscale-Funnel-*` header — with `403 forbidden`. That check matters because a
request arriving through Tailscale Serve appears to originate from `127.0.0.1`;
the proxy headers are what distinguish it from a genuine desktop call. So never
call `/pair/new` through the `ts.net` URL or any other proxy. (The development
fake host intentionally skips both checks so it can pair from the emulator.)

`{"code":"482913","expiresAt":"...","qrPayload":"dshmobile://pair?base=..."}`

1. Read `code`, open **Pair device** in the app, and enter the host URL
   `https://<pc>.<tailnet>.ts.net`, the 6-digit code, and the device name. v0.2
   has manual pairing only; the QR scanner is planned for v0.3.
2. The app calls `POST /mobile/v1/pair/complete` and persists
   `{baseUrl, token, deviceId}` in app-private storage. You can unpair in the
   app's settings and fall back to demo data at any time.
3. The code is valid for **5 minutes and one use**; re-run the `pair/new`
   command if it expires. The `qrPayload` already contains the base URL and host
   name for the v0.3 QR scanner, so it can be turned into a QR code without
   changing the pairing flow.

For the fake host, create the code with the same helper command (its `/pair/new`
deliberately accepts any peer); the code is `482913` and the token is
`dev-token` (see the [fake host README](../tools/fake-host/README.md)).

## 7. Security notes

- **Loopback only.** The host plugin must bind `127.0.0.1`, never `0.0.0.0` or a
  LAN address. Do not work around this: Tailscale Serve is the only intended
  ingress.
- **Serve, not Funnel.** Funnel puts the host on the public internet, where the
  dev token and pairing endpoint are exposed to the world. Never leave Funnel
  enabled for port 3543.
- **Pairing is desktop-only.** `/pair/new` must be called from the PC on
  `127.0.0.1`, and the real host rejects proxied requests (proxy/identity
  headers → `403`) precisely so a Tailscale-reachable caller cannot mint pairing
  codes. Never put a reverse proxy in front of that endpoint.
- **The device token is a credential.** Treat it like a password: it grants full
  API access to every workspace/session the host exposes. Don't paste it into
  chat, screenshots, commit messages, or `logcat` dumps. The v0.2 client keeps it
  in app-private SharedPreferences (cleared on unpair, no backup/export path);
  Keystore/EncryptedSharedPreferences hardening is planned.
- **Tailnet trust.** By default every device in a tailnet can reach every other
  device. If the tailnet is shared, add ACLs that only let your phone reach the
  PC on port 443, and/or use device approval / tagged devices in the admin
  console.
- **Revoke access** by removing the device in the admin console
  (<https://login.tailscale.com/admin/machines> → the device → **Remove**).
  Keep key expiry enabled; a removed/expired device loses network access even if
  it still has a token. Token/device management UI on the host is deferred in
  v0.2, so re-pairing is the current rotation mechanism.
- **Only expose the mobile host port.** Do not `tailscale serve` the DSH web
  server (57593) or Gradle/emulator ports.
- **HTTPS everywhere.** Keep HTTPS Certificates enabled; don't bypass Serve with
  a plain-HTTP LAN bind "just for testing" on a phone you carry around.

## 8. Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| `tailscale serve` says HTTPS/cert is unavailable | Enable **HTTPS Certificates** in the admin console DNS page; wait a minute and retry. `tailscale cert <pc>.<tailnet>.ts.net` shows the actual error. |
| Phone browser shows a certificate warning | Use the exact MagicDNS name (`<pc>.<tailnet>.ts.net`), not the Tailscale IP or a LAN IP — the `*.ts.net` certificate is only valid for that name. Check the URL from `tailscale serve status`. |
| Phone cannot resolve `<pc>.<tailnet>.ts.net` | MagicDNS is disabled (enable it in admin console), the phone's Tailscale app is disconnected, or Android Private DNS is set to a strict resolver — set Private DNS to **Automatic** temporarily. Toggling the phone's VPN off/on re-applies MagicDNS. |
| `502`/`504` from Serve, or "connection refused" | The host plugin is not running or is not on `127.0.0.1:3543`: check `curl.exe -s http://127.0.0.1:3543/mobile/v1/health` and `Get-NetTCPConnection -LocalPort 3543 -State Listen`. Restart the plugin, then `tailscale serve status`. |
| Phone times out, no response at all | Phone and PC are on different tailnets/accounts, Tailscale on the phone is off/expired, or the PC is asleep/offline. Run `tailscale status` on both; from the phone's Tailscale app, test with `tailscale ping <pc>`. |
| `401 unauthorized` from the API | Missing/wrong device token (the base URL may be right but the app is using a stale token). Re-pair; for the fake host the token is `dev-token`. |
| Pairing code rejected (`invalid_code`) | The 5-minute window expired or the code was already used — run the `pair/new` helper again on the PC. Repeated failures are rate limited (5/min per IP). |
| `pair/new` returns `403 forbidden` | You reached it through Tailscale Serve or another proxy (proxy headers present). Run the helper directly on the PC against `http://127.0.0.1:3543`; never call `/pair/new` through the `ts.net` URL. |
| Port 3543 already in use | Another process (e.g. a stale fake host or the host plugin) owns it: `Get-NetTCPConnection -LocalPort 3543 -State Listen`, then stop that process. |
| Windows Firewall prompt when starting the host | For a loopback bind + Serve, inbound firewall rules are **not** needed. If you deliberately use the fake host's `0.0.0.0` bind for same-Wi-Fi phone testing, allow Node.js on **Private** networks, or add: `New-NetFirewallRule -DisplayName "DSH mobile host 3543" -Direction Inbound -Protocol TCP -LocalPort 3543 -Action Allow -Profile Private`. |
| Emulator can't use the Tailscale URL | The AVD cannot resolve MagicDNS names. Keep emulator testing on `http://10.0.2.2:3543` and use the Tailscale URL for the physical phone. |
| SSE works locally but not through Serve | Confirm with `curl.exe -N https://<pc>.<tailnet>.ts.net/mobile/v1/events` with a bearer token on the PC; Serve proxies streaming responses, so failures usually mean the client buffers. Check the phone app isn't missing deltas because of its own buffering. |

## Quick checklist

- [ ] Tailscale installed and signed into the same tailnet on PC + phone.
- [ ] Admin console: MagicDNS **on**, HTTPS Certificates **on**.
- [ ] Host plugin (or fake host with `FAKE_BIND=127.0.0.1`) listening on
      `127.0.0.1:3543`; local `/mobile/v1/health` responds.
- [ ] `tailscale serve --bg 3543`; `tailscale serve status` shows the
      `https://<pc>.<tailnet>.ts.net` URL as tailnet-only.
- [ ] Phone browser can open that URL + `/mobile/v1/health`.
- [ ] Pair with a loopback `POST /mobile/v1/pair/new` code.
- [ ] HTTP, not Funnel; token treated as a credential; device revocation known.
