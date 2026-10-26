import { readFile, writeFile, mkdir, copyFile, cp, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const root = new URL('../', import.meta.url);
const readJSON = async name => JSON.parse(await readFile(new URL(name, root), 'utf8'));
const manifest = await readJSON('manifest.json');
const pkg = await readJSON('package.json');
const versions = await readJSON('versions.json');
assert.match(manifest.version, /^\d+\.\d+\.\d+$/);
assert.equal(pkg.version, manifest.version, 'Package and manifest versions must match');
assert.equal(versions[manifest.version], manifest.minAppVersion, 'versions.json must include this release');
assert.equal(manifest.id, 'gitsync-connect');
assert.equal(manifest.isDesktopOnly, false);
assert.ok(!/obsidian/i.test(manifest.description), 'Directory descriptions must not contain Obsidian');
assert.ok(manifest.description.length <= 250 && manifest.description.endsWith('.'), 'Use a short description ending in a period');
execFileSync(process.execPath, ['build.mjs'], { cwd: fileURLToPath(root), stdio: 'inherit' });
const dist = new URL('dist/', root);
// ZIP packaging is optional release preparation, never a build requirement.
await rm(new URL('gitsync-connect.zip', dist), { force: true });
execFileSync('zip', ['-qr', 'gitsync-connect.zip', 'gitsync-connect'], { cwd: fileURLToPath(dist) });
for (const name of ['main.js', 'manifest.json']) await copyFile(new URL(name, root), new URL(name, dist));

// Export a standalone repository; the native source snapshot keeps the
// cross-language protocol regression runnable outside the app monorepo.
const source = new URL('source/', dist);
await rm(source, { recursive: true, force: true });
await mkdir(source, { recursive: true });
for (const name of ['src', 'test', 'scripts', '.github', '.gitignore', 'README.md', 'RELEASE.md', 'SUBMISSION.md', 'CHANGELOG.md', 'LICENSE', 'package.json', 'package-lock.json', 'manifest.json', 'versions.json', 'main.js', 'build.mjs']) {
    await cp(new URL(name, root), new URL(name, source), { recursive: true });
}
let nativeSource;
try { nativeSource = await readFile(new URL('test/ObsidianCredentialHandoff.swift', root)); }
catch (error) {
    if (error.code !== 'ENOENT') throw error;
    nativeSource = await readFile(new URL('../Sync.md/Services/ObsidianCredentialHandoff.swift', root));
}
await writeFile(new URL('test/ObsidianCredentialHandoff.swift', source), nativeSource);
await rm(new URL('gitsync-connect-source.zip', dist), { force: true });
execFileSync('zip', ['-qr', '../gitsync-connect-source.zip', '.'], { cwd: fileURLToPath(source) });
const assets = ['main.js', 'manifest.json', 'gitsync-connect.zip', 'gitsync-connect-source.zip'];
let sums = '';
for (const name of assets) sums += `${createHash('sha256').update(await readFile(new URL(name, dist))).digest('hex')}  ${name}\n`;
await writeFile(new URL('SHA256SUMS', dist), sums);
console.log(`Prepared ${manifest.id} ${manifest.version} release assets in dist/`);
