/**
 * "Connect a phone" pairing page served by the Mobile API on loopback.
 *
 * The page asks the same server for a fresh one-use pairing code (`POST /mobile/v1/pair/new`),
 * renders the returned `qrPayload` as a QR code in the browser, and shows the 6-digit fallback
 * code. Serving it from the host plugin's loopback port means:
 *   - `pair/new` is called by the genuine desktop browser (loopback peer, no proxy headers), so
 *     the existing anti-proxy guard on the endpoint keeps its meaning, and
 *   - no secret ever reaches the HTML: the page itself is static; the code is minted per load.
 *
 * QR rendering uses a vendored copy of the MIT-licensed `qrcode-generator` library
 * (Kazuhiko Arase, https://github.com/kazuhikoarase/qrcode-generator); see NOTICE.md.
 * If that file is missing the page degrades to a copyable pairing link instead of failing.
 */

import { readFileSync } from 'node:fs';

const QR_LIBRARY_FILE = new URL('./vendor/qrcode-generator.js', import.meta.url);

/** Content-Security-Policy for the pairing page: own scripts only, no remote loads. */
export const PAIR_PAGE_CSP =
  "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; " +
  "img-src 'self' data:; base-uri 'none'; form-action 'none'";

/** Headers shared by the page and its scripts. */
export function pairPageHeaders() {
  return {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Security-Policy': PAIR_PAGE_CSP,
    'Referrer-Policy': 'no-referrer',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
  };
}

/** @type {string} */
let cachedQrLibrary = '';

/**
 * Source of the vendored QR encoder, or `''` when the vendor file is missing.
 * Read once and cached; a missing file must not take the plugin down.
 * @returns {string}
 */
export function qrLibrarySource() {
  if (cachedQrLibrary !== '') return cachedQrLibrary;
  try {
    cachedQrLibrary = readFileSync(QR_LIBRARY_FILE, 'utf8');
  } catch {
    cachedQrLibrary = '';
  }
  return cachedQrLibrary;
}

