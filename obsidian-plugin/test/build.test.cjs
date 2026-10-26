const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

test('clean build needs only Node and reproduces the committed installer bundle', async () => {
    const root = path.resolve(__dirname, '..');
    const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'gitsync-node-only-build-'));
    try {
        for (const name of ['src', 'build.mjs', 'manifest.json', 'LICENSE']) {
            await fs.cp(path.join(root, name), path.join(temporary, name), { recursive: true });
        }
        const result = spawnSync(process.execPath, ['build.mjs'], {
            cwd: temporary, env: { ...process.env, PATH: '' }, encoding: 'utf8'
        });
        assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(await fs.readFile(path.join(temporary, 'main.js')), await fs.readFile(path.join(root, 'main.js')));
        assert.deepEqual(await fs.readFile(path.join(temporary, 'dist/gitsync-connect/manifest.json')), await fs.readFile(path.join(root, 'manifest.json')));
    } finally {
        await fs.rm(temporary, { recursive: true, force: true });
    }
});
