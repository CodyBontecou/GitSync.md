const { existsSync } = require('node:fs');
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { once } = require('node:events');
const { createInterface } = require('node:readline');
const { webcrypto } = require('node:crypto');
const { Connection, createRecipient, decrypt, installCredentials, validateRequest, originURL, DIRECTORY, CONTEXT, PENDING_KEY } = require('../src/connection');
const requestID = '11111111-1111-4111-8111-111111111111';
const repoID = '22222222-2222-4222-8222-222222222222';
const remoteURL = 'https://github.com/fixture/vault.git';
const request = () => ({ version: 1, requestID, repoID, remoteURL, expiresAt: Date.now() / 1000 + 3600 });
let temporary, native;
before(async () => {
    temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'gitsync-handoff-'));
    native = path.join(temporary, 'native-handoff');
    const result = spawnSync('xcrun', ['swiftc', '-module-cache-path', path.join(temporary, 'swift-cache'),
        (existsSync(path.join(__dirname, 'ObsidianCredentialHandoff.swift')) ? path.join(__dirname, 'ObsidianCredentialHandoff.swift') : path.resolve(__dirname, '../../Sync.md/Services/ObsidianCredentialHandoff.swift')), path.join(__dirname, 'native-handoff.swift'), '-o', native], { encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
});
after(async () => { await fs.rm(temporary, { recursive: true, force: true }); });
function gitFixture(modern = false) {
    let username = null, password = null;
    const config = new Map();
    const git = { settings: {}, localStorage: { getUsername: () => username, setUsername: v => { username = v; } },
        gitManager: { getConfig: async key => config.get(key), setConfig: async (key, value) => config.set(key, value) } };
    const store = { get: () => password, set: async v => { password = v; } };
    if (modern) git.gitServerPassword = store;
    else Object.assign(git.localStorage, { getPassword: store.get, setPassword: store.set });
    return { git, store };
}
function appFixture(root) {
    const local = new Map();
    const adapter = {
        read: p => fs.readFile(path.join(root, p), 'utf8'),
        write: (p, data) => fs.writeFile(path.join(root, p), data),
        exists: async p => { try { await fs.access(path.join(root, p)); return true; } catch { return false; } },
        remove: p => fs.rm(path.join(root, p))
    };
    return { vault: { adapter, getName: () => 'Vault & Notes' }, loadLocalStorage: k => local.get(k), saveLocalStorage: (k, v) => local.set(k, v) };
}
async function capsule(pending, overrides = {}) {
    const sender = await webcrypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
    const privateKey = await webcrypto.subtle.importKey('jwk', pending.privateKey, { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const shared = await webcrypto.subtle.deriveBits({ name: 'ECDH', public: sender.publicKey }, privateKey, 256);
    const material = await webcrypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
    const key = await webcrypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt: new TextEncoder().encode(pending.request.requestID), info: new TextEncoder().encode(CONTEXT) }, material, { name: 'AES-GCM', length: 256 }, false, ['encrypt']);
    const nonce = webcrypto.getRandomValues(new Uint8Array(12));
    const credential = { version: 1, requestID: pending.request.requestID, remoteURL, username: 'x-access-token', password: 'fixture-token', expiresAt: Date.now() / 1000 + 300, ...overrides };
    const encrypted = await webcrypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: new TextEncoder().encode(pending.request.requestID) }, key, new TextEncoder().encode(JSON.stringify(credential)));
    return { version: 1, requestID: pending.request.requestID, publicKey: Buffer.from(await webcrypto.subtle.exportKey('raw', sender.publicKey)).toString('base64'), nonce: Buffer.from(nonce).toString('base64'), ciphertext: Buffer.from(encrypted).toString('base64') };
}

test('Swift producer to companion: ECDH/HKDF/AES-GCM, installation, cleanup and replay rejection', async () => {
    const root = path.join(temporary, 'vault');
    const child = spawn(native, [root], { stdio: ['pipe', 'pipe', 'pipe'] });
    const exited = once(child, 'exit');
    let stderr = ''; child.stderr.on('data', data => { stderr += data; });
    const lines = createInterface({ input: child.stdout });
    const [line] = await Promise.race([
        once(lines, 'line'),
        exited.then(([status]) => { throw new Error(`Native fixture exited before producing a request (${status}): ${stderr}`); })
    ]);
    const nativeRequest = JSON.parse(line);
    const app = appFixture(root), { git, store } = gitFixture();
    const connection = new Connection(app, () => git, webcrypto);
    const url = new URL(await connection.begin());
    assert.equal(url.searchParams.get('repoID'), nativeRequest.repoID);
    assert.equal(url.searchParams.get('vault'), 'Vault & Notes');
    assert.ok(!url.toString().includes('fixture-token'));
    child.stdin.end('respond\n');
    const [status] = await exited;
    assert.equal(status, 0, stderr);
    await connection.finish(nativeRequest.requestID);
    assert.equal(store.get(), 'fixture-token');
    assert.equal(git.localStorage.getUsername(), 'x-access-token');
    assert.equal(await git.gitManager.getConfig('user.name'), 'Fixture Author');
    assert.equal(await git.gitManager.getConfig('user.email'), 'fixture@example.invalid');
    assert.equal(app.loadLocalStorage(PENDING_KEY), null);
    for (const name of ['request', 'response', 'recipient']) assert.equal(await app.vault.adapter.exists(`${DIRECTORY}/${name}.json`), false);
    await assert.rejects(connection.finish(nativeRequest.requestID), /No matching/);
});

