# DSH Android over Tailscale — connection troubleshooting

The mobile app talks to the DSH host plugin over the Tailscale interface. When the phone is
locked, switches Wi-Fi/cellular, or Tailscale restarts its tunnel, the old TCP/SSE connection can
die silently. The app now recovers automatically, but Android and Tailscale still need to be allowed
to keep the VPN alive in the background.

## Recommended phone settings

1. **Tailscale → Always-on VPN**
   Android Settings → Network & internet → VPN → gear next to Tailscale → enable **Always-on VPN**
   (and **Block connections without VPN** only if you understand the trade-off). This makes Android
   re-establish the tunnel after a network switch instead of leaving the app offline.
2. **Unrestricted battery for Tailscale and DSH Android**
   Settings → Apps → Tailscale → App battery usage → **Unrestricted**.
   Settings → Apps → DSH Android → App battery usage → **Unrestricted**.
   Also disable “Pause app activity if unused” for DSH Android.
3. **Allow background data** for both apps (Mobile data / Wi-Fi data usage is not restricted).
4. Keep the Tailscale status notification visible; swiping away the VPN notification can let Android
   stop the tunnel.
5. If a power-saving/OEM “app cleaner” exists (Samsung, Xiaomi, etc.), exclude both apps from it.

## Recommended host settings

- In the Tailscale admin console, keep the PC online and set **key expiry** appropriately for the
  host; an expired node key looks like a dead network from the phone.
- Serve the plugin over Tailscale HTTPS:
  ```powershell
  tailscale serve --bg 3543
  tailscale serve status
  ```
  Pair the phone with the `https://<machine>.<tailnet>.ts.net` base URL. The host plugin itself stays
  bound to `127.0.0.1:3543`; Tailscale Serve terminates HTTPS and proxies to it.
- Prevent the PC from sleeping while you rely on mobile access (power plan / “keep awake when
  plugged in”), or the tunnel will go nowhere even when Tailscale is connected.
- Avoid changing the host’s `tailscale serve` port while the phone is paired; re-pair or update the
  host URL in Settings → Hosts if the URL changes.

## What the app does now

- On returning to the foreground after a few seconds, the app re-checks the host and restarts the
  event stream instead of showing a dead “Reconnect” state.
- When Android reports a new network — including the VPN interface coming back — the app debounces
  briefly and reconnects automatically.
- The SSE stream is re-established with the last event cursor (`/events?since=`), so missed events
  are replayed; snapshots and loaded transcripts are refreshed as a safety net.
- A watchdog reconnects a stream that has gone silent for too long, so a dead Tailscale route is
  detected within roughly a minute instead of hanging until you tap Reconnect.

## If it still fails

1. Open **Settings → Diagnostics → Connection diagnostics** and use **Run health check**.
   - `healthy` means the phone can reach the host; the problem is the SSE/network path.
   - A timeout/DNS error means Tailscale or the host is unreachable.
2. Check `tailscale status` and `tailscale ping <host>` from another Tailscale device.
3. Toggle Tailscale off/on **once**, then reopen DSH Android; the app should reconnect on resume.
   Prefer the in-app Reconnect button before rebooting the whole VPN.
4. Check that the phone’s host URL still matches the current `tailscale serve status` URL.
5. If only the app is stuck while other Tailscale traffic works, use Connection diagnostics →
   **Copy diagnostics** when reporting the issue.

This doc reflects app build 0.2.39+; older builds only had the manual Reconnect path.


## Multiple routes and automatic failover (0.4.0)

A host can be reachable through more than one address — for example a LAN URL at home, Tailscale
Serve elsewhere, and a named Cloudflare tunnel for locked-down networks. Add them under
**Settings → Hosts → Routes → Add route**; they all share the same device token, so no second
pairing code is needed.

- **Probe all** checks each route's `/health` in parallel and shows the latency (4 s budget per
  route).
- **Use fastest** switches the active route to the quickest reachable one and rebuilds the live
  connection without dropping the paired session.
- When the connection drops, the app probes the routes automatically and reconnects through the
  fastest reachable one instead of retrying the dead URL. The same happens for a manual
  **Reconnect**; automatic failover is rate-limited to once per minute.
- **Connection diagnostics → Routes** shows the last probe result for every route.

Limits worth knowing: a route is only as good as its answer to `/health` (reachable but wrong host
still ranks), route order breaks latency ties (reorders are not editable yet), and the client
cannot bind a request to a specific network interface — Android decides which network carries
each request.
