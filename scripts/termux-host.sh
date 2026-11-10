#!/data/data/com.termux/files/usr/bin/bash
# DSH Mobile — on-phone harness installer (Termux).
#
# Runs the DeepSeek Harness locally on an Android phone and registers the DSH Mobile host plugin
# so the app can connect over loopback (http://127.0.0.1:3543). No root required.
#
# Usage (inside Termux):
#   pkg update -y && pkg install -y nodejs git
#   git clone https://github.com/LordGrimTaken/dsh-mobile
#   cd dsh-mobile && bash scripts/termux-host.sh
#
# Preview script: run it once and read the output. It is intentionally conservative: it only
# installs packages, registers the plugin bundle and then starts `dsh web --no-open` in the
# foreground (Ctrl-C stops the host; sessions live on this phone).

set -euo pipefail

export DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
REPO="${DSH_MOBILE_REPO:-$HOME/dsh-mobile}"
PROFILE="web"
PROFILE_PKG="$DSH_HOME/profiles/$PROFILE/package.json"

say() { printf '\033[38;5;79m❯ %s\033[0m\n' "$*"; }

command -v node >/dev/null 2>&1 || { say "Node missing — run: pkg install -y nodejs git"; exit 1; }
command -v git  >/dev/null 2>&1 || { say "git missing — run: pkg install -y nodejs git"; exit 1; }

if ! command -v dsh >/dev/null 2>&1; then
  say "Installing the DeepSeek Harness (npm -g @deepseek-ai/dsh)…"
  npm install -g @deepseek-ai/dsh
fi

if [ ! -d "$REPO/.git" ]; then
  if [ -d "$REPO" ]; then
    say "$REPO exists but is not a git checkout — leaving it alone (set DSH_MOBILE_REPO to override)"; exit 1
  fi
  say "Cloning dsh-mobile into $REPO…"
  git clone https://github.com/LordGrimTaken/dsh-mobile "$REPO"
fi

say "Registering dsh-mobile-host in the '$PROFILE' profile…"
mkdir -p "$DSH_HOME/profiles/$PROFILE"
( cd "$REPO/host-plugin" && npm install --omit=dev --no-audit --no-fund >/dev/null 2>&1 || true )
dsh plugin add "link:$REPO/host-plugin" >/dev/null 2>&1 || say "warning: 'dsh plugin add' failed; adding bundle entry directly"

node - "$PROFILE_PKG" <<'NODE'
const fs = require('fs');
const file = process.argv[2];
const seed = { name: 'dsh-profile-web', private: true, dependencies: {}, dsh: { profile: { bundles: [] } } };
const pkg = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : seed;
pkg.dsh = pkg.dsh || {};
pkg.dsh.profile = pkg.dsh.profile || {};
const bundles = (pkg.dsh.profile.bundles = pkg.dsh.profile.bundles || []);
if (!bundles.includes('dsh-mobile-host')) bundles.push('dsh-mobile-host');
fs.writeFileSync(file, JSON.stringify(pkg, null, 2) + '\n');
NODE

say "Locking the wake state so Android keeps the host alive…"
termux-wake-lock 2>/dev/null || true

cat <<'TXT'

────────────────────────────────────────────────────────────
The harness starts now. In another Termux session or the app:

  • The mobile host listens on http://127.0.0.1:3543
  • Pairing page: open http://127.0.0.1:3543/pair in the phone browser
  • First harness start also asks for your provider API key

In DSH Mobile: Settings → Hosts → "On this phone" → Detect host → Pair this host.
Keep this Termux session running (or use tmux) for long agent turns.
────────────────────────────────────────────────────────────

TXT

exec dsh web --no-open
