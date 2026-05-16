/**
 * Auth & API-key management — hosted service only.
 *
 * Static `nams_*` keys can be created, listed, revealed, and revoked. OAuth
 * refresh-token rotation is also exposed for clients running PKCE flows.
 */

import type { Transport } from "../transport/index.js";
import type { AccessTokenPair, ApiKey, CreateApiKeyInput } from "../types.js";

interface WireApiKey {
  id: string;
  label: string;
  scopes?: string[];
  workspace_id: string;
  created_at: string;
  expires_at?: string;
  key?: string;
}

interface WireTokenPair {
  access_token: string;
  refresh_token: string;
  expires_in: number;
}

function toApiKey(w: WireApiKey): ApiKey {
  return {
    id: w.id,
    label: w.label,
    scopes: w.scopes ?? [],
    workspaceId: w.workspace_id,
    createdAt: w.created_at,
    expiresAt: w.expires_at,
    key: w.key,
  };
}

export class AuthClient {
  constructor(private readonly transport: Transport) {}

  /** List API keys for a workspace (no plaintext). */
  async listApiKeys(workspaceId: string): Promise<ApiKey[]> {
    const wire = await this.transport.request<WireApiKey[]>("list_api_keys", {
      workspace_id: workspaceId,
    });
    return (wire ?? []).map(toApiKey);
  }

  /** Create a new API key. The plaintext value is returned only once. */
  async createApiKey(input: CreateApiKeyInput): Promise<ApiKey> {
    const wire = await this.transport.request<WireApiKey>("create_api_key", {
      label: input.label,
      scopes: input.scopes,
      workspace_id: input.workspaceId,
    });
    return toApiKey(wire);
  }

  /** Revoke (delete) an API key by id. */
  async revokeApiKey(keyId: string): Promise<void> {
    await this.transport.request("revoke_api_key", { key_id: keyId });
  }

  /** Reveal the plaintext value of a stored API key. */
  async revealApiKey(keyId: string, workspaceId: string): Promise<ApiKey> {
    const wire = await this.transport.request<WireApiKey>("reveal_api_key", {
      key_id: keyId,
      workspace_id: workspaceId,
    });
    return toApiKey(wire);
  }

  /** Exchange a refresh token for a fresh access/refresh pair. */
  async refreshAccessToken(refreshToken: string): Promise<AccessTokenPair> {
    const wire = await this.transport.request<WireTokenPair>("refresh_access_token", {
      refresh_token: refreshToken,
    });
    return {
      accessToken: wire.access_token,
      refreshToken: wire.refresh_token,
      expiresIn: wire.expires_in,
    };
  }
}
