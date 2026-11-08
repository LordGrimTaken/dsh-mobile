# Third-party notices and inspiration

DSH Android is an independent Android client for a local DeepSeek Harness host. It does not vendor,
link, or copy source code from the projects below; their features informed product ideas and
behaviour only.

- **ccch1mneyyy/dsh-Go** (AGPL-3.0) — inspired the adaptive tablet/landscape two-pane layout,
  accent colour packs, and share-message-as-image card. No dsh-Go source code is included; this
  repository is not a derivative work of that codebase.
- **sorsama/deepseek-harness-mobile** (MIT) — inspired the read-only workspace file browser,
  trajectory journal, background jobs view, context-pressure meter, team/todo projections, and
  share-card interaction. No source code is copied; where the MIT licence would permit reuse, the
  implementation here is independent and talks to this repository's own Mobile API.

This project is released under the MIT licence; see [`LICENSE`](LICENSE). All third-party names and
trademarks belong to their respective owners.

## Vendored components

- **qrcode-generator** (MIT, © 2009 Kazuhiko Arase) — a single-file QR code encoder
  (<https://github.com/kazuhikoarase/qrcode-generator>) vendored at
  [`host-plugin/lib/vendor/qrcode-generator.js`](host-plugin/lib/vendor/qrcode-generator.js)
  so the host's "Connect a phone" page can render a pairing QR without loading
  anything from a CDN or phoning home. The upstream MIT licence header is kept in
  the file; no other third-party source code is vendored in this repository.