test('rejects expired, mismatched, tampered and wrong-recipient capsules', async () => {
    const r = request(), generated = await createRecipient(r, webcrypto), pending = { request: r, privateKey: generated.privateKey };
    const encrypted = await capsule(pending);
    assert.equal((await decrypt(encrypted, pending, webcrypto)).password, 'fixture-token');
    await assert.rejects(decrypt({ ...encrypted, requestID: repoID }, pending, webcrypto), /does not match/);
    const corrupted = Buffer.from(encrypted.ciphertext, 'base64'); corrupted[0] ^= 1;
    await assert.rejects(decrypt({ ...encrypted, ciphertext: corrupted.toString('base64') }, pending, webcrypto));
    const wrong = await createRecipient(r, webcrypto);
    await assert.rejects(decrypt(encrypted, { request: r, privateKey: wrong.privateKey }, webcrypto));
    await assert.rejects(decrypt(await capsule(pending, { expiresAt: 1 }), pending, webcrypto), /expired/);
    await assert.rejects(decrypt(await capsule(pending, { remoteURL: 'https://github.com/other/vault.git' }), pending, webcrypto), /match/);
});

for (const modern of [false, true]) test(`configures ${modern ? 'secret' : 'legacy device-local'} storage without overwriting existing credentials`, async () => {
    const { git, store } = gitFixture(modern);
    await store.set('existing'); git.localStorage.setUsername('existing-user');
    const credential = { username: 'x-access-token', password: 'fixture-token' };
    await assert.rejects(installCredentials(git, credential), /already has/);
    assert.equal(store.get(), 'existing');
    await installCredentials(git, credential, true);
    assert.equal(store.get(), 'fixture-token');
    assert.equal(git.localStorage.getUsername(), 'x-access-token');
});

test('secret storage failure preserves username', async () => {
    const { git } = gitFixture(true);
    git.localStorage.setUsername('original');
    git.gitServerPassword.set = async () => { throw Error('storage failure'); };
    await assert.rejects(installCredentials(git, { username: 'new', password: 'fixture' }), /storage failure/);
    assert.equal(git.localStorage.getUsername(), 'original');
});

test('fails closed for expired requests, alternate remotes, includes and custom Git paths', async () => {
    assert.throws(() => validateRequest({ ...request(), expiresAt: 1 }), /expired/);
    assert.throws(() => validateRequest({ ...request(), remoteURL: 'https://github.com.evil/a/b' }), /invalid/);
    assert.equal(originURL(`[remote "origin"]\nurl = ${remoteURL}\npushurl = https://evil.test/a/b`), null);
    assert.equal(originURL(`[include]\npath = outside\n[remote "origin"]\nurl = ${remoteURL}`), null);
    assert.equal(originURL(`[remote "origin"]\nurl = ${remoteURL}\nurl = ${remoteURL}`), null);
    const { git } = gitFixture(); git.settings.basePath = 'subfolder';
    await assert.rejects(installCredentials(git, { username: 'new', password: 'fixture' }), /root repository/);
});

test('interrupted public-key writes recover and concurrent callbacks import once', async () => {
    const root = path.join(temporary, 'recover-vault');
    await fs.mkdir(path.join(root, DIRECTORY), { recursive: true });
    const r = request();
    await fs.writeFile(path.join(root, DIRECTORY, 'request.json'), JSON.stringify(r));
    await fs.writeFile(path.join(root, '.git/config'), `[remote "origin"]\nurl = ${remoteURL}\n`);
    const app = appFixture(root), { git, store } = gitFixture(true);
    const originalWrite = app.vault.adapter.write;
    app.vault.adapter.write = async () => { throw Error('provider unavailable'); };
    const connection = new Connection(app, () => git, webcrypto);
    await assert.rejects(connection.begin(), /provider unavailable/);
    const saved = app.loadLocalStorage(PENDING_KEY);
    app.vault.adapter.write = originalWrite;
    assert.ok((await connection.begin()).startsWith('syncmd://'));
    assert.deepEqual(app.loadLocalStorage(PENDING_KEY).privateKey, saved.privateKey);
    await fs.writeFile(path.join(root, DIRECTORY, 'response.json'), JSON.stringify(await capsule(saved)));
    let writes = 0;
    const set = store.set; git.gitServerPassword.set = async value => { writes++; await set(value); };
    await Promise.all([connection.finish(r.requestID), connection.finish(r.requestID)]);
    assert.equal(writes, 1);
    assert.equal(store.get(), 'fixture-token');
});

