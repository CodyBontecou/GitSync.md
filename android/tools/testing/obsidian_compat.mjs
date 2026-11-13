import { readFileSync } from 'node:fs';
import { createHash, webcrypto } from 'node:crypto';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

// Inputs must be isolated synthetic test fixtures, never an actual user's vault/key.
const [fixturePath, companionPath] = process.argv.slice(2);
if (!fixturePath || !companionPath) throw new Error('Pass synthetic handoff JSON and the companion connection.js path.');
const require = createRequire(import.meta.url);
const { decrypt, installCredentials } = require(resolve(companionPath));
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
const key = await webcrypto.subtle.importKey('pkcs8', Buffer.from(fixture.privateKeyPKCS8, 'base64'),
  { name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
const privateKey = await webcrypto.subtle.exportKey('jwk', key);
const pending = { request: fixture.request, privateKey };
const credential = await decrypt(fixture.envelope, pending, webcrypto, fixture.now);
assert.ok(['synthetic-obsidian-token', 'synthetic-obsidian-native-token'].includes(credential.password));
assert.equal(credential.username, 'x-access-token');
assert.equal(credential.authorEmail, 'fixture@example.invalid');
let password = '', username = '';
const config = {};
const git = {
  settings: {},
  localStorage: { getUsername: () => username, setUsername: value => { username = value; },
    getPassword: () => password, setPassword: value => { password = value; } },
  gitManager: { getConfig: async name => config[name], setConfig: async (name, value) => { config[name] = value; } },
};
await installCredentials(git, credential);
assert.equal(password, credential.password);
assert.equal(username, credential.username);
assert.equal(config['user.email'], 'fixture@example.invalid');
await assert.rejects(() => decrypt({ ...fixture.envelope, requestID: 'mismatched' }, pending, webcrypto, fixture.now));
await assert.rejects(() => decrypt(fixture.envelope, pending, webcrypto, fixture.request.expiresAt * 1000 + 1));
console.log('PASS: companion decrypt/install, request binding and expiry checks.');
console.log(`Companion source SHA-256: ${createHash('sha256').update(readFileSync(companionPath)).digest('hex')}`);
