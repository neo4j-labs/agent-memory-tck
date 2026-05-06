package memory

import "context"

// AuthClient provides API-key and OAuth refresh management. Hosted only.
type AuthClient struct {
	transport Transport
}

// ListAPIKeys lists API keys for a workspace (no plaintext).
func (a *AuthClient) ListAPIKeys(ctx context.Context, workspaceID string) ([]APIKey, error) {
	var result []APIKey
	if err := a.transport.Call(ctx, "list_api_keys", map[string]interface{}{
		"workspace_id": workspaceID,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// CreateAPIKeyInput is the input to CreateAPIKey.
type CreateAPIKeyInput struct {
	Label       string   `json:"label"`
	Scopes      []string `json:"scopes"`
	WorkspaceID string   `json:"workspace_id"`
}

// CreateAPIKey creates a new API key. The plaintext is returned once.
func (a *AuthClient) CreateAPIKey(ctx context.Context, in CreateAPIKeyInput) (*APIKey, error) {
	var result APIKey
	if err := a.transport.Call(ctx, "create_api_key", map[string]interface{}{
		"label":        in.Label,
		"scopes":       in.Scopes,
		"workspace_id": in.WorkspaceID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// RevokeAPIKey revokes (deletes) an API key by id.
func (a *AuthClient) RevokeAPIKey(ctx context.Context, keyID string) error {
	return a.transport.Call(ctx, "revoke_api_key", map[string]interface{}{
		"key_id": keyID,
	}, nil)
}

// RevealAPIKey returns the plaintext value of a stored API key.
func (a *AuthClient) RevealAPIKey(ctx context.Context, keyID, workspaceID string) (*APIKey, error) {
	var result APIKey
	if err := a.transport.Call(ctx, "reveal_api_key", map[string]interface{}{
		"key_id":       keyID,
		"workspace_id": workspaceID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// RefreshAccessToken exchanges a refresh token for a fresh access/refresh pair.
func (a *AuthClient) RefreshAccessToken(ctx context.Context, refreshToken string) (*AccessTokenPair, error) {
	var result AccessTokenPair
	if err := a.transport.Call(ctx, "refresh_access_token", map[string]interface{}{
		"refresh_token": refreshToken,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