test('explicit reconnection renews public routing metadata without putting tokens in URLs', async () => {
    const root = path.join(temporary, 'renew-vault');
    await fs.mkdir(path.join(root, DIRECTORY), { recursive: true });
    await fs.writeFile(path.join(root, DIRECTORY, 'connection.json'), JSON.stringify({ ...request(), expiresAt: 1 }));
    await fs.writeFile(path.join(root, '.git/config'), `[remote "origin"]\nurl = ${remoteURL}\n`);
    const app = appFixture(root), { git } = gitFixture();
    const connection = new Connection(app, () => git, webcrypto);
    const url = new URL(await connection.renew(true));
    assert.equal(url.pathname, '/prepare');
    assert.equal(url.searchParams.get('repoID'), repoID);
    assert.equal(url.searchParams.get('vault'), 'Vault & Notes');
    assert.equal(url.searchParams.has('token'), false);
});

test('plugin lifecycle automatically opens GitSync.md and imports on return to the right vault', async () => {
    const root = path.join(temporary, 'lifecycle-vault');
    await fs.mkdir(path.join(root, DIRECTORY), { recursive: true });
    const r = request();
    await fs.writeFile(path.join(root, DIRECTORY, 'request.json'), JSON.stringify(r));
    await fs.writeFile(path.join(root, '.git/config'), `[remote "origin"]\nurl = ${remoteURL}\n`);
    const app = appFixture(root), { git, store } = gitFixture();
    let ready, protocol;
    app.workspace = { onLayoutReady: callback => { ready = callback; } };
    app.plugins = { getPlugin: id => id === 'obsidian-git' ? git : undefined };
    const opened = [], notices = [], commands = [];
    class Plugin {
        registerObsidianProtocolHandler(name, callback) { protocol = callback; assert.equal(name, 'gitsync-connect'); }
        addCommand(command) { commands.push(command); }
    }
    const module = { exports: {} };
    const vm = require('node:vm');
    const code = await fs.readFile(path.join(__dirname, '../main.js'), 'utf8');
    vm.runInNewContext(code, { module, TextEncoder, TextDecoder, URL, URLSearchParams, Uint8Array, atob, btoa, crypto: webcrypto,
        window: { open: url => opened.push(url), setTimeout, clearTimeout },
        require: name => {
            assert.equal(name, 'obsidian');
            return { Plugin, Notice: class { constructor(message) { notices.push(message); } }, Platform: { isMobileApp: true } };
        }
    });
    const plugin = new module.exports(); plugin.app = app;
    await plugin.onload();
    assert.equal(commands.length, 2);
    ready();
    for (let i = 0; opened.length === 0 && i < 400; i++) await new Promise(resolve => setTimeout(resolve, 5));
    assert.equal(opened.length, 1);
    assert.equal(new URL(opened[0]).host, 'obsidian');
    const pending = app.loadLocalStorage(PENDING_KEY);
    await fs.writeFile(path.join(root, DIRECTORY, 'response.json'), JSON.stringify(await capsule(pending)));
    await protocol({ status: 'ready', requestID, vault: 'another-vault' });
    assert.equal(store.get(), null);
    await protocol({ status: 'ready', requestID, vault: app.vault.getName() });
    assert.equal(store.get(), 'fixture-token');
    assert.ok(notices.includes('Obsidian Git is connected to GitSync.md.'));
    plugin.onunload();
});

test('imports clone author identity needed by Obsidian Git pull, preserving existing fields', async () => {
    const { git, store } = gitFixture();
    const config = new Map([['user.email', 'existing@example.invalid']]);
    git.gitManager = { getConfig: async key => config.get(key), setConfig: async (key, value) => config.set(key, value) };
    await installCredentials(git, { username: 'x-access-token', password: 'fixture-token', authorName: 'Fixture Author', authorEmail: 'fixture@example.invalid' });
    assert.equal(config.get('user.name'), 'Fixture Author');
    assert.equal(config.get('user.email'), 'existing@example.invalid');
    assert.equal(store.get(), 'fixture-token');
});

