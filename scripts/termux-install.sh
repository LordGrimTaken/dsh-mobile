#!/data/data/com.termux/files/usr/bin/bash
# DSH Mobile — on-phone harness installer (Termux), local bundle version.
#
# This script is shipped inside the DSH Mobile APK and exported to Downloads, so it never needs a
# GitHub checkout. Prerequisite: Termux (Play build), `pkg install -y nodejs unzip`, and
# `termux-setup-storage` run once. The only network use is npm installing @deepseek-ai/dsh.
#
# Usage:
#   cd ~/storage/downloads && unzip -o dsh-mobile-host.zip -d dsh-mobile-host
#   cd dsh-mobile-host && bash install.sh

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PLUGIN="$HERE/plugin"
DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
PROFILE="web"
PROFILE_PKG="$DSH_HOME/profiles/$PROFILE/package.json"

say() { printf '\033[38;5;79m❯ %s\033[0m\n' "$*"; }

[ -f "$PLUGIN/package.json" ] || { say "Bundle incomplete: plugin/ missing next to install.sh"; exit 1; }
command -v node >/dev/null 2>&1 || { say "Node missing — run: pkg install -y nodejs unzip"; exit 1; }
command -v zcat >/dev/null 2>&1 || true

if ! command -v dsh >/dev/null 2>&1; then
  say "Installing the DeepSeek Harness from npm (the only network step)…"
  npm install -g @deepseek-ai/dsh
fi

say "Registering dsh-mobile-host from this bundle…"
mkdir -p "$DSH_HOME/profiles/$PROFILE"
dsh plugin add "link:$PLUGIN" >/dev/null 2>&1 || say "note: 'dsh plugin add' failed; writing the bundle entry directly"

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

say "Locking wake state so Android keeps the host alive…"
termux-wake-lock 2>/dev/null || true

cat <<'TXT'

────────────────────────────────────────────────────────────
The harness starts now. In DSH Mobile:

  Settings → Hosts → On this phone → Detect host → Pair this host

  • Mobile host listens on http://127.0.0.1:3543
  • Pairing page: http://127.0.0.1:3543/pair (open in the phone browser)
  • First harness start asks for your provider API key
  • Keep this Termux session running (tmux helps) for long agent turns
────────────────────────────────────────────────────────────

TXT

exec dsh web --no-open
