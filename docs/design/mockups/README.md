# UI refresh mockups (proposed, 2026-11-09)

Static design mockups for the DSH Mobile UI refresh + connectivity plan. They are **proposals**, not
app code — the app still looks like the screenshots in `docs/screenshots/`.

| File | Screen |
|---|---|
| `png/settings-home.png` | Searchable Settings with live host/route health hero + grouped sections |
| `png/settings-connection.png` | Connection & sync: routes, failover, buffering toggles, transport, speed test |
| `png/settings-appearance.png` | Theme gallery, text/density, motion, haptics |
| `png/home-sessions.png` | Session list refresh: latency strip, filter chips, skeletons, bottom nav |
| `png/chat-buffering.png` | Chat streaming/buffering states and failover messaging |
| `png/inbox-actions.png` | Notification center with approve/deny, inline answers, mute |

Each HTML source links the shared `mock.css`; render with:

```bash
/var/lib/dsh/bin/chromium-headless --force-device-scale-factor=2 --window-size=390,844 \
  --hide-scrollbars --virtual-time-budget=4000 \
  --screenshot=png/<name>.png "file://$PWD/<name>.html"
```

Full reasoning, connectivity plan and targets: [`../ui-refresh-proposal.md`](../ui-refresh-proposal.md).
