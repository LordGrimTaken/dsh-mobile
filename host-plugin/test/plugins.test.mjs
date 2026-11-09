import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

import { readProfilePlugins } from '../lib/plugins.js';

function tempHome(pkg) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'dsh-plugins-'));
  const profile = path.join(home, 'profiles', 'web');
  fs.mkdirSync(profile, { recursive: true });
  if (pkg !== undefined) fs.writeFileSync(path.join(profile, 'package.json'), JSON.stringify(pkg));
  return home;
}

test('reads installed profile plugins and local links', () => {
  const home = tempHome({
    dependencies: {
      'dsh-mobile-host': 'link:/srv/dsh/dsh-root/dsh-mobile/host-plugin',
      '@scope/published': '^0.4.2',
      '@local/harness-deck': 'link:/plugins/harness-deck',
    },
    dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'] } },
  });
  const result = readProfilePlugins({ DSH_HOME: home });
  assert.equal(result.supported, true);
  assert.equal(result.profile, 'web');
  assert.equal(result.packages.length, 3);
  const published = result.packages.find((entry) => entry.name === '@scope/published');
  assert.equal(published.version, '0.4.2');
  assert.equal(published.local, false);
  assert.equal(result.packages.find((entry) => entry.name === 'dsh-mobile-host').local, true);
  assert.equal(result.bundles.length, 2);
  assert.equal(result.truncated, false);
  fs.rmSync(home, { recursive: true, force: true });
});

test('missing or unreadable profile is unsupported, never throws', () => {
  const home = tempHome(undefined);
  assert.equal(readProfilePlugins({ DSH_HOME: home }).supported, false);
  fs.rmSync(home, { recursive: true, force: true });
});

test('package list is capped', () => {
  const dependencies = {};
  for (let index = 0; index < 260; index += 1) dependencies[`plugin-${index}`] = '1.0.0';
  const home = tempHome({ dependencies });
  const result = readProfilePlugins({ DSH_HOME: home });
  assert.equal(result.packages.length, 200);
  assert.equal(result.truncated, true);
  fs.rmSync(home, { recursive: true, force: true });
});