/** Full standalone HTML page. No inline script: `script-src 'self'` stays valid. */
export const PAIR_PAGE_HTML = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Connect a phone · DSH Mobile</title>
<style>
  :root {
    color-scheme: dark;
    --ink: #050a08;
    --layer: #0a1510;
    --layer-2: #10221a;
    --border: rgba(120, 229, 170, 0.20);
    --border-strong: rgba(120, 229, 170, 0.40);
    --text: #dff5e8;
    --text-2: #a3cbb6;
    --text-3: #78a58f;
    --mint: #4ee59b;
    --red: #ff6b6b;
    --mono: ui-monospace, "SF Mono", "Cascadia Mono", "JetBrains Mono", Menlo, Consolas, monospace;
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; }
  body {
    margin: 0;
    min-height: 100%;
    display: grid;
    place-items: center;
    padding: 32px 16px;
    background:
      radial-gradient(1100px 600px at 50% -10%, rgba(78, 229, 155, 0.10), transparent 62%),
      var(--ink);
    color: var(--text);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    -webkit-font-smoothing: antialiased;
  }
  .card {
    width: 100%;
    max-width: 440px;
    padding: 28px 26px 22px;
    border: 1px solid var(--border);
    border-radius: 22px;
    background: linear-gradient(180deg, var(--layer-2), var(--layer));
    box-shadow: 0 24px 70px rgba(0, 0, 0, 0.45);
    text-align: center;
  }
  .brand {
    font-family: var(--mono);
    font-size: 13px;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    color: var(--mint);
  }
  .brand .dim { color: var(--text-3); letter-spacing: 0.06em; }
  h1 {
    margin: 14px 0 6px;
    font-size: 23px;
    font-weight: 650;
    letter-spacing: -0.01em;
  }
  .sub {
    margin: 0 auto 20px;
    max-width: 340px;
    font-size: 13.5px;
    line-height: 1.5;
    color: var(--text-2);
  }
  .qr {
    width: 240px;
    height: 240px;
    margin: 0 auto;
    display: grid;
    place-items: center;
    border-radius: 18px;
    background: #ffffff;
    padding: 10px;
    box-shadow: 0 0 0 1px var(--border-strong), 0 0 42px rgba(78, 229, 155, 0.16);
  }
  .qr svg { width: 100%; height: 100%; display: block; }
  .qr .placeholder { font-family: var(--mono); font-size: 12px; color: #5f8a75; }
  .code-row {
    margin-top: 18px;
    display: flex;
    align-items: baseline;
    justify-content: center;
    gap: 14px;
  }
  .code {
    font-family: var(--mono);
    font-size: 30px;
    font-weight: 650;
    letter-spacing: 0.34em;
    color: var(--mint);
    text-indent: 0.34em;
  }
  .countdown { font-family: var(--mono); font-size: 12.5px; color: var(--text-3); }
  .base {
    margin-top: 8px;
    font-family: var(--mono);
    font-size: 12px;
    line-height: 1.5;
    color: var(--text-2);
    word-break: break-all;
  }
  .status {
    min-height: 20px;
    margin: 14px 0 0;
    font-size: 12.5px;
    color: var(--text-3);
  }
  .status.error { color: var(--red); }
  .actions { margin-top: 16px; display: flex; justify-content: center; gap: 10px; }
  button {
    appearance: none;
    cursor: pointer;
    border: 1px solid transparent;
    border-radius: 12px;
    padding: 10px 18px;
    font: inherit;
    font-size: 13.5px;
    font-weight: 600;
    color: var(--ink);
    background: var(--mint);
  }
  button.ghost {
    color: var(--text-2);
    background: transparent;
    border-color: var(--border);
  }
  button:disabled { opacity: 0.5; cursor: default; }
  .payload {
    margin: 12px 0 0;
    padding: 10px 12px;
    border: 1px solid var(--border);
    border-radius: 12px;
    font-family: var(--mono);
    font-size: 11px;
    line-height: 1.5;
    color: var(--text-2);
    text-align: left;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
  .payload[hidden] { display: none; }
  .hint {
    margin: 18px 0 0;
    font-size: 11.5px;
    line-height: 1.55;
    color: var(--text-3);
  }
  @media (max-width: 420px) {
    .qr { width: 200px; height: 200px; }
    .code { font-size: 25px; }
  }
</style>
</head>
<body>
<main class="card">
  <div class="brand">❯ dsh <span class="dim">/ mobile</span></div>
  <h1>Connect a phone</h1>
  <p class="sub">Scan this code with the DSH Mobile Android app. It carries the host URL and a one-use pairing code.</p>
  <div id="qr" class="qr" aria-label="Pairing QR code">
    <span class="placeholder">creating code…</span>
  </div>
  <div class="code-row">
    <div class="code" id="code">------</div>
    <div class="countdown" id="countdown">…</div>
  </div>
  <div class="base" id="base"></div>
  <p class="status" id="status">Creating a pairing code…</p>
  <div class="actions">
    <button id="refresh" type="button">New code</button>
    <button id="copy" type="button" class="ghost" disabled>Copy link</button>
  </div>
  <pre id="payload" class="payload" hidden></pre>
  <p class="hint">Codes are single use and expire after 5 minutes. Open this page on the host itself — pairing requests that arrive through a reverse proxy are refused on purpose.</p>
</main>
<script src="/pair/qrcode-generator.js"></script>
<script src="/pair/app.js"></script>
</body>
</html>
`;

/** Inline page controller (served as `/pair/app.js` so the CSP can stay strict). */
export const PAIR_APP_JS = `(function () {
  'use strict';

  var qrEl = document.getElementById('qr');
  var codeEl = document.getElementById('code');
  var baseEl = document.getElementById('base');
  var statusEl = document.getElementById('status');
  var countdownEl = document.getElementById('countdown');
  var payloadEl = document.getElementById('payload');
  var refreshBtn = document.getElementById('refresh');
  var copyBtn = document.getElementById('copy');

  var payload = '';
  var expiresAt = 0;
  var timer = null;
  var busy = false;

  function setStatus(text, isError) {
    statusEl.textContent = text;
    statusEl.className = isError ? 'status error' : 'status';
  }

  function formatLeft(ms) {
    if (ms <= 0) return 'expired';
    var seconds = Math.ceil(ms / 1000);
    var minutes = Math.floor(seconds / 60);
    var rest = seconds % 60;
    return 'expires in ' + minutes + ':' + (rest < 10 ? '0' : '') + rest;
  }

  function tick() {
    var left = expiresAt - Date.now();
    countdownEl.textContent = formatLeft(left);
    if (left <= 0) {
      if (timer) { clearInterval(timer); timer = null; }
      setStatus('That code expired. Tap “New code” to show a fresh one.', true);
      refreshBtn.disabled = false;
    }
  }

  function renderQr(text) {
    qrEl.innerHTML = '';
    if (typeof qrcode !== 'function') {
      var note = document.createElement('span');
      note.className = 'placeholder';
      note.textContent = 'QR renderer unavailable — copy the link below';
      qrEl.appendChild(note);
      payloadEl.hidden = false;
      payloadEl.textContent = text;
      return;
    }
    var qr = qrcode(0, 'M');
    qr.addData(text);
    qr.make();
    qrEl.innerHTML = qr.createSvgTag(6, 4);
    var svg = qrEl.querySelector('svg');
    if (svg) {
      svg.setAttribute('role', 'img');
      svg.setAttribute('aria-label', 'Pairing QR code');
      svg.removeAttribute('width');
      svg.removeAttribute('height');
    }
  }

  function render(data) {
    payload = data.qrPayload || '';
    codeEl.textContent = data.code || '------';
    var base = '';
    try {
      base = new URL(payload).searchParams.get('base') || '';
    } catch (error) {
      base = '';
    }
    baseEl.textContent = base
      ? 'host: ' + base
      : 'host address missing — set publicBaseUrl on the host so the QR is scannable';
    renderQr(payload);
    // The raw link stays hidden until the QR renderer is missing or copying fails.
    payloadEl.hidden = true;
    payloadEl.textContent = payload;
    copyBtn.disabled = !payload;

    expiresAt = Date.parse(data.expiresAt || '') || 0;
    if (timer) clearInterval(timer);
    timer = setInterval(tick, 1000);
    tick();
    setStatus('Ready — scan the code, or type the 6-digit code in the app.', false);
  }

  function refresh() {
    if (busy) return;
    busy = true;
    refreshBtn.disabled = true;
    copyBtn.disabled = true;
    setStatus('Creating a pairing code…', false);
    fetch('/mobile/v1/pair/new', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
      cache: 'no-store'
    })
      .then(function (response) {
        if (response.ok) return response.json();
        if (response.status === 403) {
          throw new Error('Refused: open this page on the host itself (http://127.0.0.1:3543/pair).');
        }
        throw new Error('The host refused to create a code (HTTP ' + response.status + ').');
      })
      .then(function (data) {
        render(data);
        busy = false;
        refreshBtn.disabled = false;
      })
      .catch(function (error) {
        busy = false;
        refreshBtn.disabled = false;
        codeEl.textContent = '------';
        qrEl.innerHTML = '<span class="placeholder">no active code</span>';
        baseEl.textContent = '';
        countdownEl.textContent = '';
        setStatus(error.message || 'Could not create a pairing code.', true);
      });
  }

  copyBtn.addEventListener('click', function () {
    if (!payload) return;
    var done = function () { setStatus('Pairing link copied.', false); };
    var fail = function () { setStatus('Copy failed — select the link below manually.', true); payloadEl.hidden = false; };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(payload).then(done, fail);
    } else {
      fail();
    }
  });

  refreshBtn.addEventListener('click', refresh);
  refresh();
})();
`;
