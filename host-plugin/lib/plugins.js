/**
 * Read the installed-plugin inventory from the active DSH profile.
 *
 * This deliberately reports what is *installed* in the profile (`dependencies` in
 * `$DSH_HOME/profiles/<profile>/package.json` plus the profile bundle list) — not runtime health,
 * which the desktop owns. It is read-only, bounded and never throws: an unreadable profile returns
 * `supported: false` so the app can show an honest "not available" state.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const PROFILE_NAME = 'web';
const MAX_PACKAGES = 200;

/** Absolute path to the active profile's package.json (override the profile with `profileName`). */
export function profilePackagePath(env = process.env, profileName = PROFILE_NAME) {
  const home = env.DSH_HOME || path.join(os.homedir(), 'dsh-home');
  return path.join(home, 'profiles', profileName, 'package.json');
}

/**
 * @param {Record<string, string | undefined>} [env]
 * @param {string} [profileName]
 * @returns {{ supported: boolean, profile: string, packages: Array<{name: string, version: string, specifier: string, local: boolean}>, bundles: string[], truncated: boolean }}
 */
export function readProfilePlugins(env = process.env, profileName = PROFILE_NAME) {
  const unsupported = { supported: false, profile: profileName, packages: [], bundles: [], truncated: false };
  let raw;
  try {
    raw = fs.readFileSync(profilePackagePath(env, profileName), 'utf8');
  } catch {
    return unsupported;
  }
  let pkg;
  try {
    pkg = JSON.parse(raw);
  } catch {
    return unsupported;
  }
  const dependencies = pkg && typeof pkg.dependencies === 'object' && pkg.dependencies ? pkg.dependencies : {};
  const entries = Object.entries(dependencies).sort(([a], [b]) => a.localeCompare(b));
  const bundles = Array.isArray(pkg?.dsh?.profile?.bundles)
    ? pkg.dsh.profile.bundles.map((bundle) => String(bundle))
    : [];
  const packages = entries.slice(0, MAX_PACKAGES).map(([name, rawSpecifier]) => {
    const specifier = String(rawSpecifier);
    const linked = specifier.startsWith('link:') || specifier.startsWith('file:');
    return {
      name,
      version: linked ? '' : specifier.replace(/^[\^~=]/, ''),
      specifier,
      local: linked || name.startsWith('@local/'),
    };
  });
  return { supported: true, profile: profileName, packages, bundles, truncated: entries.length > MAX_PACKAGES };
}
