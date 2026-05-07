/**
 * Provenance tagging for e2e tests.
 *
 * Every conversation, entity, and reasoning step the e2e suite creates is
 * tagged with metadata that traces it back to:
 *
 *   - the language client            (tck_client)
 *   - the specific test              (tck_test)
 *   - the GitHub Actions run         (tck_run_id, tck_run_attempt)
 *   - the commit SHA + branch        (tck_sha, tck_branch)
 *   - the suite start time           (tck_started_at)
 *   - the runner / hostname          (tck_host)
 *
 * Querying provenance after the fact (with workspace-admin Cypher access):
 *
 *     MATCH (c:Conversation) WHERE c.metadata.tck_run_id = '12345' RETURN c
 *     MATCH (e:Entity) WHERE e.description STARTS WITH '[tck:typescript' RETURN e
 *     MATCH (s:AgentStep) WHERE s.reasoning STARTS WITH 'TCK e2e' RETURN s
 */

import { hostname } from "node:os";

export const CLIENT_NAME = "typescript";

let cachedRunInfo: Record<string, string> | null = null;

export function runInfo(): Record<string, string> {
  if (cachedRunInfo) return cachedRunInfo;
  const sha = (process.env.GITHUB_SHA ?? "local").slice(0, 7);
  cachedRunInfo = {
    tck_client: CLIENT_NAME,
    tck_run_id: process.env.GITHUB_RUN_ID ?? "local",
    tck_run_attempt: process.env.GITHUB_RUN_ATTEMPT ?? "1",
    tck_workflow: process.env.GITHUB_WORKFLOW ?? "local",
    tck_sha: sha,
    tck_branch: process.env.GITHUB_REF_NAME ?? "local",
    tck_started_at: new Date().toISOString(),
    tck_host: process.env.RUNNER_NAME ?? hostname(),
  };
  return cachedRunInfo;
}

export function metadataFor(
  testName: string,
  extra: Record<string, unknown> = {},
): Record<string, unknown> {
  return { ...runInfo(), tck_test: testName, ...extra };
}

export function tagDescription(testName: string, description: string): string {
  const info = runInfo();
  return `[tck:${info.tck_client}:${info.tck_run_id}:${testName}] ${description}`;
}

export function provenanceReasoning(testName: string, phase = "setup"): string {
  const info = runInfo();
  return (
    `TCK e2e test ${phase}: ${testName} ` +
    `[client=${info.tck_client}, run=${info.tck_run_id}, ` +
    `sha=${info.tck_sha}, branch=${info.tck_branch}]`
  );
}

export function provenanceResult(
  testName: string,
  extra: Record<string, unknown> = {},
): string {
  return JSON.stringify(metadataFor(testName, extra));
}