test('manual connection explains missing metadata for a vault published from an existing folder', async () => {
    const root = path.join(temporary, 'published-vault');
    await fs.mkdir(path.join(root, '.git'), { recursive: true });
    await fs.writeFile(path.join(root, '.git/config'), `[remote "origin"]\nurl = ${remoteURL}\n`);
    const app = appFixture(root), { git } = gitFixture();
    const notices = [], opened = [];
    app.workspace = { onLayoutReady: () => {} };
    app.plugins = { getPlugin: () => git };
    const module = { exports: {} };
    require('node:vm').runInNewContext(await fs.readFile(path.join(__dirname, '../main.js'), 'utf8'), {
        module, TextEncoder, URL, URLSearchParams,
        window: { open: url => opened.push(url) },
        require: () => ({ Plugin: class { registerObsidianProtocolHandler() {} addCommand() {} }, Notice: class { constructor(message) { notices.push(message); } }, Platform: { isMobileApp: true } })
    });
    const plugin = new module.exports();
    plugin.app = app;
    await plugin.onload();
    await plugin.connect(false, true);
    assert.equal(opened.length, 0);
    assert.deepEqual(notices, ['This vault has no GitSync.md connection metadata. Open GitSync.md with this folder added and signed into GitHub, then retry Connect this vault.']);
});

test('reconnect defers app launches beyond the incoming iOS URL callback', async () => {
    const root = path.join(temporary, 'reconnect-lifecycle-vault');
    await fs.mkdir(path.join(root, DIRECTORY), { recursive: true });
    await fs.writeFile(path.join(root, DIRECTORY, 'connection.json'), JSON.stringify(request()));
    await fs.writeFile(path.join(root, '.git/config'), `[remote "origin"]\nurl = ${remoteURL}\n`);
    const app = appFixture(root), { git, store } = gitFixture();
    await store.set('old-token');
    git.localStorage.setUsername('old-user');
    app.workspace = { onLayoutReady: () => {} };
    app.plugins = { getPlugin: () => git };
    let protocol;
    const commands = [], opened = [], notices = [], timers = new Map();
    let nextTimer = 0;
    const module = { exports: {} };
    require('node:vm').runInNewContext(await fs.readFile(path.join(__dirname, '../src/main.js'), 'utf8'), {
        module,
        window: {
            open: url => opened.push(url),
            setTimeout: (callback, delay) => { const id = ++nextTimer; timers.set(id, { callback, delay }); return id; },
            clearTimeout: id => timers.delete(id)
        },
        require: name => name === './connection' ? require('../src/connection') : {
            Plugin: class {
                registerObsidianProtocolHandler(name, callback) { protocol = callback; }
                addCommand(command) { commands.push(command); }
            },
            Notice: class { constructor(message) { notices.push(message); } },
            Platform: { isMobileApp: true }
        }
    });
    const plugin = new module.exports(); plugin.app = app;
    await plugin.onload();
    function dispatch() {
        assert.equal(timers.size, 1);
        const [id, timer] = timers.entries().next().value;
        assert.ok(timer.delay >= 750, 'allow iOS to finish the incoming URL transition');
        timers.delete(id); timer.callback();
    }
    await commands.find(command => command.id === 'reconnect').callback();
    dispatch();
    assert.equal(new URL(opened[0]).pathname, '/prepare');
    const r = request();
    await fs.writeFile(path.join(root, DIRECTORY, 'request.json'), JSON.stringify(r));
    await protocol({ status: 'prepared', requestID: r.requestID, vault: app.vault.getName() });
    assert.equal(opened.length, 1, 'the next app launch must not run inside the incoming URL callback');
    const pending = app.loadLocalStorage(PENDING_KEY);
    assert.equal(pending.replace, true);
    dispatch();
    assert.equal(new URL(opened[1]).pathname, '/connect');
    await fs.writeFile(path.join(root, DIRECTORY, 'response.json'), JSON.stringify(await capsule(pending, {
        authorName: 'Fixture Author', authorEmail: 'fixture@example.invalid'
    })));
    await protocol({ status: 'ready', requestID: r.requestID, vault: app.vault.getName() });
    assert.equal(store.get(), 'fixture-token');
    assert.equal(await git.gitManager.getConfig('user.name'), 'Fixture Author');
    assert.equal(await git.gitManager.getConfig('user.email'), 'fixture@example.invalid');
    assert.ok(notices.includes('Obsidian Git is connected to GitSync.md.'));
    await plugin.connect(true, true);
    assert.equal(timers.size, 1);
    plugin.onunload();
    assert.equal(timers.size, 0, 'unloading must cancel delayed app launches');
});
