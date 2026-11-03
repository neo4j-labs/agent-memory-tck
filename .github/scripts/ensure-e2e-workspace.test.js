const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { createServer } = require('node:http');
const { join } = require('node:path');
const { test } = require('node:test');

const script = join(__dirname, 'ensure-e2e-workspace.js');
const workspace = (status) => ({ id: 'e2e-id', name: 'github_e2e-tests', status });
const listing = (status) => ({ body: { workspaces: [workspace(status)] } });

// Run the actual CLI against a local API, including real fetch cancellation.
async function runScript(t, responses, env = {}) {
  const requests = [];
  const server = createServer((request, response) => {
    requests.push({ method: request.method, path: request.url, headers: request.headers });
    const next = responses.shift() || { status: 500, body: {} };
    if (next.hang) return;
    response.writeHead(next.status || 200, { 'Content-Type': 'application/json' });
    if (next.hangBody) {
      response.write('{"workspaces":');
      return;
    }
    response.end(JSON.stringify(next.body));
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const started = performance.now();
  const child = spawn(process.execPath, [script], {
    env: {
      ...process.env,
      MEMORY_ENDPOINT: `http://127.0.0.1:${server.address().port}/v1/`,
      MEMORY_API_KEY: 'test-workspace-key',
      MEMORY_ADMIN_API_KEY: '',
      ...env,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  t.after(() => {
    child.kill();
    server.closeAllConnections();
    server.close();
  });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => { stdout += chunk; });
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  const [code] = await once(child, 'close');
  return { code, stdout, stderr, requests, elapsedMs: performance.now() - started };
}

test('E2E workspace preparation', { concurrency: true, timeout: 75_000 }, async (t) => {
  await Promise.all([
    t.test('requires the workspace key before making requests', async (t) => {
      const result = await runScript(t, [], { MEMORY_API_KEY: '' });
      assert.equal(result.code, 1);
      assert.match(result.stderr, /MEMORY_API_KEY is required/);
      assert.equal(result.requests.length, 0);
    }),
    t.test('fails if the named workspace does not exist', async (t) => {
      const result = await runScript(t, [{ body: { workspaces: [
        { id: 'other', name: 'another-workspace', status: 'active' },
      ] } }]);
      assert.equal(result.code, 1);
      assert.match(result.stderr, /does not exist/);
      assert.equal(result.requests.length, 1);
    }),
    t.test('checks an already-active workspace again without reprovisioning', async (t) => {
      const result = await runScript(t, [listing('active'), listing('Active')]);
      assert.equal(result.code, 0, result.stderr);
      assert.deepEqual(result.requests.map((r) => r.method), ['GET', 'GET']);
      for (const request of result.requests) {
        assert.equal(request.path, '/v1/users/me/workspaces');
        assert.equal(request.headers.authorization, 'Bearer test-workspace-key');
      }
      for (let step = 1; step <= 4; step++) assert.ok(result.stdout.includes(`[${step}/4]`));
      assert.match(result.stdout, /reprovisioning skipped/);
      assert.ok(!result.stdout.includes('test-workspace-key'));
    }),
    t.test('reprovisions with only the workspace key and the selected workspace ID', async (t) => {
      const result = await runScript(t, [
        listing('deprovisioned'), { body: { status: 'active' } }, listing('active'),
      ]);
      assert.equal(result.code, 0, result.stderr);
      const provision = result.requests[1];
      assert.equal(provision.method, 'POST');
      assert.equal(provision.path, '/v1/workspace/reprovision');
      assert.equal(provision.headers.authorization, 'Bearer test-workspace-key');
      assert.equal(provision.headers['x-workspace-id'], 'e2e-id');
      assert.equal(result.requests[2].headers.authorization, 'Bearer test-workspace-key');
      assert.match(result.stdout, /Final check passed/);
    }),
    t.test('retries provisioning after 202 and verifies the final state', async (t) => {
      const result = await runScript(t, [
        listing('pending'),
        { status: 202, body: { status: 'provisioning', retry: true } },
        listing('provisioning'),
        { body: { status: 'active' } },
        listing('active'),
      ]);
      assert.equal(result.code, 0, result.stderr);
      assert.deepEqual(result.requests.map((r) => r.method), ['GET', 'POST', 'GET', 'POST', 'GET']);
      for (const request of result.requests) {
        assert.equal(request.headers.authorization, 'Bearer test-workspace-key');
      }
      assert.match(result.stdout, /Reprovision retry completed/);
    }),
    t.test('repeats the final check until the list reports Active', async (t) => {
      const result = await runScript(t, [
        listing('error'), { body: { status: 'active' } },
        listing('provisioning'), listing('active'),
      ]);
      assert.equal(result.code, 0, result.stderr);
      assert.deepEqual(result.requests.map((r) => r.method), ['GET', 'POST', 'GET', 'GET']);
    }),
    t.test('reports the API error body without exposing the workspace key', async (t) => {
      const result = await runScript(t, [listing('pending'), { status: 403, body: {
        error: 'insufficient scope',
        detail: 'Rejected workspace key: test-workspace-key',
      } }]);
      assert.equal(result.code, 1);
      assert.match(result.stderr, /POST \/workspace\/reprovision failed: HTTP 403/);
      assert.match(result.stderr, /insufficient scope/);
      assert.match(result.stderr, /Rejected workspace key: \[REDACTED\]/);
      assert.doesNotMatch(result.stderr, /test-workspace-key/);
      assert.doesNotMatch(result.stdout, /Final check passed/);
    }),
    t.test('rejects malformed workspace lists', async (t) => {
      const result = await runScript(t, [{ body: { workspaces: {} } }]);
      assert.equal(result.code, 1);
      assert.match(result.stderr, /Invalid workspace list response/);
    }),
    t.test('fails if the workspace disappears before the final check', async (t) => {
      const result = await runScript(t, [listing('active'), { body: { workspaces: [] } }]);
      assert.equal(result.code, 1);
      assert.match(result.stderr, /does not exist/);
    }),
    ...[
      ['provisioning never completes', [listing('pending'), { hang: true }]],
      ['the final response body stalls', [listing('active'), { hangBody: true }]],
      ['the workspace never becomes Active', [
        listing('pending'), { body: { status: 'active' } },
        ...Array.from({ length: 32 }, () => listing('provisioning')),
      ]],
    ].map(([name, responses]) => t.test(`times out when ${name}`, async (t) => {
      const result = await runScript(t, responses);
      assert.equal(result.code, 1);
      assert.match(result.stderr, /did not become Active within 60 seconds/);
      assert.doesNotMatch(result.stdout, /Final check passed/);
      // Allow process startup/exit and scheduling overhead around the 60s deadline.
      assert.ok(result.elapsedMs >= 60_000 && result.elapsedMs < 65_000, `${result.elapsedMs}ms`);
    })),
  ]);
});
