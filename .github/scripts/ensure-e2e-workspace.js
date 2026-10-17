// Run with Node.js 20+: node .github/scripts/ensure-e2e-workspace.js
// GitHub Actions passes MEMORY_API_KEY and MEMORY_ADMIN_API_KEY as environment variables.
// API: https://memory.neo4jlabs.com/openapi.json
const { setTimeout: sleep } = require('node:timers/promises');

const WORKSPACE_NAME = 'github_e2e-tests';
const ENDPOINT = (process.env.MEMORY_ENDPOINT || 'https://memory.neo4jlabs.com/v1').replace(/\/+$/, '');
const API_KEY = process.env.MEMORY_API_KEY?.trim();
const ADMIN_API_KEY = process.env.MEMORY_ADMIN_API_KEY?.trim();
const WAIT_TIMEOUT_MS = 60_000;
const POLL_INTERVAL_MS = 2_000;

async function request(path, apiKey, { method = 'GET', workspaceId, signal } = {}) {
  const headers = { Authorization: `Bearer ${apiKey}`, Accept: 'application/json' };
  if (workspaceId) headers['X-Workspace-Id'] = workspaceId;

  const response = await fetch(`${ENDPOINT}${path}`, {
    method,
    headers,
    signal: signal || AbortSignal.timeout(10_000),
  });
  if (!response.ok) {
    let detail = await response.text();
    // Include the server's reason (scope, workspace role, etc.), but never echo our keys.
    for (const key of [API_KEY, ADMIN_API_KEY]) {
      if (key) detail = detail.replaceAll(key, '[REDACTED]');
    }
    detail = detail.trim().slice(0, 2_000);
    throw new Error(
      `${method} ${path} failed: HTTP ${response.status} ${response.statusText}`
      + (detail ? `: ${detail}` : ''),
    );
  }
  return response.json();
}

async function findWorkspace(signal) {
  const { workspaces } = await request('/users/me/workspaces', API_KEY, { signal });
  if (!Array.isArray(workspaces)) throw new Error('Invalid workspace list response.');

  const workspace = workspaces.find((item) => item.name === WORKSPACE_NAME);
  if (!workspace) throw new Error(`Workspace "${WORKSPACE_NAME}" does not exist.`);
  if (!workspace.id || typeof workspace.status !== 'string') {
    throw new Error(`Workspace "${WORKSPACE_NAME}" is missing its id or status.`);
  }
  return workspace;
}

function isActive(workspace) {
  // The API documents lowercase "active"; also accept the display spelling "Active".
  return workspace.status.toLowerCase() === 'active';
}

async function main() {
  if (!API_KEY) throw new Error('MEMORY_API_KEY is required.');
  if (!ADMIN_API_KEY) throw new Error('MEMORY_ADMIN_API_KEY is required.');
  console.log('[1/4] API keys loaded from the environment.');

  const workspace = await findWorkspace();
  console.log(`[2/4] Found "${WORKSPACE_NAME}" (${workspace.id}); status: ${workspace.status}.`);

  // One deadline covers reprovisioning, retries, polling, and response-body reads.
  const controller = new AbortController();
  const { signal } = controller;
  const timeout = setTimeout(() => controller.abort(new Error(
    `Workspace "${WORKSPACE_NAME}" did not become Active within 60 seconds.`,
  )), WAIT_TIMEOUT_MS);

  async function reprovision() {
    return request('/workspace/reprovision', ADMIN_API_KEY, {
      method: 'POST', workspaceId: workspace.id, signal,
    });
  }

  try {
    let retryProvision = false;
    if (isActive(workspace)) {
      console.log('[3/4] Workspace is already Active; reprovisioning skipped.');
    } else {
      const result = await reprovision();
      retryProvision = result.retry === true;
      console.log(`[3/4] Reprovision request completed; status: ${result.status}.`);
    }

    while (true) {
      const current = await findWorkspace(signal);
      if (current.id !== workspace.id) throw new Error('Workspace ID changed during the check.');
      signal.throwIfAborted();
      if (isActive(current)) {
        console.log(`[4/4] Final check passed: "${WORKSPACE_NAME}" is Active.`);
        return;
      }

      console.log(`Waiting for "${WORKSPACE_NAME}"; status: ${current.status}.`);
      await sleep(POLL_INTERVAL_MS, undefined, { signal });
      // A 202 response with retry=true requires another call to finish provisioning.
      if (retryProvision) {
        const result = await reprovision();
        retryProvision = result.retry === true;
        console.log(`Reprovision retry completed; status: ${result.status}.`);
      }
    }
  } catch (error) {
    throw signal.aborted ? signal.reason : error;
  } finally {
    clearTimeout(timeout);
  }
}

main().catch((error) => {
  console.error(`Workspace check failed: ${error.message}`);
  process.exitCode = 1;
});
