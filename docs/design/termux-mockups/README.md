# “On this phone” Termux host — mockups

How the phone-as-harness flow would look in DSH Mobile. The app does **not** embed the harness;
the real harness runs in Termux over loopback (`127.0.0.1:3543`) and the app connects to it.

| Screen | File |
| --- | --- |
| Hosts list with the new “On this phone → Termux host” card | `hosts.html` / `png/hosts.png` |
| Setup guide (install Termux, Node 24, harness + mobile host plugin) | `setup.html` / `png/setup.png` |
| Loopback detection + pairing (QR / six-digit code) | `detect.html` / `png/detect.png` |
| Connected host detail (node, uptime, battery, port, actions) | `host-detail.html` / `png/host-detail.png` |
| Home running from the phone host | `home-termux.html` / `png/home-termux.png` |

Render (same pipeline as the other mockups):

```bash
/var/lib/dsh/bin/chromium-headless --force-device-scale-factor=2 --window-size=390,844 \
  --hide-scrollbars --virtual-time-budget=4000 \
  --screenshot=png/<name>.png "file://$PWD/<name>.html"
```

Notes:
- Commands in `setup.html` are illustrative for the mock; the real implementation would ship a
  tested one-shot installer script (`termux-host.sh`) for the exact `dsh`/`dsh plugin` syntax.
- No host-service changes are needed: the existing mobile host plugin binds loopback already; the
  app needs a localhost host card, detection, pairing and setup guidance.
