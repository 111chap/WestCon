const { test } = require('node:test');
const assert = require('node:assert/strict');
const { inspectFile } = require('./repository-guard.cjs');
test('blocks tracked credentials, runtime databases and installed dependencies', () => {
  for (const name of ['backend/pokemon.db', 'backend/pokemon.db-wal', 'backend/.env', '.env.production', '.npmrc', 'frontend/node_modules/pkg/index.js']) {
    assert(inspectFile(name, '').length > 0, name);
  }
});
test('allows source, fonts and the documented environment template', () => {
  for (const name of ['backend/.env.example', 'backend/server.js', 'frontend/src/fonts/font.ttf', 'package-lock.json']) assert.equal(inspectFile(name, '').length, 0);
});
test('detects credential patterns without including them in error messages', () => {
  const token = 'ghp_' + 'a'.repeat(36);
  const key = '-----BEGIN ' + 'PRIVATE KEY-----';
  for (const value of [token, key]) {
    const issues = inspectFile('source.js', value);
    assert(issues.length > 0);
    assert(!JSON.stringify(issues).includes(value));
  }
});
test('rejects privileged pull-request events and broad write permissions', () => {
  assert(inspectFile('.github/workflows/check.yml', 'on: pull_request' + '_target').length);
  assert(inspectFile('.github/workflows/check.yml', 'permissions: write' + '-all').length);
  assert.equal(inspectFile('.github/workflows/check.yml', 'on: pull_request\npermissions:\n  contents: read').length, 0);
});

